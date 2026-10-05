package com.personalos.mlsdk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one entry point an app uses for a model file.
 *
 * **Idempotent.** [ensure] locates a valid copy, fetches it if absent, verifies
 * it and returns it — creating the directory if needed. Safe to call on every
 * start, from any app: the hub is only a UI over this, so an app works with or
 * without the hub installed, and two apps agree on one file because they agree
 * on its name and its sha ([ModelSpec]).
 *
 * Every method that touches disk runs on [Dispatchers.IO], so a caller can invoke
 * it from a UI coroutine without a `NetworkOnMainThreadException` or a janky read.
 */
class ModelStore(
    private val context: Context,
    private val downloader: HttpDownloader = HttpDownloader(),
) {
    /** The models directory, created if missing. */
    suspend fun dir(): File = withContext(Dispatchers.IO) { ModelPaths.dir(context).apply { mkdirs() } }

    /** The current state of [spec] without fetching anything. */
    suspend fun status(spec: ModelSpec): ModelStatus =
        withContext(Dispatchers.IO) {
            val file = File(ModelPaths.dir(context), spec.fileName)
            if (!file.isFile) return@withContext ModelStatus.ABSENT
            val size = file.length()
            // Only hash when the size already matches — a mismatch is PARTIAL and
            // the hash would be wasted on a 171 MB read.
            val sha = if (size == spec.sizeBytes) Integrity.sha256(file) else ""
            modelStatus(PresentFile(size, sha), spec)
        }

    /**
     * The file for [spec], ready to load: returned as-is when already valid, else
     * downloaded (and verified) first. [progress] is `(written, total)`.
     */
    suspend fun ensure(
        spec: ModelSpec,
        progress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val dir = dir()
        val file = File(dir, spec.fileName)
        if (status(spec).usable) return file
        return downloader.download(spec, dir, progress)
    }

    /**
     * Deletes every file in the models directory that [keep] does not name, and
     * returns what it removed.
     *
     * Called with the current bundle's file names, this is what stops a dropped
     * model lingering for ever: a `ModelSpec` is only a definition, so removing it
     * from the code removes nothing from disk, and a runtime swap would otherwise
     * leave the old weights behind — a second copy of the same model, unused.
     * Idempotent, so it is safe on every launch.
     */
    suspend fun evictUnlisted(keep: Collection<String>): List<String> =
        withContext(Dispatchers.IO) {
            ModelFiles
                .orphans(ModelPaths.dir(context), keep)
                .mapNotNull { file -> file.name.takeIf { file.delete() } }
        }
}
