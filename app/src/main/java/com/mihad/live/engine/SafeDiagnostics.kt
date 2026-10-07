package com.mihad.live.engine

import android.util.Log

/** Diagnostic events contain only enums/counters; never pass RTMP URLs or credentials here. */
object SafeDiagnostics {
    private const val TAG = "MihadLiveEngine"

    fun transition(from: StreamState, to: StreamState) {
        Log.i(TAG, "STATE_TRANSITION ${from.name} -> ${to.name}")
    }

    fun event(name: String) {
        // Callers pass fixed internal event names only.
        Log.i(TAG, name.take(72))
    }

    fun failure(code: String) {
        // `code` is selected from a fixed allow-list by StreamService.
        Log.w(TAG, "ENGINE_FAILURE_${code.take(48)}")
    }
}
