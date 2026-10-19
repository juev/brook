package org.evsyukov.brook.ui.manage

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
import kotlinx.coroutines.launch
import org.evsyukov.brook.data.EditRefused
import org.evsyukov.brook.data.TreeEditor
import org.evsyukov.brook.data.account.AccountSession
import org.evsyukov.brook.data.remote.GReaderException
import org.evsyukov.brook.ui.home.NavTree
import org.evsyukov.brook.ui.home.buildNavTree
import java.io.IOException

/** What became of the last change; the screen shows it once. */
sealed interface ManageMessage {
    data object Subscribed : ManageMessage
    data object Imported : ManageMessage
    data object Exported : ManageMessage
    data object NoNetwork : ManageMessage
    data object FileFailed : ManageMessage
    data class NameTaken(val name: String) : ManageMessage
    data class NoFeed(val detail: String?) : ManageMessage
    data class Refused(val detail: String) : ManageMessage
}

data class ManageState(val busy: Boolean = false, val message: ManageMessage? = null)

/**
 * Subscriptions, categories and labels of one account (R6). Every change goes to the server at
 * once and is followed by [sync], which brings the tree it leaves; without the network it fails
 * and nothing changes.
 */
class ManageViewModel(
    session: AccountSession,
    private val sync: suspend () -> Unit,
) : ViewModel() {
    private val editor: TreeEditor = session.editor
    private val tree = session.db.treeDao()

    val navTree: StateFlow<NavTree> = combine(tree.categories(), tree.feeds(), tree.labels()) { categories, feeds, labels ->
        buildNavTree(categories, feeds, labels, emptyList(), emptyList(), 0)
    }
        .catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavTree())

    private val _state = MutableStateFlow(ManageState())
    val state: StateFlow<ManageState> = _state.asStateFlow()

    fun subscribe(url: String) = change(ManageMessage.Subscribed) { subscribe(url) }
    fun unsubscribe(feedId: String) = change { unsubscribe(feedId) }
    fun renameFeed(feedId: String, title: String) = change { renameFeed(feedId, title) }
    fun moveFeed(feedId: String, category: String) = change { moveFeed(feedId, category) }
    fun renameCategory(name: String, newName: String) = change { renameCategory(name, newName) }
    fun deleteCategory(name: String) = change { deleteCategory(name) }
    fun renameLabel(name: String, newName: String) = change { renameLabel(name, newName) }
    fun deleteLabel(name: String) = change { deleteLabel(name) }

    /** Imports the document [read] gives, a file the person chose. */
    fun importOpml(read: suspend () -> String) {
        launch {
            val opml = try {
                read()
            } catch (e: IOException) {
                return@launch ManageMessage.FileFailed
            }
            editor.importOpml(opml)
            syncQuietly()
            ManageMessage.Imported
        }
    }

    /** Hands the subscriptions as OPML to [write], which saves them to the file the person chose. */
    fun exportOpml(write: suspend (String) -> Unit) {
        launch {
            val opml = editor.exportOpml()
            try {
                write(opml)
            } catch (e: IOException) {
                return@launch ManageMessage.FileFailed
            }
            ManageMessage.Exported
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun change(done: ManageMessage? = null, block: suspend TreeEditor.() -> Unit) {
        launch {
            editor.block()
            syncQuietly()
            done
        }
    }

    // The change is on the server already; a failed sync only delays the tree, the next one brings it.
    private suspend fun syncQuietly() {
        try {
            sync()
        } catch (e: IOException) {
            // Nothing to tell: the change itself went through.
        }
    }

    private fun launch(block: suspend () -> ManageMessage?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val message = try {
                block()
            } catch (e: EditRefused.NameTaken) {
                ManageMessage.NameTaken(e.name)
            } catch (e: EditRefused.NoFeed) {
                ManageMessage.NoFeed(e.detail)
            } catch (e: GReaderException.BadRequest) {
                ManageMessage.Refused(e.body)
            } catch (e: GReaderException) {
                ManageMessage.Refused(e.message.orEmpty())
            } catch (e: IOException) {
                ManageMessage.NoNetwork
            }
            _state.update { ManageState(busy = false, message = message ?: it.message) }
        }
    }
}
