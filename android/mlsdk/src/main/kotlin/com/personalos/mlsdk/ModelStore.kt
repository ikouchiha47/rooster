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

    /**
     * The file [spec] is in right now — the writable dir first, then the read-only
     * fallbacks ([ModelPaths.lookup]) — or null when it is nowhere.
     */
    suspend fun find(spec: ModelSpec): File? = withContext(Dispatchers.IO) { ModelPaths.resolve(context, spec.fileName) }

    /** The current state of [spec] without fetching anything. */
    suspend fun status(spec: ModelSpec): ModelStatus =
        withContext(Dispatchers.IO) {
            val file = find(spec) ?: return@withContext ModelStatus.ABSENT
            val size = file.length()
            // Only hash when the size already matches — a mismatch is PARTIAL and
            // the hash would be wasted on a 171 MB read.
            val sha = if (size == spec.sizeBytes) Integrity.sha256(file) else ""
            modelStatus(PresentFile(size, sha), spec)
        }

    /**
     * The file for [spec], ready to load: returned as-is when already valid — from
     * **any** mapped directory, so a custom model left in the shared folder is used
     * rather than re-fetched — else downloaded (and verified) first. [progress] is
     * `(written, total)`.
     */
    suspend fun ensure(
        spec: ModelSpec,
        progress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        find(spec)?.let { existing -> if (status(spec).usable) return existing }
        return downloader.download(spec, dir(), progress)
    }

    /**
     * Reclaims space the SDK owns, and returns the file names it removed.
     *
     * In the **private** directory this deletes every file [keep] does not name:
     * a `ModelSpec` is only a definition, so removing it from the code removes
     * nothing from disk, and a runtime swap would otherwise leave the old weights
     * behind. In the **shared** directory only abandoned `.part` files go — a
     * complete file there may be a custom model the user put there deliberately,
     * and `keep` cannot know its name. Idempotent, so it is safe on every launch.
     */
    suspend fun evictUnlisted(keep: Collection<String>): List<String> =
        withContext(Dispatchers.IO) {
            val dir = ModelPaths.dir(context)
            // A shared folder belongs to the user: a **custom model** dropped there
            // is exactly the kind of file `keep` cannot name, so never delete a
            // complete file there. Only an abandoned `.part` is ours to remove.
            val candidates =
                if (dir == ModelPaths.privateRoot(context)) {
                    ModelFiles.orphans(dir, keep)
                } else {
                    (dir.listFiles() ?: emptyArray())
                        .filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
                        .sortedBy { it.name }
                }
            candidates.mapNotNull { file -> file.name.takeIf { file.delete() } }
        }

    private companion object {
        /** The suffix [HttpDownloader] streams into before the file is published. */
        const val PART_SUFFIX = ".part"
    }
}
