package com.mihad.live.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.TextureView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mihad.live.R
import com.mihad.live.engine.BitrateMode
import com.mihad.live.engine.CompositionMode
import com.mihad.live.engine.CompositionSettings
import com.mihad.live.engine.EncoderCapabilities
import com.mihad.live.engine.LiveFormat
import com.mihad.live.engine.LiveHistoryEntry
import com.mihad.live.engine.MediaProbe
import com.mihad.live.engine.RtmpEndpoint
import com.mihad.live.engine.SavedProject
import com.mihad.live.engine.SessionSnapshot
import com.mihad.live.engine.StreamRequest
import com.mihad.live.engine.StreamService
import com.mihad.live.engine.StreamSettingsStore
import com.mihad.live.engine.StreamState
import com.mihad.live.engine.VideoAsset
import com.mihad.live.engine.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Screen { HOME, FORMAT, VIDEO, EDITOR, DASHBOARD, SETTINGS, HISTORY }

    private lateinit var root: FrameLayout
    private val settings by lazy { StreamSettingsStore(applicationContext) }
    private var screen = Screen.HOME
    private var liveFormat = LiveFormat.LANDSCAPE
    private var quality = VideoQuality.P720
    private var fps = 30
    private var bitrateMode = BitrateMode.AUTO
    private var customBitrateBps = 2_500_000
    private var loopVideo = true
    private var composition = CompositionSettings()
    private var asset: VideoAsset? = null
    private var streamNameValue = "Mihad Live"
    private var serverUrlValue = ""
    private var streamKeyValue = ""
    private var rememberKey = false
    private var service: StreamService? = null
    private var bound = false
    private var lastSnapshot = SessionSnapshot()
    private var snapshotJob: Job? = null
    private var pendingLiveRequest: StreamRequest? = null
    private var permissionPromptActive = false
    private var previewTexture: TextureView? = null
    private var previewHost: AspectCanvasHost? = null
    private var editorStatus: TextView? = null
    private var editorOutputInfo: TextView? = null
    private var editorStartButton: MaterialButton? = null
    private var dashboardStatus: TextView? = null
    private var dashboardSubstatus: TextView? = null
    private var dashboardDuration: TextView? = null
    private var dashboardRtmpState: TextView? = null
    private var dashboardEngineState: TextView? = null
    private var dashboardIngestState: TextView? = null
    private var dashboardError: TextView? = null
    private var dashboardRetryButton: MaterialButton? = null
    private var dashboardDiagnostics: TextView? = null
    private var dashboardDiagnosticsContainer: View? = null
    private var dashboardDiagnosticsToggle: MaterialButton? = null
    private val dashboardMetrics = mutableMapOf<String, TextView>()
    private var preparePreviewJob: Job? = null
    private val uiHandler by lazy { android.os.Handler(mainLooper) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? StreamService.LocalBinder ?: return
            service = local.service()
            bound = true
            observeEngine(service!!)
            when (screen) {
                Screen.EDITOR -> ensureEditorPreview()
                Screen.DASHBOARD -> previewTexture?.let { service?.attachPreview(it) }
                else -> Unit
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            snapshotJob?.cancel()
            snapshotJob = null
            service = null
            bound = false
        }
    }

    private val openVideoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleVideoSelected(uri)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingLiveRequest
        pendingLiveRequest = null
        if (granted && request != null) {
            permissionPromptActive = false
            beginLive(request)
        } else if (request != null) {
            showNotificationPermissionChoice(request)
        } else {
            permissionPromptActive = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = colorResource(R.color.ml_bg)
        window.navigationBarColor = colorResource(R.color.ml_black)
        window.decorView.systemUiVisibility = 0
        streamNameValue = "Mihad Live"
        serverUrlValue = settings.serverUrl()
        settings.rememberedKey()?.let {
            streamKeyValue = it
            rememberKey = true
        }
        root = FrameLayout(this).apply { setBackgroundColor(colorResource(R.color.ml_bg)) }
        setContentView(root)
        showHome()
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bound = bindService(Intent(this, StreamService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        } else when (screen) {
            Screen.EDITOR -> ensureEditorPreview()
            Screen.DASHBOARD -> previewTexture?.let { service?.attachPreview(it) }
            else -> Unit
        }
    }

    override fun onStop() {
        // The Android notification-permission sheet is a transient system UI; keep the
        // prepared encoder alive until its result returns to the editor.
        if (pendingLiveRequest == null && !permissionPromptActive) {
            val engine = service
            if (engine != null) {
                if (engine.isSessionRunning()) engine.detachPreview()
                else engine.endPreviewSession()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        snapshotJob?.cancel()
        if (bound) {
            runCatching { unbindService(serviceConnection) }
            bound = false
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.HOME -> super.onBackPressed()
            Screen.FORMAT -> showHome()
            Screen.VIDEO -> showFormatPicker()
            Screen.EDITOR -> {
                service?.endPreviewSession()
                showVideoPickerScreen()
            }
            Screen.DASHBOARD -> showHome() // foreground service keeps a started stream alive
            Screen.SETTINGS, Screen.HISTORY -> showHome()
        }
    }

    private fun observeEngine(engine: StreamService) {
        snapshotJob?.cancel()
        snapshotJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.snapshots.collect { snapshot ->
                    lastSnapshot = snapshot
                    renderSnapshot(snapshot)
                }
            }
        }
    }

    private fun setScreen(view: View, screen: Screen) {
        this.screen = screen
        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun makeShell(title: String, onBack: (() -> Unit)?): Pair<LinearLayout, LinearLayout> {
        val shell = vertical().apply {
            setBackgroundColor(colorResource(R.color.ml_bg))
            fitsSystemWindows = true
        }
        shell.addView(header(title, onBack), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val (scroll, body) = scrollColumn()
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return shell to body
    }

    private fun showHome() {
        editorStatus = null
        dashboardStatus = null
        val (shell, body) = makeShell("MIHAD LIVE", null)
        shell.removeViewAt(0) // Home has its own branded masthead below.
        val top = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        val brandDot = label("◉", 29f, R.color.ml_cyan, bold = true)
        top.addView(brandDot, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(vertical().apply {
            addView(label("MIHAD LIVE", 22f, R.color.ml_text, bold = true))
            addView(label("Stream. Stay Live.", 12f, R.color.ml_text_secondary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settingsButton = button("⚙", stroke = true).apply {
            minWidth = dp(48); maxWidth = dp(48); minHeight = dp(48); textSize = 20f
            setOnClickListener { showSettings() }
        }
        top.addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(top)
        body.addSpace(24)

        val active = service?.isSessionRunning() == true || lastSnapshot.isStreaming
        body.addView(engineCard(active))
        body.addSpace(18)
        val create = button(if (active) "RETURN TO LIVE DASHBOARD" else "+  CREATE NEW LIVE", primary = true).apply {
            textSize = 15f
            minHeight = dp(62)
            setOnClickListener {
                if (service?.isSessionRunning() == true || lastSnapshot.isStreaming) showDashboard()
                else showFormatPicker()
            }
        }
        body.addView(create, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        body.addSpace(28)
        body.addView(sectionTitle("RECENT PROJECTS"))
        body.addSpace(10)
        val projects = settings.projects()
        if (projects.isEmpty()) {
            body.addView(emptyPanel("No projects yet. Tap CREATE NEW LIVE to start."))
        } else {
            projects.take(4).forEach { project ->
                body.addView(projectCard(project), marginParams(bottom = 10))
            }
        }
        body.addSpace(18)
        val historyHead = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        historyHead.addView(sectionTitle("LIVE HISTORY"), LinearLayout.LayoutParams(0, dp(28), 1f))
        historyHead.addView(button("VIEW ALL", stroke = true).apply {
            minHeight = dp(36); textSize = 11f
            setOnClickListener { showHistory() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        body.addView(historyHead)
        body.addSpace(10)
        val history = settings.history()
        if (history.isEmpty()) body.addView(emptyPanel("No live history yet."))
        else history.take(2).forEach { body.addView(historyCard(it), marginParams(bottom = 10)) }
        body.addSpace(18)
        body.addView(settingsRow())
        setScreen(shell, Screen.HOME)
        if (service?.isSessionRunning() == true) previewTexture?.let { service?.detachPreview() }
    }

    private fun engineCard(active: Boolean): MaterialCardView {
        val card = card(if (active) R.color.ml_cyan_dim else R.color.ml_stroke)
        val row = horizontal().apply { setPadding(dp(16), dp(15), dp(16), dp(15)) }
        row.addView(label(if (active) "●" else "◌", 18f, if (active) R.color.ml_cyan else R.color.ml_ok, bold = true), LinearLayout.LayoutParams(dp(30), dp(30)))
        row.addView(vertical().apply {
            addView(label("STREAMING ENGINE", 11f, R.color.ml_text_secondary, bold = true))
            addView(label(if (active) "SESSION ACTIVE" else "READY", 15f, if (active) R.color.ml_cyan else R.color.ml_ok, bold = true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("RTMP · MediaCodec", 11f, R.color.ml_text_muted))
        card.addView(row)
        return card
    }

    private fun settingsRow(): MaterialCardView {
        val card = card()
        val row = horizontal().apply {
            setPadding(dp(16), dp(15), dp(16), dp(15))
            isClickable = true
            isFocusable = true
            setOnClickListener { showSettings() }
        }
        row.addView(label("⚙", 20f, R.color.ml_cyan), LinearLayout.LayoutParams(dp(32), dp(32)))
        row.addView(vertical().apply {
            addView(label("Settings", 15f, R.color.ml_text, bold = true))
            addView(label("Privacy, notifications and app information", 11f, R.color.ml_text_secondary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("›", 24f, R.color.ml_text_secondary))
        card.addView(row)
        return card
    }

    private fun projectCard(project: SavedProject): MaterialCardView {
        val card = card()
        val row = horizontal().apply {
            setPadding(dp(15), dp(14), dp(15), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { loadProject(project) }
        }
        row.addView(RatioVisualView(this, project.format, false), LinearLayout.LayoutParams(dp(58), dp(50)))
        row.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        row.addView(vertical().apply {
            addView(label(project.name, 14f, R.color.ml_text, bold = true, allCaps = false))
            addView(label("${project.format.label}  •  ${project.quality.label}", 10f, R.color.ml_text_secondary))
            addView(label(project.sourceName, 11f, R.color.ml_text_muted))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("›", 22f, R.color.ml_text_secondary))
        card.addView(row)
        return card
    }

    private fun historyCard(entry: LiveHistoryEntry): MaterialCardView {
        val card = card()
        val row = horizontal().apply { setPadding(dp(15), dp(13), dp(15), dp(13)) }
        row.addView(label("●", 14f, if (entry.result == "Stopped") R.color.ml_cyan else R.color.ml_warn), LinearLayout.LayoutParams(dp(25), dp(25)))
        row.addView(vertical().apply {
            addView(label(entry.name, 13f, R.color.ml_text, bold = true))
            addView(label("${entry.resolution}  •  ${formatDuration(entry.durationMs)}", 11f, R.color.ml_text_secondary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(entry.result.uppercase(Locale.ROOT), 10f, R.color.ml_text_muted, bold = true))
        card.addView(row)
        return card
    }

    private fun emptyPanel(message: String): MaterialCardView {
        val panel = card(R.color.ml_stroke_soft, 16)
        panel.addView(label(message, 12f, R.color.ml_text_muted).apply { setPadding(dp(14), dp(17), dp(14), dp(17)) })
        return panel
    }

    private fun showFormatPicker() {
        val (shell, body) = makeShell("CREATE NEW LIVE") { showHome() }
        body.addView(label("SELECT LIVE FORMAT", 12f, R.color.ml_text_secondary, bold = true).apply { letterSpacing = .12f })
        body.addSpace(7)
        body.addView(label("Choose the exact canvas you want to send.", 13f, R.color.ml_text_secondary))
        body.addSpace(20)
        val landscape = formatCard(LiveFormat.LANDSCAPE, selected = liveFormat == LiveFormat.LANDSCAPE) {
            liveFormat = LiveFormat.LANDSCAPE
            showFormatPicker()
        }
        body.addView(landscape, marginParams(bottom = 12))
        val vertical = formatCard(LiveFormat.VERTICAL, selected = liveFormat == LiveFormat.VERTICAL) {
            liveFormat = LiveFormat.VERTICAL
            showFormatPicker()
        }
        body.addView(vertical, marginParams(bottom = 20))
        body.addView(label("The encoded output canvas is fixed to the selected ratio. Source video is fitted or cropped inside it; it is never stretched.", 12f, R.color.ml_text_muted))
        body.addSpace(28)
        body.addView(button("CONTINUE", primary = true).apply {
            setOnClickListener { showVideoPickerScreen() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        setScreen(shell, Screen.FORMAT)
    }

    private fun formatCard(format: LiveFormat, selected: Boolean, onClick: () -> Unit): MaterialCardView {
        val card = card(if (selected) R.color.ml_cyan else R.color.ml_stroke, 20).apply {
            strokeWidth = dp(if (selected) 2 else 1)
            setCardBackgroundColor(colorResource(if (selected) R.color.ml_cyan_deep else R.color.ml_surface))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val row = horizontal().apply { setPadding(dp(17), dp(17), dp(17), dp(17)) }
        row.addView(RatioVisualView(this, format, selected), LinearLayout.LayoutParams(dp(104), dp(76)))
        row.addView(View(this), LinearLayout.LayoutParams(dp(17), 1))
        row.addView(vertical().apply {
            addView(label(format.label, 14f, if (selected) R.color.ml_cyan else R.color.ml_text, bold = true))
            addView(label(if (format == LiveFormat.LANDSCAPE) "Normal YouTube Live" else "Vertical / Shorts-style live", 11f, R.color.ml_text_secondary))
            addView(label(if (selected) "SELECTED" else "TAP TO SELECT", 10f, R.color.ml_text_muted, bold = true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(if (selected) "●" else "○", 18f, if (selected) R.color.ml_cyan else R.color.ml_text_muted))
        card.addView(row)
        return card
    }

    private fun showVideoPickerScreen() {
        val (shell, body) = makeShell("SELECT VIDEO") { showFormatPicker() }
        body.addView(label("ADD A SOURCE VIDEO", 12f, R.color.ml_text_secondary, bold = true))
        body.addSpace(8)
        body.addView(label("Choose one video from your device. Mihad Live decodes this file and streams the actual video and its audio track.", 13f, R.color.ml_text_secondary))
        body.addSpace(22)
        val ratio = card(R.color.ml_stroke, 20)
        val content = vertical().apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        content.addView(RatioVisualView(this, liveFormat, true), LinearLayout.LayoutParams(dp(115), dp(90)))
        content.addSpace(14)
        content.addView(label(liveFormat.label, 14f, R.color.ml_cyan, bold = true))
        content.addView(label("Canvas ratio selected", 11f, R.color.ml_text_secondary))
        ratio.addView(content)
        body.addView(ratio)
        body.addSpace(18)
        asset?.let {
            body.addView(videoAssetCard(it))
            body.addSpace(12)
        }
        body.addView(button("+  ADD VIDEO FROM GALLERY", primary = true).apply {
            setOnClickListener { openVideoPicker.launch(arrayOf("video/*")) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        body.addSpace(14)
        body.addView(label("Gallery access is granted for the video you select only. No broad media permission is requested.", 11f, R.color.ml_text_muted))
        if (asset != null) {
            body.addSpace(26)
            body.addView(button("OPEN EDITOR", primary = false, stroke = true).apply {
                setOnClickListener { showEditor() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        setScreen(shell, Screen.VIDEO)
    }

    private fun videoAssetCard(video: VideoAsset): MaterialCardView {
        val card = card(R.color.ml_cyan_dim, 16)
        val row = horizontal().apply { setPadding(dp(14), dp(13), dp(14), dp(13)) }
        row.addView(label("▶", 18f, R.color.ml_cyan), LinearLayout.LayoutParams(dp(30), dp(30)))
        row.addView(vertical().apply {
            addView(label(video.displayName, 13f, R.color.ml_text, bold = true))
            val audio = if (video.hasAudio) "Audio track detected" else "No audio track · no audio will be generated"
            addView(label("${video.sourceWidth}×${video.sourceHeight} · ${formatDuration(video.durationMs)} · $audio", 10f, R.color.ml_text_secondary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return card
    }

    private fun showEditor() {
        val video = asset
        if (video == null) {
            showVideoPickerScreen()
            return
        }
        editorStatus = null
        val (shell, body) = makeShell("LIVE EDITOR") { showVideoPickerScreen() }
        body.addView(label("OUTPUT CANVAS", 11f, R.color.ml_text_secondary, bold = true).apply { letterSpacing = .12f })
        body.addSpace(6)
        val output = liveFormat.outputSize(quality)
        val outputText = label("${output.width} × ${output.height}  ·  ${fps} FPS", 14f, R.color.ml_cyan, bold = true)
        editorOutputInfo = outputText
        body.addView(outputText)
        body.addSpace(10)

        val (canvas, texture) = makePreviewCanvas(liveFormat)
        previewHost = canvas
        body.addView(canvas, marginParams(bottom = 10))
        body.addView(label("LIVE COMPOSITOR PREVIEW  ·  SAME SOURCE AND CANVAS AS THE ENCODER", 9f, R.color.ml_cyan_dim, bold = true))
        body.addView(label("A decoded frame appears here; this is not a placeholder video.", 10f, R.color.ml_text_muted))
        body.addSpace(14)
        body.addView(compositionCard(texture))
        body.addSpace(16)
        body.addView(videoSettingsCard())
        body.addSpace(16)
        body.addView(streamSettingsCard())
        body.addSpace(10)
        val status = label("Preparing MediaCodec encoder…", 11f, R.color.ml_text_secondary)
        editorStatus = status
        body.addView(status)
        body.addSpace(11)
        val start = button("START LIVE", primary = true).apply {
            textSize = 15f
            minHeight = dp(60)
            isEnabled = false
            alpha = .55f
            setOnClickListener { requestStartLive() }
        }
        editorStartButton = start
        body.addView(start, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        body.addSpace(6)
        body.addView(label("Use RTMPS where available. The app sends your stream key only to the server URL you enter.", 10f, R.color.ml_text_muted))
        setScreen(shell, Screen.EDITOR)
        texture.post {
            service?.attachPreview(texture)
            service?.updateComposition(composition, texture.width, texture.height)
        }
        ensureEditorPreview()
        renderSnapshot(lastSnapshot)
    }

    private fun makePreviewCanvas(format: LiveFormat): Pair<AspectCanvasHost, TextureView> {
        val host = AspectCanvasHost(this).apply {
            this.format = format
            maxCanvasHeightPx = dp(360)
        }
        val canvasLayer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(colorResource(R.color.ml_black))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), colorResource(R.color.ml_cyan_dim))
            }
            clipToOutline = true
        }
        val texture = previewTexture ?: TextureView(this).apply { isOpaque = true }
        previewTexture = texture
        (texture.parent as? ViewGroup)?.removeView(texture)
        canvasLayer.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(canvasLayer)
        host.canvasView = canvasLayer
        return host to texture
    }

    private fun compositionCard(texture: TextureView): MaterialCardView {
        val card = card()
        val column = vertical().apply { setPadding(dp(15), dp(13), dp(15), dp(14)) }
        column.addView(sectionTitle("COMPOSITION"))
        column.addSpace(12)
        val controls = horizontal()
        val fit = button("FIT", stroke = true)
        val fill = button("FILL", stroke = true)
        fit.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
        fill.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) }
        fit.setOnClickListener { composition = composition.copy(mode = CompositionMode.FIT); updateCompositionSelection(fit, fill); postCompositionUpdate(texture) }
        fill.setOnClickListener { composition = composition.copy(mode = CompositionMode.FILL); updateCompositionSelection(fit, fill); postCompositionUpdate(texture) }
        controls.addView(fit); controls.addView(fill)
        column.addView(controls)
        column.addSpace(12)
        val zoomLabel = label("ZOOM  ·  ${composition.zoom.formatOne()}×", 10f, R.color.ml_text_secondary, bold = true)
        column.addView(zoomLabel)
        val zoom = SeekBar(this).apply {
            max = 200
            progress = ((composition.zoom - 1f) * 100).roundToInt().coerceIn(0, 200)
            progressTintList = android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_cyan))
            thumbTintList = android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_cyan))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    composition = composition.copy(zoom = 1f + progress / 100f).normalized()
                    zoomLabel.text = "ZOOM  ·  ${composition.zoom.formatOne()}×"
                    postCompositionUpdate(texture)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        column.addView(zoom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        column.addSpace(6)
        column.addView(label("POSITION · MOVE THE SOURCE INSIDE THE OUTPUT CANVAS", 9f, R.color.ml_text_muted, bold = true))
        column.addSpace(4)
        column.addView(label("HORIZONTAL", 9f, R.color.ml_text_secondary, bold = true))
        column.addView(positionSeek(texture, horizontal = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        column.addView(label("VERTICAL", 9f, R.color.ml_text_secondary, bold = true))
        column.addView(positionSeek(texture, horizontal = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        column.addSpace(8)
        val reset = button("RESET  ·  FIT", stroke = true).apply {
            setOnClickListener {
                composition = CompositionSettings()
                fit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_cyan_deep)))
                fill.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_surface_2)))
                zoom.progress = 0
                postCompositionUpdate(texture)
            }
        }
        column.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        card.addView(column)
        updateCompositionSelection(fit, fill)
        return card
    }

    private fun positionSeek(texture: TextureView, horizontal: Boolean): SeekBar = SeekBar(this).apply {
        max = 200
        progress = 100
        progressTintList = android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_cyan_dim))
        thumbTintList = android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_cyan))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val offset = (progress - 100) / 100f
                composition = if (horizontal) composition.copy(panX = offset) else composition.copy(panY = offset)
                postCompositionUpdate(texture)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun updateCompositionSelection(fit: MaterialButton, fill: MaterialButton) {
        val selected = colorResource(R.color.ml_cyan_deep)
        val normal = colorResource(R.color.ml_surface_2)
        fit.backgroundTintList = android.content.res.ColorStateList.valueOf(if (composition.mode == CompositionMode.FIT) selected else normal)
        fill.backgroundTintList = android.content.res.ColorStateList.valueOf(if (composition.mode == CompositionMode.FILL) selected else normal)
    }

    private fun postCompositionUpdate(texture: TextureView) {
        uiHandler.removeCallbacks(compositionUpdate)
        compositionTarget = texture
        uiHandler.postDelayed(compositionUpdate, 65)
    }

    private var compositionTarget: TextureView? = null
    private val compositionUpdate = Runnable {
        val target = compositionTarget ?: return@Runnable
        service?.updateComposition(composition, target.width, target.height)
    }

    private fun videoSettingsCard(): MaterialCardView {
        val card = card()
        val column = vertical().apply { setPadding(dp(15), dp(13), dp(15), dp(14)) }
        column.addView(sectionTitle("VIDEO SETTINGS"))
        column.addSpace(11)
        column.addView(choiceRow("QUALITY", quality.label) { chooseQuality() })
        column.addView(choiceRow("FRAME RATE", "$fps FPS") { chooseFps() })
        val bitrateSummary = when (bitrateMode) {
            BitrateMode.AUTO -> "Auto · conservative adaptation"
            BitrateMode.RECOMMENDED -> "Recommended · ${formatRate(customOrRecommended().toLong())} target"
            BitrateMode.CUSTOM -> "Custom · ${formatRate(bitrateFor(quality).toLong())} target"
        }
        column.addView(choiceRow("BITRATE", bitrateSummary) { chooseBitrate() })
        val loopRow = horizontal().apply { setPadding(0, dp(10), 0, 0) }
        loopRow.addView(vertical().apply {
            addView(label("LOOP VIDEO", 12f, R.color.ml_text, bold = true))
            addView(label("Repeat the source without restarting RTMP or the encoder.", 10f, R.color.ml_text_secondary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val loopSwitch = MaterialSwitch(this).apply {
            isChecked = loopVideo
            thumbTintList = android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_cyan))
            setOnCheckedChangeListener { _, checked ->
                loopVideo = checked
                schedulePreviewPrepare()
            }
        }
        loopRow.addView(loopSwitch)
        column.addView(loopRow)
        card.addView(column)
        return card
    }

    private fun choiceRow(label: String, value: String, onClick: () -> Unit): View = horizontal().apply {
        setPadding(0, dp(9), 0, dp(9))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(vertical().apply {
            addView(this@MainActivity.label(label, 10f, R.color.ml_text_secondary, bold = true))
            addView(this@MainActivity.label(value, 13f, R.color.ml_text, bold = true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(this@MainActivity.label("CHANGE  ›", 10f, R.color.ml_cyan, bold = true))
    }

    private fun streamSettingsCard(): MaterialCardView {
        val card = card()
        val column = vertical().apply { setPadding(dp(15), dp(13), dp(15), dp(15)) }
        column.addView(sectionTitle("STREAM SETTINGS"))
        column.addSpace(12)
        val name = makeInput("STREAM NAME", "Enter Stream Name", streamNameValue, InputType.TYPE_CLASS_TEXT)
        column.addView(name.first)
        name.second.addTextChangedListener(simpleWatcher { streamNameValue = it })
        column.addSpace(10)
        val server = makeInput(
            "YOUTUBE RTMP / RTMPS SERVER URL",
            "rtmps://.../live2",
            serverUrlValue,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        column.addView(server.first)
        server.second.addTextChangedListener(simpleWatcher { serverUrlValue = it })
        column.addView(label("Enter the server and application path only; paste the exact stream key separately below.", 9f, R.color.ml_text_muted))
        column.addSpace(10)
        val keyInput = makeInput(
            "YOUTUBE STREAM KEY",
            "Paste your stream key",
            streamKeyValue,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            passwordToggle = true
        )
        column.addView(keyInput.first)
        keyInput.second.addTextChangedListener(simpleWatcher {
            streamKeyValue = it
            if (rememberKey) settings.saveRememberedKey(it)
        })
        column.addSpace(7)
        val actions = horizontal()
        val remember = CheckBox(this).apply {
            text = "Remember securely on this device"
            textSize = 11f
            setTextColor(colorResource(R.color.ml_text_secondary))
            buttonTintList = android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_cyan))
            isChecked = rememberKey
            setOnCheckedChangeListener { _, checked ->
                rememberKey = checked
                if (checked) settings.saveRememberedKey(streamKeyValue) else settings.saveRememberedKey(null)
            }
        }
        actions.addView(remember, LinearLayout.LayoutParams(0, dp(42), 1f))
        actions.addView(button("COPY", stroke = true).apply {
            minHeight = dp(36); textSize = 10f
            setOnClickListener { copyStreamKey() }
        }, LinearLayout.LayoutParams(dp(70), dp(36)))
        actions.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        actions.addView(button("CLEAR", stroke = true).apply {
            minHeight = dp(36); textSize = 10f
            setOnClickListener {
                keyInput.second.setText("")
                streamKeyValue = ""
                settings.saveRememberedKey(null)
            }
        }, LinearLayout.LayoutParams(dp(70), dp(36)))
        column.addView(actions)
        column.addSpace(7)
        column.addView(label("Your key is never sent to Mihad servers. RTMPS encrypts it in transit; plain RTMP does not.", 10f, R.color.ml_text_muted))
        card.addView(column)
        return card
    }

    private fun makeInput(
        label: String,
        placeholder: String,
        initial: String,
        inputType: Int,
        passwordToggle: Boolean = false
    ): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(this).apply {
            hint = label
            placeholderText = placeholder
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeColor = colorResource(R.color.ml_cyan_dim)
            hintTextColor = android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_text_secondary))
            setBoxCornerRadii(dp(13).toFloat(), dp(13).toFloat(), dp(13).toFloat(), dp(13).toFloat())
            if (passwordToggle) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(layout.context).apply {
            setText(initial)
            this.inputType = inputType
            isSingleLine = true
            textSize = 13f
            setTextColor(colorResource(R.color.ml_text))
            setHintTextColor(colorResource(R.color.ml_text_muted))
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        layout.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return layout to edit
    }

    private fun simpleWatcher(onChange: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChange(s?.toString().orEmpty())
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun chooseQuality() {
        val items = arrayOf("720p · ${liveFormat.outputSize(VideoQuality.P720).width}×${liveFormat.outputSize(VideoQuality.P720).height}", "1080p · ${liveFormat.outputSize(VideoQuality.P1080).width}×${liveFormat.outputSize(VideoQuality.P1080).height}")
        AlertDialog.Builder(this)
            .setTitle("Output quality")
            .setSingleChoiceItems(items, if (quality == VideoQuality.P720) 0 else 1) { dialog, which ->
                val next = if (which == 0) VideoQuality.P720 else VideoQuality.P1080
                val bitRate = bitrateFor(next)
                val capability = EncoderCapabilities.check(liveFormat.outputSize(next), fps, bitRate)
                if (!capability.supported) Toast.makeText(this, capability.reason, Toast.LENGTH_LONG).show()
                else {
                    quality = next
                    schedulePreviewPrepare()
                    showEditor()
                }
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun chooseFps() {
        AlertDialog.Builder(this)
            .setTitle("Frame rate")
            .setSingleChoiceItems(arrayOf("30 FPS", "60 FPS"), if (fps == 30) 0 else 1) { dialog, which ->
                val next = if (which == 0) 30 else 60
                val capability = EncoderCapabilities.check(liveFormat.outputSize(quality), next, bitrateFor(quality))
                if (!capability.supported) Toast.makeText(this, capability.reason, Toast.LENGTH_LONG).show()
                else {
                    fps = next
                    schedulePreviewPrepare()
                    showEditor()
                }
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun chooseBitrate() {
        val values = arrayOf("Auto · adapt only when congested", "Recommended", "Custom…")
        AlertDialog.Builder(this)
            .setTitle("Video bitrate")
            .setItems(values) { _, which ->
                when (which) {
                    0 -> { bitrateMode = BitrateMode.AUTO; schedulePreviewPrepare(); showEditor() }
                    1 -> { bitrateMode = BitrateMode.RECOMMENDED; schedulePreviewPrepare(); showEditor() }
                    2 -> showCustomBitrateDialog()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showCustomBitrateDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((customBitrateBps / 1000).toString())
            setTextColor(colorResource(R.color.ml_text))
            hint = "kbps"
        }
        AlertDialog.Builder(this)
            .setTitle("Custom video bitrate")
            .setMessage("Enter kbps. Safe limits depend on the selected output quality.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("APPLY") { _, _ ->
                val kbps = input.text?.toString()?.toIntOrNull()
                if (kbps == null) Toast.makeText(this, "Enter a valid bitrate", Toast.LENGTH_SHORT).show()
                else {
                    val upper = if (quality == VideoQuality.P1080) 8_000 else 5_000
                    customBitrateBps = kbps.coerceIn(1_000, upper) * 1000
                    bitrateMode = BitrateMode.CUSTOM
                    schedulePreviewPrepare()
                    showEditor()
                }
            }
            .show()
    }

    private fun streamNameInputChanged(value: String) { streamNameValue = value }

    private fun customOrRecommended(): Int = when (quality) {
        VideoQuality.P720 -> 2_800_000
        VideoQuality.P1080 -> 5_000_000
    }

    private fun bitrateFor(targetQuality: VideoQuality): Int = when (bitrateMode) {
        BitrateMode.AUTO -> if (targetQuality == VideoQuality.P1080) 4_000_000 else 3_000_000
        BitrateMode.RECOMMENDED -> if (targetQuality == VideoQuality.P1080) 5_000_000 else 2_800_000
        BitrateMode.CUSTOM -> customBitrateBps.coerceIn(1_000_000, if (targetQuality == VideoQuality.P1080) 8_000_000 else 5_000_000)
    }

    private fun copyStreamKey() {
        val value = streamKeyValue
        if (value.isBlank()) {
            Toast.makeText(this, "Enter a stream key first", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Stream key", value))
        Toast.makeText(this, "Stream key copied. Clear the clipboard after use.", Toast.LENGTH_LONG).show()
    }

    private fun requestStartLive() {
        hideKeyboard()
        val request = buildRequest()
        if (request == null) {
            Toast.makeText(this, "Select a video first", Toast.LENGTH_SHORT).show()
            return
        }
        val server = RtmpEndpoint.validateServer(request.serverUrl)
        if (!server.valid) {
            showInputError(server.message ?: "RTMP server URL is invalid")
            return
        }
        val key = RtmpEndpoint.validateKey(request.streamKey)
        if (!key.valid) {
            showInputError(key.message ?: "Stream key is missing")
            return
        }
        settings.saveServerUrl(serverUrlValue)
        if (rememberKey) settings.saveRememberedKey(streamKeyValue) else settings.saveRememberedKey(null)
        asset?.let { video ->
            settings.saveProject(
                SavedProject(
                    name = streamNameValue.ifBlank { "Mihad Live" },
                    sourceName = video.displayName,
                    sourceUri = video.uri.toString(),
                    format = liveFormat,
                    quality = quality,
                    savedAtMs = System.currentTimeMillis()
                )
            )
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingLiveRequest = request
            permissionPromptActive = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissionPromptActive = false
            beginLive(request)
        }
    }

    private fun showNotificationPermissionChoice(request: StreamRequest) {
        AlertDialog.Builder(this)
            .setTitle("Notifications are off")
            .setMessage("Android may hide the persistent stream notification and its Stop action. You can still continue, but allow notifications for the safest background controls.")
            .setNegativeButton("CANCEL") { _, _ ->
                permissionPromptActive = false
            }
            .setPositiveButton("CONTINUE") { _, _ ->
                permissionPromptActive = false
                beginLive(request)
            }
            .setOnCancelListener { permissionPromptActive = false }
            .show()
    }

    private fun beginLive(request: StreamRequest) {
        val engine = service
        if (engine == null) {
            Toast.makeText(this, "Streaming engine is still starting. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }
        engine.startLive(request)
        showDashboard()
    }

    private fun showInputError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Check stream settings")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun buildRequest(): StreamRequest? {
        val video = asset ?: return null
        val name = streamNameValue.trim().ifBlank { "Mihad Live" }
        return StreamRequest(
            streamName = name,
            serverUrl = serverUrlValue,
            streamKey = streamKeyValue,
            videoAsset = video,
            format = liveFormat,
            quality = quality,
            fps = fps,
            bitrateMode = bitrateMode,
            customBitrateBps = customBitrateBps,
            loopVideo = loopVideo,
            composition = composition.normalized()
        )
    }

    private fun ensureEditorPreview() {
        val request = buildRequest() ?: return
        val engine = service ?: return
        engine.preparePreview(request)
        previewTexture?.post {
            engine.attachPreview(previewTexture ?: return@post)
            val texture = previewTexture ?: return@post
            engine.updateComposition(composition, texture.width, texture.height)
        }
    }

    private fun schedulePreviewPrepare() {
        preparePreviewJob?.cancel()
        preparePreviewJob = lifecycleScope.launch {
            delay(220)
            if (screen == Screen.EDITOR) {
                val request = buildRequest() ?: return@launch
                service?.preparePreview(request)
                updateOutputInfo()
            }
        }
    }

    private fun updateOutputInfo() {
        val size = liveFormat.outputSize(quality)
        editorOutputInfo?.text = "${size.width} × ${size.height}  ·  $fps FPS"
    }

    private fun handleVideoSelected(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Toast.makeText(this, "Reading video details…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MediaProbe.inspect(this@MainActivity, uri) }
            }
            result.onSuccess { selected ->
                asset = selected
                composition = CompositionSettings()
                showEditor()
                settings.saveProject(
                    SavedProject(
                        name = streamNameValue.ifBlank { "Mihad Live" },
                        sourceName = selected.displayName,
                        sourceUri = selected.uri.toString(),
                        format = liveFormat,
                        quality = quality,
                        savedAtMs = System.currentTimeMillis()
                    )
                )
            }.onFailure { error ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Unsupported video")
                    .setMessage(error.message ?: "Could not open this video. Select another file.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun loadProject(project: SavedProject) {
        if (project.sourceUri.isBlank()) {
            liveFormat = project.format
            quality = project.quality
            streamNameValue = project.name
            showVideoPickerScreen()
            return
        }
        val uri = runCatching { Uri.parse(project.sourceUri) }.getOrNull()
        if (uri == null) {
            showVideoPickerScreen()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { MediaProbe.inspect(this@MainActivity, uri) } }
            result.onSuccess { selected ->
                asset = selected
                liveFormat = project.format
                quality = project.quality
                streamNameValue = project.name
                showEditor()
            }.onFailure {
                Toast.makeText(this@MainActivity, "Video access expired. Select the video again.", Toast.LENGTH_LONG).show()
                liveFormat = project.format
                quality = project.quality
                streamNameValue = project.name
                showVideoPickerScreen()
            }
        }
    }

    private fun showDashboard() {
        dashboardMetrics.clear()
        val (shell, body) = makeShell("LIVE DASHBOARD") { showHome() }
        val (canvas, texture) = makePreviewCanvas(liveFormat)
        previewHost = canvas
        body.addView(canvas)
        body.addSpace(12)
        body.addView(label("OUTPUT PREVIEW  ·  COMPOSITOR OUTPUT", 9f, R.color.ml_cyan_dim, bold = true))
        body.addSpace(14)

        val statusCard = card(R.color.ml_cyan_dim, 22)
        val statusColumn = vertical().apply { setPadding(dp(17), dp(17), dp(17), dp(17)) }
        val statusRow = horizontal()
        val status = label("● CONNECTING", 16f, R.color.ml_cyan, bold = true)
        dashboardStatus = status
        statusRow.addView(status, LinearLayout.LayoutParams(0, dp(30), 1f))
        dashboardDuration = label("00:00:00", 17f, R.color.ml_text, bold = true)
        statusRow.addView(dashboardDuration)
        statusColumn.addView(statusRow)
        dashboardSubstatus = label("Waiting for RTMP publish confirmation…", 11f, R.color.ml_text_secondary)
        statusColumn.addView(dashboardSubstatus)
        statusCard.addView(statusColumn)
        body.addView(statusCard)
        body.addSpace(12)

        val stateCard = card(R.color.ml_stroke_soft, 16)
        val stateColumn = vertical().apply { setPadding(dp(15), dp(12), dp(15), dp(12)) }
        dashboardEngineState = stateRow(stateColumn, "APP ENGINE", "PREPARING")
        dashboardRtmpState = stateRow(stateColumn, "RTMP CONNECTION", "CONNECTING")
        dashboardIngestState = stateRow(stateColumn, "RTMP PUBLISH / MEDIA", "RTMP NOT CONNECTED")
        stateCard.addView(stateColumn)
        body.addView(stateCard)
        body.addSpace(12)

        val errorCard = card(R.color.ml_error, 16)
        dashboardError = label("", 12f, R.color.ml_error, bold = true).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); visibility = View.GONE }
        errorCard.addView(dashboardError)
        body.addView(errorCard)
        dashboardRetryButton = button("RETRY CONNECTION", stroke = true).apply {
            visibility = View.GONE
            setOnClickListener { service?.retryConnection() }
        }
        body.addView(dashboardRetryButton, marginParams(top = 8))
        body.addSpace(13)

        body.addView(sectionTitle("STREAM METRICS"))
        body.addSpace(9)
        val metricRows = listOf(
            listOf("resolution" to "RESOLUTION", "fps" to "FPS"),
            listOf("videoBitrate" to "VIDEO BITRATE", "audioBitrate" to "AUDIO BITRATE"),
            listOf("upload" to "NETWORK UPLOAD", "dropped" to "DROPPED FRAMES"),
            listOf("encoded" to "ENCODED FRAMES", "sent" to "SENT DATA"),
            listOf("queue" to "SEND QUEUE", "reconnects" to "RECONNECTS")
        )
        metricRows.forEach { row ->
            val line = horizontal()
            row.forEachIndexed { index, (key, label) ->
                val cell = metricCell(key, label)
                val params = LinearLayout.LayoutParams(0, dp(72), 1f)
                if (index == 0) params.marginEnd = dp(6) else params.marginStart = dp(6)
                line.addView(cell, params)
            }
            body.addView(line, marginParams(bottom = 8))
        }
        body.addSpace(10)
        val diagnosticsToggle = button("DEVELOPER DIAGNOSTICS · SHOW", stroke = true).apply {
            setOnClickListener {
                val panel = dashboardDiagnosticsContainer ?: return@setOnClickListener
                val show = panel.visibility != View.VISIBLE
                panel.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "DEVELOPER DIAGNOSTICS · HIDE" else "DEVELOPER DIAGNOSTICS · SHOW"
            }
        }
        dashboardDiagnosticsToggle = diagnosticsToggle
        body.addView(diagnosticsToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        val diagnosticsCard = card(R.color.ml_stroke_soft, 16).apply { visibility = View.GONE }
        val diagnosticsColumn = vertical().apply { setPadding(dp(13), dp(12), dp(13), dp(12)) }
        diagnosticsColumn.addView(label("LOCAL PIPELINE EVIDENCE · STREAM KEY VALUE IS NEVER SHOWN", 8f, R.color.ml_cyan_dim, bold = true))
        val diagnostics = label("Waiting for a stream attempt…", 10f, R.color.ml_text_secondary).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        dashboardDiagnostics = diagnostics
        diagnosticsColumn.addView(diagnostics)
        diagnosticsCard.addView(diagnosticsColumn)
        dashboardDiagnosticsContainer = diagnosticsCard
        body.addView(diagnosticsCard, marginParams(top = 8))
        body.addSpace(12)
        val studio = button("OPEN YOUTUBE STUDIO", stroke = true).apply {
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://studio.youtube.com/"))) }
                    .onFailure { Toast.makeText(this@MainActivity, "Could not open browser", Toast.LENGTH_SHORT).show() }
            }
        }
        body.addView(studio, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        body.addSpace(8)
        body.addView(label("Local RTMP writes are not confirmation on YouTube. Check YouTube Studio → Live → Stream health for the received preview and broadcast status.", 10f, R.color.ml_text_muted))
        body.addSpace(18)
        body.addView(button("STOP LIVE", primary = true).apply {
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_live)))
            setTextColor(Color.WHITE)
            setOnClickListener { confirmStopLive() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        setScreen(shell, Screen.DASHBOARD)
        texture.post {
            service?.attachPreview(texture)
            service?.updateComposition(composition, texture.width, texture.height)
        }
        renderSnapshot(lastSnapshot)
    }

    private fun stateRow(parent: LinearLayout, title: String, value: String): TextView {
        val row = horizontal().apply { setPadding(0, dp(4), 0, dp(4)) }
        row.addView(
            label(title, 9f, R.color.ml_text_secondary, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .82f)
        )
        val state = label(value, 10f, R.color.ml_cyan, bold = true).apply {
            gravity = Gravity.END
            maxLines = 2
        }
        row.addView(state, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.18f))
        parent.addView(row)
        return state
    }

    private fun metricCell(key: String, title: String): MaterialCardView {
        val card = card(R.color.ml_stroke_soft, 15)
        val column = vertical().apply { setPadding(dp(11), dp(11), dp(11), dp(10)) }
        column.addView(label(title, 8f, R.color.ml_text_secondary, bold = true))
        val value = label("N/A", 14f, R.color.ml_text, bold = true)
        dashboardMetrics[key] = value
        column.addView(value)
        card.addView(column)
        return card
    }

    private fun renderSnapshot(snapshot: SessionSnapshot) {
        editorStatus?.let { status ->
            status.text = when (snapshot.state) {
                StreamState.PREPARING -> "Preparing device encoder and media pipeline…"
                StreamState.ENCODER_READY -> "Streaming engine ready · preview uses the encoded output canvas"
                StreamState.ERROR -> snapshot.errorMessage ?: "Encoder error"
                else -> "Engine: ${snapshot.engineStatus}"
            }
            status.setTextColor(colorResource(if (snapshot.state == StreamState.ERROR) R.color.ml_error else R.color.ml_text_secondary))
        }
        editorStartButton?.let { button ->
            val canStart = snapshot.state == StreamState.ENCODER_READY
            button.isEnabled = canStart
            button.alpha = if (canStart) 1f else .55f
        }
        if (screen != Screen.DASHBOARD) return
        val statusColor = when (snapshot.state) {
            StreamState.ERROR -> R.color.ml_error
            StreamState.RECONNECTING -> R.color.ml_warn
            StreamState.YOUTUBE_INGEST_DETECTED, StreamState.MEDIA_FLOWING, StreamState.PUBLISHING -> R.color.ml_cyan
            StreamState.LIVE -> R.color.ml_live
            StreamState.STOPPED -> R.color.ml_text_secondary
            else -> R.color.ml_cyan
        }
        dashboardStatus?.apply {
            text = when (snapshot.state) {
                StreamState.CONNECTING -> "● CONNECTING"
                StreamState.RTMP_HANDSHAKE -> "● RTMP HANDSHAKE"
                StreamState.RTMP_CONNECTED -> "● RTMP CONNECTED"
                StreamState.PUBLISHING -> "● RTMP PUBLISHING"
                StreamState.MEDIA_FLOWING -> "● MEDIA FLOWING"
                StreamState.YOUTUBE_INGEST_DETECTED -> "● YOUTUBE INGEST DETECTED"
                StreamState.LIVE -> "● LIVE"
                StreamState.RECONNECTING -> "● RECONNECTING"
                StreamState.ERROR -> "● ERROR"
                StreamState.STOPPING -> "● STOPPING"
                StreamState.STOPPED -> "● STOPPED"
                else -> "● ${snapshot.statusText}"
            }
            setTextColor(colorResource(statusColor))
        }
        dashboardDuration?.text = formatDuration(snapshot.elapsedMs)
        dashboardSubstatus?.text = when (snapshot.state) {
            StreamState.CONNECTING -> "Opening the exact entered RTMP or RTMPS endpoint. This is not a connected or live state."
            StreamState.RTMP_HANDSHAKE -> "TCP/TLS socket opened; exchanging the RTMP handshake."
            StreamState.RTMP_CONNECTED -> "RTMP handshake and server connect response succeeded. Waiting for NetStream.Publish.Start."
            StreamState.PUBLISHING -> "The RTMP server returned NetStream.Publish.Start. Checking actual H.264/AAC packet writes."
            StreamState.MEDIA_FLOWING -> "LOCAL RTMP WRITES OK · NOT CONFIRMED ON YOUTUBE. Check YouTube Studio → Live → Stream health."
            StreamState.YOUTUBE_INGEST_DETECTED -> "Official YouTube ingest evidence received."
            StreamState.LIVE -> "Official YouTube broadcast-LIVE evidence received."
            StreamState.RECONNECTING -> "Retrying the RTMP transport; encoder and session timer are retained."
            StreamState.ERROR -> snapshot.errorMessage ?: "Stream needs attention."
            StreamState.STOPPED -> "Stream stopped. The dashboard reports measured values only."
            else -> "Prepare the encoder, then start publishing."
        }
        dashboardEngineState?.text = snapshot.engineStatus
        dashboardRtmpState?.text = snapshot.rtmpStatus
        dashboardIngestState?.apply {
            text = snapshot.ingestStatus
            val warning = listOf("FAILED", "NOT FLOWING", "NO VIDEO", "AUDIO NOT", "NOT VERIFIED", "NOT CONNECTED", "WAITING", "RECONNECTING")
                .any { snapshot.ingestStatus.contains(it, ignoreCase = true) }
            setTextColor(colorResource(if (warning) R.color.ml_warn else R.color.ml_text_secondary))
        }
        dashboardError?.let { view ->
            val show = snapshot.state == StreamState.ERROR && !snapshot.errorMessage.isNullOrBlank()
            view.text = snapshot.errorMessage.orEmpty()
            view.visibility = if (show) View.VISIBLE else View.GONE
        }
        dashboardRetryButton?.visibility = if (snapshot.state == StreamState.ERROR &&
            snapshot.errorMessage.orEmpty().contains("network", true)) View.VISIBLE else View.GONE

        dashboardMetrics["resolution"]?.text = if (snapshot.width != null && snapshot.height != null) "${snapshot.width}×${snapshot.height}" else "N/A"
        dashboardMetrics["fps"]?.text = snapshot.actualFps?.let { "$it measured · ${snapshot.targetFps ?: "N/A"} target" } ?: "N/A · ${snapshot.targetFps ?: "N/A"} target"
        dashboardMetrics["videoBitrate"]?.text = formatRate(snapshot.encodedVideoBitrateBps)
        dashboardMetrics["audioBitrate"]?.text = if (snapshot.encodedAudioBitrateBps == null) "N/A" else formatRate(snapshot.encodedAudioBitrateBps)
        dashboardMetrics["upload"]?.text = formatRate(snapshot.uploadBitrateBps)
        dashboardMetrics["dropped"]?.text = snapshot.droppedVideoFrames?.toString() ?: "N/A"
        dashboardMetrics["encoded"]?.text = snapshot.encodedVideoFrames?.toString() ?: "N/A"
        dashboardMetrics["sent"]?.text = snapshot.sentBytes?.let(::formatBytes) ?: "N/A"
        dashboardMetrics["queue"]?.text = snapshot.sendQueueFrames?.let { "$it frames" } ?: "N/A"
        dashboardMetrics["reconnects"]?.text = snapshot.reconnectCount.toString()
        dashboardDiagnostics?.text = diagnosticPanelText(snapshot)
    }

    private fun diagnosticPanelText(snapshot: SessionSnapshot): String {
        val serverValidity = when (snapshot.serverUrlValid) {
            true -> "VALID"
            false -> "INVALID"
            null -> "NOT CHECKED"
        }
        val keyPresence = if (snapshot.streamKeyPresent) "PRESENT · VALUE HIDDEN" else "MISSING"
        val sourceVideo = snapshot.sourceVideoFrames?.toString() ?: "N/A"
        val sourceAudio = snapshot.sourceAudioFrames?.toString() ?: "N/A"
        val encodedVideo = snapshot.encodedVideoFrames?.toString() ?: "N/A"
        val encodedAudio = snapshot.encodedAudioFrames?.toString() ?: "N/A"
        val videoPackets = snapshot.sentVideoPackets?.toString() ?: "N/A"
        val audioPackets = snapshot.sentAudioPackets?.toString() ?: "N/A"
        val videoBytes = snapshot.sentVideoBytes?.toString() ?: "N/A"
        val audioBytes = snapshot.sentAudioBytes?.toString() ?: "N/A"
        val keyframes = snapshot.sentKeyframes?.toString() ?: "N/A"
        val lastVideoPacket = snapshot.lastVideoPacketAgoMs?.let { "$it ms ago" } ?: "NEVER"
        val lastKeyframe = snapshot.lastVideoKeyframeAgoMs?.let { "$it ms ago" } ?: "NEVER"
        val lastAudioPacket = snapshot.lastAudioPacketAgoMs?.let { "$it ms ago" } ?: "NEVER"
        val bytes = snapshot.sentBytes?.let(::formatBytes) ?: "N/A"
        return listOf(
            "Server URL validity: $serverValidity",
            "Stream key:          $keyPresence",
            "Media source:        ${snapshot.sourceStatus} · ${snapshot.loopCount} loops",
            "Decoded source video: $sourceVideo frames",
            "Decoded source audio: $sourceAudio PCM blocks",
            "H.264 encoder:       ${snapshot.videoEncoderStatus} · $encodedVideo frames",
            "AAC encoder:         ${snapshot.audioEncoderStatus} · $encodedAudio frames",
            "RTMP handshake:      ${snapshot.handshakeStatus}",
            "RTMP connect:        ${snapshot.connectStatus}",
            "RTMP publish:        ${snapshot.publishStatus}",
            "H.264 config writes: ${if (snapshot.h264ConfigSent) "SENT" else "NOT SENT"}",
            "AAC config writes:   ${if (snapshot.audioEncoderStatus == "NOT USED") "NOT REQUIRED" else if (snapshot.aacConfigSent) "SENT" else "NOT SENT"}",
            "Video Packets Sent:  $videoPackets",
            "Audio Packets Sent:  $audioPackets",
            "Video RTMP Msg B:    $videoBytes",
            "Audio RTMP Msg B:    $audioBytes",
            "Keyframes Sent:      $keyframes",
            "Last Video Packet:   $lastVideoPacket",
            "Last Keyframe:       $lastKeyframe",
            "Last Audio Packet:   $lastAudioPacket",
            "Total FLV/RTMP bytes: $bytes",
            "RTMP publish/media:  ${snapshot.ingestStatus}",
            "Pipeline failure:    ${snapshot.pipelineFailureStage ?: "NONE"}",
            "YouTube receipt:     NOT VERIFIED · CHECK CONTROL ROOM",
            "Broadcast LIVE:      NOT VERIFIED",
            "RTMP message bytes are RootEncoder size totals after flush, not TCP/TLS wire-byte counts.",
            "Local RTMP counters never verify YouTube receipt or broadcast LIVE."
        ).joinToString("\n")
    }

    private fun confirmStopLive() {
        AlertDialog.Builder(this)
            .setTitle("Stop this stream?")
            .setMessage("The RTMP session and foreground service will stop. The live timer will be saved to history.")
            .setNegativeButton("KEEP STREAMING", null)
            .setPositiveButton("STOP LIVE") { _, _ ->
                service?.stopLive(explicit = true)
                showHome()
            }
            .show()
    }

    private fun showSettings() {
        val (shell, body) = makeShell("SETTINGS") { showHome() }
        body.addView(sectionTitle("PRIVACY & SECURITY"))
        body.addSpace(10)
        body.addView(infoCard("Stream key", "Stored only in memory unless you enable encrypted on-device storage. It is never uploaded to Mihad servers or written to diagnostic logs."))
        body.addSpace(10)
        body.addView(infoCard("Gallery access", "Android's system document picker grants access only to the video you choose. Mihad Live does not request broad photo/video library access."))
        body.addSpace(10)
        body.addView(infoCard("Live verification", "RTMP handshake, connect, publish acceptance, codec configuration and local packet writes are reported separately. RTMP cannot prove YouTube receipt or broadcast LIVE; verify incoming video in YouTube Control Room."))
        body.addSpace(10)
        body.addView(infoCard("Background streaming", "The foreground service owns the decoder, compositor, encoders and RTMP transport. A persistent notification includes a Stop Live action."))
        body.addSpace(20)
        body.addView(sectionTitle("APP"))
        body.addSpace(10)
        body.addView(infoCard("MIHAD LIVE · 1.0.0", "Stream. Stay Live.\nBlack + cyan dashboard theme."))
        body.addSpace(18)
        body.addView(button("CLEAR REMEMBERED STREAM KEY", stroke = true).apply {
            setOnClickListener {
                settings.saveRememberedKey(null)
                streamKeyValue = ""
                rememberKey = false
                Toast.makeText(this@MainActivity, "Encrypted key cleared", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        setScreen(shell, Screen.SETTINGS)
    }

    private fun infoCard(title: String, detail: String): MaterialCardView {
        val card = card()
        val column = vertical().apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }
        column.addView(label(title, 13f, R.color.ml_text, bold = true))
        column.addSpace(5)
        column.addView(label(detail, 11f, R.color.ml_text_secondary))
        card.addView(column)
        return card
    }

    private fun showHistory() {
        val (shell, body) = makeShell("LIVE HISTORY") { showHome() }
        val entries = settings.history()
        if (entries.isEmpty()) {
            body.addView(emptyPanel("No live history yet. Completed sessions appear here."))
        } else entries.forEach { entry ->
            body.addView(historyCard(entry), marginParams(bottom = 10))
        }
        setScreen(shell, Screen.HISTORY)
    }

    private fun showVideoPickerBusy() {
        val rootView = vertical().apply {
            gravity = Gravity.CENTER
            setBackgroundColor(colorResource(R.color.ml_bg))
        }
        val progress = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(colorResource(R.color.ml_cyan)) }
        rootView.addView(progress, LinearLayout.LayoutParams(dp(44), dp(44)))
        rootView.addView(label("Reading selected video…", 13f, R.color.ml_text_secondary), marginParams(top = 14))
        setScreen(rootView, Screen.VIDEO)
    }

    private fun showInputErrorText(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun marginParams(bottom: Int = 0, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (top > 0) topMargin = dp(top)
            if (bottom > 0) bottomMargin = dp(bottom)
        }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun formatDuration(milliseconds: Long): String {
        val total = (milliseconds / 1000L).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = total / 60 % 60
        val seconds = total % 60
        return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    private fun formatRate(bitsPerSecond: Long?): String {
        if (bitsPerSecond == null || bitsPerSecond < 0) return "N/A"
        if (bitsPerSecond >= 1_000_000) return String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        return String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1_000.0)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "N/A"
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun Float.formatOne(): String = String.format(Locale.US, "%.1f", this)

}
