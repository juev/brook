package org.evsyukov.brook.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.evsyukov.brook.data.Filter
import org.evsyukov.brook.data.Scope
import org.evsyukov.brook.data.db.BrookDatabase

/** What the list shows. Unread by default: what a reader opens the app for. */
data class Selection(val scope: Scope = Scope.All, val filter: Filter = Filter.UNREAD)

/** The tree and the selection of one account. */
class HomeViewModel(db: BrookDatabase) : ViewModel() {
    private val tree = db.treeDao()

    val navTree: StateFlow<NavTree> = combine(
        tree.categories(),
        tree.feeds(),
        tree.labels(),
        tree.unreadByFeed(),
        tree.unreadByLabel(),
    ) { categories, feeds, labels, byFeed, byLabel -> buildNavTree(categories, feeds, labels, byFeed, byLabel, 0) }
        .combine(tree.unreadTotal()) { navTree, total -> navTree.copy(unreadTotal = total) }
        // Removing the account closes the database while the screen may still be listening.
        .catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavTree())

    private val _selection = MutableStateFlow(Selection())
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    fun select(scope: Scope) = _selection.update { it.copy(scope = scope) }
    fun select(filter: Filter) = _selection.update { it.copy(filter = filter) }
}
