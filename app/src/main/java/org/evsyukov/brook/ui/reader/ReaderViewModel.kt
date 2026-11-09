package org.evsyukov.brook.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.evsyukov.brook.data.account.AccountSession
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.images.ImageCache
import java.io.IOException
import org.evsyukov.brook.data.remote.TranslationReply
import org.evsyukov.brook.data.translation.TranslationCache
import org.evsyukov.brook.data.translation.TranslationReader
import org.evsyukov.brook.data.translation.TranslationUnavailable
import org.evsyukov.brook.data.translation.TranslationSourceMismatch
import org.evsyukov.brook.data.remote.GReaderException
import org.evsyukov.brook.R

data class ReaderState(
    val translation: org.evsyukov.brook.data.remote.TranslationReply? = null,
    val loadingTranslation: Boolean = false,
    val translationDone: Int = 0,
    val translationTotal: Int = 0,
    val translationSetup: Boolean = false,
    val entry: EntryEntity? = null,
    val feedTitle: String? = null,
    val labels: List<String> = emptyList(),
    val allLabels: List<String> = emptyList(),
    /** The text extracted on the device is shown instead of the one of the server. */
    val showFullText: Boolean = false,
    val loadingFullText: Boolean = false,
) {
    val hasFullText: Boolean get() = entry?.fullContent != null
    val displayTitle: String get() = translation?.title ?: entry?.title.orEmpty()
    val displayBody: String get() = translation?.content ?: if (showFullText) entry?.fullContent.orEmpty() else entry?.content.orEmpty()
}

