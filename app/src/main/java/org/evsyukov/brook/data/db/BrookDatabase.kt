package org.evsyukov.brook.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 1,
)
abstract class BrookDatabase : RoomDatabase() {
    abstract fun treeDao(): TreeDao
    abstract fun entryDao(): EntryDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        fun fileName(accountId: String) = "brook-$accountId.db"

        fun open(context: Context, accountId: String): BrookDatabase =
            Room.databaseBuilder(context, BrookDatabase::class.java, fileName(accountId)).build()
    }
}
