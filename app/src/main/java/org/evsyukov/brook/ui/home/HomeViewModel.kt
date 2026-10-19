package org.evsyukov.brook.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.ScopeQuery
import org.evsyukov.brook.data.account.AccountSession
import org.evsyukov.brook.data.db.FeedSettingsEntity
import org.evsyukov.brook.data.search.SearchIndex
import org.evsyukov.brook.ui.list.EntryRow
import org.evsyukov.brook.ui.list.toRow

/** What the list shows. Unread by default: what a reader opens the app for. */
data class Selection(val scope: Scope = Scope.All, val filter: Filter = Filter.UNREAD)

/** The entries next to an open one in its list: [newer] above it, [older] below; null at an end. */
data class Neighbours(val newer: Long? = null, val older: Long? = null)

/** The tree, the selection and the list of one account. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(
    session: AccountSession,
    private val onFullTextChanged: () -> Unit,
) : ViewModel() {
    private val tree = session.db.treeDao()
    private val entries = session.db.entryDao()
    private val actions = session.actions
    private val search = SearchIndex(session.db)

    private val treeFromDb: Flow<NavTree> = combine(
        tree.categories(),
        tree.feeds(),
        tree.labels(),
        tree.unreadByFeed(),
        tree.unreadByLabel(),
    ) { categories, feeds, labels, byFeed, byLabel -> buildNavTree(categories, feeds, labels, byFeed, byLabel, 0) }
        .combine(tree.unreadTotal()) { navTree, total -> navTree.copy(unreadTotal = total) }
        // Removing the account closes the database while the screen may still be listening.
        .catch { }

    val navTree: StateFlow<NavTree> = treeFromDb.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavTree())

    private val _selection = MutableStateFlow(Selection())
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    private val _query = MutableStateFlow<String?>(null)

    /** What the search field holds; null while the list is not searched. */
    val query: StateFlow<String?> = _query.asStateFlow()

    init {
        // A category, feed or label renamed or deleted, here or elsewhere, takes the list back to all.
        viewModelScope.launch {
            treeFromDb.collect { navTree -> if (!navTree.has(_selection.value.scope)) select(Scope.All) }
        }
    }

    private var listSelection: Selection? = null
    private var listFlow: Flow<PagingData<EntryRow>> = emptyFlow()
    private var listScope: CoroutineScope? = null

    /**
     * The list of [selection], newest first, straight from Room: it works without the network.
     * Every selection gets a flow of its own, so the screen never shows the rows of the last one
     * under the new; the pages of the last one are dropped.
     */
    fun list(selection: Selection): Flow<PagingData<EntryRow>> {
        if (selection == listSelection) return listFlow
        listScope?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        val q = ScopeQuery(selection.scope)
        listFlow = Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
            entries.list(
                feedId = q.feedId,
                category = q.category,
                label = q.label,
                onlyUnread = selection.filter == Filter.UNREAD,
                onlyStarred = selection.filter == Filter.STARRED,
            )
        }.flow.map { page -> page.map { it.toRow() } }.cachedIn(scope)
        listSelection = selection
        listScope = scope
        return listFlow
    }

    /**
     * Entries of the scope that match what is typed, whatever the filter (R7). A flow of its own, so
     * the list keeps its pages and place while a search runs.
     */
    val searchResults: Flow<PagingData<EntryRow>> = combine(_selection, _query.debounce { if (it.isNullOrBlank()) 0 else 250 }) { selection, query ->
        selection.scope to query?.let(SearchIndex::matchQuery)
    }
        .flatMapLatest { (scope, match) ->
            if (match == null) {
                flowOf(PagingData.empty())
            } else {
                val q = ScopeQuery(scope)
                flow {
                    // Entries whose text changed since the last sync are indexed first.
                    search.indexMissing()
                    emitAll(Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) { entries.search(match, q.feedId, q.category, q.label) }.flow)
                }.map { page -> page.map { it.toRow() } }
            }
        }
        .cachedIn(viewModelScope)

    /** Starts a search with [text], changes it, or with null ends it. */
    fun setQuery(text: String?) = _query.update { text }

    /** Entries opened since the selection changed: read now, yet still in an unread list for moving through it. */
    private val openedHere = MutableStateFlow<Set<Long>>(emptySet())

    /** The entry [id] is on screen. */
    fun opened(id: Long) = openedHere.update { it + id }

    /**
     * The entries above and below [id] in the list it was opened from: the search results while
     * searching, the selection otherwise. Entries read on the way keep their places in an unread list.
     */
    fun neighbours(id: Long): Flow<Neighbours> = combine(
        entries.entry(id).map { it?.sortTime }.distinctUntilChanged(),
        _selection,
        _query.map { it?.let(SearchIndex::matchQuery) },
        openedHere,
    ) { time, selection, match, opened -> NeighbourQuery(time, selection, match, opened) }
        .distinctUntilChanged()
        .flatMapLatest { n ->
            val time = n.time ?: return@flatMapLatest flowOf(Neighbours())
            val q = ScopeQuery(n.selection.scope)
            fun side(older: Boolean): Flow<Long?> = if (n.match != null) {
                entries.searchNeighbour(time, id, older, n.match, q.feedId, q.category, q.label)
            } else {
                entries.neighbour(
                    time, id, older, q.feedId, q.category, q.label,
                    onlyUnread = n.selection.filter == Filter.UNREAD,
                    onlyStarred = n.selection.filter == Filter.STARRED,
                    keep = n.opened.toList(),
                )
            }
            combine(side(older = false), side(older = true)) { newer, older -> Neighbours(newer, older) }
        }
        .catch { }

    private data class NeighbourQuery(val time: Long?, val selection: Selection, val match: String?, val opened: Set<Long>)

    /** Whether the feed in the selection always shows full text; null when no feed is selected. */
    val feedFullText: StateFlow<Boolean?> = _selection
        .flatMapLatest { selection ->
            val feed = selection.scope as? Scope.Feed
            if (feed == null) flowOf(null) else tree.feedSettings(feed.feedId).map { it?.alwaysFullText ?: false }
        }
        .catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Sets the feed of the selection to always show full text, downloaded ahead with the pictures (R9). */
    fun setFeedFullText(on: Boolean) {
        val feed = _selection.value.scope as? Scope.Feed ?: return
        viewModelScope.launch {
            tree.upsertFeedSettings(FeedSettingsEntity(feed.feedId, alwaysFullText = on))
            if (on) onFullTextChanged()
        }
    }

    fun select(scope: Scope) {
        openedHere.value = emptySet()
        _selection.update { it.copy(scope = scope) }
    }

    fun select(filter: Filter) {
        openedHere.value = emptySet()
        _selection.update { it.copy(filter = filter) }
    }

    fun toggleRead(row: EntryRow) {
        viewModelScope.launch { actions.setRead(listOf(row.id), !row.isRead) }
    }

    fun toggleStar(row: EntryRow) {
        viewModelScope.launch { actions.setStarred(row.id, !row.isStarred) }
    }

    /** Marks read rows the reader scrolled past. */
    fun markRead(rows: List<EntryRow>) {
        viewModelScope.launch { actions.setRead(rows.map { it.id }, true) }
    }

    /** Marks read the entries of the list above [row] when [above], below it otherwise. */
    fun markBeside(row: EntryRow, above: Boolean) {
        val selection = _selection.value
        viewModelScope.launch { actions.markReadBeside(selection.scope, selection.filter, row.time, row.id, above) }
    }

    /** Marks read what the list shows now. */
    fun markAllRead() {
        val selection = _selection.value
        viewModelScope.launch { actions.markAllRead(selection.scope, selection.filter) }
    }
}
