package com.personalos.mlsdk

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Where models live. One convention, so an app that follows it finds another
 * app's download instead of fetching its own.
 *
 * The shared root is `<external storage>/Downloads/ml-models` — a folder the user
 * can see, back up and drop a hand-built model into from any file manager. That
 * matters for **custom models**: a file placed there under the right name is
 * found and used, no download and no app involvement.
 *
 * Each app holds `MANAGE_EXTERNAL_STORAGE` (all-files access), so the folder is
 * readable and writable by all of them. When that permission is not granted — or
 * the platform is below API 30, where there is no all-files access to ask for —
 * the SDK degrades to the app's private `files/models`, so it always works,
 * sharing or not.
 */
object Paths {
    /** The shared root, relative to external storage. */
    const val ROOT = "Downloads/ml-models"

    /**
     * The root this SDK used before it moved to [ROOT]. Kept in the **read** path
     * only: an existing download, or a custom model dropped in under the old
     * convention, still resolves instead of being re-fetched.
     */
    const val LEGACY_ROOT = "PersonalOS/ml/models"

    fun sharedRoot(): File = File(Environment.getExternalStorageDirectory(), ROOT)

    fun legacyRoot(): File = File(Environment.getExternalStorageDirectory(), LEGACY_ROOT)

    fun privateRoot(context: Context): File = File(context.filesDir, "models")

    fun sharedUsable(context: Context): Boolean {
        // `isExternalStorageManager` is API 30+; below that there is no all-files
        // access to ask for, so the shared root is not usable.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return Environment.isExternalStorageManager()
    }

    /** The directory to read/write models in — shared when reachable, else private. */
    fun dir(context: Context): File = if (sharedUsable(context)) sharedRoot() else privateRoot(context)

    /**
     * Every directory [name] may already exist in, **in order**: the writable
     * dir first (so a fresh download wins), then the read-only fallbacks. This is
     * what preserves the directory mapping — moving the root does not strand a
     * model that is already on disk.
     */
    fun lookup(context: Context): List<File> =
        if (sharedUsable(context)) {
            listOf(sharedRoot(), legacyRoot(), privateRoot(context))
        } else {
            listOf(privateRoot(context), sharedRoot(), legacyRoot())
        }

    fun file(
        context: Context,
        name: String,
    ): File = File(dir(context), name)

    /** The first existing copy of [name], or null. */
    fun resolve(
        context: Context,
        name: String,
    ): File? = Lookup.findExisting(lookup(context), name)

    // ------------------------------------------------------------ per model

    /** The directory [model] occupies in the writable root — where a fetch goes. */
    fun dirFor(
        context: Context,
        model: Artifact,
    ): File = File(dir(context), model.dir)

    /**
     * Every directory [model]'s files may already be in, in order: each root's
     * **per-model** directory first, then each root itself.
     *
     * The root is in the list because files used to land *flat*, directly in the
     * root, before a model owned a directory. Leaving it out would re-download a
     * 176 MB model that is already sitting there — which is the whole point of
     * keeping the mapping.
     */
    fun lookupRoots(
        context: Context,
        model: Artifact,
    ): List<File> = lookup(context).flatMap { root -> listOf(File(root, model.dir), root) }

    /** The first existing copy of [fileName] for [model], or null. */
    fun resolve(
        context: Context,
        model: Artifact,
        fileName: String,
    ): File? = Lookup.findExisting(lookupRoots(context, model), fileName)

    /**
     * The first file named [name] **anywhere** under the models root: each root
     * itself, then `<scope>/`, then `<scope>/<model>/`.
     *
     * For a caller that knows a file name but no model — a downloaded overlay read
     * by name — so it does not have to guess which directory the file went into.
     * The flat roots are checked first, which keeps a pre-directory file working.
     */
    fun locate(
        context: Context,
        name: String,
    ): File? {
        val roots = lookup(context)
        Lookup.findExisting(roots, name)?.let { return it }
        for (root in roots) {
            for (scope in root.listFiles()?.filter { it.isDirectory } ?: emptyList<File>()) {
                File(scope, name).takeIf { it.isFile }?.let { return it }
                for (modelDir in scope.listFiles()?.filter { it.isDirectory } ?: emptyList<File>()) {
                    File(modelDir, name).takeIf { it.isFile }?.let { return it }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------ registry

    /**
     * Where the persistent registry lives: the **shared** root whenever that is
     * usable, because the point of the registry is that another app can read it.
     * Falls back to the private dir, so an unshared install still registers — it
     * just cannot be read by anyone else.
     */
    fun registryRoot(context: Context): File = if (sharedUsable(context)) sharedRoot() else privateRoot(context)

    /**
     * One registry file **per scope**, written only by the app that owns it:
     *
     * ```
     * Downloads/ml-models/
     *   global/manifest.json        what is shared
     *   personalos/manifest.json    what PersonalOS registered
     *   manifest.json               a model written before scopes existed
     * ```
     *
     * Per scope, not one shared file, so two apps registering at the same time do
     * not read-modify-write over each other — there is no lock to take and no
     * update to lose. A reader merges whatever it finds.
     */
    fun scopeManifest(
        context: Context,
        scope: String,
    ): File = File(File(registryRoot(context), scope), MANIFEST_NAME)

    /** The pre-scope registry: a flat manifest in the root itself, if one exists. */
    fun rootManifest(context: Context): File = File(registryRoot(context), MANIFEST_NAME)

    /** Every registry file on disk — the root one plus one per scope directory. */
    fun manifests(context: Context): List<File> {
        val root = registryRoot(context)
        val scoped = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }.map { File(it, MANIFEST_NAME) }
        return (listOf(rootManifest(context)) + scoped).filter { it.isFile }
    }

    const val MANIFEST_NAME = "manifest.json"
}

/**
 * The read-path decision, as a pure function over `File`s so it is testable with
 * no device: which existing file, if any, is the model the caller asked for.
 */
object Lookup {
    /** The first directory in [roots] that holds a regular file named [name]. */
    fun findExisting(
        roots: List<File>,
        name: String,
    ): File? = roots.asSequence().map { File(it, name) }.firstOrNull { it.isFile }
}
