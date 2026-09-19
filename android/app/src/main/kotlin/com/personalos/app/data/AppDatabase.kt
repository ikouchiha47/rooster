package com.personalos.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EventEntity::class, TaggerEntity::class, ItemTagEntity::class, PlaceEntity::class, MentionEntity::class, PartyEntity::class, PartySourceEntity::class, SourceEntity::class, RuleEntity::class, ItemRuleEntity::class, ItemFieldEntity::class, KindEntity::class, FacetEntity::class, KindFacetEntity::class, SyncRunEntity::class],
    views = [ItemTagCurrent::class],
    version = 16,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    abstract fun itemTagDao(): ItemTagDao

    abstract fun placeDao(): PlaceDao

    abstract fun mentionDao(): MentionDao

    abstract fun partyDao(): PartyDao

    abstract fun partySourceDao(): PartySourceDao

    abstract fun sourceDao(): SourceDao

    abstract fun ruleDao(): RuleDao

    abstract fun itemRuleDao(): ItemRuleDao

    abstract fun itemFieldDao(): ItemFieldDao

    abstract fun kindDao(): KindDao

    abstract fun facetDao(): FacetDao

    abstract fun kindFacetDao(): KindFacetDao

    abstract fun syncRunDao(): SyncRunDao

    companion object {
        @Volatile
        private var dbInstance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            dbInstance ?: synchronized(this) {
                val created =
                    Room
                        .databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "personalos.db",
                        ).addMigrations(*MIGRATIONS)
                        // v1 and v2 predate the exported schema baseline and never
                        // shipped, so they are the only versions allowed to reset.
                        // Everything from v3 on migrates and keeps its data.
                        .fallbackToDestructiveMigrationFrom(true, *LEGACY_VERSIONS)
                        .build()
                dbInstance = created
                created
            }
    }
}
