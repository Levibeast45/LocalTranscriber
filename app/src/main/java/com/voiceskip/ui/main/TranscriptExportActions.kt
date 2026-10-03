// SPDX-License-Identifier: GPL-3.0-or-later
package com.voiceskip.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voiceskip.R
import com.voiceskip.export.TranscriptExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TranscriptExportActions(text: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentText by rememberUpdatedState(text)
    var saving by remember { mutableStateOf(false) }

    fun save(uri: Uri?, format: TranscriptExporter.Format) {
        if (uri == null) return // The user cancelled the system save dialog.
        val snapshot = currentText
        scope.launch {
            saving = true
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Cannot open destination")
                    output.use { TranscriptExporter.write(snapshot, format, it) }
                }
                Toast.makeText(context, R.string.export_saved, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
    }

    val saveTxt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TranscriptExporter.Format.TXT.mimeType)
    ) { save(it, TranscriptExporter.Format.TXT) }
    val saveWord = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TranscriptExporter.Format.DOCX.mimeType)
    ) { save(it, TranscriptExporter.Format.DOCX) }

    fun filename(extension: String): String =
        "transcription-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date())}.$extension"

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", currentText))
                    Toast.makeText(context, R.string.transcript_copied, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.copy_failed, Toast.LENGTH_LONG).show()
                }
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.copy_all)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { saveTxt.launch(filename("txt")) },
                enabled = !saving && text.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.export_txt)) }
            OutlinedButton(
                onClick = { saveWord.launch(filename("docx")) },
                enabled = !saving && text.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.export_word)) }
        }
        if (saving) Text(stringResource(R.string.export_saving))
    }
}
