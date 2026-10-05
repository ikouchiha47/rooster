package com.personalos.app.core.notify

/** What a notification is about. A producer's identity, not its look. */
enum class NotificationKind { SYNC, MODEL, RULE_FIRE, ERROR, REMINDER }

/** How loud it is. Ordered, so the host can show the loudest first. */
enum class Severity { INFO, SUCCESS, WARNING, ERROR }

/**
 * One in-app notification — **pure data**. Producers [NotificationCenter.post] it,
 * a host renders it; neither knows the other, and the model carries no colour or
 * layout (those are the UI's, CODE-DESIGN-GUIDELINES.md §3). This is the thing
 * that replaced a free-form `step: String`, so content is structured and one
 * owner decides what is shown.
 *
 * [progress] means "still working" (an indeterminate marker); [ttlMs] means
 * "auto-dismiss after this long", null meaning until dismissed. [at] orders ties.
 */
data class Notification(
    val id: String,
    val kind: NotificationKind,
    val severity: Severity,
    val title: String,
    val body: String? = null,
    val progress: Boolean = false,
    val ttlMs: Long? = null,
    val at: Long = 0L,
)
