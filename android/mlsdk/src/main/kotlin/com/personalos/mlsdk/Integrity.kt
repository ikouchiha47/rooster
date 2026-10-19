package com.personalos.mlsdk

import java.io.File
import java.security.MessageDigest

/** Size + SHA-256: the two facts that decide whether a file is the model. */
object Integrity {
    /** Pure comparison, so "is this the model?" is testable without a file. */
    fun matches(
        sizeBytes: Long,
        sha256: String,
        spec: ArtifactFile,
    ): Boolean = sizeBytes == spec.sizeBytes && sha256.equals(spec.sha256, ignoreCase = true)

    /** Hex SHA-256 of a file, streamed so a 171 MB model never loads into memory. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
