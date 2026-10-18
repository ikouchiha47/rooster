package com.personalos.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

@Database(
    entities = [EventEntity::class, TaggerEntity::class, ItemTagEntity::class, PlaceEntity::class, MentionEntity::class, PartyEntity::class, PartySourceEntity::class, SourceEntity::class, RuleEntity::class, ItemRuleEntity::class, ItemFieldEntity::class, KindEntity::class, FacetEntity::class, KindFacetEntity::class, SyncRunEntity::class, CalendarDateEntity::class, ConversationEntity::class, CategoryEntity::class, CategoryRuleEntity::class],
    views = [ItemTagCurrent::class],
    version = 21,
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

    abstract fun ruleFireDao(): RuleFireDao

    abstract fun ruleBackfillDao(): RuleBackfillDao

    abstract fun retagDao(): RetagDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun messageDeleteDao(): MessageDeleteDao

    abstract fun messageSearchDao(): MessageSearchDao

    abstract fun conversationDao(): ConversationDao

    abstract fun categoryDao(): CategoryDao

    abstract fun categoryBackfillDao(): CategoryBackfillDao

    abstract fun itemFieldDao(): ItemFieldDao

    abstract fun kindDao(): KindDao

    abstract fun facetDao(): FacetDao

    abstract fun kindFacetDao(): KindFacetDao

    abstract fun syncRunDao(): SyncRunDao

    abstract fun calendarDao(): CalendarDao

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
                        )
                        // The bundled SQLite 3.50.1, not the platform's: the
                        // platform has FTS3/FTS4 but no FTS5, and Messages search
                        // is an FTS5 trigram table (v19). Room resolves the name
                        // above to the app's databases directory through the
                        // driver, so the file on disk does not move.
                        .setDriver(BundledSQLiteDriver())
                        .addMigrations(*MIGRATIONS)
                        .addCallback(DerivedObjectsCallback)
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

/**
 * Installs the objects Room cannot model as entities, whenever the database is
 * opened: the `events_fts` table and its sync triggers, and the `conversations`
 * triggers.
 *
 * On an upgrade the migrations create them. On a **fresh install** Room creates
 * the schema directly at the current version and **no migration runs**, so
 * without this those objects simply would not exist — which is how search was
 * broken on a fresh install while working on every upgraded device. Every
 * statement is `IF NOT EXISTS`, so on an upgrade path this is a cheap no-op.
 *
 * The DDL is shared with the migrations ([DERIVED_OBJECT_STATEMENTS]), so the
 * two paths cannot drift; a test asserts they agree.
 *
 * Both callback overloads are overridden because Room dispatches the
 * `SQLiteConnection` one when a driver is set (as here) and the
 * `SupportSQLiteDatabase` one on the platform path; running both is harmless.
 */
internal object DerivedObjectsCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        DERIVED_OBJECT_STATEMENTS.forEach { db.execSQL(it) }
    }

    override fun onOpen(connection: SQLiteConnection) {
        DERIVED_OBJECT_STATEMENTS.forEach { connection.execSQL(it) }
    }
}
