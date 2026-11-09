package org.evsyukov.brook.data.translation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.brook.data.db.BrookDatabase
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.remote.TranslationReply
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationCacheTest {
    @Test
    fun lateReplyAfterEntryDeletionIsIgnoredWithoutRecreatingIt() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), BrookDatabase::class.java).allowMainThreadQueries().build()
        try {
            val cache = TranslationCache(db)
            val reply = TranslationReply("1","ru",sourceHash="a".repeat(64),exists=true,complete=true)
            val outcome = runCatching { cache.save(1,false,reply) }
            assertThat(outcome.isSuccess).isTrue()
            assertThat(cache.read(1,false,reply.sourceHash,"ru")).isNull()
            assertThat(db.entryDao().entryNow(1)).isNull()
        } finally { db.close() }
    }

    @Test
    fun remembersAccountLanguageAndConsentAndClearsOnlyCachedTranslations() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), BrookDatabase::class.java).allowMainThreadQueries().build()
        try {
            val cache = TranslationCache(db)
            assertThat(cache.preferences().consent).isFalse()
            cache.configure("ru", true)
            assertThat(TranslationCache(db).preferences()).isEqualTo(TranslationPreferences("ru",true))
            db.entryDao().insertIgnore(listOf(EntryEntity(1,"feed/1","Title","","",1,"body","",false,false,null)))
            val reply = TranslationReply("1","ru",sourceHash="a".repeat(64),complete=true,exists=true)
            cache.save(1,false,reply)
            cache.clear()
            assertThat(cache.read(1,false,reply.sourceHash,"ru")).isNull()
            assertThat(cache.preferences().language).isEqualTo("ru")
            assertThat(db.entryDao().entryNow(1)).isNotNull()
        } finally { db.close() }
    }

    @Test
    fun keepsTranslationSeparateAndDeletesItWithItsEntry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<android.content.Context>(), BrookDatabase::class.java).allowMainThreadQueries().build()
        try {
            val entry = EntryEntity(1,"feed/1","Original","","https://example.org",1,"<p>Original</p>","",false,false,null)
            db.entryDao().insertIgnore(listOf(entry))
            val cache = TranslationCache(db)
            val reply = TranslationReply("1","ru",sourceHash="a".repeat(64),revision="b".repeat(64),exists=true,complete=true,title="Перевод",content="<p>Текст</p>",done=1,total=1)
            cache.save(1,false,reply)
            assertThat(cache.read(1,false,reply.sourceHash,"ru")).isEqualTo(reply)
            assertThat(db.entryDao().entryNow(1)).isEqualTo(entry)
            assertThat(cache.read(1,true,reply.sourceHash,"ru")).isNull()
            assertThat(cache.read(1,false,"c".repeat(64),"ru")).isNull()
            assertThat(cache.read(1,false,reply.sourceHash,"en")).isNull()
            db.entryDao().delete(listOf(1))
            assertThat(cache.read(1,false,reply.sourceHash,"ru")).isNull()
        } finally { db.close() }
    }
}
