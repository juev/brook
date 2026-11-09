package org.evsyukov.brook.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.PrimaryKey

/** Per-account translation cache. Format/provider identity is part of the key. */
@Entity(tableName = "translations", primaryKeys = ["entryId", "fullText", "language", "format"], foreignKeys = [ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)])
data class TranslationEntity(val entryId: Long, val fullText: Boolean, val language: String, val format: Int = 1, val sourceHash: String, val reply: String)

@Entity(tableName = "translation_preferences")
data class TranslationPreferencesEntity(@PrimaryKey val key: Int = 0, val language: String, val consent: Boolean)

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translation_preferences WHERE `key` = 0")
    suspend fun preferences(): TranslationPreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun configure(value: TranslationPreferencesEntity)

    @Query("DELETE FROM translations")
    suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(value: TranslationEntity)

    @Query("SELECT reply FROM translations WHERE entryId = :id AND fullText = :fullText AND language = :language AND sourceHash = :hash AND format = 1")
    suspend fun read(id: Long, fullText: Boolean, hash: String, language: String): String?
}
