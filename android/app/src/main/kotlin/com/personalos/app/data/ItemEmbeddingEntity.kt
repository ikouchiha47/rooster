package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The embedder's vector for one item, kept so a semantic watcher can compare a
 * natural-language query with stored text without re-running the model.
 *
 * One row per item: the embedding is a property of the text, not of a tagger
 * version, and the head and kNN rungs use the same model. A newer tagger
 * overwrites it.
 */
@Entity(tableName = "item_embeddings")
data class ItemEmbeddingEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "tagger_id") val taggerId: String,
    @ColumnInfo(name = "dim") val dim: Int,
    @ColumnInfo(name = "vector") val vector: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
