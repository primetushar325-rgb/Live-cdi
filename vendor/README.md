# Vendored streaming core

Mihad Live vendors the RTMP-capable modules from **RootEncoder 2.7.0** by pedroSG94 under the Apache License 2.0.

- Upstream: <https://github.com/pedroSG94/RootEncoder>
- Tag: `2.7.0`
- Commit: `5e3938fae6a7697c26ab7e21c9b7ec02f1f8ebbe`
- License: [`LICENSE.RootEncoder.txt`](LICENSE.RootEncoder.txt)
- Reproducible trim/patch script: [`../tools/vendor-rootencoder.py`](../tools/vendor-rootencoder.py)

Included modules are `common`, `encoder`, `rtmp`, and the RTMP library facade. RTSP, SRT, UDP and WHIP facade modules are intentionally omitted. RootEncoder's RTMP transport, FLV muxing, MediaCodec encoding, source decoding and GL compositor remain the real upstream implementation, not a simulator.

Mihad Live patches:

1. Track source video dimensions independently of the output canvas; output letterbox/crop transforms use the same per-frame GL compositor as the encoded surface.
2. Expose output-buffer byte/frame counters from the real MediaCodec callback for dashboard metrics.
3. Remove sensitive AMF payload/server-error logs that could include the RTMP stream name/key.
4. Ensure the file decoder/extractor is released when a preview was not attached.
5. Remove Kotlin 2.2-only `when` subject syntax where needed by the pinned compiler.

Do not upgrade this directory by copying arbitrary upstream files. Use the pinned tag and the script, then build/test on CI. All modifications to upstream source remain Apache-2.0 licensed.
