package org.evsyukov.brook.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/** The data of one account; every account has a file of its own (`brook-<accountId>.db`). */
@Database(
    entities = [
        CategoryEntity::class,
        LabelEntity::class,
        FeedEntity::class,
        FeedSettingsEntity::class,
        EntryEntity::class,
        EntryLabelEntity::class,
        PendingActionEntity::class,
        SyncStateEntity::class,
        EntryFtsEntity::class,
    ],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = BrookDatabase.SearchMigration::class)],
)
abstract class BrookDatabase : RoomDatabase() {
    abstract fun treeDao(): TreeDao
    abstract fun entryDao(): EntryDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun searchDao(): SearchDao

    /** Version 2 adds the search index; it fills from the entries already here on first use. */
    class SearchMigration : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) = createSearchTriggers(db)
    }

    companion object {
        fun fileName(accountId: String) = "brook-$accountId.db"

        fun open(context: Context, accountId: String): BrookDatabase =
            Room.databaseBuilder(context, BrookDatabase::class.java, fileName(accountId)).withSearchTriggers().build()

        /** Room knows nothing of triggers: every builder of the database, tests included, adds them here. */
        fun <T : RoomDatabase> RoomDatabase.Builder<T>.withSearchTriggers(): RoomDatabase.Builder<T> =
            addCallback(
                object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) = createSearchTriggers(db)
                },
            )

        /** An entry that goes, or whose text changes, leaves the index until it is indexed again. */
        private fun createSearchTriggers(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS entries_fts_delete AFTER DELETE ON entries " +
                    "BEGIN DELETE FROM entries_fts WHERE rowid = old.id; END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS entries_fts_update AFTER UPDATE OF title, content, fullContent ON entries " +
                    "WHEN old.title IS NOT new.title OR old.content IS NOT new.content OR old.fullContent IS NOT new.fullContent " +
                    "BEGIN DELETE FROM entries_fts WHERE rowid = old.id; END",
            )
        }
    }
}
