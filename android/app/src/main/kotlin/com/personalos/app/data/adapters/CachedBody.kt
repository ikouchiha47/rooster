package com.personalos.app.data.adapters

import com.personalos.app.core.cache.StringCache

/**
 * A response body fetched at most once per window, shared by every reader of
 * the same key.
 *
 * This is the discipline the providers already had (`StringCache` exists for
 * exactly this: "keeps network calls off the screen-open path"). It lives here
 * as one small piece so an adapter cannot accidentally fetch on every call —
 * which is what happened when the store-backed adapters were written with a
 * bare `Http.getText`.
 *
 * Three behaviours, deliberately:
 *  - inside the window: serve the stored body, never call [fetch];
 *  - outside the window: fetch, store, serve;
 *  - fetch fails: serve the stale body rather than nothing.
 *
 * Keys are the providers' own, so a provider and an adapter reading the same
 * place share one cached body and the network sees one request.
 */
class CachedBody(
    private val cache: StringCache,
) {
    fun get(
        key: String,
        ttlMs: Long,
        now: Long,
        fetch: () -> String?,
    ): String? {
        val entry = cache.read(key)
        if (entry != null && now - entry.at < ttlMs) return entry.value
        val body = fetch()
        if (body.isNullOrBlank()) return entry?.value
        cache.write(key, body, now)
        return body
    }
}
