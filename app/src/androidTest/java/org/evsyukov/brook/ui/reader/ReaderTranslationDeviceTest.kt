package org.evsyukov.brook.ui.reader

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.evsyukov.brook.R
import org.evsyukov.brook.data.db.EntryEntity
import org.evsyukov.brook.data.remote.TranslationReply
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs in canonical CI at both the compact and genuinely wide emulator viewport. */
@RunWith(AndroidJUnit4::class)
class ReaderTranslationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun overflowAndConsentRemainReachableAtThisViewport() {
        var actions = 0
        val entry = EntryEntity(1,"feed/1","Fixture article","","https://example.org",1,"<p>Fixture</p>","",true,false,1)
        compose.setContent {
            var translated by remember { mutableStateOf(false) }
            var options by remember { mutableStateOf(false) }
            MaterialTheme {
                Column {
                    ReaderTopBar(
                        state=ReaderState(entry=entry,translation=if(translated) TranslationReply("1","ru",title="Перевод",content="Текст") else null),
                        onBack=if(compose.activity.resources.configuration.screenWidthDp < 1000) ({}) else null,
                        onLabels={},onOpenOriginal={},
                        onTranslate={translated=!translated;actions++},
                        onTranslationSettings={options=true},
                    )
                }
                if(options) TranslationDialog("ru",false,{ language, consent -> assertThat(language).isEqualTo("ru");assertThat(consent).isTrue();options=false;actions++ },{options=false},{options=false})
            }
        }
        compose.onNodeWithTag(ReaderTags.MORE).performClick()
        compose.onNodeWithTag(ReaderTags.LABELS).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTags.OPEN).assertIsDisplayed()
        compose.onNodeWithTag("reader-translate").assertIsDisplayed().performClick()
        compose.onNodeWithTag(ReaderTags.MORE).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.show_untranslated)).assertIsDisplayed()
        compose.onNodeWithTag("reader-translation-options").performClick()
        compose.onNodeWithTag("translation-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("translation-language").assertIsDisplayed()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val wide=compose.activity.resources.configuration.screenWidthDp >= 1000
        val name=if(wide) "reader-translation-wide" else "reader-translation-compact"
        val directory=File(compose.activity.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
        val image=instrumentation.uiAutomation.takeScreenshot()
        try { File(directory,"$name.png").outputStream().use { assertThat(image.compress(Bitmap.CompressFormat.PNG,100,it)).isTrue() } } finally { image.recycle() }
        compose.onNodeWithTag("translation-consent").performClick()
        compose.onNodeWithTag("translation-confirm").performClick()
        compose.runOnIdle { assertThat(actions).isEqualTo(2) }
    }
}
