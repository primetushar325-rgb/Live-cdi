# Mihad Live test report

## Automated checks included

The app module contains JVM tests for:

- RTMP/RTMPS validation and exact preservation of scheme, host, explicit port, encoded application path and the separate stream key; whitespace is rejected rather than silently modified.
- Encoded-buffer copies preserve a ByteBuffer view's logical range without moving its position; MediaCodec slicing uses `BufferInfo.offset + size`, preventing truncated or over-read codec payloads when offsets are non-zero.
- Pipeline readiness evidence: handshake/publish failures, accepted publish with no successful media writes, audio-required readiness and the required video codec-config/keyframe/media evidence.
- Legal state transitions through handshake, publish, media flow, ingest readiness and reconnect. `LIVE` cannot be entered from an unverified state.
- Landscape/portrait composition, Fit/Fill, zoom and pan viewport math.

GitHub Actions runs `:app:testDebugUnitTest`, then assembles debug and release APKs. Lint is also attempted but is non-blocking. The workflow uploads APKs as a downloadable artifact; release tags also publish a GitHub Release.

## Sandbox validation

- The repository contains a Gradle wrapper pinned to Gradle 8.14.3, Android Gradle Plugin 8.13.2, Kotlin 2.2.21 and JDK 17 in CI.
- RootEncoder 2.7.0 source and its license are vendored. The app uses the real Android MediaCodec → GL → FLV/RTMP/RTMPS pipeline; sender packet counters advance only after the packet write/flush returns successfully.
- `tools/vendor-rootencoder.py` reproduces the transport stages, decoder/source counts and successful packet counters from the pinned upstream source; it was run against RootEncoder 2.7.0 during this change.
- The sandbox does not include a JDK or Android SDK, so local Gradle build/tests are unavailable. The most recent completed baseline CI run before this diagnostic change set was [run #10](https://github.com/primetushar325-rgb/Live-cdi/actions/runs/37638492892), which passed unit tests, debug/release assembly, lint and artifact upload at `e810758`.
- **This diagnostic change set still requires its own GitHub Actions run.** The baseline APK/run above does not include these edits.

## Required device / YouTube validation before production claims

Use an unlisted/private test broadcast and a test stream key. On each representative Android model:

1. Confirm 16:9→16:9, 16:9→9:16, 9:16→16:9 and rotation-metadata variants in YouTube Live Control Room.
2. Verify Fit, Fill, Zoom, pan and Reset in both preview and received YouTube frames. Check that the source is not stretched and the encoded canvas matches the chosen ratio.
3. Confirm source audio, mono/stereo content, audio/video sync, loop continuity and end-of-file behavior.
4. Confirm available H.264/AAC hardware encoders and device thermal limits at each selected resolution/FPS/bitrate.
5. Start a stream and verify both video and expected audio in Control Room. Keep it stable for at least 10 minutes, lock the screen and confirm the foreground notification remains actionable.
6. Disconnect and restore Wi-Fi/cellular. Confirm one continuous session and timer, no duplicate publisher, and recovery without destroying the encoder; verify the recovered video/audio in Control Room.
7. Run longer under realistic power/thermal conditions. A green app status, encoder activity or rising local write counters alone are not success.
8. Test invalid server URL, invalid key, unsupported codec/media, notification denial, explicit stop and a fresh restart.

## Current result / limitations

- The current code adds source/encoder/transport/packet diagnostics and refuses to call `INGEST CONNECTED` without publish acceptance plus successful required FLV media writes.
- `INGEST CONNECTED` is local RTMP evidence only. The app has no authenticated YouTube receipt or broadcast-LIVE signal and never transitions to `LIVE` in this build.
- **The current change set has not yet passed CI and has not been tested on a physical device or in YouTube Control Room.** No 10-minute stability, screen-lock, network-loss or reconnect test is claimed.
