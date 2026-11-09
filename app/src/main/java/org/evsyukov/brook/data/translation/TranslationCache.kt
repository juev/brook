package org.evsyukov.brook.data.translation

import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.remote.TranslationReply
import org.evsyukov.brook.data.db.TranslationEntity
import org.evsyukov.brook.data.db.TranslationPreferencesEntity
import kotlinx.serialization.json.Json
import androidx.room.withTransaction

data class TranslationPreferences(val language: String = "", val consent: Boolean = false)

class TranslationCache(private val db: BrookDatabase) {
    suspend fun preferences(): TranslationPreferences = db.translationDao().preferences()?.let { TranslationPreferences(it.language,it.consent) } ?: TranslationPreferences()
    suspend fun configure(language: String, consent: Boolean) { db.translationDao().configure(TranslationPreferencesEntity(language=language,consent=consent)) }
    suspend fun clear() { db.translationDao().clear() }

    suspend fun save(entryId: Long, fullText: Boolean, reply: TranslationReply) {
        db.withTransaction {
            if (db.entryDao().entryNow(entryId) != null) {
                db.translationDao().put(TranslationEntity(entryId, fullText, reply.language, sourceHash = reply.sourceHash, reply = Json.encodeToString(reply)))
            }
        }
    }
    suspend fun read(entryId: Long, fullText: Boolean, sourceHash: String, language: String): TranslationReply? =
        db.translationDao().read(entryId, fullText, sourceHash, language)?.let { Json.decodeFromString<TranslationReply>(it) }
}
