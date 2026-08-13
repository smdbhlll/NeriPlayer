package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.auth.common.PlatformAccountPurpose
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField

internal data class AccountManagementItem(
    val id: String,
    val name: String,
    val platformUserId: String? = null
)

@Composable
internal fun SettingsMultiAccountDialog(
    title: String,
    accounts: List<AccountManagementItem>,
    primaryAccountId: String?,
    playHistoryAccountId: String?,
    streamingAccountId: String?,
    onSelect: (PlatformAccountPurpose, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    var accountToRename by remember { mutableStateOf<AccountManagementItem?>(null) }
    var accountToDelete by remember { mutableStateOf<AccountManagementItem?>(null) }
    var renameInput by remember { mutableStateOf("") }

    val renameAccount = accountToRename
    val deleteAccount = accountToDelete
    if (deleteAccount != null) {
        MiuixSettingsDialog(
            onDismissRequest = { accountToDelete = null },
            title = { Text(stringResource(R.string.settings_multi_account_delete)) },
            text = {
                Text(stringResource(R.string.settings_multi_account_delete_confirm, deleteAccount.name))
            },
            confirmButton = {
                MiuixSettingsTextButton(onClick = {
                    onDelete(deleteAccount.id)
                    accountToDelete = null
                }) { Text(stringResource(R.string.settings_multi_account_delete)) }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { accountToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    } else if (renameAccount != null) {
        MiuixSettingsDialog(
            onDismissRequest = { accountToRename = null },
            title = { Text(stringResource(R.string.settings_multi_account_rename)) },
            text = {
                MiuixSettingsTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true
                )
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        onRename(renameAccount.id, renameInput)
                        accountToRename = null
                    }
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { accountToRename = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    } else {
        MiuixSettingsDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_multi_account_purpose_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(accounts, key = AccountManagementItem::id) { account ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(account.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = stringResource(
                                            R.string.settings_multi_account_id,
                                            account.platformUserId ?: account.id.take(8)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    renameInput = account.name
                                    accountToRename = account
                                }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = stringResource(R.string.settings_multi_account_rename)
                                    )
                                }
                                IconButton(onClick = { accountToDelete = account }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.settings_multi_account_delete)
                                    )
                                }
                            }
                            AccountPurposeRow(
                                label = stringResource(R.string.settings_multi_account_primary),
                                selected = primaryAccountId == account.id,
                                onClick = { onSelect(PlatformAccountPurpose.PRIMARY, account.id) }
                            )
                            AccountPurposeRow(
                                label = stringResource(R.string.settings_multi_account_history),
                                selected = playHistoryAccountId == account.id,
                                onClick = { onSelect(PlatformAccountPurpose.PLAY_HISTORY, account.id) }
                            )
                            AccountPurposeRow(
                                label = stringResource(R.string.settings_multi_account_streaming),
                                selected = streamingAccountId == account.id,
                                onClick = { onSelect(PlatformAccountPurpose.STREAMING, account.id) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .settingsItemClickable(onClick = onAdd)
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.settings_multi_account_add))
                        }
                    }
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done))
                }
            }
        )
    }
}

@Composable
private fun AccountPurposeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsItemClickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
