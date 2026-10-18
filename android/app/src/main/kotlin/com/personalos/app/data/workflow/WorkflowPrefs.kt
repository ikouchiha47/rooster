package com.personalos.app.data.workflow

import android.content.Context

/**
 * The enrichment path as a stored switch, defaulting to the **workflow** (ADR 0009
 * P3). It exists so a bad release can be turned back to the direct call without a
 * new build — a kill switch, not an opt-in.
 *
 * Deliberately not a branch in the worker: the flag is read once, when
 * [com.personalos.app.core.AppContainer] binds the [Enrichment] implementation.
 */
class WorkflowPrefs(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("workflow", Context.MODE_PRIVATE)

    fun enrichViaWorkflow(): Boolean = prefs.getBoolean(KEY_ENRICH_VIA_WORKFLOW, true)

    fun setEnrichViaWorkflow(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENRICH_VIA_WORKFLOW, value).apply()
    }

    private companion object {
        const val KEY_ENRICH_VIA_WORKFLOW = "enrich_via_workflow"
    }
}
