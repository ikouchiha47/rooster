package com.personalos.app.ui.messages

/**
 * Merges newly-read rows into an already-loaded list.
 *
 * A screen subscribed to "something in the store changed" faces two very
 * different situations, and they need different handling:
 *
 *  - **the user changed what they are looking at** (a tab, a filter) — the list
 *    genuinely is a different list, so replacing it is correct;
 *  - **data arrived** — it is the same list with rows added or updated.
 *    Clearing it and re-reading page one is what made Messages blank and
 *    flicker on every write anywhere in the store.
 *
 * This is the second case. Rows are keyed by id in the UI, so an arrival lets
 * Compose reuse everything already composed. An existing key is a
 * **replacement** (a gauge observation upserted in place, or enrichment filling
 * in text); a new key is an **insert at its sorted position** — not appended,
 * because a feed item can arrive now carrying an older publish time.
 *
 * [newestFirst] must be the same ordering the query uses, so the merged list
 * cannot disagree with the store about what "first" means.
 *
 * @param cap the most rows kept; the **oldest** are dropped, never the newest.
 */
fun <T> mergeArrivals(
    current: List<T>,
    arrivals: List<T>,
    key: (T) -> String,
    newestFirst: Comparator<T>,
    cap: Int,
): List<T> {
    if (arrivals.isEmpty()) return current
    val byKey = LinkedHashMap<String, T>(current.size + arrivals.size)
    current.forEach { byKey[key(it)] = it }
    // Newest wins: an arrival whose key is already loaded replaces it.
    arrivals.forEach { byKey[key(it)] = it }
    return byKey.values.sortedWith(newestFirst).take(cap.coerceAtLeast(0))
}
