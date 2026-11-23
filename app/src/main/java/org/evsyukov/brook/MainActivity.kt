package org.evsyukov.brook

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.evsyukov.brook.ui.BrookApp
import org.evsyukov.brook.ui.theme.BrookTheme

class MainActivity : ComponentActivity() {
    private var sharedAddress by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            receiveShare(intent)
        } else {
            // A restored null means the original intent was already handled. Don't parse it again.
            sharedAddress = savedInstanceState.getString(SHARED_ADDRESS)
        }
        val container = (application as BrookApplication).container
        setContent {
            BrookTheme {
                // The window of the platform theme is dark; gaps between panes show this instead.
                Surface(Modifier.fillMaxSize()) {
                    BrookApp(container, sharedAddress, ::consumeSharedAddress)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SHARED_ADDRESS, sharedAddress)
    }

    private fun receiveShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val address = intent.sharedFeedUrl()
        if (address == null) {
            Toast.makeText(this, R.string.feed_share_no_address, Toast.LENGTH_SHORT).show()
        } else {
            sharedAddress = address
        }
    }

    private fun consumeSharedAddress(address: String): Boolean {
        // A callback from a replaced dialog cannot submit or clear the newer share.
        if (sharedAddress != address) return false
        sharedAddress = null
        return true
    }

    private companion object {
        const val SHARED_ADDRESS = "pending-shared-feed-address"
    }
}
