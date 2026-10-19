package com.personalos.mlsdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams a model into [destDir], with three properties that matter for a file
 * this size on a phone:
 *
 *  - **It resumes.** Bytes go to `<name>.part`; a retry sends `Range:` from the
 *    partial length, so a killed 171 MB download continues instead of restarting.
 *  - **It verifies before it publishes.** Size and SHA-256 are checked on the
 *    `.part` file; only then is it renamed into place, so a truncated or wrong
 *    file never becomes "the model".
 *  - **A reject starts clean.** A validation failure deletes the `.part`, so the
 *    next attempt is a fresh download rather than a wedged resume.
 */
class HttpDownloader(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 60_000,
) {
    /**
     * Downloads [spec] into [destDir] and returns the verified file. [progress]
     * receives `(written, total)`; total is the spec's size, so a UI can show a
     * percentage without a HEAD request.
     */
    suspend fun download(
        spec: ModelSpec,
        destDir: File,
        progress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): File =
        // Blocking network + disk IO, so it runs on IO regardless of the caller's
        // dispatcher — a UI coroutine must be able to call this safely.
        withContext(Dispatchers.IO) {
            destDir.mkdirs()
            val finalFile = File(destDir, spec.fileName)
            if (finalFile.isFile && Integrity.matches(finalFile.length(), Integrity.sha256(finalFile), spec)) {
                return@withContext finalFile
            }

            val part = File(destDir, spec.fileName + ".part")
            var offset = if (part.isFile) part.length() else 0L

            val connection =
                (URL(spec.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
                    connect()
                }

            // 416 = the range is past the end (a stale, too-long .part): restart.
            if (connection.responseCode == 416) {
                connection.disconnect()
                part.delete()
                return@withContext download(spec, destDir, progress)
            }

            // Anything but 200/206 is a failed fetch — said plainly, because a
            // 404 would otherwise surface as an IOException from `inputStream`
            // ("No content provider"), which reads like a device fault.
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect()
                throw IllegalStateException("HTTP $code fetching ${spec.fileName}")
            }
            val contentType = connection.contentType

            val resuming = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (!resuming) {
                offset = 0L
                part.delete()
            }

            connection.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(offset)
                    val buffer = ByteArray(1 shl 16)
                    var written = offset
                    progress(written, spec.sizeBytes)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        progress(written, spec.sizeBytes)
                    }
                }
            }
            connection.disconnect()

            // Cheap content check before the expensive hash: a web page or a
            // truncated runtime file is rejected on its first bytes. Not
            // resume-worthy, so the `.part` goes and the next attempt is clean.
            ModelValidation.problem(part, contentType)?.let { reason ->
                part.delete()
                throw IllegalStateException("download rejected: $reason (${spec.fileName})")
            }

            val sha = Integrity.sha256(part)
            if (!Integrity.matches(part.length(), sha, spec)) {
                part.delete()
                throw IllegalStateException("download failed integrity: ${spec.fileName}")
            }
            if (finalFile.exists() && !finalFile.delete()) throw IllegalStateException("could not replace ${spec.fileName}")
            if (!part.renameTo(finalFile)) throw IllegalStateException("could not place ${spec.fileName}")
            return@withContext finalFile
        }
}
