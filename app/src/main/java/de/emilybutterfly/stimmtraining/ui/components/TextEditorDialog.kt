package de.emilybutterfly.stimmtraining.ui.components

import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.emilybutterfly.stimmtraining.data.PastedText

/**
 * Text einfügen oder bearbeiten. „Aus Zwischenablage einfügen“ setzt den kopierten Text an
 * der Cursorposition ein; Einfügen per langem Tippen ins Feld geht natürlich auch.
 */
@Composable
fun TextEditorDialog(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val tooLong = { Toast.makeText(context, "Der Text wurde auf ${"%,d".format(java.util.Locale.GERMANY, PastedText.MAX_CHARS)} Zeichen gekürzt.", Toast.LENGTH_LONG).show() }
    val change = { new: TextFieldValue ->
        if (new.text.length > PastedText.MAX_CHARS) {
            tooLong()
            onValueChange(TextFieldValue(new.text.take(PastedText.MAX_CHARS), new.selection))
        } else {
            onValueChange(new)
        }
    }
    val pasteFromClipboard = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (clip.isNullOrEmpty()) {
            Toast.makeText(context, "In der Zwischenablage ist kein Text.", Toast.LENGTH_SHORT).show()
        } else {
            val start = minOf(value.selection.start, value.selection.end)
            val end = maxOf(value.selection.start, value.selection.end)
            change(TextFieldValue(value.text.replaceRange(start, end, clip), TextRange(start + clip.length)))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            // safeDrawing enthält die Tastatur: das Textfeld wird kleiner statt verdeckt.
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Abbrechen")
                    }
                    Text("Text zum Lesen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onConfirm, enabled = value.text.isNotBlank()) { Text("Übernehmen") }
                }
                HorizontalDivider()
                Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = pasteFromClipboard) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                        Text("Aus Zwischenablage einfügen", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = change,
                        placeholder = { Text("Füge hier den Text ein, den du lesen möchtest – oder tippe ihn.") },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}
