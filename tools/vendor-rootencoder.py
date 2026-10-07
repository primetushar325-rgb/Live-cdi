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

    gl = VENDOR / "re-library/src/main/java/com/pedro/library/view/GlStreamInterface.kt"
    patch(gl, *GL_SOURCE_SIZE_FIELDS)
    patch(gl, *GL_START_SIZE)
    patch(gl, *GL_SOURCE_SIZE_METHODS)

    total = sum(1 for _ in (VENDOR / "re-common").rglob("*") if _.is_file())
    print(f"Vendored RootEncoder from {src}")
    for module, target in MODULES.items():
        count = sum(1 for _ in (VENDOR / target / "src").rglob("*") if _.is_file())
        print(f"  {target}: {count} files")
    print(f"  (re-common sanity count: {total})")


if __name__ == "__main__":
    main()
