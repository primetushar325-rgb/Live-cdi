package com.pedro.library.base.recording

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
