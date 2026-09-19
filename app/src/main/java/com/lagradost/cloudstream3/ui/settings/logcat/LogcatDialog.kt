package com.lagradost.cloudstream3.ui.settings.logcat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component1
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component2
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment as ComposeAlignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream4.compose.BlackButton
import com.lagradost.cloudstream4.compose.WhiteButton
import com.lagradost.cloudstream4.compose.circle
import com.lagradost.cloudstream4.compose.rounded
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.lang.System.currentTimeMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/** The app may not have permission to clear all Android log buffers. Hide old entries locally too. */
private object LogcatClearState {
    @Volatile var hideBeforeMillis: Long = 0L
}

private suspend fun readLogcatSnapshot(vararg command: String): List<LogcatItem> =
    withContext(Dispatchers.IO) {
        val process = Runtime.getRuntime().exec(command)
        try {
            val items = arrayListOf<LogcatItem>()
            LogcatBinaryParser(process.inputStream).use { parser ->
                while (true) {
                    val item = parser.parseItem() ?: break
                    items.add(item)
                }
            }
            process.waitFor()
            items
        } finally {
            process.destroy()
        }
    }

@Composable
fun LogcatDialog(dismiss: () -> Unit) {
    val list = remember { mutableStateOf(persistentListOf<LogcatItem>()) }
    var isLoading by remember { mutableStateOf(true) }
    var filtered by remember { mutableStateOf(false) } // Raw by default.
    var search by remember { mutableStateOf("") }
    var clearedAfter by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(dismiss) {
        try {
            isLoading = true

            // The binary parser preserves the original CloudStream Logcat format.
            val items = readLogcatSnapshot("logcat", "--binary", "-d")
            list.value = items.filter {
                it.date.toEpochMilliseconds() > LogcatClearState.hideBeforeMillis
            }.toPersistentList()
        } catch (e: Exception) {
            logError(e) // kinda ironic
        } finally {
            isLoading = false
        }
    }
    // After Clear, append new Logcat entries while this dialog stays open.
    // We do not depend on `logcat -c`, which may be denied on some Android devices.
    LaunchedEffect(clearedAfter) {
        val cutoff = clearedAfter ?: return@LaunchedEffect
        while (isActive) {
            delay(2000)
            try {
                val recent = readLogcatSnapshot("logcat", "--binary", "-d", "-t", "1000")
                val existing = list.value.toHashSet()
                val additions = recent.filter {
                    it.date.toEpochMilliseconds() > cutoff && existing.add(it)
                }
                if (additions.isNotEmpty()) {
                    list.value = (list.value + additions).takeLast(2000).toPersistentList()
                }
            } catch (t: Exception) {
                logError(t)
                // Keep the dialog open even when this device restricts logcat access.
                break
            }
        }
    }
    val visibleItems by remember {
        derivedStateOf {
            val source = if (filtered) list.value.filter {
                ProviderLogcatFilter.keep(it.pid, android.os.Process.myPid(), it.tag, it.message)
            } else list.value
            if (search.isBlank()) source else source.filter { item ->
                item.toString().contains(search, ignoreCase = true)
            }
        }
    }
    val (dismissFocus, confirmFocus) = remember { FocusRequester.createRefs() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = dismiss,
        title = {
            Text(text = stringResource(R.string.log_cat))
        },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = ComposeAlignment.CenterVertically
                ) {
                    BasicTextField(
                        value = search,
                        onValueChange = { search = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        decorationBox = { innerTextField ->
                            Box {
                                if (search.isEmpty()) {
                                    Text("Search log…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                                }
                                innerTextField()
                            }
                        }
                    )
                    TextButton(onClick = { filtered = !filtered }) {
                        Text(if (filtered) "Filtered" else "Raw")
                    }
                }
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onBackground,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                // Original plain-text Logcat look: no per-entry chips/colored bars.
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .focusProperties {
                            start = dismissFocus
                            end = confirmFocus
                        }
                ) {
                    items(items = visibleItems) { item ->
                        Text(
                            text = item.toString(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable {
                                    clipboardHelper(txt("Logcat"), ProviderLogcatFilter.forSharing(item.toString()))
                                },
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = ComposeAlignment.CenterVertically
            ) {
            WhiteButton(
                text = stringResource(R.string.sort_save),
                modifier = Modifier.focusRequester(confirmFocus)
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val date = SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault()).format(
                            Date(currentTimeMillis())
                        )
                        var fileStream: OutputStream?
                        try {
                            fileStream = VideoDownloadManager.setupStream(
                                context,
                                "logcat_${date}",
                                null,
                                "txt",
                                false
                            ).openNew()
                            fileStream.bufferedWriter()
                                .use { writer ->
                                    visibleItems.forEach {
                                        writer.write(ProviderLogcatFilter.forSharing(it.toString()))
                                        writer.write("\n\n")
                                    }
                                }
                            dismiss()
                        } catch (t: Throwable) {
                            logError(t)
                            showToast(t.message)
                        }
                        /*try {
                            val date = SimpleDateFormat(
                                "yyyy_MM_dd_HH_mm",
                                Locale.getDefault()
                            ).format(
                                Date(System.currentTimeMillis())
                            )

                            val file = FileHelper.logcat.createFile(context, "logcat_${date}")
                                ?: throw ErrorLoadingException("Unable to create file")
                            val stream = file.openOutputStream(append = false)
                                ?: throw ErrorLoadingException("Unable to create stream")

                            stream.bufferedWriter()
                                .use { writer ->
                                    visibleItems.forEach {
                                        writer.write(ProviderLogcatFilter.forSharing(it.toString()))
                                        writer.write("\n\n")
                                    }
                                }
                            dismiss()
                            showToast(
                                txt(
                                    R.string.logcat_success,
                                    file.absolutePath ?: file.uri.toString()
                                ),
                                Toast.LENGTH_LONG
                            )
                        } catch (t: Throwable) {
                            logError(t)
                            showToast(t.message)
                        }*/
                    }
                }
            }

            WhiteButton(text = stringResource(R.string.sort_copy)) {
                clipboardHelper(
                    txt("Logcat"),
                    ProviderLogcatFilter.forSharing(visibleItems.joinToString(separator = "\n\n") { it.toString() })
                )
            }
            WhiteButton(text = stringResource(R.string.sort_clear)) {
                val cutoff = currentTimeMillis()
                LogcatClearState.hideBeforeMillis = cutoff
                list.value = persistentListOf() // Clear immediately, even if system Logcat cannot be cleared.
                clearedAfter = cutoff // Start collecting new entries without closing the dialog.
                scope.launch(Dispatchers.IO) {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
                        process.waitFor() // Best effort: device may deny clearing the system buffer.
                    } catch (t: Exception) {
                        logError(t)
                    }
                }
            }
            BlackButton(
                text = stringResource(R.string.sort_close),
                onClick = dismiss,
                modifier = Modifier.focusRequester(dismissFocus)
            )
            }
        },
        dismissButton = {},
        properties = DialogProperties(usePlatformDefaultWidth = false)
    )
}


@Composable
fun LogcatItem(item: LogcatItem, modifier: Modifier = Modifier) {
    val color = when (item.level) {
        LogcatLevel.Fatal -> Color.Magenta
        LogcatLevel.Error -> Color.Red
        LogcatLevel.Warning -> Color.Yellow
        LogcatLevel.Info -> Color.White
        LogcatLevel.Debug -> Color.Green
        LogcatLevel.Verbose -> Color.Gray
        null -> Color.Transparent
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        item.level?.identifier?.let { value ->
            Text(
                value,
                modifier = Modifier
                    .padding(2.dp)
                    .rounded()
                    .background(MaterialTheme.colorScheme.onBackground)
                    .padding(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Text(
            item.date.toHumanReadable(),
            modifier = Modifier
                .padding(2.dp)
                .rounded()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            item.tag,
            modifier = Modifier
                .padding(2.dp)
                .rounded()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .fillMaxWidth()
            .rounded()
            .clickable(
                onClick = {
                    clipboardHelper(txt("Logcat"), item.toString())
                })
            .padding(5.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .circle()
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            item.message,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            lineHeight = 15.sp,
        )
    }
}