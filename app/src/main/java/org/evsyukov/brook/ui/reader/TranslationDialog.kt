package org.evsyukov.brook.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.evsyukov.brook.R
import java.util.Locale

@Composable
fun TranslationDialog(initialLanguage: String, initialConsent: Boolean, onConfirm: (String, Boolean) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var language by remember { mutableStateOf(initialLanguage.ifBlank { Locale.getDefault().toLanguageTag() }) }
    var consent by remember { mutableStateOf(initialConsent) }
    val valid = language.length <= 64 && Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*").matches(language) && Locale.forLanguageTag(language).language.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translation_options)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.translation_disclosure))
                OutlinedTextField(value=language,onValueChange={ language=it },label={Text(stringResource(R.string.translation_language))},isError=!valid,singleLine=true,modifier=Modifier.fillMaxWidth().testTag("translation-language"))
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=consent,role=Role.Checkbox,onValueChange={consent=it}).testTag("translation-consent"),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=consent,onCheckedChange=null)
                    Text(stringResource(R.string.translation_consent))
                }
                TextButton(onClick=onClear,modifier=Modifier.testTag("translation-clear")) { Text(stringResource(R.string.translation_clear)) }
            }
        },
        confirmButton = { TextButton(onClick={onConfirm(Locale.forLanguageTag(language).toLanguageTag(),consent)},enabled=valid && consent,modifier=Modifier.testTag("translation-confirm")) { Text(stringResource(R.string.translation_allow)) } },
        dismissButton = { TextButton(onClick=onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
