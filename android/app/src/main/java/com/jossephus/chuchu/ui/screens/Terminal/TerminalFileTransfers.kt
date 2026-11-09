package com.jossephus.chuchu.ui.screens.Terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.jossephus.chuchu.R
import com.jossephus.chuchu.ui.components.ChuDialog
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.screens.Files.ConnectionTab
import com.jossephus.chuchu.ui.screens.Files.FileBrowserEntry
import com.jossephus.chuchu.ui.screens.Files.FileBrowserScreen
import com.jossephus.chuchu.ui.screens.Files.FileBrowserUiState
import com.jossephus.chuchu.ui.screens.Files.UploadProgress
import com.jossephus.chuchu.ui.screens.Files.formatFileSize
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal class TerminalFileTransfers(
    private val context: Context,
    private val vm: TerminalViewModel,
    private val scope: CoroutineScope,
) {
    private var pendingDownloadBytes by mutableStateOf<ByteArray?>(null)
    private var pendingDownloadName by mutableStateOf("download.bin")
    private var pendingDelete by mutableStateOf<FileBrowserEntry?>(null)
    private var importLauncher: (() -> Unit)? = null
    private var downloadLauncher: ((String) -> Unit)? = null

    val pendingDeleteEntry: FileBrowserEntry?
        get() = pendingDelete

    internal fun attachLaunchers(
        onImport: () -> Unit,
        onDownload: (String) -> Unit,
    ) {
        importLauncher = onImport
        downloadLauncher = onDownload
    }

    fun importFiles() {
        importLauncher?.invoke()
    }

    internal fun onImportResult(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            var success = 0
            var failed = 0
            var lastError: String? = null
            val total = uris.size
            uris.forEachIndexed { index, uri ->
                val fileName =
                    context.contentResolver
                        .query(uri, null, null, null, null)
                        ?.use { cursor ->
                            val idx =
                                cursor.getColumnIndex(
                                    OpenableColumns.DISPLAY_NAME
                                )
                            if (cursor.moveToFirst() && idx >= 0)
                                cursor.getString(idx)
                            else null
                        } ?: uri.lastPathSegment ?: "uploaded_${index}"
                val fileSize =
                    context.contentResolver
                        .query(uri, null, null, null, null)
                        ?.use { cursor ->
                            val idx =
                                cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (cursor.moveToFirst() && idx >= 0)
                                cursor.getLong(idx)
                            else 0L
                        } ?: 0L
                try {
                    val stream =
                        context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Cannot open file")
                    stream.use { input ->
                        vm.beginUpload(fileName)
                        vm.setUploadProgress(
                            UploadProgress(
                                fileName = fileName,
                                bytesWritten = 0,
                                totalBytes = fileSize,
                                fileIndex = index,
                                totalFiles = total,
                            )
                        )
                        val buffer = ByteArray(65536)
                        var bytesWritten = 0L
                        var lastProgressBytes = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            vm.writeUploadChunk(buffer.copyOf(read))
                            bytesWritten += read
                            if (
                                bytesWritten - lastProgressBytes >= 262144 ||
                                    bytesWritten == fileSize
                            ) {
                                lastProgressBytes = bytesWritten
                                vm.setUploadProgress(
                                    UploadProgress(
                                        fileName = fileName,
                                        bytesWritten = bytesWritten,
                                        totalBytes = fileSize,
                                        fileIndex = index,
                                        totalFiles = total,
                                    )
                                )
                            }
                        }
                        vm.finishUpload()
                    }
                    success++
                } catch (e: Exception) {
                    failed++
                    lastError = e.message ?: e.javaClass.simpleName
                    runCatching { vm.finishUpload() }
                } finally {
                    vm.setUploadProgress(null)
                }
            }
            withContext(Dispatchers.Main) {
                val msg =
                    when {
                        failed == 0 ->
                            context.resources.getQuantityString(
                                R.plurals.terminal_uploaded_files,
                                success,
                                success,
                            )
                        lastError != null ->
                            context.getString(
                                R.string.terminal_uploaded_some_failed_reason,
                                success,
                                failed,
                                lastError,
                            )
                        else ->
                            context.getString(
                                R.string.terminal_uploaded_some_failed,
                                success,
                                failed,
                            )
                    }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openFile(tabId: String, entry: FileBrowserEntry) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                    val bytes = vm.readFile(tabId, entry, 32 * 1024 * 1024)
                    val safeName = entry.name.ifBlank { "remote_file" }
                    val outFile = File(context.cacheDir, safeName)
                    outFile.writeBytes(bytes)
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            outFile,
                        )
                    val ext =
                        safeName.substringAfterLast('.', "").lowercase()
                    val mime =
                        MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(ext)
                            ?: "application/octet-stream"
                    val viewIntent =
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                    withContext(Dispatchers.Main) {
                        context.startActivity(
                            Intent.createChooser(
                                viewIntent,
                                context.getString(R.string.terminal_open_with),
                            )
                        )
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                                context,
                                context.getString(
                                    R.string.terminal_open_failed,
                                    it.message.orEmpty(),
                                ),
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
        }
    }

    fun downloadFile(tabId: String, entry: FileBrowserEntry) {
        scope.launch(Dispatchers.IO) {
            runCatching { vm.readFile(tabId, entry, 16 * 1024 * 1024) }
                .onSuccess { bytes ->
                    pendingDownloadBytes = bytes
                    pendingDownloadName = entry.name
                    withContext(Dispatchers.Main) {
                        downloadLauncher?.invoke(entry.name)
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                                context,
                                context.getString(
                                    R.string.terminal_download_failed,
                                    it.message.orEmpty(),
                                ),
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
        }
    }

    internal fun onDownloadDestination(uri: Uri?) {
        val bytes = pendingDownloadBytes
        if (uri == null || bytes == null) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(bytes)
                    } ?: error("Cannot open destination")
                }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                                context,
                                context.getString(
                                    R.string.terminal_downloaded,
                                    pendingDownloadName,
                                ),
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                                context,
                                context.getString(
                                    R.string.terminal_download_failed,
                                    it.message.orEmpty(),
                                ),
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
        }
    }

    fun requestDelete(entry: FileBrowserEntry) {
        pendingDelete = entry
    }

    fun dismissDelete() {
        pendingDelete = null
    }

    fun deleteFile(tabId: String, entry: FileBrowserEntry) {
        pendingDelete = null
        scope.launch(Dispatchers.IO) {
            runCatching { vm.deleteFile(tabId, entry) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                                context,
                                context.getString(
                                    R.string.terminal_deleted_file,
                                    entry.name,
                                ),
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                                context,
                                context.getString(
                                    R.string.terminal_delete_failed,
                                    it.message.orEmpty(),
                                ),
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
        }
    }
}

