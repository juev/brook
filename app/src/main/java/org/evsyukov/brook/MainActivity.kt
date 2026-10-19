package org.evsyukov.brook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.evsyukov.brook.ui.BrookApp
import org.evsyukov.brook.ui.theme.BrookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as BrookApplication).container
        setContent {
            BrookTheme {
                BrookApp(container)
            }
        }
    }
}
