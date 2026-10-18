package com.personalos.app.core.cache

import kotlinx.coroutines.flow.Flow

/**
 * A [StringCache] whose writes can be observed, so a reader of a value that is
 * not itself reactive can re-read it when it changes.
 *
 * Deliberately a second, narrow interface rather than a method on [StringCache]
 * (docs/CODE-DESIGN-GUIDELINES.md §4, §7): most writers only read and write, and
 * a cache that cannot announce changes should not have to pretend it can.
 */
interface ObservableStringCache : StringCache {
    /**
     * Emits [key]'s current [StringCache.Entry] at subscription, then again on
     * every write to that key. Null until the key has been written.
     */
    fun observe(key: String): Flow<StringCache.Entry?>
}
