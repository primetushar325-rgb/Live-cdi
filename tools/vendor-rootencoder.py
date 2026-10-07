#!/usr/bin/env python3
"""
Re-vendor the RootEncoder (pedroSG94) streaming core into Mihad Live.

Usage:
    git clone --depth 1 -b <tag> https://github.com/pedroSG94/RootEncoder.git /tmp/RootEncoder
    python3 tools/vendor-rootencoder.py /tmp/RootEncoder

Why vendored
------------
Mihad Live needs a real RTMP/RTMPS transport, a real hardware encoder pipeline and
a real GL compositor. RootEncoder provides all three as proven, Apache-2.0 code.
It is vendored (not pulled from JitPack) so that a clean GitHub Actions runner can
reproduce the APK without depending on a third-party build-on-demand service, and
so the compositor can be extended with the exact output-canvas model Mihad Live
needs (see GlStreamInterface.setSourceSize).

The script is idempotent: it deletes vendor/re-* and rebuilds them from the checkout.
"""

import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
VENDOR = ROOT / "vendor"

MODULES = {
    "common": "re-common",
    "encoder": "re-encoder",
    "rtmp": "re-rtmp",
    "library": "re-library",
}

# Files that are not needed by Mihad Live (RTSP/SRT/UDP/WHIP front-ends, camera
# front-ends and the legacy FromFile paths). Keeping them would drag in modules
# that are not vendored.
DELETE = [
    "library/src/main/java/com/pedro/library/rtsp",
    "library/src/main/java/com/pedro/library/srt",
    "library/src/main/java/com/pedro/library/udp",
    "library/src/main/java/com/pedro/library/whip",
    "library/src/main/java/com/pedro/library/generic",
    "library/src/main/java/com/pedro/library/multiple",
    "library/src/main/java/com/pedro/library/base/Camera1Base.java",
    "library/src/main/java/com/pedro/library/base/Camera2Base.java",
    "library/src/main/java/com/pedro/library/base/DisplayBase.java",
    "library/src/main/java/com/pedro/library/base/OnlyAudioBase.java",
    "library/src/main/java/com/pedro/library/base/FromFileBase.java",
    "library/src/main/java/com/pedro/library/rtmp/RtmpCamera1.kt",
    "library/src/main/java/com/pedro/library/rtmp/RtmpCamera2.kt",
    "library/src/main/java/com/pedro/library/rtmp/RtmpDisplay.kt",
    "library/src/main/java/com/pedro/library/rtmp/RtmpFromFile.kt",
    "library/src/main/java/com/pedro/library/rtmp/RtmpOnlyAudio.kt",
    "library/src/main/java/com/pedro/library/util/streamclient/GenericStreamClient.kt",
    "library/src/main/java/com/pedro/library/util/streamclient/RtspStreamClient.kt",
    "library/src/main/java/com/pedro/library/util/streamclient/SrtStreamClient.kt",
    "library/src/main/java/com/pedro/library/util/streamclient/UdpStreamClient.kt",
    "library/src/main/java/com/pedro/library/util/streamclient/WhipStreamClient.kt",
    "library/src/main/java/com/pedro/library/util/Mpeg2TsMuxerRecordController.kt",
    "library/src/main/java/com/pedro/library/view/OpenGlView.kt",
    "library/src/main/java/com/pedro/library/view/AutoFitTextureView.java",
]

# Kotlin >= 2.2 only syntax ("when" with a subject variable). Rewritten so the
# vendored code stays compilable with the Kotlin version used by this project.
WHEN_SUBJECT_FIXES = [
    ("rtmp/src/main/java/com/pedro/rtmp/amf/v0/AmfData.kt",
     "      val amfData = when (val type = getMarkType(input.read())) {",
     "      val type = getMarkType(input.read())\n      val amfData = when (type) {"),
    ("rtmp/src/main/java/com/pedro/rtmp/amf/v3/Amf3Data.kt",
     "      val amf3Data = when (val type = getMark3Type(input.read())) {",
     "      val type = getMark3Type(input.read())\n      val amf3Data = when (type) {"),
    ("rtmp/src/main/java/com/pedro/rtmp/rtmp/RtmpClient.kt",
     "        when (val type = userControl.type) {",
     "        val type = userControl.type\n        when (type) {"),
    ("rtmp/src/main/java/com/pedro/rtmp/rtmp/RtmpClient.kt",
     "              when (val code = command.getCode()) {",
     "              val code = command.getCode()\n              when (code) {"),
]

NAL_CONSTANTS = """package com.pedro.library.base.recording

/**
 * Local copy of the H264/H265 NAL constants that RootEncoder keeps inside the
 * RTSP module. The RTSP module is not vendored into Mihad Live, so the values
 * are duplicated here (they are fixed by the H264/H265 specifications).
 */
object MihadNal {
  // H264 IDR
  const val IDR = 5
  // H265 IDR
  const val IDR_N_LP = 20
  const val IDR_W_DLP = 19
}
"""

GL_SOURCE_SIZE_FIELDS = (
    "  private var encoderWidth = 0\n"
    "  private var encoderHeight = 0\n"
    "  private var encoderRecordWidth = 0\n"
    "  private var encoderRecordHeight = 0\n",
    "  private var encoderWidth = 0\n"
    "  private var encoderHeight = 0\n"
    "  private var encoderRecordWidth = 0\n"
    "  private var encoderRecordHeight = 0\n"
    "  //Mihad Live: real size of the pixels produced by the video source (decoder output).\n"
    "  //The GL input buffer/FBO must match it so the decoder never re-scales the picture.\n"
    "  private var sourceWidth = 0\n"
    "  private var sourceHeight = 0\n",
)

GL_START_SIZE = (
    "    val width = max(encoderWidth, encoderRecordWidth)\n"
    "    val height = max(encoderHeight, encoderRecordHeight)\n"
    "    surfaceManager.release()\n",
    "    //Mihad Live: use the real source resolution for the GL input buffer so the decoder\n"
    "    //never re-scales the picture. The output canvas keeps encoderWidth/encoderHeight.\n"
    "    val width = if (sourceWidth > 0) sourceWidth else max(encoderWidth, encoderRecordWidth)\n"
    "    val height = if (sourceHeight > 0) sourceHeight else max(encoderHeight, encoderRecordHeight)\n"
    "    surfaceManager.release()\n",
)

GL_SOURCE_SIZE_METHODS = (
    "  fun setAspectRatioMode(aspectRatioMode: AspectRatioMode) {\n"
    "    this.aspectRatioMode = aspectRatioMode\n"
    "  }\n",
    "  fun setAspectRatioMode(aspectRatioMode: AspectRatioMode) {\n"
    "    this.aspectRatioMode = aspectRatioMode\n"
    "  }\n"
    "\n"
    "  /**\n"
    "   * Mihad Live: resolution of the frames produced by the video source.\n"
    "   * Must be called before [start], it is applied on the next GL start.\n"
    "   */\n"
    "  fun setSourceSize(width: Int, height: Int) {\n"
    "    sourceWidth = width\n"
    "    sourceHeight = height\n"
    "  }\n"
    "\n"
    "  /**\n"
    "   * Mihad Live: current GL input resolution.\n"
    "   */\n"
    "  fun getSourceSize(): Point = Point(\n"
    "    if (sourceWidth > 0) sourceWidth else encoderWidth,\n"
    "    if (sourceHeight > 0) sourceHeight else encoderHeight\n"
    "  )\n",
)


