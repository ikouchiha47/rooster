package com.personalos.app.ui.settings

import com.personalos.mlsdk.ModelStatus

// The Models section's decisions, kept out of the composable so they can be
// tested without rendering — the same split as `MessageTab` and `WatchersModel`.

/** A model's state, in the words the row shows. */
fun modelStatusLabel(status: ModelStatus): String =
    when (status) {
        ModelStatus.READY -> "installed"
        ModelStatus.ABSENT -> "not installed"
        ModelStatus.PARTIAL -> "incomplete"
        ModelStatus.STALE -> "outdated"
    }

/** A model's size in megabytes, e.g. `171 MB`. */
fun modelSizeLabel(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

/** Whether the section should offer a download (anything that is not already valid). */
fun needsDownload(status: ModelStatus): Boolean = status != ModelStatus.READY
