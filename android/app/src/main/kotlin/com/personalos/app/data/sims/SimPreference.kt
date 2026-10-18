package com.personalos.app.data.sims

import android.content.Context

/**
 * The chosen default SIM, shared by Contacts, Compose and the thread reply bar so
 * a choice made in one place is the choice everywhere.
 */
class SimPreference(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("sims", Context.MODE_PRIVATE)

    fun selected(): Int? = prefs.getInt(KEY, -1).takeIf { it >= 0 }

    fun select(id: Int?) {
        prefs
            .edit()
            .apply {
                if (id == null) remove(KEY) else putInt(KEY, id)
            }.apply()
    }

    private companion object {
        const val KEY = "selected"
    }
}
