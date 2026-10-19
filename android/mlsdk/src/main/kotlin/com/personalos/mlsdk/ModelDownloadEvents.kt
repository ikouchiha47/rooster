package com.personalos.mlsdk

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Progress from [ModelDownloadService] to whoever is watching — the Settings
 * screen, an in-app toast host, a log.
 *
 * In-process on purpose (a `SharedFlow` in a singleton): the service runs in the
 * same process as the app, so no Binder, no broadcast, no parcel. An app that is
 * killed simply stops watching; the service keeps downloading.
 *
 * Every emission is best-effort (`tryEmit`, drop-oldest): progress is a stream of
 * *latest values*, and a slow collector must never stall the download.
 */
object ModelDownloadEvents {
    /**
     * One tick. [done] marks the end of the file — with [error] null on success
     * and a reason on failure. `total` is the spec's size (0 when unknown).
     */
    data class Update(
        val fileName: String,
        val written: Long,
        val total: Long,
        val done: Boolean = false,
        val error: String? = null,
    )

    private val mutable =
        MutableSharedFlow<Update>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val updates: SharedFlow<Update> = mutable.asSharedFlow()

    internal fun emit(update: Update) {
        mutable.tryEmit(update)
    }
}
