package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/**
 * The runtime store of the categorisation taxonomy: which categories exist and
 * the rules that project a message onto one of them (v21).
 *
 * Its own interface, like [SourceDao] and [RuleDao]: the taxonomy is one reason
 * to change, and [EventDao]'s test fakes stay untouched. Reads return the whole
 * (tiny) set — seven categories and a handful of rules — so no paging is needed.
 */
@Dao
interface CategoryDao {
    /** Every category, in display order. */
    @Query("SELECT * FROM categories ORDER BY sort_order ASC")
    suspend fun categories(): List<CategoryEntity>

    /** Every rule, lowest priority first: the order the evaluator takes them. */
    @Query("SELECT * FROM category_rules ORDER BY priority ASC")
    suspend fun rules(): List<CategoryRuleEntity>

    /** Insert only what is absent, so a reconcile never overwrites a stored row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(rows: List<CategoryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRules(rows: List<CategoryRuleEntity>): List<Long>

    /**
     * Deploy a new bundled row: update the row with this id, or insert it if it
     * is absent. Only the given ids are touched, so a row the bundle does not
     * name (a future user-added category or rule) is left exactly as it is.
     */
    @Upsert
    suspend fun upsertCategories(rows: List<CategoryEntity>)

    @Upsert
    suspend fun upsertRules(rows: List<CategoryRuleEntity>)
}
