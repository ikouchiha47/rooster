package com.personalos.mlsdk

import java.io.File

/**
 * Which files in a models directory no longer belong there.
 *
 * Pure and filesystem-only, so it is unit-testable without a Context: hand it a
 * directory and the set of names the current bundle needs, and it returns the
 * regular files that are neither — the weights a dropped [ArtifactFile] left behind
 * (swapping one runtime for another is exactly this), or an abandoned `.part`.
 *
 * **Keep is by file name**, because that is the identity [ArtifactFile] and ONNX
 * Runtime's external-data resolver both use.
 */
object Files {
    /** The regular files in [dir] that [keep] does not name, sorted by name. */
    fun orphans(
        dir: File,
        keep: Collection<String>,
    ): List<File> {
        val kept = keep.toSet()
        val entries = dir.listFiles() ?: return emptyList()
        return entries
            .filter { it.isFile && it.name !in kept }
            .sortedBy { it.name }
    }
}
