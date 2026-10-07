# MIHAD LIVE — Stream. Stay Live.

Mihad Live is an Android application for streaming a selected local video to a user-supplied RTMP/RTMPS destination. The project contains a real Android MediaCodec → OpenGL compositor → FLV/RTMP publishing pipeline based on the Apache-2.0 RootEncoder 2.7.0 implementation.

> **Important honest status:** An RTMP server accepting `NetStream.Publish.Start` confirms RTMP publishing only. It does **not** prove that YouTube has decoded the video, accepted the stream as healthy, or switched the broadcast to LIVE. This app does not currently implement an authenticated YouTube Live Streaming API verifier, so its dashboard intentionally says **STREAMING TO RTMP** and **NOT VERIFIED**—never LIVE. Check the actual YouTube Live Control Room before telling viewers the stream is live.

## Get the APK on a phone

1. Open the repository's **Actions** tab on GitHub.
2. Choose **Android CI** and select **Run workflow** (or wait for a push build).
3. Open the completed **Build APK** run.
4. Download the **MihadLive-APK-…** artifact and install the debug APK. Android may ask you to allow installs from the browser/files app.

Every push runs the JVM tests and builds/uploads debug and release APKs. Manual runs do the same by default; workflow inputs can disable tests or the release build. A tag such as `v1.0.0` creates a GitHub Release with the APKs. Releases are signed with the supplied `MIL_*` keystore secrets when present; without those secrets, the release artifact is signed with the Android debug key and is for testing only.

## Build locally

Requirements: Android Studio (or Android SDK command-line tools), JDK 17, Android platform/build tools 36.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

APKs are written to `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`.

Optional release signing environment variables:

```text
MIL_KEYSTORE_FILE=/absolute/path/to/release.jks
MIL_KEYSTORE_PASSWORD=...
MIL_KEY_ALIAS=...
MIL_KEY_PASSWORD=...
```

Never commit a keystore or signing passwords.

## First stream

1. Tap **CREATE NEW LIVE** and choose a 16:9 landscape or 9:16 vertical output canvas.
2. Select a video through Android's document picker. The app asks only for access to the selected file.
3. Review the decoded preview. It is rendered through the same OpenGL compositor used by the encoder. Fit, Fill, Zoom, Position, and Reset change the compositor viewport; the source is not stretched.
4. Enter the stream name, the RTMP/RTMPS **server URL** and the stream key separately. Use the official values from your YouTube Live Control Room. Prefer RTMPS; plain RTMP does not encrypt the key in transit.
5. Select a supported output quality/FPS and start. The foreground-service notification has a **STOP LIVE** action and the session uses a partial wake lock while active.
6. Confirm the stream in YouTube Studio. Mihad Live will continue to display **STREAMING TO RTMP** until it has a trustworthy, official ingest-verification mechanism.

Stream keys are kept in memory for the current session. The optional **Remember securely on this device** checkbox stores the key with AndroidX EncryptedSharedPreferences/Android Keystore. Keys are never written to project/history records, included in Mihad diagnostics, or sent to any Mihad server. The only network destination for the key is the RTMP/RTMPS server URL the user entered.

## Architecture

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the pipeline, session states, compositor math, reconnect strategy, service lifetime, metrics, and verification boundary.

## Test report

See [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md). JVM tests cover endpoint validation, aspect-ratio composition, and state-machine transitions. Device/YouTube hardware tests require an Android phone, a valid private test stream key, network access, and the YouTube Live Control Room; they cannot be honestly claimed from a source-only sandbox.

## Known limitations

- YouTube ingest/Live status is not verified with an authenticated official API. The UI deliberately avoids a false LIVE claim.
- No YouTube viewer count is shown.
- Actual device codec support, thermal behavior, long-running stability, OEM background restrictions, and RTMP server compatibility must be tested on the target Android phone.
- Optional secure-key storage is device-local and depends on Android Keystore. If it cannot initialize, the key remains session-only.
- YouTube RTMP server URL/key are user supplied; this app does not create or manage YouTube broadcasts.
- A long-running stream keeps a partial CPU wake lock; expect increased battery and temperature. Keep the phone powered and ventilated.