@Composable
internal fun rememberTerminalFileTransfers(vm: TerminalViewModel): TerminalFileTransfers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfers = remember(vm, context, scope) {
        TerminalFileTransfers(context, vm, scope)
    }
    val importFileLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents()
        ) { uris: List<Uri> ->
            transfers.onImportResult(uris)
        }
    val downloadLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri: Uri? ->
            transfers.onDownloadDestination(uri)
        }
    transfers.attachLaunchers(
        onImport = { importFileLauncher.launch("*/*") },
        onDownload = { name -> downloadLauncher.launch(name) },
    )
    return transfers
}

@Composable
internal fun TerminalFilesPane(
    vm: TerminalViewModel,
    state: FileBrowserUiState,
    transfers: TerminalFileTransfers,
    activeTabId: String?,
    clipboard: ClipboardManager?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val typography = ChuTypography.current
    FileBrowserScreen(
        state = state,
        onGoUp = vm::goUpDirectory,
        onRefresh = vm::refreshFileBrowser,
        onSelectSort = vm::selectFileSort,
        onOpenPath = vm::openPath,
        onBackToTerminal = {
            vm.selectConnectionTab(ConnectionTab.Terminal)
        },
        onCopyPath = { path ->
            clipboard?.setPrimaryClip(
                ClipData.newPlainText(
                    context.getString(R.string.terminal_clipboard_path_label),
                    path,
                )
            )
            Toast.makeText(
                    context,
                    context.getString(R.string.terminal_copied_path),
                    Toast.LENGTH_SHORT,
                )
                .show()
        },
        onImportFile = { transfers.importFiles() },
        onOpenFile = { entry ->
            val tabId = activeTabId ?: return@FileBrowserScreen
            transfers.openFile(tabId, entry)
        },
        onDeleteFile = { entry -> transfers.requestDelete(entry) },
        onDownloadFile = { entry ->
            val tabId = activeTabId ?: return@FileBrowserScreen
            transfers.downloadFile(tabId, entry)
        },
        modifier = modifier,
    )
    transfers.pendingDeleteEntry?.let { entry ->
        ChuDialog(
            title = stringResource(R.string.terminal_delete_file_title, entry.name),
            confirmLabel = stringResource(R.string.dialog_confirm_delete),
            dismissLabel = stringResource(R.string.dialog_dismiss),
            onConfirm = {
                val tabId = activeTabId
                if (tabId == null) {
                    transfers.dismissDelete()
                    return@ChuDialog
                }
                transfers.deleteFile(tabId, entry)
            },
            onDismiss = { transfers.dismissDelete() },
        ) {
            ChuText(
                stringResource(R.string.terminal_delete_warning),
                style = typography.body,
            )
        }
    }
}

@Composable
internal fun UploadProgressDialog(progress: UploadProgress) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    val barWidthDp = 240.dp
    val barHeightDp = 12.dp
    val filledWidth =
        if (progress.totalBytes > 0) {
            barWidthDp * progress.percent.coerceAtMost(100) / 100f
        } else {
            barWidthDp * 0.15f
        }

    Box(
        modifier =
            Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.7f)).clickable(
                enabled = false
            ) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier.background(colors.surfaceVariant)
                    .border(1.dp, colors.border)
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChuText(
                "\u2500\u2500\u25B2\u2500\u2500",
                style = typography.title,
                color = colors.accent,
            )

            ChuText(text = progress.fileName, style = typography.body, color = colors.textPrimary)

            if (progress.totalFiles > 1) {
                ChuText(
                    text =
                        stringResource(
                            R.string.terminal_file_progress,
                            progress.fileIndex + 1,
                            progress.totalFiles,
                        ),
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }

            Box(
                modifier =
                    Modifier.width(barWidthDp)
                        .height(barHeightDp)
                        .background(colors.surface)
                        .border(1.dp, colors.border),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier.width(filledWidth).fillMaxHeight().background(colors.accent)
                )
            }

            ChuText(
                text =
                    if (progress.totalBytes > 0) {
                        val written = formatFileSize(progress.bytesWritten)
                        val total = formatFileSize(progress.totalBytes)
                        "$written / $total  ${progress.percent}%"
                    } else {
                        formatFileSize(progress.bytesWritten)
                    },
                style = typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}
