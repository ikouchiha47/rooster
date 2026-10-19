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
object ModelPaths {
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
    ): File? = ModelLookup.findExisting(lookup(context), name)
}

/**
 * The read-path decision, as a pure function over `File`s so it is testable with
 * no device: which existing file, if any, is the model the caller asked for.
 */
object ModelLookup {
    /** The first directory in [roots] that holds a regular file named [name]. */
    fun findExisting(
        roots: List<File>,
        name: String,
    ): File? = roots.asSequence().map { File(it, name) }.firstOrNull { it.isFile }
}
