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

## Looping and presentation timestamps

Static source inspection found two relevant facts: `BaseDecoder` creates decoder-input PTS from elapsed time (`TimeUtils` elapsed since decoder start plus extractor pacing), not directly from a file PTS that restarts at zero; however, the old loop-mode EOS branch did not call `extractor.seekTo(0)`. Therefore the specific “container PTS reset at loop” hypothesis was not supported, while a decoder that reaches EOS without rewinding is a code defect consistent with a loop-boundary stall if the selected clip reaches EOS. The reported ~46-second device failure was not reproduced, so this is not yet a confirmed explanation of that field symptom.

Loop-mode video and audio decoders now copy/queue the final sample, seek their extractor to zero, and continue without resetting the elapsed-time decoder clock. `GlStreamInterface` normalizes each `SurfaceTexture` timestamp once and passes that same monotonic timestamp to stream and record surfaces; each `VideoEncoder` also enforces strictly increasing output PTS after its normal rebase. Audio decoding/encoding stays active across the extractor seek rather than restarting its clock. A source-loop callback requests a keyframe and enables bounded logs for the next 180 GL frames and encoded outputs (raw/normalized PTS, flags and keyframe indication only). The logs contain no endpoint, stream key or media content.

## Encoder timing and fallback

H.264 continues to use a two-second `iFrameInterval` and the stream remains frame-rate limited to the selected FPS. Auto bitrate is adaptive only under measured congestion; a bitrate change requests a keyframe. If a hardware H.264 encoder is selected but fails to prepare, the service makes one fallback attempt (software when it supports the required input surface, otherwise first-compatible) and surfaces the fallback type in the dashboard. The 720p Auto default is 3 Mbps (within the recommended 2.5–4 Mbps range); the 1080p Auto default is 4 Mbps. Device capability, real frame pacing and YouTube ingest quality still require device/Studio validation.

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

The transport lifecycle uses separate `CONNECTING`, `RTMP_HANDSHAKE`, `RTMP_CONNECTED`, `PUBLISHING` and `MEDIA_FLOWING` states, alongside preparation, reconnect, stop and error states. `PUBLISHING` is entered only after the server returns `NetStream.Publish.Start`; sending the publish command is not acceptance. `MEDIA_FLOWING` requires successful media packet writes after publish acceptance. H.264/AAC configuration packets, raw per-track packets, keyframes, per-track serialized message bytes and last-write ages are measured only after the corresponding RTMP packet write/flush succeeds. Startup watchdogs identify the first missing source/decoder, encoder, codec-configuration, keyframe, FLV/RTMP writer or audio stage. If encoded video has no successful keyframe write, the service requests one after about four seconds, rate-limits requests to two seconds, and fails the keyframe condition no earlier than 20 seconds without a keyframe and only after a request has actually been issued. Separate source-frame, encoded-frame, video/audio-packet, publish and transport watchdogs remain active; a static image is not itself treated as a stall. `YOUTUBE_INGEST_DETECTED` and `LIVE` are reserved states but have no incoming transition in this build because there is no authenticated YouTube receipt/status signal. Local RTMP writes are never presented as proof of YouTube receipt or broadcast status.

The continuous-session clock uses `SystemClock.elapsedRealtime()`. It is initialized once at explicit start, survives retry callbacks, and stops only on explicit stop or a terminal stop. Reconnects use RootEncoder's `RtmpStreamClient.reTry()` / `RtmpClient.reConnect()` path on the existing client—no second `RtmpStream`, broadcast, decoder, encoder or timer is created for temporary network failure.

## Background lifetime

`StreamService` is a `mediaPlayback` foreground service while publishing. It posts an ongoing notification with a `STOP LIVE` service action, updates status/duration, and acquires a partial wake lock for the active session. Notification permission is requested on Android 13+. Android OEM battery policies, thermal shutdown, user force-stop, and low battery may still terminate a stream; the app cannot override those platform controls.

No camera or microphone permission is requested. Gallery access uses Android's system document picker. Network/state and wake-lock permissions are used by the RTMP client/foreground session.

