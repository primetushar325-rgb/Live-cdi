# Test matrix (device follow-up)

| Case | Automated in this repository | Device / YouTube required | Expected handling |
|---|---:|---:|---|
| 16:9 source → 16:9 output | Viewport math only | Yes | Fit/Fill in a 16:9 H.264 canvas |
| 16:9 source → 9:16 output | Viewport math only | Yes | Preserve source ratio; black side/letterbox canvas for Fit |
| 9:16 source → 16:9 output | Viewport math only | Yes | Preserve ratio; black pillarbox canvas for Fit |
| Zoom / pan / reset | Viewport math only | Yes | Preview and encoder receive same normalized GL viewport |
| Actual RTMP H.264/AAC publication | No | Yes | One RootEncoder RTMP session, server publish callback only |
| Screen off / app minimized | No | Yes | Foreground service + notification + partial wake lock |
| Wi-Fi lost/restored | No | Yes | Existing RTMP client reconnect; timer remains continuous |
| No-audio video | Source selection in code | Yes | Video-only RTMP; no synthetic silence |
| Stream key invalid | URL/state code | Yes | Useful rejection message; never display LIVE |
| Unsupported MediaCodec setup | Capability preflight code | Yes | Reject before publish; suggest lower quality/FPS |
| Long-running/thermal behavior | No | Yes | Multi-hour run on representative OEM devices |
| YouTube ingest / LIVE status | No | Yes + official API | Current app intentionally reports STREAMING TO RTMP / NOT VERIFIED |
