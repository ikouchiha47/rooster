package com.personalos.mlsdk

/** What is on disk for a spec right now, read cheaply (a `stat` and, only if the size matches, a hash). */
data class PresentFile(
    val sizeBytes: Long,
    val sha256: String,
)

enum class ModelStatus {
    /** No file. */
    ABSENT,

    /** A file exists but its size differs — a truncated/partial copy. */
    PARTIAL,

    /** Size matches but the hash differs — the wrong or a corrupted file. */
    STALE,

    /** Size and hash both match. Usable. */
    READY,
}

/**
 * The one decision `ModelStore.ensure` acts on, as a **pure function** so it can
 * be tested without files: is the present file the model, or must it be (re)fetched?
 *
 * Size is checked before the hash because it is free, and it catches the common
 * failure — a download that stopped early — without hashing a 171 MB file.
 */
fun modelStatus(
    present: PresentFile?,
    spec: ModelSpec,
): ModelStatus =
    when {
        present == null -> ModelStatus.ABSENT
        present.sizeBytes != spec.sizeBytes -> ModelStatus.PARTIAL
        !present.sha256.equals(spec.sha256, ignoreCase = true) -> ModelStatus.STALE
        else -> ModelStatus.READY
    }

/** True when [status] means the file is present and trustworthy. */
val ModelStatus.usable: Boolean get() = this == ModelStatus.READY