## Transport, reconnect, adaptation

- RTMP and RTMPS are accepted; the app does not hardcode a server URL. The entered scheme, host, explicit port and raw application path are passed unchanged into the publish endpoint; the separate key is appended as one unescaped path segment without trimming or normalization.
- RootEncoder logging is disabled for media packets. The vendored AMF logs are changed to static safe messages so stream names/keys are not printed. Transport callbacks emit only fixed stage names, never endpoint components or key text.
- RTMP ping requests and read-failure callbacks are enabled. Socket timeout is 15 seconds.
- Recoverable transport failure uses capped exponential backoff and waits for network availability. Retries are capped. RootEncoder's internal RTMP reconnect keeps the video decoder/GL/encoder and session clock in place.
- Network adaptation is conservative and only used with Auto bitrate. If RTMP send queue congestion or actual dropped frames persist, the H.264 target bitrate is lowered by 15% at most once per interval, without changing resolution. If the queue remains clear and loss-free for 60 seconds, it is raised gradually toward the selected target. Resolution is not switched while live.
- If the network remains down past the retry wait limit or an auth/publish rejection occurs, the dashboard shows an actionable failure rather than LIVE.

## Metrics

- **Resolution/FPS target:** selected configuration used to prepare the hardware encoder.
- **Actual FPS:** RootEncoder `FpsListener` measurement when available.
- **Encoded video/audio bitrate:** calculated from byte counts in the actual MediaCodec output callbacks. Audio is `N/A` if the source has no audio track or no AAC output is present.
- **Network upload:** RootEncoder sender's measured bits written per second, updated after successful packet flushes.
- **Dropped frames/send queue/FLV bytes:** queue counters plus bytes returned by successful FLV/RTMP packet writes.
- **Source decoded and H.264/AAC encoded frames:** counted at their actual decoder/source callbacks and RTMP-stream encoder callbacks. MediaCodec output slicing respects `BufferInfo.offset + BufferInfo.size`; ByteBuffer copies respect sliced-view offsets and do not mutate the source. Malformed ranges fail rather than sending a truncated or over-read buffer.
- **Video/audio packet counts, codec configs and keyframes:** classified from FLV packet type/header only after the corresponding RTMP write flush succeeds; codec config packets are not counted as raw media.
- The expandable dashboard diagnostic panel shows URL validity and key presence only (never the key value), handshake/connect/publish results, source/encoder readiness, per-track packet counts and RootEncoder RTMP message-length totals after successful local flush (not TCP/TLS wire-byte totals), keyframes, last packet/keyframe ages, pipeline failure stage and unverified YouTube receipt. Unavailable metrics display `N/A`; no viewer count is inferred from RTMP.

## Security boundaries

- The key is separated from the server URL in the UI and validated before building the endpoint. Whitespace is rejected instead of silently trimming or changing the host, scheme, port, path or key.
- RTMP/RTMPS endpoint values and key values are never logged by Mihad code. RootEncoder AMF payloads that include the stream name are not logged; diagnostics contain only fixed event names, validity/presence booleans and counters.
- A key is memory-only unless the user checks Remember; then AndroidX EncryptedSharedPreferences uses Android Keystore-backed encryption.
- Only the selected media URI and non-secret project metadata are retained.
- RTMP is unencrypted. Use RTMPS when supported by the server.

## Verification boundary

A successful RTMP socket, handshake, connect response, `NetStream.Publish.Start`, encoded frames or local socket writes cannot prove that YouTube Control Room received or processed the media. `PUBLISHING` and `MEDIA_FLOWING` report only RTMP-server acceptance and successful local packet writes. An authenticated YouTube Live Streaming API/Control Room integration is not included; therefore `YOUTUBE_INGEST_DETECTED` and `LIVE` are unreachable in this build, and the dashboard explicitly reports receipt/status as unverified. The user must confirm actual receipt and LIVE status in Control Room. Viewer count is omitted rather than fabricated.
