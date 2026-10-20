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

/** A human size: `171 MB`, `6 KB`, or `0 B` for an empty file. */
fun modelSizeLabel(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }

/** Whether the section should offer a download (anything that is not already valid). */
fun needsDownload(status: ArtifactStatus): Boolean = status != ArtifactStatus.READY
