package io.github.techpool.textkeep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(8.dp), content = content) }
    }
}

@Composable fun SettingsLink(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun SettingsToggle(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, null, enabled = enabled)
    }
}

@Composable fun SettingsScreen(appearance: Appearance, onAppearance: (Appearance) -> Unit, sync: Boolean, pending: Int, onSync: () -> Unit, isDefault: Boolean, onDefault: () -> Unit, onPermissions: () -> Unit, onNotifications: () -> Unit, otp: Boolean, onOtp: (Boolean) -> Unit, lifetime: Int, onLifetime: (Int) -> Unit, onAccess: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        SettingsSection("Appearance") {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.primary); Text("Make it yours", style = MaterialTheme.typography.titleMedium) }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Appearance.entries.forEach { mode -> FilterChip(appearance == mode, onClick = { onAppearance(mode) }, label = { Text(mode.label) }, leadingIcon = { Icon(when(mode) { Appearance.SYSTEM -> Icons.Outlined.Contrast; Appearance.LIGHT -> Icons.Outlined.LightMode; Appearance.DARK -> Icons.Outlined.DarkMode }, null, Modifier.size(16.dp)) }) }
            }
            Text("System follows your phone’s light or dark theme.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSection("Your archive") {
            SettingsLink("Cloud sync", if(sync) { if(pending == 0) "Enabled · no pending changes" else "Enabled · $pending pending changes" } else "Paused · your messages stay on this phone", Icons.Outlined.CloudSync, onSync)
            SettingsLink("Connected apps", "Manage dashboard and read-only MCP access", Icons.Outlined.Link, onAccess)
        }
        SettingsSection("Messaging") {
            SettingsLink("Default SMS app", if(isDefault) "TextKeep is your default" else "Choose TextKeep for SMS and MMS", Icons.Outlined.ChatBubbleOutline, onDefault)
            SettingsLink("Messaging permissions", "SMS, MMS, notifications and SIM selection", Icons.Outlined.AdminPanelSettings, onPermissions)
            SettingsLink("Notification settings", "Control alerts and lock-screen visibility", Icons.Outlined.NotificationsNone, onNotifications)
        }
        SettingsSection("Verification codes") {
            SettingsToggle("Copy code notifications", "Recognize codes on this phone, even offline", otp, onChange = onOtp)
            Text("Notification display time", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 300).forEach { seconds -> FilterChip(lifetime == seconds, { onLifetime(seconds) }, label = { Text(if(seconds < 60) "30 sec" else "${seconds / 60} min") }) }
            }
            Text("Display time does not change a code’s validity.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TextKeep", style = MaterialTheme.typography.titleMedium)
            Text("${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Local first. SMS, MMS and code copying work offline. Cloud sync is optional. RCS and private messengers are outside TextKeep’s scope.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable fun SyncStatusCard(pending: Int, failed: Int, state: String, running: Boolean, lastUpload: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.CloudSync, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(if(pending == 0) "No pending changes" else "$pending pending", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if(running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Last upload · $lastUpload", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if(failed > 0) Text("$failed rejected · included in pending", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}
