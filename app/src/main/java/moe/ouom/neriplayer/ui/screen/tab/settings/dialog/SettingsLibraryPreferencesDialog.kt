package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.screen.tab.LibraryTab
import moe.ouom.neriplayer.ui.screen.tab.libraryTabDisplayOrder
import moe.ouom.neriplayer.ui.screen.tab.normalizeLibraryTabOrder
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.data.platform.youtube.YouTubeFeatureGate
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

@Composable
internal fun SettingsLibraryPreferencesDialog(
    onDismissRequest: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val settingsRepo = AppContainer.settingsRepo
    val isInternational by settingsRepo.internationalizationEnabledFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val youtubeEnabled by settingsRepo.youtubeEnabledFlow
        .collectAsStateWithLifecycle(initialValue = YouTubeFeatureGate.isEnabled())
    val rawDefaultTab by settingsRepo.libraryDefaultTabFlow
        .collectAsStateWithLifecycle(initialValue = LibraryTab.LOCAL.name)
    val rawOrder by settingsRepo.libraryTabOrderFlow
        .collectAsStateWithLifecycle(initialValue = "LOCAL,FAVORITE,NETEASE,YTMUSIC,BILI")
    val visibleTabs = remember(isInternational, youtubeEnabled) {
        libraryTabDisplayOrder(isInternational, youtubeEnabled)
    }
    val defaultTab = remember(rawDefaultTab, visibleTabs) {
        val parsed = runCatching { LibraryTab.valueOf(rawDefaultTab) }.getOrNull()
        parsed?.takeIf { it in visibleTabs } ?: visibleTabs.firstOrNull() ?: LibraryTab.LOCAL
    }
    val orderedTabs = remember(rawOrder, visibleTabs) {
        normalizeLibraryTabOrder(rawOrder).filter { it in visibleTabs }.toMutableList()
    }
    val reorderableTabs = remember { mutableStateListOf<LibraryTab>() }
    LaunchedEffect(orderedTabs) {
        reorderableTabs.clear()
        reorderableTabs.addAll(orderedTabs)
    }
    val reorderState = rememberReorderableLazyListState(
        listState = rememberLazyListState(),
        onMove = { from: ItemPosition, to: ItemPosition ->
            val fromTab = from.key as? LibraryTab ?: return@rememberReorderableLazyListState
            val toTab = to.key as? LibraryTab ?: return@rememberReorderableLazyListState
            val fromIndex = reorderableTabs.indexOf(fromTab)
            val toIndex = reorderableTabs.indexOf(toTab)
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                reorderableTabs.add(toIndex, reorderableTabs.removeAt(fromIndex))
                scope.launch {
                    settingsRepo.setLibraryTabOrder(reorderableTabs.joinToString(",") { it.name })
                }
            }
        }
    )

    MiuixSettingsDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.settings_library_home)) },
        text = {
            Column {
                MiuixSettingsSectionIntro(
                    title = stringResource(R.string.settings_library_default_tab),
                    description = stringResource(R.string.settings_library_default_tab_desc)
                )
                visibleTabs.forEach { tab ->
                    MiuixSettingsChoiceRow(
                        title = stringResource(tab.labelResId),
                        selected = tab == defaultTab,
                        onClick = { scope.launch { settingsRepo.setLibraryDefaultTab(tab.name) } }
                    )
                }
                MiuixSettingsSectionIntro(
                    modifier = Modifier.padding(top = 12.dp),
                    title = stringResource(R.string.settings_library_tab_order),
                    description = stringResource(R.string.settings_library_tab_order_desc)
                )
                LazyColumn(
                    state = reorderState.listState,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .reorderable(reorderState),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(reorderableTabs, key = { it }) { tab ->
                        ReorderableItem(state = reorderState, key = tab) {
                            MiuixSettingsChoiceRow(
                                title = stringResource(tab.labelResId),
                                selected = false,
                                onClick = {},
                                modifier = Modifier.detectReorder(reorderState),
                                subtitle = stringResource(R.string.settings_library_drag_handle)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