/**
 * The entry on screen, one view model per account: [show] switches the entry and marks it read.
 * (Entries of NavDisplay share the store of the activity, so one per entry would pile up.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel internal constructor(
    session: AccountSession,
    /**
     * Stores the text extracted from the page. A test gates the return of this call, so a reader
     * turning the text off while the request is still finishing is reproduced deterministically.
     */
    private val storeFullText: suspend (id: Long, html: String) -> Unit,
) : ViewModel() {
    constructor(session: AccountSession) : this(
        session,
        { id, html -> session.db.entryDao().setFullContent(id, html) },
    )

    private val entries = session.db.entryDao()
    private val tree = session.db.treeDao()
    private val actions = session.actions
    private val extractor = session.fullText
    private val translationCache = TranslationCache(session.db)
    private val translator = session.translationApi?.let { TranslationReader(it, translationCache) }
    private val translated = MutableStateFlow<TranslationReply?>(null)
    private data class TranslationWork(val id: Long? = null, val loading: Boolean = false, val done: Int = 0, val total: Int = 0)
    private val translationWork = MutableStateFlow(TranslationWork())
    private val translationMessages = Channel<Int>(Channel.CONFLATED)
    private val translationSetup = MutableStateFlow(false)
    val translationNotices: Flow<Int> = translationMessages.receiveAsFlow()
    private var translationJob: Job? = null
    private val translationOwner = SupervisorJob(session.translationLifetime)
    private val translationScope = CoroutineScope(viewModelScope.coroutineContext + translationOwner)
    private var translationGeneration = 0L
    val images: ImageCache = session.images

    private val entryIdFlow = MutableStateFlow<Long?>(null)
    private val entryId: Long get() = checkNotNull(entryIdFlow.value)

    /** Null until the reader chose; then whether to show the text extracted here. */
    private val fullTextChoice = MutableStateFlow<Boolean?>(null)
    private val loading = MutableStateFlow(false)
    private val _failures = Channel<Unit>(Channel.CONFLATED)

    /** A full text that could not be had; the screen says so once. */
    val fullTextFailures: Flow<Unit> = _failures.receiveAsFlow()

    val state: StateFlow<ReaderState> = entryIdFlow.flatMapLatest { id ->
        if (id == null) return@flatMapLatest flowOf(ReaderState())
        combine(
            entries.entry(id),
            entries.labelsOf(id),
            tree.labels().map { list -> list.map { it.name } },
            fullTextChoice,
            loading,
        ) { entry, labels, allLabels, choice, loadingNow ->
            val feedTitle = entry?.let { tree.feed(it.feedId)?.title }
            ReaderState(
                entry = entry,
                feedTitle = feedTitle,
                labels = labels,
                allLabels = (allLabels + labels).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
                // A text extracted before is shown again, offline too, until the reader turns it off.
                showFullText = (choice ?: (entry?.fullContent != null)) && entry?.fullContent != null,
                loadingFullText = loadingNow,
            )
        }
    }
        .combine(translated) { reader, translation ->
            val e = reader.entry
            val matches = e != null && translation?.id == e.id.toString() && translation.sourceHash == TranslationReader.fingerprint(e.title, if (reader.showFullText) e.fullContent.orEmpty() else e.content)
            reader.copy(translation = translation.takeIf { matches })
        }
        .combine(translationWork) { reader, work -> reader.copy(loadingTranslation = work.loading && work.id == reader.entry?.id, translationDone=work.done, translationTotal=work.total) }
        .combine(translationSetup) { reader, setup -> reader.copy(translationSetup = setup) }
        .catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderState())

    fun show(id: Long) {
        if (entryIdFlow.value == id) return
        showOriginal()
        fullTextChoice.value = null
        loading.value = false
        entryIdFlow.value = id
        viewModelScope.launch { actions.setRead(listOf(id), true) }
    }

    suspend fun translationPreferences() = translationCache.preferences()
    fun dismissTranslationSetup() { translationSetup.value = false }

    fun clearTranslations(): Job {
        val previous = translationJob
        showOriginal()
        return translationScope.launch { previous?.join(); translationCache.clear() }
    }

    fun configureTranslation(language: String, consent: Boolean): Job {
        showOriginal()
        val generation = translationGeneration
        return translationScope.launch {
            translationCache.configure(language,consent)
            if (translationGeneration == generation && consent) translate().join()
        }
    }

    fun translate(): Job {
        showOriginal()
        val generation = translationGeneration
        return translationScope.launch {
        val current = state.value
        val entry = current.entry ?: return@launch
        val owner = coroutineContext[Job]!!
        val sourceHash = TranslationReader.fingerprint(entry.title, if (current.showFullText) entry.fullContent.orEmpty() else entry.content)
        val sourceWatch = launch {
            entries.entry(entry.id).collect { fresh ->
                val full = fullTextChoice.value ?: (fresh?.fullContent != null)
                if (fresh == null || full != current.showFullText || TranslationReader.fingerprint(fresh.title, if (full) fresh.fullContent.orEmpty() else fresh.content) != sourceHash) owner.cancel()
            }
        }
        translationWork.value = TranslationWork(entry.id,true)
        try {
            val prefs = translationCache.preferences()
            if (!prefs.consent || prefs.language.isBlank()) {
                if (translationGeneration == generation) translationSetup.value = true
                return@launch
            }
            val language = prefs.language
            val result = (translator ?: throw TranslationUnavailable()).translate(entry,current.showFullText,language) { progress ->
                if (translationGeneration == generation) translationWork.value = TranslationWork(entry.id,true,progress.done,progress.total)
            }
            if (translationGeneration == generation) {
                translated.value = result.takeUnless { it.sameLanguage }
                if (result.sameLanguage) translationMessages.trySend(R.string.translation_same)
                else if (result.failed > 0) translationMessages.trySend(R.string.translation_partial)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (translationGeneration == generation) translationMessages.trySend(R.string.translation_failed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (translationGeneration == generation) translationMessages.trySend(when(e) {
                is TranslationUnavailable -> R.string.translation_unavailable
                is TranslationSourceMismatch, is GReaderException.Conflict -> R.string.translation_source_mismatch
                is GReaderException.Unauthorized -> R.string.translation_auth
                is GReaderException.RateLimited -> R.string.translation_rate_limit
                else -> R.string.translation_failed
            })
        } catch (e: IllegalArgumentException) {
            if (translationGeneration == generation) translationMessages.trySend(R.string.translation_failed)
        } finally {
            sourceWatch.cancel()
            if (translationGeneration == generation) translationWork.value = TranslationWork()
        }
        }.also { translationJob = it }
    }
    fun showOriginal() {
        translationGeneration++
        translationJob?.cancel()
        translationJob = null
        translated.value = null
        translationWork.value = TranslationWork()
        translationSetup.value = false
    }

    override fun onCleared() { translationOwner.cancel() }

    fun setStarred(starred: Boolean) {
        viewModelScope.launch { actions.setStarred(entryId, starred) }
    }

    fun setRead(read: Boolean) {
        viewModelScope.launch { actions.setRead(listOf(entryId), read) }
    }

    fun setLabel(label: String, on: Boolean) {
        val name = label.trim()
        if (name.isEmpty()) return
        viewModelScope.launch { actions.setLabel(entryId, name, on) }
    }

    /** Shows the full text, extracting it from the page first when it is not here. */
    fun toggleFullText() {
        showOriginal()
        val current = state.value
        if (current.showFullText) {
            fullTextChoice.value = false
            return
        }
        if (current.hasFullText) {
            fullTextChoice.value = true
            return
        }
        val url = current.entry?.url?.takeIf { it.isNotBlank() }
        if (url == null || loading.value) {
            if (url == null) _failures.trySend(Unit)
            return
        }
        loading.value = true
        val id = entryId
        viewModelScope.launch {
            try {
                val html = extractor.extract(url)
                // The choice is made before the text is stored: the state shows the text as soon as
                // the store lands, and a reader turning it off meanwhile must not be overruled by
                // the end of this request.
                if (entryIdFlow.value == id) fullTextChoice.value = true
                storeFullText(id, html)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _failures.trySend(Unit)
            } finally {
                if (entryIdFlow.value == id) loading.update { false }
            }
        }
    }
}
