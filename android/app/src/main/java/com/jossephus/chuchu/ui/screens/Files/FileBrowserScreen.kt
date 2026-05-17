package com.jossephus.chuchu.ui.screens.Files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    state: FileBrowserUiState,
    onGoUp: () -> Unit,
    onRefresh: () -> Unit,
    onSelectSort: (FileSort) -> Unit,
    onOpenPath: (String) -> Unit,
    onBackToTerminal: () -> Unit,
    onCopyPath: (String) -> Unit,
    onImportFile: () -> Unit,
    onOpenFile: (FileBrowserEntry) -> Unit,
    onDownloadFile: (FileBrowserEntry) -> Unit,
    onDeleteFile: (FileBrowserEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    var searchQuery by remember { mutableStateOf("") }
    var showSortDropdown by remember { mutableStateOf(false) }
    var optionsEntryPath by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (state.currentPath == "/" || state.currentPath == state.resolvedHomePath) {
            onBackToTerminal()
        } else {
            onGoUp()
        }
    }

    LaunchedEffect(state.currentPath) { searchQuery = "" }

    val filteredEntries =
        remember(state.entries, searchQuery) {
            if (searchQuery.isBlank()) state.entries
            else state.entries.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChuButton(
                onClick = onBackToTerminal,
                variant = ChuButtonVariant.Ghost,
                bracketed = false,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            ) {
                TerminalIcon(color = colors.success, modifier = Modifier.size(20.dp))
            }
            ChuTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = "",
                placeholder = "filter...",
                singleLine = true,
                showLabel = false,
                autoFocus = false,
                modifier = Modifier.weight(1f),
            )
            ChuButton(
                onClick = onRefresh,
                variant = ChuButtonVariant.Ghost,
                bracketed = false,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            ) {
                RefreshIcon(color = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading -> ChuText("Loading...", style = typography.body)
                state.error != null ->
                    ChuText(state.error, style = typography.body, color = colors.error)
                state.entries.isEmpty() ->
                    ChuText("Empty directory", style = typography.body, color = colors.textMuted)
                filteredEntries.isEmpty() ->
                    ChuText("No matches", style = typography.body, color = colors.textMuted)
                else ->
                    key(state.currentPath) {
                        val lazyState = rememberLazyListState()
                        LazyColumn(
                            state = lazyState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(filteredEntries, key = { it.path }) { entry ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .defaultMinSize(minHeight = 44.dp)
                                                .border(1.dp, colors.border.copy(alpha = 0.5f))
                                                .combinedClickable(
                                                    onClick = {
                                                        optionsEntryPath = null
                                                        when (entry.type) {
                                                            FileEntryType.Directory,
                                                            FileEntryType.Symlink ->
                                                                onOpenPath(entry.path)
                                                            else -> Unit
                                                        }
                                                    },
                                                    onLongClick = {
                                                        optionsEntryPath =
                                                            if (optionsEntryPath == entry.path) null
                                                            else entry.path
                                                    },
                                                )
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        FileEntryIcon(
                                            entry = entry,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            ChuText(entry.name, style = typography.body)
                                            val meta =
                                                listOfNotNull(
                                                        entry.sizeBytes?.let { formatFileSize(it) },
                                                        entry.modifiedAtText,
                                                    )
                                                    .joinToString("  ")
                                            if (meta.isNotBlank()) {
                                                ChuText(
                                                    meta,
                                                    style = typography.labelSmall,
                                                    color = colors.textMuted,
                                                )
                                            }
                                        }
                                        ChuButton(
                                            onClick = { onCopyPath(entry.path) },
                                            variant = ChuButtonVariant.Ghost,
                                            bracketed = false,
                                            contentPadding =
                                                PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                        ) {
                                            CopyIcon(
                                                color = colors.textMuted,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                    if (optionsEntryPath == entry.path) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            Column(
                                                modifier =
                                                    Modifier.background(
                                                            colors.background,
                                                            RectangleShape,
                                                        )
                                                        .border(1.dp, colors.border, RectangleShape)
                                                        .padding(4.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                if (entry.type != FileEntryType.Directory) {
                                                    ChuButton(
                                                        onClick = {
                                                            onOpenFile(entry)
                                                            optionsEntryPath = null
                                                        },
                                                        variant = ChuButtonVariant.Ghost,
                                                        bracketed = true,
                                                        contentPadding =
                                                            PaddingValues(
                                                                horizontal = 10.dp,
                                                                vertical = 6.dp,
                                                            ),
                                                    ) {
                                                        ChuText(
                                                            "open",
                                                            style = typography.label,
                                                            color = colors.textMuted,
                                                        )
                                                    }
                                                    ChuButton(
                                                        onClick = {
                                                            onDownloadFile(entry)
                                                            optionsEntryPath = null
                                                        },
                                                        variant = ChuButtonVariant.Ghost,
                                                        bracketed = true,
                                                        contentPadding =
                                                            PaddingValues(
                                                                horizontal = 10.dp,
                                                                vertical = 6.dp,
                                                            ),
                                                    ) {
                                                        ChuText(
                                                            "download",
                                                            style = typography.label,
                                                            color = colors.textMuted,
                                                        )
                                                    }
                                                }
                                                ChuButton(
                                                    onClick = {
                                                        onDeleteFile(entry)
                                                        optionsEntryPath = null
                                                    },
                                                    variant = ChuButtonVariant.Ghost,
                                                    bracketed = true,
                                                    contentPadding =
                                                        PaddingValues(
                                                            horizontal = 10.dp,
                                                            vertical = 6.dp,
                                                        ),
                                                ) {
                                                    ChuText(
                                                        "delete",
                                                        style = typography.label,
                                                        color = colors.error,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChuButton(
                    onClick = {
                        if (
                            state.currentPath == "/" || state.currentPath == state.resolvedHomePath
                        ) {
                            onBackToTerminal()
                        } else {
                            onGoUp()
                        }
                    },
                    variant = ChuButtonVariant.Ghost,
                    bracketed = true,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    ChuText("\u2190", style = typography.label, color = colors.textSecondary)
                }

                Box {
                    ChuButton(
                        onClick = { showSortDropdown = true },
                        variant = ChuButtonVariant.Ghost,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        ChuText("\u21C5", style = typography.label, color = colors.textSecondary)
                    }
                    if (showSortDropdown) {
                        Popup(
                            onDismissRequest = { showSortDropdown = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Column(
                                modifier =
                                    Modifier.background(colors.background, RectangleShape)
                                        .border(1.dp, colors.border, RectangleShape)
                                        .padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                FileSort.entries.forEach { sort ->
                                    val isSelected = sort == state.sort
                                    ChuButton(
                                        onClick = {
                                            onSelectSort(sort)
                                            showSortDropdown = false
                                        },
                                        variant =
                                            if (isSelected) ChuButtonVariant.Filled
                                            else ChuButtonVariant.Ghost,
                                        bracketed = true,
                                        contentPadding =
                                            PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        ChuText(
                                            sort.name.lowercase(),
                                            style = typography.label,
                                            color =
                                                if (isSelected) colors.onAccent
                                                else colors.textMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                ChuButton(
                    onClick = onImportFile,
                    variant = ChuButtonVariant.Ghost,
                    bracketed = true,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    ChuText("\u2191", style = typography.label, color = colors.textSecondary)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            ChuText(state.currentPath, style = typography.labelSmall, color = colors.textMuted)
        }
    }
}