def patch(path: pathlib.Path, old: str, new: str) -> None:
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"PATCH FAILED (pattern not found) in {path}:\n{old}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    src = pathlib.Path(sys.argv[1]).resolve()
    if not (src / "settings.gradle.kts").exists():
        raise SystemExit(f"{src} does not look like a RootEncoder checkout")

    if VENDOR.exists():
        shutil.rmtree(VENDOR)
    VENDOR.mkdir(parents=True)

    for module, target in MODULES.items():
        shutil.copytree(src / module, VENDOR / target)
        for junk in ("build", ".gradle"):
            shutil.rmtree(VENDOR / target / junk, ignore_errors=True)
        for srcdir in ("test", "androidTest"):
            shutil.rmtree(VENDOR / target / "src" / srcdir, ignore_errors=True)

    # This app builds only the RTMP pipeline. Avoid upstream Dokka/publication
    # settings and dependencies on RTSP/SRT/UDP/WHIP modules that are not included.
    for target in MODULES.values():
        shutil.copy2(ROOT / "tools/rootencoder-build" / f"{target}.gradle.kts",
                     VENDOR / target / "build.gradle.kts")
    shutil.copy2(src / "LICENSE.txt", VENDOR / "LICENSE.RootEncoder.txt")
    shutil.copy2(ROOT / "tools/rootencoder-notice.md", VENDOR / "README.md")

    for rel in DELETE:
        target = VENDOR / MODULES[rel.split("/")[0]] / rel.split("/", 1)[1]
        if target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()

    for rel, old, new in WHEN_SUBJECT_FIXES:
        path = VENDOR / MODULES[rel.split("/")[0]] / rel.split("/", 1)[1]
        if path.exists():
            patch(path, old, new)

    # URI schemes are case-insensitive and raw paths must not be decoded before publish.
    url_parser = VENDOR / "re-common/src/main/java/com/pedro/common/UrlParser.kt"
    patch(
        url_parser,
        "      if (uri.scheme != null && !requiredProtocol.contains(uri.scheme.trim())) {",
        "      if (uri.scheme != null && !requiredProtocol.contains(uri.scheme.trim().lowercase())) {",
    )
    patch(
        url_parser,
        "    scheme = uri.scheme\n    host = uri.host\n    port = if (uri.port < 0) null else uri.port\n    path = uri.path.removePrefix(\"/\")",
        "    // URI schemes are case-insensitive, but preserve the user's original URL outside this parser.\n"
        "    // Normalize only the protocol identifier for TLS selection and RTMP command construction.\n"
        "    scheme = uri.scheme.lowercase()\n"
        "    host = uri.host\n"
        "    port = if (uri.port < 0) null else uri.port\n"
        "    path = uri.rawPath.orEmpty().removePrefix(\"/\")",
    )
    extensions = VENDOR / "re-common/src/main/java/com/pedro/common/Extensions.kt"
    patch(
        extensions,
        "fun ByteBuffer.toByteArray(): ByteArray {\n"
        "  return if (this.hasArray() && !isDirect) {\n"
        "    this.array()\n"
        "  } else {\n"
        "    this.rewind()\n"
        "    val byteArray = ByteArray(this.remaining())\n"
        "    this.get(byteArray)\n"
        "    byteArray\n"
        "  }\n}",
        "fun ByteBuffer.toByteArray(): ByteArray {\n"
        "  if (hasArray() && !isDirect && arrayOffset() == 0 && position() == 0 && limit() == capacity()) {\n"
        "    return array()\n"
        "  }\n"
        "  // Copy this view's logical contents from index zero to its limit without changing its position.\n"
        "  val source = duplicate().apply { position(0) }\n"
        "  return ByteArray(source.limit()).also { source.get(it) }\n}",
    )
    patch(
        extensions,
        "fun ByteBuffer.removeInfo(info: MediaFrame.Info): ByteBuffer {\n"
        "  try {\n    position(info.offset)\n    limit(info.size)\n  } catch (_: Exception) { }\n"
        "  return slice()\n}",
        "fun ByteBuffer.removeInfo(info: MediaFrame.Info): ByteBuffer {\n"
        "  require(info.offset >= 0 && info.size >= 0) { \"Invalid encoded buffer range\" }\n"
        "  require(info.offset <= limit() && info.size <= limit() - info.offset) { \"Encoded buffer range exceeds its limit\" }\n"
        "  val end = info.offset + info.size\n"
        "  return duplicate().apply {\n    position(info.offset)\n    limit(end)\n  }.slice()\n}",
    )

    # Count FLV packet writes only after CommandsManager has flushed the socket.
    rtmp_sender = VENDOR / "re-rtmp/src/main/java/com/pedro/rtmp/rtmp/RtmpSender.kt"
    patch(rtmp_sender, "import android.util.Log\n", "import android.os.SystemClock\nimport android.util.Log\n")
    patch(rtmp_sender, "import com.pedro.common.validMessage\n", "")
    patch(
        rtmp_sender,
        "import java.nio.ByteBuffer\n",
        "import java.nio.ByteBuffer\n"
        "import java.util.concurrent.atomic.AtomicBoolean\n"
        "import java.util.concurrent.atomic.AtomicLong\n",
    )
    patch(
        rtmp_sender,
        "class RtmpSender(\n  connectChecker: ConnectChecker,\n  private val commandsManager: CommandsManager\n): BaseSender(connectChecker, \"RtmpSender\") {",
        "class RtmpSender(\n  connectChecker: ConnectChecker,\n  private val commandsManager: CommandsManager,\n"
        "  private val reportStage: (String) -> Unit = {}\n): BaseSender(connectChecker, \"RtmpSender\") {",
    )
    patch(
        rtmp_sender,
        "  var socket: RtmpSocket? = null\n",
        "  var socket: RtmpSocket? = null\n\n"
        "  // Packet/config counters advance only after the corresponding FLV packet's socket flush succeeds.\n"
        "  // Byte totals are RootEncoder RTMP message-length values (not TCP/TLS wire-byte counters).\n"
        "  // Unlike BaseSender's per-connection counters, these totals remain cumulative across RTMP retries.\n"
        "  private val successfulVideoPackets = AtomicLong(0)\n"
        "  private val successfulAudioPackets = AtomicLong(0)\n"
        "  private val successfulVideoConfigs = AtomicLong(0)\n"
        "  private val successfulAudioConfigs = AtomicLong(0)\n"
        "  private val successfulVideoKeyframes = AtomicLong(0)\n"
        "  private val successfulVideoBytes = AtomicLong(0)\n"
        "  private val successfulAudioBytes = AtomicLong(0)\n"
        "  private val successfulMediaBytes = AtomicLong(0)\n"
        "  private val lastVideoPacketAtMs = AtomicLong(0)\n"
        "  private val lastVideoKeyframeAtMs = AtomicLong(0)\n"
        "  private val lastAudioPacketAtMs = AtomicLong(0)\n"
        "  private val videoConfigReported = AtomicBoolean(false)\n"
        "  private val audioConfigReported = AtomicBoolean(false)\n"
        "  private val videoPacketReported = AtomicBoolean(false)\n"
        "  private val audioPacketReported = AtomicBoolean(false)\n"
        "  private val keyframeReported = AtomicBoolean(false)\n\n"
        "  fun getSuccessfulVideoPackets(): Long = successfulVideoPackets.get()\n"
        "  fun getSuccessfulAudioPackets(): Long = successfulAudioPackets.get()\n"
        "  fun getSuccessfulVideoConfigs(): Long = successfulVideoConfigs.get()\n"
        "  fun getSuccessfulAudioConfigs(): Long = successfulAudioConfigs.get()\n"
        "  fun getSuccessfulVideoKeyframes(): Long = successfulVideoKeyframes.get()\n"
        "  fun getSuccessfulVideoBytes(): Long = successfulVideoBytes.get()\n"
        "  fun getSuccessfulAudioBytes(): Long = successfulAudioBytes.get()\n"
        "  fun getLastVideoPacketAtMs(): Long = lastVideoPacketAtMs.get()\n"
        "  fun getLastVideoKeyframeAtMs(): Long = lastVideoKeyframeAtMs.get()\n"
        "  fun getLastAudioPacketAtMs(): Long = lastAudioPacketAtMs.get()\n"
        "  fun getSuccessfulMediaBytes(): Long = successfulMediaBytes.get()\n",
    )
    patch(
        rtmp_sender,
        "        getFlvPacket(mediaFrame) { flvPacket ->\n"
        "          var size = 0L\n"
        "          if (flvPacket.type == FlvType.VIDEO) {\n"
        "            videoFramesSent.incrementAndGet()\n"
        "            socket?.let { socket ->\n"
        "              size = commandsManager.sendVideoPacket(flvPacket, socket).toLong()\n"
        "              if (isEnableLogs) {\n"
        "                Log.i(TAG, \"wrote Video packet, size $size\")\n"
        "              }\n"
        "            }\n"
        "          } else {\n"
        "            audioFramesSent.incrementAndGet()\n"
        "            socket?.let { socket ->\n"
        "              size = commandsManager.sendAudioPacket(flvPacket, socket).toLong()\n"
        "              if (isEnableLogs) {\n"
        "                Log.i(TAG, \"wrote Audio packet, size $size\")\n"
        "              }\n"
        "            }\n"
        "          }\n"
        "          bytesSend.addAndGet(size)\n"
        "          bytesSendPerSecond.addAndGet(size)\n"
        "        }",
        "        getFlvPacket(mediaFrame) { flvPacket ->\n"
        "          val activeSocket = socket ?: return@getFlvPacket\n"
        "          val size = if (flvPacket.type == FlvType.VIDEO) {\n"
        "            commandsManager.sendVideoPacket(flvPacket, activeSocket).toLong()\n"
        "          } else {\n"
        "            commandsManager.sendAudioPacket(flvPacket, activeSocket).toLong()\n"
        "          }\n"
        "          if (size <= 0L) return@getFlvPacket\n\n"
        "          // The send methods flush before returning. Count only those completed writes.\n"
        "          bytesSend.addAndGet(size)\n"
        "          bytesSendPerSecond.addAndGet(size)\n"
        "          successfulMediaBytes.addAndGet(size)\n"
        "          if (flvPacket.type == FlvType.VIDEO) {\n"
        "            successfulVideoBytes.addAndGet(size)\n"
        "            val packetType = flvPacket.buffer.getOrNull(1)\n"
        "            if (packetType == H264Packet.Type.SEQUENCE.value) {\n"
        "              successfulVideoConfigs.incrementAndGet()\n"
        "              if (videoConfigReported.compareAndSet(false, true)) reportStage(\"H264_CONFIG_SENT\")\n"
        "            } else if (packetType == H264Packet.Type.NALU.value) {\n"
        "              successfulVideoPackets.incrementAndGet()\n"
        "              lastVideoPacketAtMs.set(SystemClock.elapsedRealtime())\n"
        "              videoFramesSent.incrementAndGet()\n"
        "              if (videoPacketReported.compareAndSet(false, true)) reportStage(\"VIDEO_PACKET_SENT\")\n"
        "              val isKeyframe = ((flvPacket.buffer[0].toInt() and 0xF0) shr 4) == 1\n"
        "              if (isKeyframe) {\n"
        "                successfulVideoKeyframes.incrementAndGet()\n"
        "                lastVideoKeyframeAtMs.set(SystemClock.elapsedRealtime())\n"
        "                if (keyframeReported.compareAndSet(false, true)) reportStage(\"VIDEO_KEYFRAME_SENT\")\n"
        "              }\n"
        "            }\n"
        "            if (isEnableLogs) Log.i(TAG, \"wrote Video packet, size $size\")\n"
        "          } else {\n"
        "            successfulAudioBytes.addAndGet(size)\n"
        "            val packetType = flvPacket.buffer.getOrNull(1)\n"
        "            if (packetType == AacPacket.Type.SEQUENCE.mark) {\n"
        "              successfulAudioConfigs.incrementAndGet()\n"
        "              if (audioConfigReported.compareAndSet(false, true)) reportStage(\"AAC_CONFIG_SENT\")\n"
        "            } else if (packetType == AacPacket.Type.RAW.mark) {\n"
        "              successfulAudioPackets.incrementAndGet()\n"
        "              lastAudioPacketAtMs.set(SystemClock.elapsedRealtime())\n"
        "              audioFramesSent.incrementAndGet()\n"
        "              if (audioPacketReported.compareAndSet(false, true)) reportStage(\"AUDIO_PACKET_SENT\")\n"
        "            }\n"
        "            if (isEnableLogs) Log.i(TAG, \"wrote Audio packet, size $size\")\n"
        "          }\n"
        "        }",
    )
    patch(
        rtmp_sender,
        "      if (error != null) {\n"
        "        onMainThread {\n"
        "          connectChecker.onConnectionFailed(\"Error send packet, ${error.validMessage()}\")\n"
        "        }\n"
        "        Log.e(TAG, \"send error: \", error)\n"
        "        running = false",
        "      if (error != null) {\n"
        "        reportStage(\"TRANSPORT_ERROR\")\n"
        "        onMainThread {\n"
        "          connectChecker.onConnectionFailed(\"RTMP packet write failed\")\n"
        "        }\n"
        "        Log.e(TAG, \"RTMP media packet write failed (${error.javaClass.simpleName})\")\n"
        "        running = false",
    )

    # Wire fixed, non-sensitive RTMP stage events and successful-write counters to the app.
    rtmp_client = VENDOR / "re-rtmp/src/main/java/com/pedro/rtmp/rtmp/RtmpClient.kt"
    patch(
        rtmp_client,
        "  private var jobRetry: Job? = null\n  private var commandsManager: CommandsManager = CommandsManagerAmf0()\n"
        "  private val rtmpSender = RtmpSender(connectChecker, commandsManager)",
        "  private var jobRetry: Job? = null\n  private var commandsManager: CommandsManager = CommandsManagerAmf0()\n"
        "  @Volatile private var stageListener: ((String) -> Unit)? = null\n"
        "  private val rtmpSender = RtmpSender(connectChecker, commandsManager) { stage -> reportStage(stage) }",
    )
    patch(
        rtmp_client,
        "  val bytesSend: Long\n    get() = rtmpSender.getBytesSend()",
        "  val bytesSend: Long\n    get() = rtmpSender.getBytesSend()\n"
        "  val successfulMediaBytes: Long\n    get() = rtmpSender.getSuccessfulMediaBytes()\n"
        "  val sentVideoPackets: Long\n    get() = rtmpSender.getSuccessfulVideoPackets()\n"
        "  val sentAudioPackets: Long\n    get() = rtmpSender.getSuccessfulAudioPackets()\n"
        "  val sentVideoKeyframes: Long\n    get() = rtmpSender.getSuccessfulVideoKeyframes()\n"
        "  val sentVideoCodecConfigs: Long\n    get() = rtmpSender.getSuccessfulVideoConfigs()\n"
        "  val sentAudioCodecConfigs: Long\n    get() = rtmpSender.getSuccessfulAudioConfigs()\n"
        "  val sentVideoBytes: Long\n    get() = rtmpSender.getSuccessfulVideoBytes()\n"
        "  val sentAudioBytes: Long\n    get() = rtmpSender.getSuccessfulAudioBytes()\n"
        "  val lastVideoPacketAtMs: Long\n    get() = rtmpSender.getLastVideoPacketAtMs()\n"
        "  val lastVideoKeyframeAtMs: Long\n    get() = rtmpSender.getLastVideoKeyframeAtMs()\n"
        "  val lastAudioPacketAtMs: Long\n    get() = rtmpSender.getLastAudioPacketAtMs()",
    )
    patch(
        rtmp_client,
        "  fun setIgnoredCommandCallback(callback: ((String) -> Unit)?) {\n"
        "    ignoredCommandReceived = callback\n  }\n",
        "  fun setIgnoredCommandCallback(callback: ((String) -> Unit)?) {\n"
        "    ignoredCommandReceived = callback\n  }\n\n"
        "  /** Receives fixed, non-sensitive transport/media events for app diagnostics. */\n"
        "  fun setStageListener(listener: ((String) -> Unit)?) {\n"
        "    stageListener = listener\n  }\n\n"
        "  private fun reportStage(stage: String) {\n"
        "    stageListener?.invoke(stage)\n  }\n",
    )
    patch(
        rtmp_client,
        "        this@RtmpClient.url = url\n        onMainThread {\n          connectChecker.onConnectionStarted(url)\n        }",
        "        this@RtmpClient.url = url\n        reportStage(\"CONNECTING\")\n"
        "        onMainThread {\n          connectChecker.onConnectionStarted(url)\n        }",
    )
    patch(
        rtmp_client,
        "        } catch (_: URISyntaxException) {\n          isStreaming = false\n          onMainThread {",
        "        } catch (_: URISyntaxException) {\n          isStreaming = false\n          reportStage(\"ENDPOINT_INVALID\")\n          onMainThread {",
    )
    patch(
        rtmp_client,
        "        if (commandsManager.appName.isEmpty()) {\n          isStreaming = false\n          onMainThread {\n            connectChecker.onConnectionFailed(\n              \"Endpoint malformed, should be: rtmp://ip:port/appname/streamname\")\n          }\n          return@launch\n        }\n\n        val user = urlParser.getAuthUser()",
        "        if (commandsManager.appName.isEmpty()) {\n          isStreaming = false\n          reportStage(\"ENDPOINT_INVALID\")\n          onMainThread {\n            connectChecker.onConnectionFailed(\n              \"Endpoint malformed, should be: rtmp://ip:port/appname/streamname\")\n          }\n          return@launch\n        }\n"
        "        // Never report parsed host, app, or stream name: the final component is the secret key.\n"
        "        reportStage(\"ENDPOINT_PARSED\")\n\n"
        "        val user = urlParser.getAuthUser()",
    )
    patch(
        rtmp_client,
        "          if (!establishConnection()) {\n            onMainThread {\n              connectChecker.onConnectionFailed(\"Handshake failed\")\n            }\n            return@launch\n          }\n          val socket = this@RtmpClient.socket ?: throw IOException(\"Invalid socket, Connection failed\")\n          commandsManager.sendChunkSize(socket)\n          commandsManager.sendConnect(\"\", socket)",
        "          if (!establishConnection()) {\n            onMainThread {\n              connectChecker.onConnectionFailed(\"RTMP transport setup failed\")\n            }\n            return@launch\n          }\n          val socket = this@RtmpClient.socket ?: throw IOException(\"Invalid socket, Connection failed\")\n          commandsManager.sendChunkSize(socket)\n          commandsManager.sendConnect(\"\", socket)\n          reportStage(\"RTMP_CONNECT_SENT\")",
    )
    patch(
        rtmp_client,
        "        if (error != null) {\n          Log.e(TAG, \"connection error\", error)",
        "        if (error != null) {\n          reportStage(\"TRANSPORT_ERROR\")\n"
        "          Log.e(TAG, \"RTMP connection operation failed (${error.javaClass.simpleName})\")",
    )
    patch(
        rtmp_client,
        "    this.socket = socket\n    socket.connect()\n    if (!socket.isConnected()) return false\n    val timestamp = TimeUtils.getCurrentTimeMillis() / 1000\n    val handshake = Handshake()\n    if (!handshake.sendHandshake(socket)) return false\n    commandsManager.timestamp = timestamp.toInt()\n    commandsManager.startTs = TimeUtils.getCurrentTimeNano() / 1000\n    return true",
        "    this.socket = socket\n    try {\n      socket.connect()\n    } catch (error: Exception) {\n      reportStage(\"SOCKET_CONNECT_FAILED\")\n      throw error\n    }\n    if (!socket.isConnected()) {\n      reportStage(\"SOCKET_CONNECT_FAILED\")\n      return false\n    }\n    reportStage(\"SOCKET_CONNECTED\")\n"
        "    val timestamp = TimeUtils.getCurrentTimeMillis() / 1000\n    val handshake = Handshake()\n"
        "    val handshakeSucceeded = try {\n      handshake.sendHandshake(socket)\n    } catch (error: Exception) {\n"
        "      reportStage(\"HANDSHAKE_FAILED\")\n      throw error\n    }\n"
        "    if (!handshakeSucceeded) {\n      reportStage(\"HANDSHAKE_FAILED\")\n      return false\n    }\n"
        "    commandsManager.timestamp = timestamp.toInt()\n"
        "    commandsManager.startTs = TimeUtils.getCurrentTimeNano() / 1000\n"
        "    reportStage(\"HANDSHAKE_COMPLETE\")\n    return true",
    )
    patch(
        rtmp_client,
        "              \"connect\" -> {\n                if (commandsManager.onAuth) {",
        "              \"connect\" -> {\n                reportStage(\"RTMP_CONNECT_ACCEPTED\")\n"
        "                if (commandsManager.onAuth) {",
    )
    patch(
        rtmp_client,
        "                  commandsManager.streamId = command.getStreamId()\n                  commandsManager.sendPublish(socket)",
        "                  commandsManager.streamId = command.getStreamId()\n"
        "                  commandsManager.sendPublish(socket)\n"
        "                  reportStage(\"PUBLISH_SENT\")",
    )
    patch(
        rtmp_client,
        "                  if (description.contains(\"reason=authfail\") || description.contains(\"reason=nosuchuser\")) {\n                    onMainThread {",
        "                  if (description.contains(\"reason=authfail\") || description.contains(\"reason=nosuchuser\")) {\n"
        "                    reportStage(\"RTMP_CONNECT_FAILED\")\n                    onMainThread {",
    )
    patch(
        rtmp_client,
        "                  } else {\n                    onMainThread {\n                      connectChecker.onAuthError()\n                    }\n                  }\n                }\n"
        "                //We can ignore this errors.",
        "                  } else {\n                    reportStage(\"RTMP_CONNECT_FAILED\")\n"
        "                    onMainThread {\n                      connectChecker.onAuthError()\n                    }\n                  }\n                }\n"
        "                //We can ignore this errors.",
    )
    patch(
        rtmp_client,
        "                else -> {\n                  onMainThread {\n                    connectChecker.onConnectionFailed(description)\n                  }\n                }",
        "                else -> {\n                  if (commandName == \"publish\") reportStage(\"PUBLISH_FAILED\")\n"
        "                  else reportStage(\"RTMP_CONNECT_FAILED\")\n"
        "                  onMainThread {\n                    connectChecker.onConnectionFailed(description)\n                  }\n                }",
    )
    patch(
        rtmp_client,
        "                \"NetStream.Publish.Start\" -> {\n                  commandsManager.sendMetadata(socket)\n                  onMainThread {",
        "                \"NetStream.Publish.Start\" -> {\n                  commandsManager.sendMetadata(socket)\n"
        "                  reportStage(\"PUBLISH_ACCEPTED\")\n                  onMainThread {",
    )
    patch(
        rtmp_client,
        "                \"NetConnection.Connect.Rejected\", \"NetStream.Publish.BadName\", \"NetConnection.Connect.Closed\", \"NetStream.Publish.Failed\" -> {\n                  onMainThread {",
        "                \"NetConnection.Connect.Rejected\", \"NetStream.Publish.BadName\", \"NetConnection.Connect.Closed\", \"NetStream.Publish.Failed\" -> {\n"
        "                  when (code) {\n"
        "                    \"NetConnection.Connect.Rejected\" -> reportStage(\"RTMP_CONNECT_FAILED\")\n"
        "                    \"NetStream.Publish.BadName\", \"NetStream.Publish.Failed\" -> reportStage(\"PUBLISH_FAILED\")\n"
        "                    else -> reportStage(\"TRANSPORT_ERROR\")\n"
        "                  }\n                  onMainThread {",
    )

    rtmp_stream_client = VENDOR / "re-library/src/main/java/com/pedro/library/util/streamclient/RtmpStreamClient.kt"
    patch(
        rtmp_stream_client,
        "  fun setIgnoredCommandCallback(callback: ((String) -> Unit)?) {\n"
        "    rtmpClient.setIgnoredCommandCallback(callback)\n  }\n",
        "  fun setIgnoredCommandCallback(callback: ((String) -> Unit)?) {\n"
        "    rtmpClient.setIgnoredCommandCallback(callback)\n  }\n\n"
        "  /** Fixed, non-sensitive transport/media events only; endpoint and stream key are never emitted. */\n"
        "  fun setStageListener(listener: ((String) -> Unit)?) {\n"
        "    rtmpClient.setStageListener(listener)\n  }\n\n"
        "  fun getSentVideoPackets(): Long = rtmpClient.sentVideoPackets\n"
        "  fun getSentAudioPackets(): Long = rtmpClient.sentAudioPackets\n"
        "  fun getSentVideoKeyframes(): Long = rtmpClient.sentVideoKeyframes\n"
        "  fun getSentVideoCodecConfigs(): Long = rtmpClient.sentVideoCodecConfigs\n"
        "  fun getSentAudioCodecConfigs(): Long = rtmpClient.sentAudioCodecConfigs\n"
        "  fun getSentVideoBytes(): Long = rtmpClient.sentVideoBytes\n"
        "  fun getSentAudioBytes(): Long = rtmpClient.sentAudioBytes\n"
        "  fun getLastVideoPacketAtMs(): Long = rtmpClient.lastVideoPacketAtMs\n"
        "  fun getLastVideoKeyframeAtMs(): Long = rtmpClient.lastVideoKeyframeAtMs\n"
        "  fun getLastAudioPacketAtMs(): Long = rtmpClient.lastAudioPacketAtMs\n"
        "  fun getSuccessfulMediaBytes(): Long = rtmpClient.successfulMediaBytes\n",
    )

    # RtpConstants lives in the (not vendored) RTSP module.
    for rel in (
        "library/src/main/java/com/pedro/library/base/recording/AsyncBaseRecordController.kt",
        "library/src/main/java/com/pedro/library/base/recording/BaseRecordController.java",
    ):
        path = VENDOR / MODULES[rel.split("/")[0]] / rel.split("/", 1)[1]
        text = path.read_text()
        text = text.replace("import com.pedro.rtsp.utils.RtpConstants\n", "")
        text = text.replace("import com.pedro.rtsp.utils.RtpConstants;", "")
        text = re.sub(r"RtpConstants\.(IDR_W_DLP|IDR_N_LP|IDR)", r"MihadNal.\1", text)
        path.write_text(text)
    (VENDOR / "re-library/src/main/java/com/pedro/library/base/recording/NalConstants.kt").write_text(
        NAL_CONSTANTS
    )

    # Count non-empty video frames decoded from the selected file before they enter GL.
    video_decoder = VENDOR / "re-encoder/src/main/java/com/pedro/encoder/input/decoder/VideoDecoder.java"
    patch(video_decoder, "import java.nio.ByteBuffer;\n", "import java.nio.ByteBuffer;\nimport java.util.concurrent.atomic.AtomicLong;\n")
    patch(
        video_decoder,
        "  private final VideoDecoderInterface videoDecoderInterface;\n  private int width;",
        "  private final VideoDecoderInterface videoDecoderInterface;\n"
        "  private final AtomicLong decodedFrames = new AtomicLong(0);\n  private int width;",
    )
    patch(
        video_decoder,
        "  @Override\n  protected boolean decodeOutput(ByteBuffer outputBuffer, long timeStamp) {\n    return true;\n  }",
        "  @Override\n  protected boolean decodeOutput(ByteBuffer outputBuffer, long timeStamp) {\n"
        "    if (bufferInfo.size > 0) decodedFrames.incrementAndGet();\n    return true;\n  }\n\n"
        "  /** Number of non-empty decoded source video frames. */\n"
        "  public long getDecodedFrames() {\n    return decodedFrames.get();\n  }",
    )

    # Preserve source decoder dimensions separately from the chosen output canvas.
    video_source = VENDOR / "re-encoder/src/main/java/com/pedro/encoder/input/sources/video/VideoFileSource.kt"
    patch(
        video_source,
        "  fun getDuration() = videoDecoder.duration\n\n  fun getTime() = videoDecoder.time\n",
        "  fun getDuration() = videoDecoder.duration\n\n  fun getTime() = videoDecoder.time\n\n"
        "  /** Actual decoded track size, available after [create] / prepareVideo. */\n"
        "  fun getSourceWidth() = videoDecoder.width\n\n"
        "  fun getSourceHeight() = videoDecoder.height\n",
    )
    patch(
        video_source,
        "  fun getSourceHeight() = videoDecoder.height\n\n  fun setLoopMode(enabled: Boolean) {",
        "  fun getSourceHeight() = videoDecoder.height\n\n"
        "  /** Number of non-empty MediaCodec-decoded source video frames. */\n"
        "  fun getDecodedFrames() = videoDecoder.getDecodedFrames()\n\n"
        "  fun setLoopMode(enabled: Boolean) {",
    )
    patch(
        video_source,
        "  override fun release() {\n    if (running) stop()\n  }\n",
        "  override fun release() {\n    running = false\n"
        "    // stop() also releases MediaExtractor if no preview surface was attached.\n"
        "    videoDecoder.stop()\n  }\n",
    )
    audio_source = VENDOR / "re-encoder/src/main/java/com/pedro/encoder/input/sources/audio/AudioFileSource.kt"
    patch(audio_source, "import java.io.IOException\n", "import java.io.IOException\nimport java.util.concurrent.atomic.AtomicLong\n")
    patch(
        audio_source,
        "  private val getMicrophoneDataCallback = object: GetMicrophoneData {\n"
        "    override fun inputPCMData(frame: Frame) {\n"
        "      audioTrackPlayer?.write(frame.buffer, frame.offset, frame.size)",
        "  private val getMicrophoneDataCallback = object: GetMicrophoneData {\n"
        "    override fun inputPCMData(frame: Frame) {\n"
        "      if (frame.size > 0) decodedPcmFrames.incrementAndGet()\n"
        "      audioTrackPlayer?.write(frame.buffer, frame.offset, frame.size)",
    )
    patch(
        audio_source,
        "  private var running = false\n  private var audioDecoder = AudioDecoder(getMicrophoneDataCallback, audioDecoderInterface, decoderInterface)",
        "  private var running = false\n  private val decodedPcmFrames = AtomicLong(0)\n"
        "  private var audioDecoder = AudioDecoder(getMicrophoneDataCallback, audioDecoderInterface, decoderInterface)",
    )
    patch(
        audio_source,
        "  fun getTime() = audioDecoder.time\n\n  fun setLoopMode(enabled: Boolean) {",
        "  fun getTime() = audioDecoder.time\n\n"
        "  /** Number of non-empty source PCM frames delivered to the audio encoder input. */\n"
        "  fun getDecodedFrames() = decodedPcmFrames.get()\n\n"
        "  fun setLoopMode(enabled: Boolean) {",
    )
    stream_base = VENDOR / "re-library/src/main/java/com/pedro/library/base/StreamBase.kt"
    patch(
        stream_base,
        "import com.pedro.encoder.input.sources.video.VideoSource\n",
        "import com.pedro.encoder.input.sources.video.VideoSource\n"
        "import com.pedro.encoder.input.sources.video.VideoFileSource\n",
    )
    patch(
        stream_base,
        "    val videoResult = videoSource.init(max(width, recordWidth), max(height, recordHeight), fps, rotation)\n"
        "    if (videoResult) {\n",
        "    val videoResult = videoSource.init(max(width, recordWidth), max(height, recordHeight), fps, rotation)\n"
        "    if (videoResult) {\n"
        "      val fileVideoSource = videoSource as? VideoFileSource\n"
        "      if (fileVideoSource != null) {\n"
        "        glInterface.setSourceSize(fileVideoSource.getSourceWidth(), fileVideoSource.getSourceHeight())\n"
        "      } else {\n"
        "        glInterface.setSourceSize(max(width, recordWidth), max(height, recordHeight))\n"
        "      }\n",
    )

    # Count actual encoded MediaCodec output buffers (not packets/bytes written) for honest metrics.
    rtmp_stream = VENDOR / "re-library/src/main/java/com/pedro/library/rtmp/RtmpStream.kt"
    patch(rtmp_stream, "import java.nio.ByteBuffer\n", "import java.nio.ByteBuffer\nimport java.util.concurrent.atomic.AtomicLong\n")
    patch(
        rtmp_stream,
        "import com.pedro.encoder.input.sources.audio.AudioSource\n",
        "import com.pedro.encoder.input.sources.audio.AudioFileSource\n"
        "import com.pedro.encoder.input.sources.audio.AudioSource\n",
    )
    patch(
        rtmp_stream,
        "import com.pedro.encoder.input.sources.video.VideoSource\n",
        "import com.pedro.encoder.input.sources.video.VideoFileSource\n"
        "import com.pedro.encoder.input.sources.video.VideoSource\n",
    )
    patch(
        rtmp_stream,
        "  private val rtmpClient = RtmpClient(connectChecker)\n",
        "  private val rtmpClient = RtmpClient(connectChecker)\n"
        "  private val encodedVideoBytes = AtomicLong(0)\n"
        "  private val encodedAudioBytes = AtomicLong(0)\n"
        "  private val encodedVideoFrames = AtomicLong(0)\n"
        "  private val encodedAudioFrames = AtomicLong(0)\n"
        "  private val fileVideoSource = videoSource as? VideoFileSource\n"
        "  private val fileAudioSource = audioSource as? AudioFileSource\n\n"
        "  /** Actual decoded media counters from the selected local file source. */\n"
        "  fun getDecodedSourceVideoFrames(): Long = fileVideoSource?.getDecodedFrames() ?: 0L\n"
        "  fun getDecodedSourceAudioFrames(): Long = fileAudioSource?.getDecodedFrames() ?: 0L\n\n"
        "  /** Encoded MediaCodec output counters; values do not include RTMP/FLV overhead. */\n"
        "  fun getEncodedVideoBytes(): Long = encodedVideoBytes.get()\n"
        "  fun getEncodedAudioBytes(): Long = encodedAudioBytes.get()\n"
        "  fun getEncodedVideoFrames(): Long = encodedVideoFrames.get()\n"
        "  fun getEncodedAudioFrames(): Long = encodedAudioFrames.get()\n",
    )
    patch(
        rtmp_stream,
        "  override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {\n"
        "    rtmpClient.sendVideo(videoBuffer, info)\n  }\n\n"
        "  override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {\n"
        "    rtmpClient.sendAudio(audioBuffer, info)\n  }\n",
        "  override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {\n"
        "    if (info.size > 0) {\n"
        "      encodedVideoBytes.addAndGet(info.size.toLong())\n"
        "      encodedVideoFrames.incrementAndGet()\n"
        "    }\n"
        "    rtmpClient.sendVideo(videoBuffer, info)\n  }\n\n"
        "  override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {\n"
        "    if (info.size > 0) {\n"
        "      encodedAudioBytes.addAndGet(info.size.toLong())\n"
        "      encodedAudioFrames.incrementAndGet()\n"
        "    }\n"
        "    rtmpClient.sendAudio(audioBuffer, info)\n  }\n",
    )

    # RTMP stream names can include the YouTube key. Do not dump AMF payloads, server replies or errors.
    for filename in ("CommandsManagerAmf0.kt", "CommandsManagerAmf3.kt"):
        path = VENDOR / "re-rtmp/src/main/java/com/pedro/rtmp/rtmp" / filename
        text = path.read_text()
        for variable, label in (
            ("connect", "connect"), ("releaseStream", "releaseStream"),
            ("fcPublish", "FCPublish"), ("createStream", "createStream"),
            ("metadata", "metadata"), ("publish", "publish"), ("closeStream", "closeStream"),
        ):
            text = text.replace(f'Log.i(TAG, "send ${variable}")', f'Log.i(TAG, "send {label} command")')
        path.write_text(text)
    commands = VENDOR / "re-rtmp/src/main/java/com/pedro/rtmp/rtmp/CommandsManager.kt"
    text = commands.read_text().replace('Log.i(TAG, "read $message")', 'Log.i(TAG, "received RTMP control/message packet")')
    commands.write_text(text)
    rtmp_client = VENDOR / "re-rtmp/src/main/java/com/pedro/rtmp/rtmp/RtmpClient.kt"
    text = rtmp_client.read_text()
    text = text.replace('Log.e(TAG, "connection error", error)', 'Log.e(TAG, "RTMP connection operation failed (${error.javaClass.simpleName})")')
    text = text.replace('connectChecker.onConnectionFailed("Error configure stream, ${error.validMessage()}")', 'connectChecker.onConnectionFailed("RTMP connection setup failed")')
    text = text.replace('Log.e(TAG, "$commandName failed: $description")', 'Log.e(TAG, "$commandName failed (server rejected the RTMP command)")')
    rtmp_client.write_text(text)

    gl = VENDOR / "re-library/src/main/java/com/pedro/library/view/GlStreamInterface.kt"
    patch(
        gl,
        "  private var previewViewPort: ViewPort? = null\n  private var streamViewPort: ViewPort? = null",
        "  @Volatile private var previewViewPort: ViewPort? = null\n  @Volatile private var streamViewPort: ViewPort? = null",
    )
    patch(gl, *GL_SOURCE_SIZE_FIELDS)
    patch(gl, *GL_START_SIZE)
    patch(gl, *GL_SOURCE_SIZE_METHODS)

    # Preserve the monotonic PTS normalizer as a reproducible vendor patch.
    normalizer_source = ROOT / "tools/rootencoder-patches/MonotonicTimestampNormalizer.kt"
    normalizer_target = VENDOR / "re-common/src/main/java/com/pedro/common/MonotonicTimestampNormalizer.kt"
    normalizer_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(normalizer_source, normalizer_target)

    # Rewind file extractors at EOS in loop mode, but only after copying the final sample
    # into MediaCodec. Decoder input PTS is elapsed-time based and remains one A/V timeline.
    base_decoder = VENDOR / "re-encoder/src/main/java/com/pedro/encoder/input/decoder/BaseDecoder.java"
    patch(
        base_decoder,
        "          sampleSize = extractor.readFrame(input);\n"
        "          long ts = TimeUtils.getCurrentTimeMicro() - startTs;\n"
        "          sleepTime = extractor.getSleepTime(ts);\n"
        "          finished = !extractor.advance();\n"
        "          if (finished) {\n"
        "            if (!loopMode) {\n"
        "              codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);\n"
        "            }\n"
        "          } else {\n"
        "            codec.queueInputBuffer(inIndex, 0, sampleSize, ts + sleepTime, 0);\n"
        "          }",
        "          sampleSize = extractor.readFrame(input);\n"
        "          long ts = TimeUtils.getCurrentTimeMicro() - startTs;\n"
        "          sleepTime = extractor.getSleepTime(ts);\n"
        "          finished = sampleSize < 0 || !extractor.advance();\n"
        "          if (finished && !loopMode) {\n"
        "            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);\n"
        "          } else {\n"
        "            // Decoder input PTS comes from elapsed time (not the container's sample PTS), so it\n"
        "            // stays monotonic across this seek and keeps the video/audio timelines continuous.\n"
        "            codec.queueInputBuffer(inIndex, 0, Math.max(0, sampleSize), ts + sleepTime, 0);\n"
        "            if (finished) {\n"
        "              // Rewind only after readSampleData copied the last sample into this codec buffer.\n"
        "              // Without this seek the old loop callback fired once while decoding then stalled.\n"
        "              extractor.seekTo(0);\n"
        "              looped = true;\n"
        "            }\n"
        "          }",
    )
    patch(
        base_decoder,
        "          if (finished) {\n"
        "            if (loopMode) {\n"
        "              looped = true;\n"
        "            } else {\n"
        "              Log.i(TAG, \"end of file\");\n"
        "              shouldFinish = true;\n"
        "            }\n"
        "          }",
        "          if (finished && !loopMode) {\n"
        "            Log.i(TAG, \"end of file\");\n"
        "            shouldFinish = true;\n"
        "          }",
    )

    # SurfaceTexture can repeat or reset its clock. Normalize one timestamp once per GL frame
    # and feed the identical value to stream and record encoders.
    patch(gl, "import android.os.HandlerThread\n", "import android.os.HandlerThread\nimport android.util.Log\n")
    patch(gl, "import androidx.annotation.RequiresApi\n", "import androidx.annotation.RequiresApi\nimport com.pedro.common.MonotonicTimestampNormalizer\n")
    patch(
        gl,
        "import java.util.concurrent.atomic.AtomicBoolean\n",
        "import java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicInteger\n",
    )
    patch(
        gl,
        "  private val fpsLimiter = FpsLimiter()\n  private val forceRender = ForceRenderer()",
        "  private val fpsLimiter = FpsLimiter()\n  private val forceRender = ForceRenderer()\n"
        "  private val presentationTimestampNormalizer = MonotonicTimestampNormalizer()\n"
        "  private val loopTimestampDiagnosticsRemaining = AtomicInteger(0)\n"
        "  @Volatile private var nominalFrameDurationNanos = 33_333_333L\n"
        "  private val TAG = \"GlStreamInterface\"",
    )
    patch(
        gl,
        "  override fun getEncoderSize(): Point {\n    return Point(encoderWidth, encoderHeight)\n  }\n",
        "  override fun getEncoderSize(): Point {\n    return Point(encoderWidth, encoderHeight)\n  }\n\n"
        "  fun startLoopTimestampDiagnostics(frameCount: Int) {\n"
        "    loopTimestampDiagnosticsRemaining.set(frameCount.coerceAtLeast(0))\n  }\n",
    )
    patch(gl, "  override fun start() {\n    threadQueue.clear()", "  override fun start() {\n    presentationTimestampNormalizer.reset()\n    threadQueue.clear()")
    patch(
        gl,
        "    if (surfaceManagerEncoder.isReady || surfaceManagerEncoderRecord.isReady || surfaceManagerPhoto.isReady) {\n"
        "      mainRender.drawFilters(false)\n"
        "    }\n"
        "    // render VideoEncoder (stream and record)\n"
        "    if (surfaceManagerEncoder.isReady && mainRender.isReady() && !limitFps) {\n"
        "      val w = if (muteVideo) 0 else encoderWidth\n"
        "      val h = if (muteVideo) 0 else encoderHeight\n"
        "      if (surfaceManagerEncoder.makeCurrent()) {\n"
        "        mainRender.drawScreenEncoder(w, h, orientation, streamOrientation,\n"
        "          isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort)\n"
        "        surfaceManagerEncoder.setPresentationTime(mainRender.getSurfaceTexture().timestamp)\n"
        "        surfaceManagerEncoder.swapBuffer()\n"
        "      }\n"
        "    }\n"
        "    // render VideoEncoder (record if the resolution is different than stream)\n"
        "    if (surfaceManagerEncoderRecord.isReady && mainRender.isReady() && !limitFps) {\n"
        "      val w = if (muteVideo) 0 else encoderRecordWidth\n"
        "      val h = if (muteVideo) 0 else encoderRecordHeight\n"
        "      if (surfaceManagerEncoderRecord.makeCurrent()) {\n"
        "        mainRender.drawScreenEncoder(w, h, orientation, streamOrientation,\n"
        "          isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort)\n"
        "        // Fix: same timestamp fix for the dedicated record surface\n"
        "        surfaceManagerEncoderRecord.setPresentationTime(mainRender.getSurfaceTexture().timestamp)\n"
        "        surfaceManagerEncoderRecord.swapBuffer()\n"
        "      }\n"
        "    }",
        "    if (surfaceManagerEncoder.isReady || surfaceManagerEncoderRecord.isReady || surfaceManagerPhoto.isReady) {\n"
        "      mainRender.drawFilters(false)\n"
        "    }\n"
        "    val encoderPresentationTimeNanos = if (!limitFps && mainRender.isReady() &&\n"
        "      (surfaceManagerEncoder.isReady || surfaceManagerEncoderRecord.isReady)) {\n"
        "      val sourceTimestampNanos = mainRender.getSurfaceTexture().timestamp\n"
        "      val monotonicTimestampNanos = presentationTimestampNormalizer.normalize(\n"
        "        sourceTimestampNanos,\n        nominalFrameDurationNanos\n      )\n"
        "      val diagnosticsRemaining = loopTimestampDiagnosticsRemaining.getAndUpdate { remaining ->\n"
        "        if (remaining > 0) remaining - 1 else 0\n      }\n"
        "      if (diagnosticsRemaining > 0) {\n"
        "        Log.i(TAG, \"loop-pts gl remaining=$diagnosticsRemaining sourceSurfaceTextureNs=$sourceTimestampNanos \" +\n"
        "          \"monotonicInputNs=$monotonicTimestampNanos streamSurface=${surfaceManagerEncoder.isReady} \" +\n"
        "          \"recordSurface=${surfaceManagerEncoderRecord.isReady}\")\n      }\n"
        "      monotonicTimestampNanos\n    } else {\n      null\n    }\n"
        "    // render VideoEncoder (stream and record) on one shared, monotonic timestamp timeline.\n"
        "    if (surfaceManagerEncoder.isReady && mainRender.isReady() && !limitFps) {\n"
        "      val w = if (muteVideo) 0 else encoderWidth\n"
        "      val h = if (muteVideo) 0 else encoderHeight\n"
        "      if (surfaceManagerEncoder.makeCurrent()) {\n"
        "        mainRender.drawScreenEncoder(w, h, orientation, streamOrientation,\n"
        "          isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort)\n"
        "        surfaceManagerEncoder.setPresentationTime(encoderPresentationTimeNanos!!)\n"
        "        surfaceManagerEncoder.swapBuffer()\n      }\n    }\n"
        "    // render VideoEncoder (record if the resolution is different than stream)\n"
        "    if (surfaceManagerEncoderRecord.isReady && mainRender.isReady() && !limitFps) {\n"
        "      val w = if (muteVideo) 0 else encoderRecordWidth\n"
        "      val h = if (muteVideo) 0 else encoderRecordHeight\n"
        "      if (surfaceManagerEncoderRecord.makeCurrent()) {\n"
        "        mainRender.drawScreenEncoder(w, h, orientation, streamOrientation,\n"
        "          isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort)\n"
        "        surfaceManagerEncoderRecord.setPresentationTime(encoderPresentationTimeNanos!!)\n"
        "        surfaceManagerEncoderRecord.swapBuffer()\n      }\n    }",
    )
    patch(
        gl,
        "  override fun forceFpsLimit(fps: Int) {\n    fpsLimiter.setFPS(fps)\n  }",
        "  override fun forceFpsLimit(fps: Int) {\n    fpsLimiter.setFPS(fps)\n"
        "    if (fps > 0) nominalFrameDurationNanos = (1_000_000_000L / fps).coerceAtLeast(1L)\n  }",
    )

    # Also normalize the MediaCodec output PTS (with a per-encoder timeline) and provide
    # bounded output PTS/keyframe-flag diagnostics during the 180 frames after a loop.
    video_encoder = VENDOR / "re-encoder/src/main/java/com/pedro/encoder/video/VideoEncoder.java"
    patch(video_encoder, "import com.pedro.common.TimeUtils;\n", "import com.pedro.common.MonotonicTimestampNormalizer;\nimport com.pedro.common.TimeUtils;\n")
    patch(video_encoder, "import java.util.List;\n", "import java.util.List;\nimport java.util.concurrent.atomic.AtomicInteger;\n")
    patch(
        video_encoder,
        "  private int iFrameInterval = 2;\n  private long firstTimestamp = 0;",
        "  private int iFrameInterval = 2;\n  private long firstTimestamp = 0;\n"
        "  private final MonotonicTimestampNormalizer timestampNormalizer = new MonotonicTimestampNormalizer();\n"
        "  private final AtomicInteger loopTimestampDiagnosticsRemaining = new AtomicInteger(0);\n"
        "  private volatile String loopTimestampDiagnosticPath = \"video\";",
    )
    patch(
        video_encoder,
        "  public VideoEncoder(GetVideoData getVideoData) {\n"
        "    this.getVideoData = getVideoData;\n"
        "    typeError = CodecUtil.CodecTypeError.VIDEO_CODEC;\n"
        "    type = CodecUtil.H264_MIME;\n"
        "    TAG = \"VideoEncoder\";\n  }",
        "  public VideoEncoder(GetVideoData getVideoData) {\n"
        "    this.getVideoData = getVideoData;\n"
        "    typeError = CodecUtil.CodecTypeError.VIDEO_CODEC;\n"
        "    type = CodecUtil.H264_MIME;\n"
        "    TAG = \"VideoEncoder\";\n  }\n\n"
        "  public void startLoopTimestampDiagnostics(String path, int frameCount) {\n"
        "    loopTimestampDiagnosticPath = path;\n"
        "    loopTimestampDiagnosticsRemaining.set(Math.max(0, frameCount));\n  }",
    )
    patch(
        video_encoder,
        "  public void start(boolean resetTs) {\n    if (resetTs) firstTimestamp = 0;\n    forceKey = false;",
        "  public void start(boolean resetTs) {\n    if (resetTs) {\n"
        "      firstTimestamp = 0;\n      timestampNormalizer.reset();\n    }\n    forceKey = false;",
    )
    patch(
        video_encoder,
        "  @Override\n  public void formatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {\n"
        "    getVideoData.onVideoFormat(mediaFormat);\n    spsPpsSetted = sendSPSandPPS(mediaFormat);\n  }",
        "  @Override\n  public void formatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {\n"
        "    getVideoData.onVideoFormat(mediaFormat);\n    spsPpsSetted = sendSPSandPPS(mediaFormat);\n  }\n\n"
        "  private void normalizeOutputTimestamp(MediaCodec.BufferInfo info) {\n"
        "    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;\n"
        "    long sourceTimestampNanos = info.presentationTimeUs * 1_000L;\n"
        "    long frameDurationNanos = Math.max(1L, 1_000_000_000L / Math.max(1, fps));\n"
        "    info.presentationTimeUs = timestampNormalizer.normalize(sourceTimestampNanos, frameDurationNanos) / 1_000L;\n"
        "    int diagnosticsRemaining = loopTimestampDiagnosticsRemaining.getAndUpdate(remaining ->\n"
        "        remaining > 0 ? remaining - 1 : 0);\n"
        "    if (diagnosticsRemaining > 0) {\n"
        "      boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;\n"
        "      Log.i(TAG, \"loop-pts encoded path=\" + loopTimestampDiagnosticPath + \" remaining=\" + diagnosticsRemaining +\n"
        "          \" ptsUs=\" + info.presentationTimeUs + \" flags=\" + info.flags + \" keyframe=\" + keyframe +\n"
        "          \" size=\" + info.size);\n    }\n  }",
    )
    patch(
        video_encoder,
        "    } else {\n      if (firstTimestamp == 0) firstTimestamp = bufferInfo.presentationTimeUs;\n"
        "      bufferInfo.presentationTimeUs -= firstTimestamp;\n    }\n  }\n\n  @Override\n  protected void sendBuffer",
        "    } else {\n      if (firstTimestamp == 0) firstTimestamp = bufferInfo.presentationTimeUs;\n"
        "      bufferInfo.presentationTimeUs -= firstTimestamp;\n    }\n"
        "    normalizeOutputTimestamp(bufferInfo);\n  }\n\n  @Override\n  protected void sendBuffer",
    )

    # Loop diagnostics and explicit keyframe recovery are exposed through StreamBase.
    patch(
        stream_base,
        "  fun requestKeyframe() {\n"
        "    if (videoEncoder.isRunning) {\n"
        "      videoEncoder.requestKeyframe()\n    }\n"
        "    if (videoEncoderRecord.isRunning) {\n"
        "      videoEncoderRecord.requestKeyframe()\n    }\n"
        "  }",
        "  fun requestKeyframe() {\n"
        "    if (videoEncoder.isRunning) {\n"
        "      videoEncoder.requestKeyframe()\n    }\n"
        "    if (videoEncoderRecord.isRunning) {\n"
        "      videoEncoderRecord.requestKeyframe()\n    }\n"
        "  }\n\n"
        "  /** Enable bounded, non-sensitive timestamp/keyframe logs around a detected source loop. */\n"
        "  fun startLoopTimestampDiagnostics(frameCount: Int) {\n"
        "    glInterface.startLoopTimestampDiagnostics(frameCount)\n"
        "    videoEncoder.startLoopTimestampDiagnostics(\"stream\", frameCount)\n"
        "    videoEncoderRecord.startLoopTimestampDiagnostics(\"record\", frameCount)\n"
        "  }",
    )

    total = sum(1 for _ in (VENDOR / "re-common").rglob("*") if _.is_file())
    print(f"Vendored RootEncoder from {src}")
    for module, target in MODULES.items():
        count = sum(1 for _ in (VENDOR / target / "src").rglob("*") if _.is_file())
        print(f"  {target}: {count} files")
    print(f"  (re-common sanity count: {total})")


if __name__ == "__main__":
    main()
