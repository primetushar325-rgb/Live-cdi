# Mihad Live test report

## Automated checks included

The app module contains JVM tests for:

- RTMP/RTMPS server/key separation, URL scheme/host/application-path validation, and malformed keys.
- Landscape source into vertical canvas, vertical source into landscape canvas, Fit, Fill, zoom and pan viewport math.
- Legal state transitions through RTMP publishing/reconnect and a guard against claiming `LIVE_VERIFIED` directly from `IDLE`.

GitHub Actions runs `:app:testDebugUnitTest`, then assembles debug and release APKs. Lint is also attempted but is non-blocking. The workflow uploads APKs as a downloadable artifact; release tags also publish a GitHub Release.

## Sandbox validation

- Repository contains a Gradle wrapper pinned to Gradle 8.14.3, Android Gradle Plugin 8.13.2, Kotlin 2.2.21 and JDK 17 in CI.
- RootEncoder 2.7.0 source and its license are vendored; the RTMP facade and real H.264/AAC MediaCodec pipeline are used.
- Python re-vendoring script compiles with `python3 -m py_compile` and can reproduce the pinned source trim/patches.
- APK build/test could not be executed in the source-editing sandbox because no Java/Android SDK is installed there. GitHub Actions is the build environment; check the latest workflow run for actual build results.
- No actual Android device or YouTube Live Control Room credentials were available. No physical device, screen-off, network-disconnect, thermal, audio-sync or long-running YouTube-ingest test is claimed.

## Required device / YouTube validation before production claims

Use an unlisted/private test broadcast and a test stream key. On each representative Android model:

1. Confirm 16:9→16:9, 16:9→9:16, 9:16→16:9 and rotation-metadata variants in YouTube Live Control Room.
2. Verify Fit, Fill, Zoom, pan and Reset in both the on-device preview and received YouTube frames. Check that the source is not stretched and the encoded canvas matches the chosen ratio.
3. Confirm source audio, mono/stereo content, audio/video sync, loop continuity and end-of-file behavior.
4. Confirm available H.264/AAC hardware encoders and device thermal limits at each selected resolution/FPS/bitrate.
5. Start a stream, lock the screen, minimize the app, and confirm the foreground notification remains actionable.
6. Toggle Wi-Fi/cellular and disconnect/reconnect the internet. Confirm a single RTMP publisher, one continuous timer, no duplicate broadcast/session, and recovery without destroying the encoder on ordinary reconnects.
7. Run for several hours under realistic power/thermal conditions. Inspect YouTube Studio throughout; a green app state or rising sent bytes alone is not success.
8. Test invalid server URL, invalid key, unsupported codec/media, notification denial, explicit stop and a fresh restart.

## Current result / limitations

- The implementation is source-complete for the Android app, service, UI, RTMP engine, build workflow, JVM test suite, architecture and setup documentation.
- **Build status is pending CI** until a successful Actions run produces the APK artifact.
- **YouTube success is unverified.** This build must not be described as production-proven or as showing a verified YouTube LIVE state until the device/Control Room test plan passes and official ingest verification is implemented.
