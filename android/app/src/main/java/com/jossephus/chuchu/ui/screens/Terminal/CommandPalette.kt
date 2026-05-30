package com.jossephus.chuchu.ui.screens.Terminal

import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.service.terminal.TabSession
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.terminal.AccessoryAction
import com.jossephus.chuchu.ui.terminal.AccessoryKeyItem
import com.jossephus.chuchu.ui.terminal.KeyboardAccessoryBar
import com.jossephus.chuchu.ui.terminal.ModifierState
import com.jossephus.chuchu.ui.terminal.TerminalCanvas
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

@Composable
fun CommandPalette(
    tabs: List<TabSession>,
    activeTabId: String?,
    focusedTabIndex: Int,
    onFocusedTabIndexChange: (Int) -> Unit,
    accessoryItems: List<AccessoryKeyItem>,
    accessoryModifierState: ModifierState,
    onAccessoryAction: (AccessoryAction) -> Unit,
    onChuchuKey: () -> Unit,
    chuchuKeyActive: Boolean,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    useSingleRowAccessoryBar: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onAddTab: () -> Unit,
    onDismiss: () -> Unit,
) {
  val colors = ChuColors.current
  val typography = ChuTypography.current
  val context = LocalContext.current
  val view = LocalView.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val entries = remember(tabs) { tabs.map { it to tabAlias(it) } }
  val maxIndex = (entries.size - 1).coerceAtLeast(0)
  LaunchedEffect(entries.size) { onFocusedTabIndexChange(focusedTabIndex.coerceIn(0, maxIndex)) }
  LaunchedEffect(activeTabId, entries) {
    val activeIndex = entries.indexOfFirst { it.first.id == activeTabId }
    if (activeIndex >= 0) onFocusedTabIndexChange(activeIndex)
  }
  val listState = rememberLazyListState()
  LaunchedEffect(focusedTabIndex, entries.size) {
    if (entries.isNotEmpty()) listState.animateScrollToItem(focusedTabIndex.coerceIn(0, maxIndex))
  }
  LaunchedEffect(Unit) {
    keyboardController?.hide()
    val imm = context.getSystemService(InputMethodManager::class.java)
    imm?.hideSoftInputFromWindow(view.windowToken, 0)
  }

  AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
    Box(
        modifier =
            Modifier.fillMaxSize().background(colors.background).clickable(onClick = onDismiss)
    ) {
      Column(
          modifier =
              Modifier.align(Alignment.TopCenter)
                  .windowInsetsPadding(WindowInsets.safeDrawing)
                  .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                  .fillMaxWidth()
                  .background(colors.surface)
                  .border(1.dp, colors.border.copy(alpha = 0.65f)),
      ) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
          ChuText(
              "tabs",
              style = typography.body,
              color = colors.textSecondary,
              modifier = Modifier.align(Alignment.Center),
          )
          ChuText(
              "${entries.size}/${tabs.size}",
              style = typography.labelSmall,
              color = colors.textMuted,
              modifier = Modifier.align(Alignment.CenterEnd),
          )
        }
        if (entries.isNotEmpty()) {
          LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
            items(entries.size) { idx ->
              val (tab, alias) = entries[idx]
              val isActive = tab.id == activeTabId
              val isFocused = idx == focusedTabIndex
              val state = tab.sessionState.value
              val displayLabel = state.title?.takeIf { it.isNotBlank() } ?: alias
              Row(
                  modifier =
                      Modifier.fillMaxWidth()
                          .background(if (isFocused) colors.surface else Color.Transparent)
                          .border(
                              1.dp,
                              if (isFocused) colors.accent.copy(alpha = 0.7f)
                              else colors.border.copy(alpha = 0.35f),
                          )
                          .clickable { onSelectTab(tab.id) }
                          .padding(horizontal = 12.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Box(modifier = Modifier.width(10.dp)) {
                  if (isFocused) ChuText("▌", style = typography.body, color = colors.accent)
                }
                Box(
                    modifier =
                        Modifier.size(8.dp)
                            .background(if (isActive) colors.success else Color.Transparent)
                )
                Column(modifier = Modifier.weight(1f)) {
                  ChuText(
                      displayLabel,
                      style = typography.body,
                      color = if (isFocused) colors.accent else colors.textPrimary,
                  )
                }
                if (tabs.size > 1) {
                  ChuButton(
                      onClick = { onCloseTab(tab.id) },
                      variant = ChuButtonVariant.Ghost,
                      bracketed = true,
                      borderColor = colors.textMuted,
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                  ) {
                    ChuText("x", style = typography.labelSmall, color = colors.textMuted)
                  }
                }
              }
            }
          }
        }
        val focusedSnapshot =
            entries.getOrNull(focusedTabIndex)?.first?.sessionState?.value?.snapshot
        if (focusedSnapshot != null) {
          Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))
          Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Box(
                modifier =
                    Modifier.fillMaxWidth(0.68f)
                        .height(104.dp)
                        .background(colors.background.copy(alpha = 0.86f))
                        .border(1.dp, colors.border.copy(alpha = 0.8f))
                        .align(Alignment.Center)
            ) {
              TerminalCanvas(
                  snapshot = focusedSnapshot,
                  fontSizeSp = 10.5f,
                  fitSnapshotToCanvas = true,
                  enableGestures = false,
                  cursorColor = Color.Transparent,
                  selectionBackgroundColor = Color.Transparent,
                  selectionForegroundColor = Color.Transparent,
                  onTap = {},
                  onPrimaryClick = { _, _ -> },
                  onScroll = { _, _, _ -> },
                  onZoom = {},
                  onSelectionChanged = { _, _, _, _ -> },
                  modifier = Modifier.fillMaxSize(),
              )
            }
          }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          PaletteHint("↵", "open")
          PaletteHint("esc", "dismiss")
          Spacer(modifier = Modifier.weight(1f))
          ChuButton(
              onClick = onAddTab,
              variant = ChuButtonVariant.Outlined,
              bracketed = true,
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
          ) {
            ChuText("+ new", style = typography.labelSmall, color = colors.accent)
          }
        }
      }
      KeyboardAccessoryBar(
          items = accessoryItems,
          modifierState = accessoryModifierState,
          onAction = onAccessoryAction,
          onSettings = onOpenSettings,
          onChuchuKey = onChuchuKey,
          chuchuKeyActive = chuchuKeyActive,
          onOpenFiles = onOpenFiles,
          useSingleRow = useSingleRowAccessoryBar,
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .windowInsetsPadding(WindowInsets.safeDrawing)
                  .background(colors.background.copy(alpha = 0.92f)),
      )
    }
  }
}

@Composable
private fun PaletteHint(key: String, label: String) {
  val colors = ChuColors.current
  val typography = ChuTypography.current
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(
        modifier = Modifier.border(1.dp, colors.border).padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
      ChuText(key, style = typography.labelSmall, color = colors.textSecondary)
    }
    ChuText(label, style = typography.labelSmall, color = colors.textMuted)
  }
}

private fun tabAlias(tab: TabSession): String {
  val adjectives =
      listOf(
          "amber",
          "brisk",
          "cedar",
          "delta",
          "ember",
          "frost",
          "golden",
          "hazel",
          "indigo",
          "jade",
          "kilo",
          "lunar",
          "mango",
          "nova",
          "onyx",
          "pluto",
      )
  val nouns =
      listOf(
          "otter",
          "falcon",
          "pine",
          "river",
          "comet",
          "harbor",
          "meadow",
          "quartz",
          "signal",
          "orbit",
          "anchor",
          "summit",
          "thunder",
          "voyager",
          "willow",
          "zenith",
      )
  val seed = tab.id.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
  return "${adjectives[seed % adjectives.size]}-${nouns[(seed / adjectives.size) % nouns.size]}"
}
