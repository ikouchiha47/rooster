package com.personalos.app.core.sync

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared "something is syncing right now" state.
 *
 * No single screen can own this: the scheduled workers sync while no screen is
 * open, and several tiles trigger the same ingest. One holder in the container
 * means the indicator and the log line are the same everywhere, and every step
 * shows up in `adb logcat -s Sync`.
 */
class SyncStatus {
    private val _running = MutableStateFlow(false)

    /** True while a sync is in flight, so a tile can show a progress bar. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _step = MutableStateFlow("")
    val step: StateFlow<String> = _step.asStateFlow()

    /**
     * Runs [block] as the current sync, unless one is already running - a second
     * tap should not start a competing pass.
     */
    suspend fun run(
        label: String,
        block: suspend () -> Unit,
    ) {
        if (_running.value) {
            Log.i(TAG, "$label: already syncing, ignoring")
            return
        }
        _running.value = true
        _step.value = "SYNCING"
        Log.i(TAG, "$label: start")
        val startedAt = System.currentTimeMillis()
        try {
            block()
            Log.i(TAG, "$label: done in ${System.currentTimeMillis() - startedAt}ms")
        } catch (e: Exception) {
            Log.w(TAG, "$label: failed", e)
        } finally {
            _running.value = false
            _step.value = ""
        }
    }

    /** Records a step, so the bar and the log agree on what is happening. */
    fun step(text: String) {
        _step.value = text
        Log.i(TAG, text)
    }

    private companion object {
        const val TAG = "Sync"
    }
}
