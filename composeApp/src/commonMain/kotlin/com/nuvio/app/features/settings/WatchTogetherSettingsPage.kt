package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.watchtogether.DEFAULT_WATCH_TOGETHER_PORT
import com.nuvio.app.features.watchtogether.WatchTogetherRepository
import com.nuvio.app.features.watchtogether.parseWatchTogetherInvite
import com.nuvio.app.features.watchtogether.WatchTogetherSettings
import com.nuvio.app.features.watchtogether.WatchTogetherSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_watch_together_address_description
import nuvio.composeapp.generated.resources.settings_watch_together_address_label
import nuvio.composeapp.generated.resources.settings_watch_together_auto_accept
import nuvio.composeapp.generated.resources.settings_watch_together_auto_accept_description
import nuvio.composeapp.generated.resources.settings_watch_together_enable
import nuvio.composeapp.generated.resources.settings_watch_together_enable_description
import nuvio.composeapp.generated.resources.settings_watch_together_join_action
import nuvio.composeapp.generated.resources.settings_watch_together_join_description
import nuvio.composeapp.generated.resources.settings_watch_together_join_label
import nuvio.composeapp.generated.resources.settings_watch_together_name_label
import nuvio.composeapp.generated.resources.settings_watch_together_port_description
import nuvio.composeapp.generated.resources.settings_watch_together_port_label
import nuvio.composeapp.generated.resources.settings_watch_together_reach_note
import nuvio.composeapp.generated.resources.settings_watch_together_section_network
import nuvio.composeapp.generated.resources.settings_watch_together_section_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.watchTogetherSettingsContent(
    isTablet: Boolean,
    settings: WatchTogetherSettings,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_watch_together_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_watch_together_enable),
                    description = stringResource(Res.string.settings_watch_together_enable_description),
                    checked = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = WatchTogetherSettingsRepository::setEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_watch_together_auto_accept),
                    description = stringResource(Res.string.settings_watch_together_auto_accept_description),
                    checked = settings.autoAcceptJoins,
                    enabled = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = WatchTogetherSettingsRepository::setAutoAcceptJoins,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_watch_together_section_network),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                WatchTogetherTextRow(
                    label = stringResource(Res.string.settings_watch_together_name_label),
                    initialValue = settings.displayName,
                    isTablet = isTablet,
                    onCommit = WatchTogetherSettingsRepository::setDisplayName,
                )
                SettingsGroupDivider(isTablet = isTablet)
                WatchTogetherTextRow(
                    label = stringResource(Res.string.settings_watch_together_port_label),
                    description = stringResource(Res.string.settings_watch_together_port_description),
                    initialValue = settings.port.toString(),
                    isTablet = isTablet,
                    onCommit = { raw ->
                        // Anything unusable keeps the current port rather than silently
                        // moving it, which would invalidate the firewall rule already allowed.
                        raw.trim().toIntOrNull()
                            ?.takeIf { it in 1024..65535 }
                            ?.let(WatchTogetherSettingsRepository::setPort)
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                WatchTogetherTextRow(
                    label = stringResource(Res.string.settings_watch_together_address_label),
                    description = stringResource(Res.string.settings_watch_together_address_description),
                    initialValue = settings.publicBaseUrl.orEmpty(),
                    isTablet = isTablet,
                    onCommit = { WatchTogetherSettingsRepository.setPublicBaseUrl(it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                WatchTogetherJoinRow(isTablet = isTablet, enabled = settings.enabled)
                SettingsGroupDivider(isTablet = isTablet)
                WatchTogetherReachNote(isTablet = isTablet)
            }
        }
    }
}

@Composable
private fun WatchTogetherTextRow(
    label: String,
    initialValue: String,
    isTablet: Boolean,
    onCommit: (String) -> Unit,
    description: String? = null,
) {
    // Keyed on nothing: keying on the stored value means the first keystroke that fails
    // validation writes null, the stored value changes, and the field resets under you.
    var draft by rememberSaveable { mutableStateOf(initialValue) }
    val horizontal = if (isTablet) 20.dp else 16.dp

    Column(modifier = Modifier.padding(horizontal = horizontal, vertical = 12.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(draft) }),
            modifier = Modifier
                .fillMaxWidth()
                // Losing focus counts as done: nobody presses Enter in a settings field.
                .onFocusChanged { if (!it.isFocused) onCommit(draft) },
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The honest caveat, in the UI rather than only in the docs: a guest outside the LAN cannot
 * reach the host without help, and the fix is configuration rather than anything in the app.
 */
@Composable
private fun WatchTogetherReachNote(isTablet: Boolean) {
    Column(modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(Res.string.settings_watch_together_reach_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Default port, surfaced for the settings copy. */
internal val watchTogetherDefaultPort: Int = DEFAULT_WATCH_TOGETHER_PORT

/**
 * Pasting the invite, which is the primary way in.
 *
 * The `nuvio://` link is a convenience and its registration by the Windows installer is
 * unverified, so joining must not depend on it.
 */
@Composable
private fun WatchTogetherJoinRow(isTablet: Boolean, enabled: Boolean) {
    var invite by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val horizontal = if (isTablet) 20.dp else 16.dp

    Column(modifier = Modifier.padding(horizontal = horizontal, vertical = 12.dp)) {
        OutlinedTextField(
            value = invite,
            onValueChange = {
                invite = it
                error = null
            },
            label = { Text(stringResource(Res.string.settings_watch_together_join_label)) },
            singleLine = true,
            enabled = enabled,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = error ?: stringResource(Res.string.settings_watch_together_join_description),
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(
            enabled = enabled && invite.isNotBlank(),
            onClick = {
                val parsed = parseWatchTogetherInvite(invite)
                if (parsed == null) {
                    error = invalidInviteMessage
                    return@Button
                }
                WatchTogetherRepository.joinRoom(
                    invite = parsed,
                    displayName = WatchTogetherSettingsRepository.uiState.value.displayName,
                    appVersion = AppVersionConfig.VERSION_NAME,
                    clientId = SyncClientIdentity.currentClientId(),
                    p2pEnabled = P2pSettingsRepository.uiState.value.p2pEnabled,
                )
                invite = ""
            },
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Text(stringResource(Res.string.settings_watch_together_join_action))
        }
    }
}

private const val invalidInviteMessage =
    "That does not look like a Watch Together invite. Paste the whole link the host gave you."
