package com.personalos.mlsdk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one entry point an app uses: **references in, files out**.
 *
 * ```kotlin
 * val hub = Hub(context, KnownModels.enabled)      // what this app declares
 * val dir = hub.ensure("embeddinggemma")           // complete directory, or throws
 * val graph = hub.files("embeddinggemma")[KnownModels.embeddingGemma.fileWithRole(FileRole.GRAPH)]
 * ```
 *
 * **Lookups never fetch** ([status], [files], [path], [plan]) — a screen can ask
 * what is on disk without spending data. Only [ensure] and [download] reach the
 * network.
 *
 * **A set is complete or it fails.** [ensure] returns a directory with every file
 * present and verified, or throws — it never hands back a partial artifact, which
 * is the failure mode that leaves a runtime loading a half-downloaded model.
 *
 * A reference is `name` (the shared [Scope.GLOBAL] artifact) or `scope:name` (the
 * copy one app retrained). Resolution is against what is **declared** here *and*
 * what is **registered** on the device, so an app finds another app's download.
 */
class Hub(
    private val context: Context,
    private val declared: Collection<Artifact> = emptyList(),
) {
    private val downloader = HttpDownloader()

    // ------------------------------------------------------------------ registry

    /** Everything on this device, plus what this app declares. */
    suspend fun list(): List<Artifact> = merged().artifacts

    /** A router over everything on this device, plus what this app declares. */
    suspend fun router(): ArtifactRouter = merged().router()

    /** The persisted registry alone — what another app would see. */
    suspend fun registry(): ArtifactRegistry =
        withContext(Dispatchers.IO) {
            ArtifactRegistry.of(
                Paths
                    .manifests(context)
                    .flatMap { file ->
                        runCatching { file.readText() }
                            .getOrNull()
                            ?.let(Manifest::parse)
                            ?.artifacts
                            .orEmpty()
                    },
            )
        }

    /**
     * Registers [artifacts] persistently, so other apps (and the hub) can find
     * them. Defaults to what this app declares.
     *
     * Written **per scope**, and only to the scopes the artifacts belong to: two
     * apps registering at once touch different files, so neither can lose the
     * other's update. `.tmp` + rename, so a reader never sees a half-written
     * registry.
     */
    suspend fun register(artifacts: Collection<Artifact> = declared): ArtifactRegistry =
        withContext(Dispatchers.IO) {
            for ((scope, scoped) in artifacts.groupBy { it.scope() }) {
                val file = Paths.scopeManifest(context, scope)
                val existing =
                    runCatching { file.readText() }.getOrNull()?.let(Manifest::parse) ?: ArtifactRegistry.EMPTY
                write(file, existing.plus(scoped))
            }
            merged()
        }

    // ------------------------------------------------------------------- inspect

    /** The state of [ref] as a whole — no fetch, no hash unless the size matches. */
    suspend fun status(ref: String): ArtifactStatus {
        val artifact = resolve(ref)
        return withContext(Dispatchers.IO) {
            artifact.files.firstNotNullOfOrNull { file ->
                statusOf(Paths.resolve(context, artifact, file.fileName), file)
                    .takeUnless { it == ArtifactStatus.READY }
            } ?: ArtifactStatus.READY
        }
    }

    /** The whole set for [ref]: each file, and where it is now (`null` when absent). */
    suspend fun files(ref: String): Map<ArtifactFile, File?> {
        val artifact = resolve(ref)
        return withContext(Dispatchers.IO) {
            artifact.files.associateWith { file -> Paths.resolve(context, artifact, file.fileName) }
        }
    }

    /**
     * Whether the shared models folder is usable — i.e. all-files access is
     * granted. When false, everything still works, but only this app can read the
     * files: [storageDir] is then the app-private directory.
     *
     * Exposed because the *screen* has to tell the user which one they are in; the
     * decision itself belongs here, not in the UI.
     */
    fun sharedStorageUsable(): Boolean = Paths.sharedUsable(context)

    /** The directory artifacts are written to now: shared when usable, else private. */
    fun storageDir(): File = Paths.dir(context)

    /**
     * The file for a **declared** artifact, resolved synchronously.
     *
     * For reading a dataset at startup, where the caller cannot suspend. Declared
     * artifacts only — resolving the persisted registry is IO, and pretending
     * otherwise here would be a lie in the signature.
     */
    fun declaredPath(
        ref: String,
        fileName: String,
    ): File? {
        val artifact = ArtifactRouter.of(declared).artifact(ref) ?: return null
        return Paths.resolve(context, artifact, fileName)
    }

    /** The directory [ref] occupies in the writable root, created if missing. */
    suspend fun path(ref: String): File {
        val artifact = resolve(ref)
        return withContext(Dispatchers.IO) { Paths.dirFor(context, artifact).apply { mkdirs() } }
    }

    /**
     * What [refs] would fetch — the files not already present and verified.
     * Nothing is written and no bytes are downloaded, so a manager UI can say
     * *"this will download 176 MB · 3 files"* before committing.
     */
    suspend fun plan(refs: Collection<String>): List<ArtifactFile> =
        withContext(Dispatchers.IO) {
            refs.flatMap { ref ->
                val artifact = resolve(ref)
                artifact.files.filterNot { file ->
                    statusOf(Paths.resolve(context, artifact, file.fileName), file) == ArtifactStatus.READY
                }
            }
        }

    // --------------------------------------------------------------------- fetch

    /**
     * [ref]'s directory, with every file present and verified — fetching what is
     * missing. **Complete or throws**: a partial set is never returned.
     *
     * [localOnly] refuses to touch the network: if the set is not already here, it
     * throws instead of downloading, which is what an offline-first or
     * data-conscious caller needs.
     */
    suspend fun ensure(
        ref: String,
        localOnly: Boolean = false,
        onProgress: (file: ArtifactFile, written: Long, total: Long) -> Unit = { _, _, _ -> },
    ): File {
        val artifact = resolve(ref)
        val dir = path(ref)
        for (file in artifact.files) {
            val target = File(dir, file.fileName)
            if (statusOf(target, file) == ArtifactStatus.READY) continue
            if (relocate(artifact, file, target)) continue
            if (localOnly) {
                throw IllegalStateException("$ref is not present locally (${file.fileName})")
            }
            downloader.download(file, dir) { written, total -> onProgress(file, written, total) }
        }
        return dir
    }

    /**
     * Fetches [refs] in the **foreground service**, so a large transfer survives
     * the app being backgrounded. Progress arrives on [DownloadEvents]. Returns
     * false when the platform refuses to start the service, so a caller can fall
     * back to [ensure].
     */
    suspend fun download(
        refs: Collection<String>,
        title: String = "Downloading models",
    ): Boolean {
        val artifacts = refs.map { resolve(it) }
        return DownloadService.start(context, artifacts, title)
    }

    // ------------------------------------------------------------------- maintain

    /** Deletes [ref]'s files from the writable directory, and its directory if empty. */
    suspend fun remove(ref: String): Boolean =
        withContext(Dispatchers.IO) {
            val artifact = resolve(ref)
            val dir = Paths.dirFor(context, artifact)
            val removed =
                artifact.files.count { file ->
                    val target = File(dir, file.fileName)
                    Verified.marker(target).delete()
                    target.delete()
                }
            dir.delete()
            removed > 0
        }

    /**
     * Reclaims space the SDK owns, and returns the names it removed.
     *
     * In the **private** directory this deletes every file no known artifact
     * names: a spec is only a definition, so removing it from code removes nothing
     * from disk. In the **shared** directory only abandoned `.part` files go — a
     * complete file there may be a dataset or model the user put there
     * deliberately, and no declaration can know its name.
     */
    suspend fun prune(): List<String> =
        withContext(Dispatchers.IO) {
            val artifacts = merged().artifacts
            val writable = Paths.dir(context)
            val isPrivate = writable == Paths.privateRoot(context)
            val keep =
                artifacts.flatMap { a -> a.fileNames().flatMap { listOf(it, it + Verified.SUFFIX) } }.toSet() +
                    Paths.MANIFEST_NAME +
                    "${Paths.MANIFEST_NAME}.tmp"
            val dirs = listOf(writable) + artifacts.map { File(writable, it.dir) }
            dirs
                .flatMap { it.listFiles()?.filter { entry -> entry.isFile } ?: emptyList() }
                .filter { file ->
                    file.name.endsWith(PART_SUFFIX) || (isPrivate && file.name !in keep)
                }.sortedBy { it.name }
                .mapNotNull { file -> file.name.takeIf { file.delete() } }
        }

    // --------------------------------------------------------------------- internals

    /** What this app declares, over what the device has — declared wins by `dir`. */
    private suspend fun merged(): ArtifactRegistry = registry().plus(declared)

    /** Resolve a reference, or fail loudly: a silent miss would mean "no model". */
    private suspend fun resolve(ref: String): Artifact = router().artifact(ref) ?: throw IllegalArgumentException("no artifact for reference '$ref'")

    /**
     * Moves a verified copy of [file] into [target] from wherever it already is,
     * returning false when there is no such copy. A rename is preferred (same
     * volume); a copy covers the case where it is not.
     *
     * This is what keeps a **complete set** complete: a graph moved into place
     * without its weights would leave ONNX unable to resolve them.
     */
    private fun relocate(
        artifact: Artifact,
        file: ArtifactFile,
        target: File,
    ): Boolean {
        val existing = Paths.resolve(context, artifact, file.fileName) ?: return false
        if (existing == target || statusOf(existing, file) != ArtifactStatus.READY) return false
        if (existing.renameTo(target)) return true
        return runCatching {
            existing.copyTo(target, overwrite = true)
            existing.delete()
            true
        }.getOrDefault(false)
    }

    private fun statusOf(
        file: File?,
        spec: ArtifactFile,
    ): ArtifactStatus {
        if (file == null) return ArtifactStatus.ABSENT
        val size = file.length()
        // A wrong size is PARTIAL, and hashing it would be a wasted read.
        if (size != spec.sizeBytes) return ArtifactStatus.PARTIAL
        // Verified before, and the file has not changed since: a stat is enough.
        if (Verified.matches(file, spec)) return ArtifactStatus.READY
        val sha = Integrity.sha256(file)
        if (!sha.equals(spec.sha256, ignoreCase = true)) return ArtifactStatus.STALE
        // Remember it, so the next start does not re-hash 167 MB.
        Verified.write(file, spec)
        return ArtifactStatus.READY
    }

    private fun write(
        file: File,
        registry: ArtifactRegistry,
    ) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(Manifest.write(registry))
        // Rename is atomic on one volume, so a reader sees the old document or the
        // new one, never a partial write.
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
    }

    private companion object {
        /** The suffix [HttpDownloader] streams into before the file is published. */
        const val PART_SUFFIX = ".part"
    }
}

/**
 * The file for a **declared** artifact, resolved synchronously — [Hub.declaredPath]
 * without needing a `Hub`, for a caller that reads a dataset during construction
 * and therefore cannot reference a later-declared property.
 *
 * Declared artifacts only: the persisted registry is IO.
 */
fun declaredPath(
    context: Context,
    declared: Collection<Artifact>,
    ref: String,
    fileName: String,
): File? {
    val artifact = ArtifactRouter.of(declared).artifact(ref) ?: return null
    return Paths.resolve(context, artifact, fileName)
}
