# Test matrix (device follow-up)

| Case | Automated in this repository | Device / YouTube required | Expected handling |
|---|---:|---:|---|
| Exact RTMP/RTMPS scheme, host, explicit port and raw path preservation | Yes (JVM endpoint tests) | Yes | Reject whitespace; preserve valid entered components and append the exact separate key without logging it |
| MediaCodec / sliced ByteBuffer range handling | Yes (JVM unit tests) | Yes for device codecs | Copy the buffer's logical view and exactly `BufferInfo.offset..offset + size`; reject invalid ranges without mutating the shared encoder buffer |
| RTMP media-flow evidence reducer | Yes (JVM unit tests) | Yes for actual transport | Require accepted publish plus successful H.264 config/raw video/keyframe writes and AAC config/raw audio when audio is expected; this is not YouTube receipt evidence |
| Handshake failure vs publish failure vs accepted publish with no media | Yes (JVM state/evidence tests) | Yes | Separate RTMP handshake/connect/publish results; remain `PUBLISHING` while media is missing, then stop with the measured first failing pipeline stage |
| 16:9 source → 16:9 output | Viewport math only | Yes | Fit/Fill in a 16:9 H.264 canvas |
| 16:9 source → 9:16 output | Viewport math only | Yes | Preserve source ratio; black side/letterbox canvas for Fit |
| 9:16 source → 16:9 output | Viewport math only | Yes | Preserve ratio; black pillarbox canvas for Fit |
| Zoom / pan / reset | Viewport math only | Yes | Preview and encoder receive same normalized GL viewport |
| Actual RTMP/RTMPS H.264/AAC transmission and Control Room receipt | No | Yes | Confirm actual video and audio in YouTube Control Room; local packet-write counters alone are not success |
| Ten-minute stability, screen lock and network-loss reconnect | No | Yes | One continuous session, no false LIVE state, reconnect without duplicate publisher; verify in Control Room |
| Screen off / app minimized | No | Yes | Foreground service + notification + partial wake lock |
| Wi-Fi lost/restored | No | Yes | Existing RTMP client reconnect; timer remains continuous |
| No-audio video | Source selection in code | Yes | Video-only RTMP; no synthetic silence; ingest criteria do not require AAC |
| Stream key invalid | URL/state code | Yes | Useful rejection; key value never appears in logs or diagnostics; never display LIVE |
| Unsupported MediaCodec setup | Capability preflight code | Yes | Reject before publish; suggest lower quality/FPS |
| Long-running/thermal behavior | No | Yes | Multi-hour run on representative OEM devices |
| YouTube ingest / LIVE status | No | Yes; Control Room or official authenticated API | `YOUTUBE_INGEST_DETECTED` and `LIVE` have no incoming state transition. Local RTMP publish/packet writes are clearly marked as unverified; actual receipt and broadcast status require Control Room evidence. |
