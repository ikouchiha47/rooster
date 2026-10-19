package com.personalos.app.ui.settings

import com.personalos.mlsdk.ArtifactStatus

// The Models section's decisions, kept out of the composable so they can be
// tested without rendering — the same split as `MessageTab` and `WatchersModel`.

/** An artifact's state, in the words the row shows. */
fun modelStatusLabel(status: ArtifactStatus): String =
    when (status) {
        ArtifactStatus.READY -> "installed"
        ArtifactStatus.ABSENT -> "not installed"
        ArtifactStatus.PARTIAL -> "incomplete"
        ArtifactStatus.STALE -> "outdated"
    }

/** An artifact's size in megabytes, e.g. `171 MB`. */
fun modelSizeLabel(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

/** Whether the section should offer a download (anything that is not already valid). */
fun needsDownload(status: ArtifactStatus): Boolean = status != ArtifactStatus.READY
