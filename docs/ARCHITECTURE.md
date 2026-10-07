# Mihad Live streaming architecture

## Pipeline

```text
Android document picker / persisted content URI
   ↓
RootEncoder VideoFileSource + Android MediaExtractor/MediaCodec decoder
   ├── source audio track → AudioFileSource → Android AAC MediaCodec encoder
   └── no source audio → NoAudioSource (no synthetic audio is generated)
   ↓
RootEncoder GL compositor (input SurfaceTexture + OpenGL FBO)
   ├── black output canvas at fixed selected dimensions
   ├── custom viewport for Fit / Fill / Zoom / Position / Reset
   ├── Activity TextureView preview
   └── H.264 MediaCodec input surface
   ↓
RootEncoder H.264/AAC encoder callbacks
   ↓
RootEncoder RTMP client + FLV packetization/muxing
   ↓
user-entered RTMP or RTMPS server URL + stream key
   ↓
YouTube ingest (verification not currently available in-app)
```

The upstream transport is not implemented by UI code. `RtmpStream` owns one RootEncoder `RtmpClient`; the app's service uses the same object for preview, publishing and reconnect. The user-entered key is appended in memory only at `startLive()`. URL/key values are not written to project/history data, Android intents, or diagnostics.

The project vendors the pinned RootEncoder 2.7.0 modules `common`, `encoder`, `rtmp` and the RTMP library facade in `vendor/re-*`. RTSP/SRT/UDP/WHIP facades are excluded. Patches and re-vendoring instructions are in `vendor/README.md` and `tools/vendor-rootencoder.py`.

## Exact output canvas / preview

The format and selected quality determine the encoder canvas before the video encoder is prepared:

| Format | 720p | 1080p |
|---|---:|---:|
| Landscape | 1280×720 | 1920×1080 |
| Vertical | 720×1280 | 1080×1920 |

The source decoder's actual video-track dimensions are fed to the compositor input surface. The compositor's output canvas remains the selected encoder size. The source is not resized to the output surface by configuring the decoder at the output size.

`CompositionViewport` computes a source rectangle without changing its aspect ratio. Fit uses the smaller canvas/source scale; Fill uses the larger scale and crops off-canvas pixels. Zoom multiplies that scale and pan is bounded to the remaining blank/cropped range. The resulting normalized composition is converted to two GL viewports: one for the encoded output surface and one for the on-screen preview surface. Both viewports are consumed by RootEncoder's GL `ScreenRender`; the preview is not an independent player or a placeholder animation.

`VideoFileSource` exposes its real decoded-track size so the GL FBO can be created at source dimensions. Orientation metadata is passed to the RootEncoder output rotation. Metadata rotation variants still need device validation; the required landscape/portrait aspect-ratio paths have JVM viewport tests but no physical-device render test in this environment.

## Session ownership and state

`StreamService` is the process-level session manager and sole owner of `RtmpStream`, its decoder, GL pipeline, encoders and RTMP client. Activities only send preview/start/stop/composition requests and observe a `StateFlow<SessionSnapshot>`. A command mutex serializes session preparation/start/stop; duplicate start requests are rejected.

Internal states are `IDLE`, `PREPARING`, `ENCODER_READY`, `RTMP_CONNECTING`, `RTMP_CONNECTED`, `RTMP_PUBLISHING`, `INGEST_VERIFYING`, `LIVE_VERIFIED`, `RECONNECTING`, `STOPPING`, `STOPPED` and `ERROR`. RootEncoder exposes a publish-start callback, but does not expose YouTube's ingest-health or broadcast-LIVE event. Therefore the application can transition to `RTMP_PUBLISHING`, but never transitions to `LIVE_VERIFIED` in this build. The user-visible status is **STREAMING TO RTMP**, with ingest **NOT VERIFIED**.

The continuous-session clock uses `SystemClock.elapsedRealtime()`. It is initialized once at explicit start, survives retry callbacks, and stops only on explicit stop or a terminal stop. Reconnects use RootEncoder's `RtmpStreamClient.reTry()` / `RtmpClient.reConnect()` path on the existing client—no second `RtmpStream`, broadcast, decoder, encoder or timer is created for temporary network failure.

## Background lifetime

`StreamService` is a `mediaPlayback` foreground service while publishing. It posts an ongoing notification with a `STOP LIVE` service action, updates status/duration, and acquires a partial wake lock for the active session. Notification permission is requested on Android 13+. Android OEM battery policies, thermal shutdown, user force-stop, and low battery may still terminate a stream; the app cannot override those platform controls.

No camera or microphone permission is requested. Gallery access uses Android's system document picker. Network/state and wake-lock permissions are used by the RTMP client/foreground session.

## Transport, reconnect, adaptation

- RTMP and RTMPS are accepted; the app does not hardcode a server URL.
- RootEncoder logging is disabled for media packets. The vendored AMF logs are changed to static safe messages so stream names/keys are not printed.
- RTMP ping requests and read-failure callbacks are enabled. Socket timeout is 15 seconds.
- Recoverable transport failure uses capped exponential backoff and waits for network availability. Retries are capped. RootEncoder's internal RTMP reconnect keeps the video decoder/GL/encoder and session clock in place.
- Network adaptation is conservative and only used with Auto bitrate. If RTMP send queue congestion or actual dropped frames persist, the H.264 target bitrate is lowered by 15% at most once per interval, without changing resolution. If the queue remains clear and loss-free for 60 seconds, it is raised gradually toward the selected target. Resolution is not switched while live.
- If the network remains down past the retry wait limit or an auth/publish rejection occurs, the dashboard shows an actionable failure rather than LIVE.

## Metrics

- **Resolution/FPS target:** selected configuration used to prepare the hardware encoder.
- **Actual FPS:** RootEncoder `FpsListener` measurement when available.
- **Encoded video/audio bitrate:** calculated from byte counts in the actual MediaCodec output callbacks. Audio is `N/A` if the source has no audio track or no AAC output is present.
- **Network upload:** RootEncoder sender's measured bits written per second.
- **Dropped frames/send queue/sent bytes:** RootEncoder RTMP sender counters.
- **Encoded frames:** counted at the real RTMP-stream encoder callback.
- Unavailable metrics display `N/A`; no viewer count is inferred from RTMP.

## Security boundaries

- The key is separated from the server URL in the UI and validated before building the endpoint.
- RTMP/RTMPS endpoint values are never logged by Mihad code. RootEncoder AMF payloads that include the stream name are not logged.
- A key is memory-only unless the user checks Remember; then AndroidX EncryptedSharedPreferences uses Android Keystore-backed encryption.
- Only the selected media URI and non-secret project metadata are retained.
- RTMP is unencrypted. Use RTMPS when supported by the server.

## Verification boundary

An RTMP socket connection, bytes sent, encoded frames, and `NetStream.Publish.Start` are not proof YouTube is live. A trustworthy verification would require an official, authenticated YouTube Live Streaming API flow with the user's consent and a correctly associated broadcast/stream resource. That API integration is not included. Mihad Live therefore labels this state STREAMING TO RTMP and links to YouTube Studio for manual verification. Viewer count is omitted rather than fabricated.
