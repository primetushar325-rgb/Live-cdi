# Mihad Live test report

## Automated checks included

The app module contains JVM tests for:

- RTMP/RTMPS validation and exact preservation of scheme, host, explicit port, encoded application path and the separate stream key; whitespace is rejected rather than silently modified.
- Encoded-buffer copies preserve a ByteBuffer view's logical range without moving its position; MediaCodec slicing uses `BufferInfo.offset + size`, preventing truncated or over-read codec payloads when offsets are non-zero.
- Pipeline evidence models measured after publish acceptance: source/decoder output, H.264/AAC encoder output, codec-configuration writes, per-track media writes, keyframes and audio-required readiness.
- Separate protocol stages for handshake, RTMP connect acceptance, publish request/acceptance and media flow. Local media flow cannot transition into `YOUTUBE_INGEST_DETECTED` or `LIVE` without an official YouTube signal.
- Landscape/portrait composition, Fit/Fill, zoom and pan viewport math.
- Strictly monotonic timestamps through increasing, repeated, backward/reset and explicitly restarted source timelines.
- Pure keyframe-watchdog timing decisions: wait until four seconds, request no more often than every two seconds, and fail no earlier than 20 seconds without a successful keyframe after a request has actually been issued.

GitHub Actions runs `:app:testDebugUnitTest`, then assembles debug and release APKs. Lint is also attempted but is non-blocking. The workflow uploads APKs as a downloadable artifact; release tags also publish a GitHub Release.

## Sandbox validation

- The repository contains a Gradle wrapper pinned to Gradle 8.14.3, Android Gradle Plugin 8.13.2, Kotlin 2.2.21 and JDK 17 in CI.
- RootEncoder 2.7.0 source and its license are vendored. The app uses the real Android MediaCodec → GL → FLV/RTMP/RTMPS pipeline; sender packet counters advance only after the packet write/flush returns successfully.
- `tools/vendor-rootencoder.py` now reproduces the loop extractor rewind, monotonic SurfaceTexture/encoder PTS normalization, bounded loop diagnostics, transport stages, decoder/source counts and successful packet counters from the pinned upstream source. It was run against RootEncoder 2.7.0 after the patch was added.
- The sandbox does not include a JDK or Android SDK, so local Gradle build/tests—including the newly added JVM tests—are unavailable. Prior baseline code passed [Android CI run `37660772805`](https://github.com/primetushar325-rgb/Live-cdi/actions/runs/37660772805) at commit `18a1ae0`, but that run does not include the current loop/watchdog changes; the current change set still requires CI.
- Downloadable APK artifact: [MihadLive-APK-12](https://github.com/primetushar325-rgb/Live-cdi/actions/runs/37660772805/artifacts/11500861069).

## Required device / YouTube validation before production claims

Use an unlisted/private test broadcast and a test stream key. On each representative Android model:

1. Confirm 16:9→16:9, 16:9→9:16, 9:16→16:9 and rotation-metadata variants in YouTube Live Control Room.
2. Verify Fit, Fill, Zoom, pan and Reset in both preview and received YouTube frames. Check that the source is not stretched and the encoded canvas matches the chosen ratio.
3. Confirm source audio, mono/stereo content, audio/video sync, loop continuity and finite-source end behavior. For a loop test, capture `MihadLoopPts` and `VideoEncoder` logs around at least three loop boundaries; compare raw `SurfaceTexture` timestamps, normalized GL inputs, encoded PTS/keyframe flags, and last successful RTMP keyframe age. Confirm timestamps remain strictly increasing and keyframes resume after the loop.
4. Confirm available H.264/AAC encoders and device thermal limits at each selected resolution/FPS/bitrate. Where possible, induce a hardware H.264 prepare failure and confirm there is only one software/first-compatible retry and the fallback label is visible in the dashboard.
5. Start an unlisted/private stream at the 720p30/3 Mbps default. Verify H.264/AAC, constant frame pacing and keyframe intervals no longer than two seconds; check the received preview and broadcast state in YouTube Studio → Live → Stream health (not just local RTMP counters). Keep it stable for at least 10 minutes, lock the screen and confirm the foreground notification remains actionable.
6. Disconnect and restore Wi-Fi/cellular. Confirm one continuous session and timer, no duplicate publisher, and recovery without destroying the encoder; verify the recovered video/audio in Control Room.
7. Run longer under realistic power/thermal conditions. A green app status, encoder activity or rising local write counters alone are not success.
8. Test invalid server URL, invalid key, unsupported codec/media, notification denial, explicit stop and a fresh restart.

## Current result / limitations

- Static source evidence does **not** support the original “container PTS resets at loop” theory: `BaseDecoder` queues timestamps from elapsed time plus extractor pacing. It does show that loop-mode EOS previously signaled a loop without rewinding the extractor. The code now queues the last sample and seeks to zero without resetting that elapsed timeline. This is a confirmed source-level defect/fix, but the reported ~46-second phone failure has not been reproduced and the selected clip duration/logcat were not available, so the relationship remains unconfirmed.
- Around each active source-loop callback, temporary bounded diagnostics record raw `SurfaceTexture` time, normalized stream/record input time, encoded PTS, keyframe flags and local loop time. They do not record endpoint/key values. The app now requests a keyframe on the loop callback and via the watchdog after four seconds without a successful keyframe write (at most every two seconds); only the keyframe-specific failure waits 20 seconds. Other publish, source, packet, encoder and transport watchdogs remain separate.
- The current code distinguishes RTMP handshake, server connect acceptance, publish request/acceptance and successful media flow. Per-track packet counts, RootEncoder RTMP message-length totals after successful local flush (not TCP/TLS wire-byte totals), keyframes, last packet/keyframe ages and codec-config evidence are displayed. These are local RTMP write measurements only. Hardware H.264 prepare failure gets one visible software/first-compatible fallback attempt; iFrameInterval remains two seconds.
- The app has no authenticated YouTube receipt or broadcast-LIVE signal. `YOUTUBE_INGEST_DETECTED` and `LIVE` remain unreachable. Confirm YouTube Studio → Live → Stream health and the received preview; do not treat local writes as confirmation.
- **This current change set has not yet passed CI and has not been tested on a physical device or in YouTube Studio.** The JVM tests were added but could not be run locally because the sandbox lacks a JDK/Android SDK. No 46-second reproduction, three-loop timestamp capture, 10-minute stability, screen-lock, network-loss or reconnect test is claimed.
