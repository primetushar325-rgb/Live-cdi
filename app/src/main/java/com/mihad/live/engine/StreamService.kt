package com.mihad.live.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mihad.live.R
import com.mihad.live.ui.MainActivity
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.CodecErrorCallback
import com.pedro.encoder.TimestampMode
import com.pedro.encoder.input.sources.audio.AudioFileSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.VideoFileSource
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.util.FpsListener
import com.pedro.library.util.streamclient.RtmpStreamClient
import com.mihad.live.engine.StreamState.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Process-level owner of the one and only streaming pipeline.
 *
 * The service owns the decoder, GL compositor, MediaCodec encoders and RootEncoder RTMP
 * client. Activities only send intent-like commands and observe immutable snapshots.
 * Reconnects call RootEncoder's RTMP client reconnect path; the encoder, source decoder,
 * compositor and session clock remain alive.
 */
class StreamService : Service(), ConnectChecker, CodecErrorCallback {

    inner class LocalBinder : Binder() {
        fun service(): StreamService = this@StreamService
    }

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private val retryPending = AtomicBoolean(false)
    private val mutableSnapshot = MutableStateFlow(SessionSnapshot())
    val snapshots: StateFlow<SessionSnapshot> = mutableSnapshot.asStateFlow()

    @Volatile private var stream: RtmpStream? = null
    @Volatile private var currentRequest: StreamRequest? = null
    @Volatile private var previewTarget: TextureView? = null
    @Volatile private var isStopping = false
    @Volatile private var foreground = false
    @Volatile private var networkConnected: Boolean? = null
    @Volatile private var actualFps: Int? = null
    @Volatile private var uploadBitrateBps: Long? = null
    @Volatile private var sessionStartElapsedMs: Long? = null
    @Volatile private var sessionStartWallMs: Long? = null
    @Volatile private var reconnectCount = 0
    @Volatile private var loopCount = 0L
    @Volatile private var currentVideoTargetBps = 0
    @Volatile private var lastAdaptiveChangeMs = 0L
    @Volatile private var stableSinceMs = 0L
    @Volatile private var lastEncodedFrames = 0L
    @Volatile private var lastTransportBytes = 0L
    @Volatile private var lastVideoProgressMs = 0L
    @Volatile private var lastTransportProgressMs = 0L
    @Volatile private var lastVideoBytes = 0L
    @Volatile private var lastAudioBytes = 0L
    @Volatile private var encodedVideoBitrateBps: Long? = null
    @Volatile private var encodedAudioBitrateBps: Long? = null
    @Volatile private var previousEncodedVideoBytes = 0L
    @Volatile private var previousEncodedAudioBytes = 0L
    @Volatile private var previousMetricAtMs = 0L
    @Volatile private var lastDroppedVideoFrames = 0L
    @Volatile private var lastAdaptDroppedFrames = 0L
    @Volatile private var activeRequestFingerprint: String? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationJob: Job? = null
    private var connectivityManager: ConnectivityManager? = null
    private var currentNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            currentNetwork = network
            updateNetworkState(isInternetAvailable(network))
        }

        override fun onLost(network: Network) {
            if (currentNetwork == network) currentNetwork = null
            val available = currentNetwork?.let(::isInternetAvailable) ?: hasInternetNetwork()
            updateNetworkState(available)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetworkState(
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }
    }

    private val stateMachine = SessionStateMachine()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(application)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkConnected = hasInternetNetwork()
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        }.onFailure {
            SafeDiagnostics.event("NETWORK_CALLBACK_UNAVAILABLE")
        }
        serviceScope.launch(Dispatchers.Default) { monitorSession() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROMOTE -> promoteToForeground()
            ACTION_STOP -> stopLive(explicit = true)
            else -> Unit
        }
        return START_NOT_STICKY
    }

    /** Prepare the preview using the very same video source and compositor used for publishing. */
    fun preparePreview(request: StreamRequest) {
        serviceScope.launch {
            commandMutex.withLock {
                if (mutableSnapshot.value.isStreaming) return@withLock
                preparePreviewLocked(request)
            }
        }
    }

    fun attachPreview(textureView: TextureView) {
        previewTarget = textureView
        serviceScope.launch {
            val active = stream ?: return@launch
            if (active.isStreaming || mutableSnapshot.value.state == ENCODER_READY) {
                attachPreviewOnMain(active, textureView)
            }
        }
    }

    fun detachPreview() {
        val target = previewTarget
        previewTarget = null
        serviceScope.launch {
            val active = stream ?: return@launch
            runCatching {
                if (active.isOnPreview) active.stopPreview(true)
                else if (target != null) active.stopPreview(true)
            }.onFailure { SafeDiagnostics.event("PREVIEW_DETACH_FAILED") }
        }
    }

    /** True includes an RTMP attempt currently reconnecting after an error callback. */
    fun isSessionRunning(): Boolean = stream?.isStreaming == true || sessionStartElapsedMs != null

    /** Release a preview-only pipeline when the editor is left or the screen is closed. */
    fun endPreviewSession() {
        if (isSessionRunning()) {
            detachPreview()
            return
        }
        serviceScope.launch {
            commandMutex.withLock {
                val active = stream
                if (active != null) withContext(Dispatchers.IO) { runCatching { active.release() } }
                stream = null
                currentRequest = null
                activeRequestFingerprint = null
                previewTarget = null
                releaseWakeLock()
                val state = mutableSnapshot.value.state
                if (state !in setOf(IDLE, STOPPED)) {
                    transition(STOPPING)
                    transition(STOPPED)
                }
                setSnapshot {
                    it.copy(
                        state = STOPPED,
                        statusText = "STOPPED",
                        errorMessage = null,
                        engineStatus = "READY",
                        rtmpStatus = "DISCONNECTED",
                        ingestStatus = "NOT VERIFIED",
                        width = null,
                        height = null,
                        targetFps = null
                    )
                }
            }
        }
    }

    /** Live GL viewport updates only; encoder canvas, RTMP client and source decoder are untouched. */
    fun updateComposition(settings: CompositionSettings, previewWidth: Int, previewHeight: Int) {
        serviceScope.launch {
            commandMutex.withLock {
                val active = stream ?: return@withLock
                val request = currentRequest ?: return@withLock
                val normalized = settings.normalized()
                val updated = request.copy(composition = normalized)
                currentRequest = updated
                applyComposition(active, updated, previewWidth, previewHeight)
            }
        }
    }

    fun startLive(request: StreamRequest) {
        // startForegroundService is called while the user is in the foreground. Promotion is
        // synchronous here so the notification is visible before the encoder begins work.
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, StreamService::class.java).setAction(ACTION_PROMOTE)
            )
        }
        promoteToForeground()
        serviceScope.launch {
            commandMutex.withLock {
                if (mutableSnapshot.value.isStreaming || stream?.isStreaming == true) {
                    SafeDiagnostics.event("DUPLICATE_START_IGNORED")
                    return@withLock
                }
                if (mutableSnapshot.value.state != ENCODER_READY) {
                    fail("ENGINE_NOT_READY", "The video encoder is not ready. Try selecting the video again.")
                    return@withLock
                }
                val endpoint = try {
                    RtmpEndpoint.build(request.serverUrl, request.streamKey)
                } catch (e: IllegalArgumentException) {
                    fail("INVALID_ENDPOINT", e.message ?: "RTMP server URL or stream key is invalid")
                    return@withLock
                }
                val validation = EncoderCapabilities.check(request.canvas(), request.fps, request.videoBitrateBps())
                if (!validation.supported) {
                    fail("ENCODER_UNSUPPORTED", validation.reason ?: "Encoder initialization failed")
                    return@withLock
                }
                if (fingerprint(request) != activeRequestFingerprint) {
                    preparePreviewLocked(request)
                    if (mutableSnapshot.value.state != ENCODER_READY) return@withLock
                }

                currentRequest = request
                isStopping = false
                retryPending.set(false)
                reconnectCount = 0
                sessionStartElapsedMs = SystemClock.elapsedRealtime()
                sessionStartWallMs = System.currentTimeMillis()
                currentVideoTargetBps = request.videoBitrateBps()
                lastAdaptiveChangeMs = SystemClock.elapsedRealtime()
                stableSinceMs = SystemClock.elapsedRealtime()
                previousMetricAtMs = SystemClock.elapsedRealtime()
                previousEncodedVideoBytes = stream?.getEncodedVideoBytes() ?: 0
                previousEncodedAudioBytes = stream?.getEncodedAudioBytes() ?: 0
                lastVideoBytes = previousEncodedVideoBytes
                lastAudioBytes = previousEncodedAudioBytes
                lastEncodedFrames = stream?.getEncodedVideoFrames() ?: 0
                lastTransportBytes = safeClient()?.getBytesSend() ?: 0
                lastVideoProgressMs = SystemClock.elapsedRealtime()
                lastTransportProgressMs = SystemClock.elapsedRealtime()
                actualFps = null
                uploadBitrateBps = null
                encodedVideoBitrateBps = null
                encodedAudioBitrateBps = null
                loopCount = 0
                isStopping = false
                acquireWakeLock()
                updateState(RTMP_CONNECTING, engine = "ENCODER READY", rtmp = "CONNECTING", ingest = "NOT VERIFIED")
                setSnapshot { it.copy(errorMessage = null, reconnectCount = 0) }
                SafeDiagnostics.event("STREAM_SESSION_START")
                try {
                    withContext(Dispatchers.IO) {
                        val active = stream ?: error("streaming engine not initialized")
                        configureTransport(active, request)
                        active.startStream(endpoint)
                    }
                } catch (e: Exception) {
                    SafeDiagnostics.failure("START_FAILED")
                    fail("START_FAILED", friendlyError(e.message.orEmpty()))
                }
                updateNotification()
            }
        }
    }

    /** Reconnects on the existing RootEncoder session; it never constructs a second stream client. */
    fun retryConnection() {
        val request = currentRequest ?: return
        if (!mutableSnapshot.value.errorMessage.orEmpty().contains("network", ignoreCase = true) &&
            mutableSnapshot.value.state != RECONNECTING && mutableSnapshot.value.state != ERROR) return
        isStopping = false
        retryPending.set(false)
        if (reconnectCount >= MAX_RECONNECTS) reconnectCount = 0
        SafeDiagnostics.event("USER_RETRY_REQUESTED")
        scheduleReconnect("User requested retry", request, forced = true)
    }

    fun stopLive(explicit: Boolean = true) {
        if (isStopping) return
        isStopping = true
        serviceScope.launch {
            commandMutex.withLock {
                val before = mutableSnapshot.value
                val startedAt = sessionStartElapsedMs
                if (startedAt == null && stream == null) {
                    transition(STOPPED)
                    setSnapshot { it.copy(state = STOPPED, statusText = "STOPPED", engineStatus = "READY", rtmpStatus = "DISCONNECTED") }
                    finishForeground()
                    return@withLock
                }
                transition(STOPPING)
                setSnapshot {
                    it.copy(state = STOPPING, statusText = "STOPPING", engineStatus = "STOPPING", rtmpStatus = "STOPPING")
                }
                updateNotification()
                val finalDuration = startedAt?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) }
                    ?: before.elapsedMs
                val request = currentRequest
                val result = if (explicit) "Stopped" else (before.errorMessage ?: "Stream ended")
                withContext(Dispatchers.IO) {
                    runCatching { stream?.release() }
                        .onFailure { SafeDiagnostics.event("ENGINE_RELEASE_FAILED") }
                }
                stream = null
                activeRequestFingerprint = null
                previewTarget = null
                currentRequest = null
                sessionStartElapsedMs = null
                releaseWakeLock()
                retryPending.set(false)
                if (request != null && sessionStartWallMs != null) {
                    val canvas = request.canvas()
                    StreamSettingsStore(this@StreamService).addHistory(
                        LiveHistoryEntry(
                            name = request.streamName.ifBlank { "Untitled live" },
                            format = request.format,
                            resolution = "${canvas.width}×${canvas.height}",
                            startedAtMs = sessionStartWallMs ?: System.currentTimeMillis(),
                            durationMs = finalDuration,
                            result = result
                        )
                    )
                }
                sessionStartWallMs = null
                transition(STOPPED)
                setSnapshot {
                    it.copy(
                        state = STOPPED,
                        statusText = "STOPPED",
                        errorMessage = null,
                        engineStatus = "READY",
                        rtmpStatus = "DISCONNECTED",
                        ingestStatus = "NOT VERIFIED",
                        elapsedMs = finalDuration,
                        startedAtElapsedMs = null,
                        width = null,
                        height = null,
                        targetFps = null,
                        actualFps = null,
                        encodedVideoBitrateBps = null,
                        encodedAudioBitrateBps = null,
                        uploadBitrateBps = null,
                        droppedVideoFrames = null,
                        encodedVideoFrames = null,
                        sentBytes = null,
                        sendQueueBytes = null,
                        reconnectCount = reconnectCount
                    )
                }
                SafeDiagnostics.event("STREAM_SESSION_STOPPED")
                finishForeground()
            }
        }
    }

    override fun onConnectionStarted(url: String) {
        // The URL contains the stream key. Deliberately never log or display it.
        retryPending.set(false)
        if (isStopping) return
        SafeDiagnostics.event("RTMP_CONNECTING")
        updateState(RTMP_CONNECTING, engine = "ENCODING", rtmp = "CONNECTING", ingest = "NOT VERIFIED")
    }

    override fun onConnectionSuccess() {
        if (isStopping) return
        // RootEncoder receives NetStream.Publish.Start from the RTMP server. This confirms
        // RTMP publishing only, not that YouTube has processed the stream or made it LIVE.
        SafeDiagnostics.event("RTMP_PUBLISHING")
        retryPending.set(false)
        reconnectCount = reconnectCount.coerceAtLeast(0)
        stableSinceMs = SystemClock.elapsedRealtime()
        updateState(RTMP_PUBLISHING, engine = "ENCODING", rtmp = "PUBLISHING", ingest = "NOT CONFIRMED")
        setSnapshot { it.copy(errorMessage = null, reconnectCount = reconnectCount) }
        updateNotification()
    }

    override fun onConnectionFailed(reason: String) {
        if (isStopping) return
        val message = friendlyError(reason)
        if (isTerminalPublishError(reason)) {
            retryPending.set(false)
            SafeDiagnostics.failure(errorCode(reason))
            fail(errorCode(reason), message)
            return
        }
        SafeDiagnostics.event("RTMP_CONNECTION_INTERRUPTED")
        scheduleReconnect(reason, currentRequest)
    }

    override fun onDisconnect() {
        if (isStopping) return
        if (mutableSnapshot.value.state !in setOf(STOPPED, STOPPING, IDLE)) {
            scheduleReconnect("RTMP transport disconnected", currentRequest)
        }
    }

    override fun onAuthError() {
        if (isStopping) return
        retryPending.set(false)
        SafeDiagnostics.failure("AUTH_REJECTED")
        fail("AUTH_REJECTED", "The RTMP server rejected the stream key. Check the key in YouTube Studio.")
    }

    override fun onAuthSuccess() {
        // Never log credentials, challenge/response values or the endpoint.
        SafeDiagnostics.event("RTMP_AUTH_ACCEPTED")
    }

    override fun onNewBitrate(bitrate: Long) {
        uploadBitrateBps = bitrate.takeIf { it >= 0 }
        adaptBitrateIfNeeded(bitrate)
    }

    override fun onCodecError(type: CodecUtil.CodecTypeError, e: android.media.MediaCodec.CodecException) {
        SafeDiagnostics.failure("MEDIA_CODEC_ERROR")
        serviceScope.launch {
            fail("MEDIA_CODEC_ERROR", if (type == CodecUtil.CodecTypeError.VIDEO_CODEC) {
                "Video encoder initialization failed. Try 720p or 30 FPS."
            } else {
                "Audio encoder initialization failed for this video's audio track."
            })
        }
    }

    override fun onEncodeError(type: CodecUtil.CodecTypeError, e: IllegalStateException): Boolean {
        SafeDiagnostics.failure("MEDIA_CODEC_RUNTIME_ERROR")
        serviceScope.launch {
            fail("MEDIA_CODEC_RUNTIME_ERROR", if (type == CodecUtil.CodecTypeError.VIDEO_CODEC) {
                "The video encoder stopped unexpectedly. Stop and try again at a lower quality."
            } else {
                "The audio encoder stopped unexpectedly."
            })
        }
        return true
    }

    override fun onDestroy() {
        isStopping = true
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        releaseWakeLock()
        notificationJob?.cancel()
        runCatching { stream?.release() }
        stream = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun preparePreviewLocked(request: StreamRequest) {
        val requestedFingerprint = fingerprint(request)
        if (requestedFingerprint == activeRequestFingerprint && stream != null && mutableSnapshot.value.state == ENCODER_READY) {
            currentRequest = request
            applyComposition(stream!!, request, previewTarget?.width ?: 0, previewTarget?.height ?: 0)
            return
        }
        if (stream?.isStreaming == true || mutableSnapshot.value.isStreaming) return
        transition(PREPARING)
        setSnapshot {
            it.copy(
                state = PREPARING,
                statusText = "PREPARING",
                errorMessage = null,
                engineStatus = "PREPARING",
                rtmpStatus = "DISCONNECTED",
                ingestStatus = "NOT VERIFIED",
                width = null,
                height = null,
                actualFps = null,
                encodedVideoBitrateBps = null,
                encodedAudioBitrateBps = null,
                uploadBitrateBps = null,
                droppedVideoFrames = null,
                encodedVideoFrames = null,
                sentBytes = null,
                sendQueueBytes = null
            )
        }
        updateNotification()
        try {
            withContext(Dispatchers.IO) {
                runCatching { stream?.release() }
                stream = null
                currentRequest = request
                val source = VideoFileSource(this@StreamService, request.videoAsset.uri, request.loopVideo) { looped ->
                    if (looped) {
                        loopCount += 1
                        SafeDiagnostics.event("SOURCE_VIDEO_LOOP")
                    } else if (!request.loopVideo && !isStopping) {
                        serviceScope.launch { sourceFinished() }
                    }
                }
                val audio = if (request.videoAsset.hasAudio) {
                    val channels = request.videoAsset.audioChannels ?: 2
                    if (channels !in 1..2) throw UnsupportedOperationException("Unsupported audio channel layout")
                    AudioFileSource(this@StreamService, request.videoAsset.uri, request.loopVideo) { }
                } else {
                    NoAudioSource()
                }
                val active = RtmpStream(this@StreamService, this@StreamService, source, audio)
                stream = active
                active.setVideoCodec(VideoCodec.H264)
                active.setEncoderErrorCallback(this@StreamService)
                active.setFpsListener(FpsListener.Callback { fps -> actualFps = fps.takeIf { it > 0 } })
                active.setTimestampMode(TimestampMode.CLOCK, TimestampMode.CLOCK)
                active.getStreamClient().setLogs(false)
                active.getStreamClient().setReTries(MAX_RECONNECTS)
                active.getStreamClient().setSocketTimeout(SOCKET_TIMEOUT_MS)
                active.getStreamClient().setOnlyVideo(!request.videoAsset.hasAudio)
                (active.getStreamClient() as? RtmpStreamClient)?.apply {
                    shouldSendPings(true)
                    shouldFailOnRead(true)
                }
                val capability = EncoderCapabilities.check(request.canvas(), request.fps, request.videoBitrateBps())
                if (!capability.supported) throw UnsupportedOperationException(capability.reason ?: "Encoder configuration unsupported")
                if (capability.hardwareAvailable) {
                    val audioHardware = runCatching {
                        CodecUtil.getAllHardwareEncoders(CodecUtil.AAC_MIME).isNotEmpty()
                    }.getOrDefault(false)
                    active.forceCodecType(
                        CodecUtil.CodecType.HARDWARE,
                        if (audioHardware) CodecUtil.CodecType.HARDWARE else CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
                    )
                }
                val canvas = request.canvas()
                SafeDiagnostics.event("ENCODER_PREPARING")
                val videoReady = active.prepareVideo(
                    canvas.width,
                    canvas.height,
                    request.videoBitrateBps(),
                    request.fps,
                    2,
                    0
                )
                if (!videoReady) throw IllegalStateException("Video encoder initialization failed")
                active.setVideoCodec(VideoCodec.H264)
                val sampleRate = request.videoAsset.audioSampleRate ?: 48_000
                val channels = request.videoAsset.audioChannels ?: 2
                val audioReady = active.prepareAudio(sampleRate, channels > 1, request.audioBitrateBps())
                if (!audioReady) throw IllegalStateException("Audio encoder initialization failed")
                val gl = active.getGlInterface()
                gl.setStreamRotation(request.videoAsset.sourceRotation)
                gl.setPreviewRotation(request.videoAsset.sourceRotation)
                applyComposition(active, request, previewTarget?.width ?: 0, previewTarget?.height ?: 0)
                activeRequestFingerprint = requestedFingerprint
                currentVideoTargetBps = request.videoBitrateBps()
                SafeDiagnostics.event("ENCODER_READY")
            }
            transition(ENCODER_READY)
            setSnapshot {
                val canvas = request.canvas()
                it.copy(
                    state = ENCODER_READY,
                    statusText = "ENGINE READY",
                    errorMessage = null,
                    engineStatus = "READY",
                    rtmpStatus = "DISCONNECTED",
                    ingestStatus = "NOT VERIFIED",
                    width = request.canvas().width,
                    height = request.canvas().height,
                    targetFps = request.fps,
                    reconnectCount = reconnectCount
                )
            }
            previewTarget?.let { attachPreviewOnMain(stream ?: return, it) }
            updateNotification()
        } catch (e: Exception) {
            SafeDiagnostics.failure(errorCode(e.message.orEmpty()))
            releaseWakeLock()
            activeRequestFingerprint = null
            transition(ERROR)
            setSnapshot {
                it.copy(
                    state = ERROR,
                    statusText = "ERROR",
                    errorMessage = friendlyError(e.message.orEmpty()),
                    engineStatus = "ERROR",
                    rtmpStatus = "DISCONNECTED",
                    ingestStatus = "NOT VERIFIED"
                )
            }
            withContext(Dispatchers.IO) { runCatching { stream?.release() } }
            stream = null
            updateNotification()
        }
    }

    private suspend fun attachPreviewOnMain(active: RtmpStream, textureView: TextureView) {
        withContext(Dispatchers.Main.immediate) {
            if (stream !== active) return@withContext
            runCatching { active.startPreview(textureView, true) }
                .onFailure { SafeDiagnostics.event("PREVIEW_ATTACH_FAILED") }
            applyComposition(active, currentRequest ?: return@withContext, textureView.width, textureView.height)
        }
    }

    private fun applyComposition(active: RtmpStream, request: StreamRequest, previewWidth: Int, previewHeight: Int) {
        val sourceWidth = request.videoAsset.effectiveWidth.coerceAtLeast(2)
        val sourceHeight = request.videoAsset.effectiveHeight.coerceAtLeast(2)
        val canvas = request.canvas()
        val gl = active.getGlInterface()
        gl.setStreamViewPort(
            CompositionViewport.calculate(
                sourceWidth,
                sourceHeight,
                canvas.width,
                canvas.height,
                request.composition
            ).toRootEncoder()
        )
        if (previewWidth > 0 && previewHeight > 0) {
            gl.setPreviewViewPort(
                CompositionViewport.forPreview(
                    sourceWidth,
                    sourceHeight,
                    previewWidth,
                    previewHeight,
                    request.composition
                ).toRootEncoder()
            )
        }
    }

    private fun configureTransport(active: RtmpStream, request: StreamRequest) {
        // RootEncoder uses one RtmpClient in this RtmpStream. Reconnects reuse it.
        active.getStreamClient().setReTries((MAX_RECONNECTS - reconnectCount).coerceAtLeast(1))
        active.getStreamClient().setLogs(false)
        (active.getStreamClient() as? RtmpStreamClient)?.apply {
            shouldSendPings(true)
            shouldFailOnRead(true)
        }
        SafeDiagnostics.event(if (request.serverUrl.trim().startsWith("rtmps://", true)) "RTMPS_TRANSPORT_SELECTED" else "RTMP_TRANSPORT_SELECTED")
    }

    private suspend fun monitorSession() {
        while (serviceJob.isActive) {
            delay(1000)
            val active = stream
            val request = currentRequest
            val now = SystemClock.elapsedRealtime()
            if (active != null && request != null) {
                sampleMetrics(active, request, now)
                val state = mutableSnapshot.value.state
                if (state == RTMP_PUBLISHING || state == INGEST_VERIFYING || state == LIVE_VERIFIED) {
                    val frames = active.getEncodedVideoFrames()
                    val bytes = safeClient()?.getBytesSend() ?: 0L
                    if (frames > lastEncodedFrames) {
                        lastEncodedFrames = frames
                        lastVideoProgressMs = now
                    } else if (now - lastVideoProgressMs > ENCODER_STALL_MS) {
                        fail("ENCODER_STALLED", "The video encoder stopped producing frames. Stop Live and try again.")
                    }
                    if (bytes > lastTransportBytes) {
                        lastTransportBytes = bytes
                        lastTransportProgressMs = now
                    } else if (frames > 0 && now - lastTransportProgressMs > TRANSPORT_STALL_MS) {
                        scheduleReconnect("RTMP transport stopped sending data", request)
                    }
                    considerAdaptiveBitrate(active, request, now)
                } else if (state == RECONNECTING && networkConnected == true && !retryPending.get()) {
                    scheduleReconnect("Network restored", request)
                }
            }
            refreshDurationAndNotification(now)
        }
    }

    private fun sampleMetrics(active: RtmpStream, request: StreamRequest, now: Long) {
        val elapsed = (now - previousMetricAtMs).coerceAtLeast(1L)
        val videoBytes = active.getEncodedVideoBytes()
        val audioBytes = active.getEncodedAudioBytes()
        if (videoBytes >= previousEncodedVideoBytes) {
            encodedVideoBitrateBps = ((videoBytes - previousEncodedVideoBytes) * 8_000L / elapsed)
                .takeIf { active.getEncodedVideoFrames() > 0 }
        }
        if (audioBytes >= previousEncodedAudioBytes) {
            encodedAudioBitrateBps = if (request.videoAsset.hasAudio && active.getEncodedAudioFrames() > 0) {
                (audioBytes - previousEncodedAudioBytes) * 8_000L / elapsed
            } else null
        }
        previousEncodedVideoBytes = videoBytes
        previousEncodedAudioBytes = audioBytes
        previousMetricAtMs = now
        val client = active.getStreamClient()
        val canvas = request.canvas()
        val snapshot = mutableSnapshot.value
        setSnapshot {
            it.copy(
                width = canvas.width,
                height = canvas.height,
                targetFps = request.fps,
                actualFps = actualFps,
                encodedVideoBitrateBps = encodedVideoBitrateBps,
                encodedAudioBitrateBps = encodedAudioBitrateBps,
                uploadBitrateBps = uploadBitrateBps,
                droppedVideoFrames = client.getDroppedVideoFrames(),
                encodedVideoFrames = active.getEncodedVideoFrames(),
                sentBytes = client.getBytesSend(),
                sendQueueBytes = client.getItemsInCache().toLong(),
                reconnectCount = reconnectCount,
                networkConnected = networkConnected,
                loopCount = loopCount
            )
        }
    }

    private fun considerAdaptiveBitrate(active: RtmpStream, request: StreamRequest, now: Long) {
        if (request.bitrateMode != BitrateMode.AUTO || now - lastAdaptiveChangeMs < ADAPTIVE_INTERVAL_MS) return
        val client = active.getStreamClient()
        val dropped = client.getDroppedVideoFrames()
        val queued = client.getItemsInCache()
        val hasCongestion = runCatching { client.hasCongestion(25f) }.getOrDefault(false) ||
            (dropped > lastAdaptDroppedFrames) || queued >= QUEUE_CONGESTION_FRAMES
        if (hasCongestion) {
            val lowerBound = 900_000
            val next = max(lowerBound, (currentVideoTargetBps * 0.85f).toInt())
            if (next < currentVideoTargetBps) {
                active.setVideoBitrateOnFly(next)
                currentVideoTargetBps = next
                lastAdaptiveChangeMs = now
                lastAdaptDroppedFrames = dropped
                stableSinceMs = now
                SafeDiagnostics.event("ADAPTIVE_BITRATE_REDUCED")
            }
            return
        }
        if (queued <= 2 && dropped == lastAdaptDroppedFrames && now - stableSinceMs >= BITRATE_RECOVERY_MS) {
            val ceiling = request.videoBitrateBps()
            val next = min(ceiling, max(currentVideoTargetBps + 1, (currentVideoTargetBps * 1.05f).toInt()))
            if (next > currentVideoTargetBps) {
                active.setVideoBitrateOnFly(next)
                currentVideoTargetBps = next
                lastAdaptiveChangeMs = now
                stableSinceMs = now
                SafeDiagnostics.event("ADAPTIVE_BITRATE_RECOVERED")
            }
        }
    }

    private fun scheduleReconnect(reason: String, request: StreamRequest?, forced: Boolean = false) {
        if (isStopping || request == null || stream == null) return
        if (!retryPending.compareAndSet(false, true)) return
        val attempt = reconnectCount + 1
        if (!forced && attempt > MAX_RECONNECTS) {
            retryPending.set(false)
            SafeDiagnostics.failure("RECONNECT_LIMIT_REACHED")
            fail("RECONNECT_LIMIT_REACHED", "Network reconnect limit reached. Check your connection, then retry or stop Live.")
            return
        }
        reconnectCount = attempt
        transition(RECONNECTING)
        setSnapshot {
            it.copy(
                state = RECONNECTING,
                statusText = "RECONNECTING",
                errorMessage = null,
                engineStatus = "ENCODING",
                rtmpStatus = "RECONNECTING",
                ingestStatus = "NOT CONFIRMED",
                reconnectCount = reconnectCount
            )
        }
        SafeDiagnostics.event("RECONNECTING_ATTEMPT_$attempt")
        updateNotification()
        serviceScope.launch(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            while (!isStopping && serviceJob.isActive && networkConnected != true &&
                SystemClock.elapsedRealtime() - startedAt < NETWORK_WAIT_LIMIT_MS) {
                delay(1000)
            }
            if (isStopping || !serviceJob.isActive) {
                retryPending.set(false)
                return@launch
            }
            if (networkConnected != true) {
                retryPending.set(false)
                fail("NETWORK_UNAVAILABLE", "Network connection lost. Reconnect to the internet and tap Retry.")
                return@launch
            }
            val active = stream
            if (active == null) {
                retryPending.set(false)
                fail("ENGINE_UNAVAILABLE", "Streaming engine is not available. Stop Live and start again.")
                return@launch
            }
            val delayMs = if (forced) 0L else min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceIn(0, 5)))
            delay(delayMs)
            if (isStopping) {
                retryPending.set(false)
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                updateState(RECONNECTING, engine = "ENCODING", rtmp = "RECONNECTING", ingest = "NOT CONFIRMED")
            }
            active.getStreamClient().setReTries((MAX_RECONNECTS - reconnectCount).coerceAtLeast(1))
            val accepted = runCatching { active.getStreamClient().reTry(delayMs, sanitizeReason(reason)) }
                .getOrDefault(false)
            if (!accepted) {
                retryPending.set(false)
                if (forced) {
                    fail("RETRY_UNAVAILABLE", "RTMP retry could not be started. Stop Live and start a new session.")
                } else {
                    fail("RECONNECT_LIMIT_REACHED", "RTMP reconnect could not be started. Check the server URL and network.")
                }
            }
        }
    }

    private suspend fun sourceFinished() {
        if (currentRequest?.loopVideo == true || isStopping) return
        fail("SOURCE_FINISHED", "Video finished. Stop Live before starting another video.")
    }

    private fun updateNetworkState(connected: Boolean) {
        val previous = networkConnected
        networkConnected = connected
        setSnapshot { it.copy(networkConnected = connected) }
        if (previous == true && !connected && mutableSnapshot.value.isStreaming && !isStopping) {
            SafeDiagnostics.event("NETWORK_LOST")
            scheduleReconnect("Network connection lost", currentRequest)
        } else if (!previous.orFalse() && connected && mutableSnapshot.value.state == RECONNECTING) {
            SafeDiagnostics.event("NETWORK_RESTORED")
        }
    }

    private fun refreshDurationAndNotification(now: Long) {
        val start = sessionStartElapsedMs
        if (start != null) {
            val elapsed = (now - start).coerceAtLeast(0L)
            setSnapshot { it.copy(elapsedMs = elapsed, startedAtElapsedMs = start, networkConnected = networkConnected) }
            if (foreground) updateNotification()
        }
    }

    private fun updateState(next: StreamState, engine: String, rtmp: String, ingest: String) {
        transition(next)
        setSnapshot {
            it.copy(
                state = next,
                statusText = statusFor(next),
                engineStatus = engine,
                rtmpStatus = rtmp,
                ingestStatus = ingest,
                networkConnected = networkConnected,
                reconnectCount = reconnectCount
            )
        }
    }

    private fun transition(next: StreamState) {
        val previous = stateMachine.current
        if (previous == next) return
        if (stateMachine.moveTo(next)) SafeDiagnostics.transition(previous, next)
        else {
            SafeDiagnostics.event("INVALID_STATE_TRANSITION_${previous.name}_TO_${next.name}")
            // Keep snapshots honest even if a vendor callback arrives out of order.
            if (next == ERROR || next == STOPPING || next == STOPPED) stateMachine.moveTo(next)
        }
    }

    private fun fail(code: String, message: String) {
        retryPending.set(false)
        SafeDiagnostics.failure(code)
        val current = mutableSnapshot.value.state
        if (current !in setOf(ERROR, STOPPING, STOPPED)) transition(ERROR)
        setSnapshot {
            it.copy(
                state = ERROR,
                statusText = "ERROR",
                errorMessage = message,
                engineStatus = if (code.startsWith("ENCODER") || code.startsWith("MEDIA_CODEC")) "ERROR" else "RUNNING",
                rtmpStatus = "ERROR",
                ingestStatus = "NOT CONFIRMED",
                reconnectCount = reconnectCount
            )
        }
        updateNotification()
    }

    private fun setSnapshot(change: (SessionSnapshot) -> SessionSnapshot) {
        mutableSnapshot.update(change)
    }

    private fun safeClient() = stream?.getStreamClient()

    private fun acquireWakeLock() {
        runCatching {
            val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MihadLive:Streaming")
            lock.setReferenceCounted(false)
            // Released on user stop/service destruction. No timeout avoids a hard 12-hour cutoff.
            lock.acquire()
            wakeLock = lock
        }.onFailure { SafeDiagnostics.event("WAKELOCK_UNAVAILABLE") }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun promoteToForeground() {
        if (foreground) return
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Notifications.NOTIFICATION_ID_STREAM,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(Notifications.NOTIFICATION_ID_STREAM, notification)
            }
            foreground = true
            notificationJob?.cancel()
            notificationJob = serviceScope.launch {
                while (foreground) {
                    delay(1000)
                    updateNotification()
                }
            }
        }.onFailure {
            SafeDiagnostics.event("FOREGROUND_PROMOTION_FAILED")
            fail("FOREGROUND_SERVICE", "Android could not keep Mihad Live running in the background. Check notification permission and try again.")
        }
    }

    private fun updateNotification() {
        if (!foreground) return
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(Notifications.NOTIFICATION_ID_STREAM, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val snapshot = mutableSnapshot.value
        val request = currentRequest
        val duration = formatDuration(snapshot.elapsedMs)
        val title = "MIHAD LIVE"
        val body = when (snapshot.state) {
            RTMP_PUBLISHING, INGEST_VERIFYING, LIVE_VERIFIED -> "Streaming to RTMP — $duration"
            RECONNECTING -> "Reconnecting — $duration"
            RTMP_CONNECTING, RTMP_CONNECTED -> "Connecting — $duration"
            STOPPING -> "Stopping stream…"
            ERROR -> snapshot.errorMessage ?: "Stream needs attention"
            else -> "Preparing live stream"
        }
        val formatLine = request?.let {
            val canvas = it.canvas()
            "${canvas.width}×${canvas.height} • ${it.fps} FPS"
        }
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_STREAM)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle(title)
            .setContentText(if (formatLine != null) "$body\n$formatLine" else body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (formatLine != null) "$body\n$formatLine" else body))
            .setContentIntent(openIntent)
            .setOngoing(snapshot.state != STOPPED && snapshot.state != IDLE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "STOP LIVE", stopIntent)
            .build()
    }

    private fun finishForeground() {
        notificationJob?.cancel()
        notificationJob = null
        if (foreground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        foreground = false
        stopSelf()
    }

    private fun hasInternetNetwork(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        return isInternetAvailable(network)
    }

    private fun isInternetAvailable(network: Network): Boolean {
        val manager = connectivityManager ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
    }

    private fun adaptBitrateIfNeeded(bitrate: Long) {
        // bitrate is an actual sender measurement in bits/sec; it is not treated as proof of ingest.
        if (bitrate < 0) return
    }

    private fun sanitizeReason(reason: String): String = when {
        reason.contains("timeout", true) -> "connection timeout"
        reason.contains("network", true) || reason.contains("socket", true) -> "network connection lost"
        else -> "RTMP transport interrupted"
    }

    private fun isTerminalPublishError(reason: String): Boolean {
        val lower = reason.lowercase()
        return lower.contains("endpoint malformed") ||
            lower.contains("publish.badname") ||
            lower.contains("publish.failed") ||
            lower.contains("connect.rejected") ||
            lower.contains("auth")
    }

    private fun friendlyError(reason: String): String {
        val lower = reason.lowercase()
        return when {
            lower.contains("endpoint malformed") || lower.contains("invalid rtmp") -> "RTMP server URL is invalid. Check the server address and application path."
            lower.contains("authfail") || lower.contains("nosuchuser") || lower.contains("auth") -> "The RTMP server rejected the stream key. Check the key in YouTube Studio."
            lower.contains("publish.badname") || lower.contains("publish.failed") || lower.contains("connect.rejected") -> "YouTube rejected the stream. Check the stream key and live encoder settings."
            lower.contains("unsupported") || lower.contains("format") -> "Unsupported video format. Choose an MP4 or another Android-supported video."
            lower.contains("network") || lower.contains("socket") || lower.contains("timeout") || lower.contains("host") || lower.contains("response from server") -> "Network connection lost or RTMP server did not respond."
            lower.contains("audio encoder") -> "Audio encoder initialization failed for this video's audio track."
            lower.contains("video encoder") || lower.contains("codec") -> "Encoder initialization failed. Try 720p or 30 FPS."
            else -> "RTMP connection failed. Check the server URL, stream key and network."
        }
    }

    private fun errorCode(reason: String): String {
        val lower = reason.lowercase()
        return when {
            lower.contains("endpoint") || lower.contains("url") -> "INVALID_URL"
            lower.contains("auth") || lower.contains("publish.badname") -> "PUBLISH_REJECTED"
            lower.contains("audio") -> "AUDIO_ENCODER"
            lower.contains("codec") || lower.contains("video") -> "VIDEO_ENCODER"
            lower.contains("network") || lower.contains("socket") || lower.contains("timeout") -> "NETWORK"
            else -> "RTMP"
        }
    }

    private fun fingerprint(request: StreamRequest): String = listOf(
        request.videoAsset.uri.toString(),
        request.videoAsset.sourceWidth,
        request.videoAsset.sourceHeight,
        request.videoAsset.sourceRotation,
        request.format.name,
        request.quality.name,
        request.fps,
        request.bitrateMode.name,
        request.videoBitrateBps(),
        request.videoAsset.hasAudio,
        request.videoAsset.audioSampleRate,
        request.videoAsset.audioChannels,
        request.loopVideo
    ).joinToString("|")

    private fun statusFor(state: StreamState): String = when (state) {
        IDLE -> "READY"
        PREPARING -> "PREPARING"
        ENCODER_READY -> "ENGINE READY"
        RTMP_CONNECTING, RTMP_CONNECTED -> "CONNECTING"
        RTMP_PUBLISHING, INGEST_VERIFYING -> "STREAMING TO RTMP"
        LIVE_VERIFIED -> "LIVE"
        RECONNECTING -> "RECONNECTING"
        STOPPING -> "STOPPING"
        STOPPED -> "STOPPED"
        ERROR -> "ERROR"
    }

    private fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds / 1000L).coerceAtLeast(0L)
        return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    private fun Boolean?.orFalse(): Boolean = this ?: false

    companion object {
        const val ACTION_PROMOTE = "com.mihad.live.action.PROMOTE"
        const val ACTION_STOP = "com.mihad.live.action.STOP"

        private const val MAX_RECONNECTS = 8
        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val NETWORK_WAIT_LIMIT_MS = 5 * 60_000L
        private const val SOCKET_TIMEOUT_MS = 15_000L
        private const val ENCODER_STALL_MS = 10_000L
        private const val TRANSPORT_STALL_MS = 18_000L
        private const val ADAPTIVE_INTERVAL_MS = 20_000L
        private const val BITRATE_RECOVERY_MS = 60_000L
        private const val QUEUE_CONGESTION_FRAMES = 100
    }
}
