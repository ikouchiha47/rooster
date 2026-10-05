package com.personalos.mlsdk

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Where models live. One convention, so an app that follows it finds another
 * app's download instead of fetching its own.
 *
 * The shared root is `/<external storage>/PersonalOS/ml/models` — a plain public
 * folder any of these apps can read, because each holds `MANAGE_EXTERNAL_STORAGE`
 * (all-files access). When that permission is not granted — or the platform is
 * below API 30 — it degrades to the app's private `files/models`, so the SDK
 * always works, sharing or not.
 */
object ModelPaths {
    const val ROOT = "PersonalOS/ml/models"

    fun sharedRoot(): File = File(Environment.getExternalStorageDirectory(), ROOT)

    fun sharedUsable(context: Context): Boolean {
        // `isExternalStorageManager` is API 30+; below that there is no all-files
        // access to ask for, so the shared root is not usable.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return Environment.isExternalStorageManager()
    }

    /** The directory to read/write models in — shared when reachable, else private. */
    fun dir(context: Context): File = if (sharedUsable(context)) sharedRoot() else File(context.filesDir, "models")

    fun file(
        context: Context,
        name: String,
    ): File = File(dir(context), name)
}
