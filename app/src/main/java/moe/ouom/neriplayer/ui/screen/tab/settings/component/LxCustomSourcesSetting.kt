package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.lx.LxSourceRuntimeStatus
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.config.LimitedTextReader
import moe.ouom.neriplayer.data.lx.LxCustomSource
import moe.ouom.neriplayer.data.lx.LxCustomSourceParser
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun LxCustomSourcesSetting(
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = AppContainer.lxCustomSourceRepository
    val manager = AppContainer.lxMusicSourceManager
    val sources by repository.sources.collectAsState()
    val statuses by manager.statuses.collectAsState()
    var pendingDelete by remember { mutableStateOf<LxCustomSource?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val script = withContext(Dispatchers.IO) {
                    LimitedTextReader.readUtf8(
                        context = context,
                        uri = uri,
                        maxBytes = LxCustomSourceParser.MAX_SCRIPT_LENGTH.toLong()
                    )
                }
                repository.importScript(script)
            }.onSuccess { source ->
                AppFeedback.show(context, context.getString(R.string.settings_lx_source_imported, source.name))
            }.onFailure { error ->
                AppFeedback.show(
                    context,
                    context.getString(R.string.settings_lx_source_import_failed, error.message.orEmpty())
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        AutoSettingSpecSwitchItem(
            setting = AutoSettingsSchema.playback.lxSourcesOnly,
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Science,
                    contentDescription = stringResource(R.string.settings_lx_sources_only),
                    modifier = Modifier.size(24.dp)
                )
            },
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )

        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        ListItem(
            modifier = Modifier.clickable {
                importLauncher.launch(
                    arrayOf("application/javascript", "text/javascript", "text/plain", "application/octet-stream")
                )
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_lx_source_import)) },
            supportingContent = {
                Text(
                    if (sources.isEmpty()) {
                        stringResource(R.string.settings_lx_source_import_desc)
                    } else {
                        stringResource(R.string.settings_lx_source_count, sources.size)
                    }
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        sources.forEachIndexed { index, source ->
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            LxSourceRow(
                source = source,
                index = index,
                sourceCount = sources.size,
                status = statuses[source.id],
                onEnabledChange = { enabled ->
                    scope.launch {
                        repository.setEnabled(source.id, enabled)
                        manager.invalidate(source.id)
                    }
                },
                onMove = { offset ->
                    scope.launch { repository.move(source.id, offset) }
                },
                onDelete = { pendingDelete = source }
            )
        }
    }

    pendingDelete?.let { source ->
        MiuixSettingsDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.settings_lx_source_delete_title)) },
            text = { Text(stringResource(R.string.settings_lx_source_delete_message, source.name)) },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            repository.remove(source.id)
                            manager.invalidate(source.id)
                        }
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun LxSourceRow(
    source: LxCustomSource,
    index: Int,
    sourceCount: Int,
    status: LxSourceRuntimeStatus?,
    onEnabledChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit
) {
    val subtitle = buildList {
        source.version.takeIf(String::isNotBlank)?.let { add("v$it") }
        source.author.takeIf(String::isNotBlank)?.let(::add)
        add(lxStatusLabel(status))
    }.joinToString(" | ")

    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable { onEnabledChange(!source.enabled) },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            },
            headlineContent = { Text(source.name) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (source.description.isNotBlank()) {
                        Text(source.description, maxLines = 2)
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            },
            trailingContent = {
                MiuixSettingsSwitch(
                    checked = source.enabled,
                    onCheckedChange = onEnabledChange
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                Icon(
                    imageVector = Icons.Outlined.ArrowUpward,
                    contentDescription = stringResource(R.string.settings_lx_source_move_up)
                )
            }
            IconButton(onClick = { onMove(1) }, enabled = index < sourceCount - 1) {
                Icon(
                    imageVector = Icons.Outlined.ArrowDownward,
                    contentDescription = stringResource(R.string.settings_lx_source_move_down)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun lxStatusLabel(status: LxSourceRuntimeStatus?): String = when (status) {
    null,
    LxSourceRuntimeStatus.Idle -> stringResource(R.string.settings_lx_source_status_not_tested)
    LxSourceRuntimeStatus.Initializing -> stringResource(R.string.settings_lx_source_status_initializing)
    is LxSourceRuntimeStatus.Ready -> stringResource(R.string.settings_lx_source_status_ready)
    is LxSourceRuntimeStatus.Failed -> stringResource(
        R.string.settings_lx_source_status_failed,
        status.message
    )
}
