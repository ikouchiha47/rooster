package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A message category as a **row**. The core model is
 * [com.personalos.app.core.category.Category]; this is its storage shape, and
 * the asset (`assets/category/categorization.v1.json`) is only the seed.
 *
 * `sort_order`, not `sort`: the latter reads as SQL syntax and the migration
 * DDL is hand-written, so the spelling avoids quoting surprises. No `DEFAULT`
 * clauses anywhere — Room compares declared defaults when it validates the
 * schema on open, so an added default would fail there (see `Migrations.kt`).
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val label: String,
    @ColumnInfo(name = "sort_order") val sort: Int,
    val color: String,
)

/**
 * One categorisation rule as a **row**: an ordered predicate. The core model is
 * [com.personalos.app.core.category.CategoryRule]. `matcher_type` is an open
 * string id resolved through a registry of matchers, never a closed enum, so a
 * new kind of test is a new implementation and a new row — not a branch.
 */
@Entity(tableName = "category_rules")
data class CategoryRuleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    val priority: Int,
    @ColumnInfo(name = "matcher_type") val matcherType: String,
    val pattern: String,
    val note: String,
)
