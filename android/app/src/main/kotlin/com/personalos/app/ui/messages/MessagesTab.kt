package com.personalos.app.ui.messages

/**
 * The Messages tab strip as data: a label and what it loads.
 *
 * Four tabs filter SMS by **which box it sits in** (all / inbox / sent / other).
 * Saved is not a box — it is a different read entirely (bookmarked rows,
 * ordered by when they were saved). Modelling that here as `mode() == null`
 * means the screen has no special case of its own to get wrong, and the
 * distinction is testable without rendering anything.
 */
enum class MessagesTab(
    val label: String,
) {
    ALL("All"),
    ALERTS("Alerts"),
    DIGEST("Digest"),
    MUTED("Muted"),
    SAVED("Saved"),
}

/** The tab at [index], or [MessagesTab.ALL] when the index is out of range. */
fun messagesTabAt(index: Int): MessagesTab = MessagesTab.entries.getOrElse(index) { MessagesTab.ALL }

/** The SMS box this tab filters by, or null for Saved, which is not a box. */
fun MessagesTab.mode(): String? =
    when (this) {
        MessagesTab.ALL -> "all"
        MessagesTab.ALERTS -> "inbox"
        MessagesTab.DIGEST -> "sent"
        MessagesTab.MUTED -> "other"
        MessagesTab.SAVED -> null
    }
