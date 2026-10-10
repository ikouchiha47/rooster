package com.personalos.app.ui.sources

import com.personalos.app.core.feed.FeedStatus

/**
 * The feed row's subtitle: the last error when there is one, otherwise the host
 * and the category it declares. Pure and testable — the row just renders it.
 */
internal fun feedSubtitle(status: FeedStatus): String =
    status.lastError?.let { "${status.host} · $it" }
        ?: listOfNotNull(status.host, status.tags.firstOrNull()).joinToString(" · ")
