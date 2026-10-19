package org.evsyukov.brook.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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

data class ReaderState(
    val entry: EntryEntity? = null,
    val feedTitle: String? = null,
    val labels: List<String> = emptyList(),
    val allLabels: List<String> = emptyList(),
    /** The text extracted on the device is shown instead of the one of the server. */
    val showFullText: Boolean = false,
    val loadingFullText: Boolean = false,
) {
    val hasFullText: Boolean get() = entry?.fullContent != null
}

/**
 * The entry on screen, one view model per account: [show] switches the entry and marks it read.
 * (Entries of NavDisplay share the store of the activity, so one per entry would pile up.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(session: AccountSession) : ViewModel() {
    private val entries = session.db.entryDao()
    private val tree = session.db.treeDao()
    private val actions = session.actions
    private val extractor = session.fullText
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
        .catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderState())

    fun show(id: Long) {
        if (entryIdFlow.value == id) return
        fullTextChoice.value = null
        loading.value = false
        entryIdFlow.value = id
        viewModelScope.launch { actions.setRead(listOf(id), true) }
    }

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
                entries.setFullContent(id, html)
                if (entryIdFlow.value == id) fullTextChoice.value = true
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
