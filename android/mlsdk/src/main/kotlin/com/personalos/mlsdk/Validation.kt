package com.personalos.mlsdk

import java.io.File

/**
 * Is the bytes we just wrote plausibly a model, or a web page telling us it is
 * not? Size and SHA-256 answer that *authoritatively* ([Integrity]), but only
 * after hashing the whole file — and a 176 MB read is a poor way to discover that
 * a CDN returned a login page.
 *
 * This is the cheap check that runs first, on the first bytes, and it is the one
 * thing [Integrity] cannot do: tell a **truncated** download apart from a **wrong**
 * one, and both from an **HTML** response. Every case here was observed as a real
 * failure, not imagined:
 *
 *  - GitHub releases returns `200 OK` with an HTML "file not found" page when the
 *    asset is missing — there is no 404, because the *page* exists.
 *  - A captive portal injects an HTML login page, and the download "succeeds".
 *  - A premature EOF leaves a `.tflite`/`.onnx` that passes `exists()` and then
 *    fails at load time with "does not encode a valid model".
 */
object Validation {
    /**
     * What is wrong with [file], or null when nothing is. [contentType] is the
     * HTTP `Content-Type` the bytes arrived with, when known — checked as well as
     * the first byte, because an HTML error page can begin with a BOM or whitespace
     * and still be mark-up.
     */
    fun problem(
        file: File,
        contentType: String? = null,
    ): String? {
        if (!file.isFile) return "no file"
        if (file.length() == 0L) return "empty file"

        val header = ByteArray(8)
        file.inputStream().use { it.read(header) }

        val firstMeaningful = header.firstOrNull { it.toInt() !in 0x09..0x0D && it.toInt() != 0x20 }
        // '<' as the first non-whitespace byte, or a declared HTML type: mark-up,
        // so not a model.
        if (firstMeaningful?.toInt() == 0x3C ||
            contentType?.lowercase()?.contains("text/html") == true
        ) {
            return "server returned a web page, not a model file"
        }

        when {
            file.name.endsWith(".tflite", ignoreCase = true) -> {
                // FlatBuffer file identifier at offset 4..7: "TFL3".
                val tfl3 =
                    header[4].toInt() == 0x54 &&
                        header[5].toInt() == 0x46 &&
                        header[6].toInt() == 0x4C &&
                        header[7].toInt() == 0x33
                if (file.length() <= 8 || !tfl3) return "not a valid TensorFlow Lite model"
            }

            file.name.endsWith(".onnx", ignoreCase = true) -> {
                // ONNX is protobuf; field 1 (ir_version) is the varint tag 0x08.
                if (header[0].toInt() != 0x08) return "not a valid ONNX model"
            }
        }
        // Tokenizers, vocabularies and JSON have no magic bytes; the empty and
        // mark-up checks above still apply, and Integrity decides the rest.
        return null
    }

    /** True when [file] carries no [problem]. */
    fun plausible(
        file: File,
        contentType: String? = null,
    ): Boolean = problem(file, contentType) == null
}
