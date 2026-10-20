package com.personalos.mlsdk

import java.io.File

/**
 * "This file was checked against its size and SHA-256" — remembered beside it, so
 * answering *is this the artifact?* is a `stat`, not a re-read of 167 MB.
 *
 * Verification is cheap once and expensive forever otherwise: hashing the weights
 * on every tagger start costs tens of seconds on a phone, and the answer cannot
 * change unless the file does. The marker records **the spec's** sha and size, so a
 * replaced file (a new version, a corrupted copy of the same length) fails to match
 * and pays for one fresh hash — it never hides a change, only avoids repeating a
 * check that already happened.
 *
 * An internal detail: it is a cache of a fact, not a second owner of it.
 */
internal object Verified {
    const val SUFFIX = ".verified"

    fun marker(file: File): File = File(file.parentFile, file.name + SUFFIX)

    /** The value a marker must contain for [spec] — lower-case sha, then the size. */
    private fun expected(spec: ArtifactFile): String = "${spec.sha256.lowercase()}:${spec.sizeBytes}"

    fun matches(
        file: File,
        spec: ArtifactFile,
    ): Boolean = runCatching { marker(file).readText() }.getOrNull() == expected(spec)

    fun write(
        file: File,
        spec: ArtifactFile,
    ) {
        runCatching { marker(file).writeText(expected(spec)) }
    }
}
