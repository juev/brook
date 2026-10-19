package org.evsyukov.brook.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Settings of the app, the same for every account. */
data class Settings(
    /** Minutes between background syncs. */
    val syncIntervalMinutes: Int = 30,
    /** Pictures and full texts are downloaded ahead only on Wi-Fi and other unmetered networks. */
    val prefetchOnlyUnmetered: Boolean = true,
    /** Read entries are kept this long after they were read; starred ones always. */
    val readRetentionDays: Int = 30,
    /** A notification tells about new entries a background sync brought. */
    val notifyNewEntries: Boolean = false,
    /** The server pushes through a UnifiedPush distributor; the periodic sync stays as a fallback. */
    val push: Boolean = false,
    /** Entries that scroll off the top of the list become read. */
    val markReadOnScroll: Boolean = false,
    /** The colors of entries. */
    val readerTheme: ReaderTheme = ReaderTheme.SYSTEM,
    /** The typeface of entries. */
    val readerFont: ReaderFont = ReaderFont.SANS,
    /** CSS pixels of the text of entries. */
    val readerTextSize: Int = 17,
    /** Line height of entries, in percent of the text size. */
    val readerLineHeight: Int = 155,
) {
    companion object {
        val SYNC_INTERVALS = listOf(15, 30, 60)
        val RETENTION_DAYS = listOf(7, 30, 90)
        val TEXT_SIZES = listOf(15, 17, 19, 21, 23)
        val LINE_HEIGHTS = listOf(140, 155, 170)
    }
}

/** System follows the colors of the app, light or dark; sepia and black stay whatever the system is. */
enum class ReaderTheme { SYSTEM, SEPIA, BLACK }

/** Sans is the font of the system; serif is Literata, bundled. */
enum class ReaderFont { SANS, SERIF }

class AppSettings(context: Context, scope: CoroutineScope, file: String = "settings") {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile(file) },
    )

    /** The settings as stored: the first value comes once the file is read. */
    val stored: Flow<Settings> = store.data.map { prefs ->
        val defaults = Settings()
        Settings(
            syncIntervalMinutes = prefs[SYNC_INTERVAL] ?: defaults.syncIntervalMinutes,
            prefetchOnlyUnmetered = prefs[PREFETCH_UNMETERED] ?: defaults.prefetchOnlyUnmetered,
            readRetentionDays = prefs[RETENTION_DAYS] ?: defaults.readRetentionDays,
            notifyNewEntries = prefs[NOTIFY] ?: defaults.notifyNewEntries,
            push = prefs[PUSH] ?: defaults.push,
            markReadOnScroll = prefs[MARK_ON_SCROLL] ?: defaults.markReadOnScroll,
            readerTheme = ReaderTheme.entries.firstOrNull { it.name == prefs[READER_THEME] } ?: defaults.readerTheme,
            readerFont = ReaderFont.entries.firstOrNull { it.name == prefs[READER_FONT] } ?: defaults.readerFont,
            readerTextSize = prefs[READER_TEXT_SIZE] ?: defaults.readerTextSize,
            readerLineHeight = prefs[READER_LINE_HEIGHT] ?: defaults.readerLineHeight,
        )
    }

    /** The settings now; defaults until the file is read. */
    val current: StateFlow<Settings> = stored.stateIn(scope, SharingStarted.Eagerly, Settings())

    suspend fun setSyncInterval(minutes: Int) = store.edit { it[SYNC_INTERVAL] = minutes }
    suspend fun setPrefetchOnlyUnmetered(value: Boolean) = store.edit { it[PREFETCH_UNMETERED] = value }
    suspend fun setReadRetentionDays(days: Int) = store.edit { it[RETENTION_DAYS] = days }
    suspend fun setNotifyNewEntries(value: Boolean) = store.edit { it[NOTIFY] = value }
    suspend fun setPush(value: Boolean) = store.edit { it[PUSH] = value }
    suspend fun setMarkReadOnScroll(value: Boolean) = store.edit { it[MARK_ON_SCROLL] = value }
    suspend fun setReaderTheme(theme: ReaderTheme) = store.edit { it[READER_THEME] = theme.name }
    suspend fun setReaderFont(font: ReaderFont) = store.edit { it[READER_FONT] = font.name }
    suspend fun setReaderTextSize(px: Int) = store.edit { it[READER_TEXT_SIZE] = px }
    suspend fun setReaderLineHeight(percent: Int) = store.edit { it[READER_LINE_HEIGHT] = percent }

    private companion object {
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        val PREFETCH_UNMETERED = booleanPreferencesKey("prefetch_only_unmetered")
        val RETENTION_DAYS = intPreferencesKey("read_retention_days")
        val NOTIFY = booleanPreferencesKey("notify_new_entries")
        val PUSH = booleanPreferencesKey("push")
        val MARK_ON_SCROLL = booleanPreferencesKey("mark_read_on_scroll")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_FONT = stringPreferencesKey("reader_font")
        val READER_TEXT_SIZE = intPreferencesKey("reader_text_size")
        val READER_LINE_HEIGHT = intPreferencesKey("reader_line_height")
    }
}
