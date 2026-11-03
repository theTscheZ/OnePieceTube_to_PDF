package at.aaron.onepiece

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler

/** Shared 4dp spacing scale; Material components retain their own standard insets/touch targets. */
internal object Space {
    val tiny = 4.dp
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
}

enum class Accent(val label: String, val argb: Long) {
    MINT("Mint", 0xFF77DBC8), BLUE("Blau", 0xFFA6C8FF),
    LAVENDER("Lavendel", 0xFFD0BCFF), PINK("Rosa", 0xFFFFB1C8),
    PEACH("Pfirsich", 0xFFFFB68C), GOLD("Gold", 0xFFEAC970)
}

class AppSettings(context: Context) {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var keepCurrent: Boolean
        get() = prefs.getBoolean("keepCurrent", false)
        set(value) { prefs.edit().putBoolean("keepCurrent", value).apply() }
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(value) { prefs.edit().putBoolean("wifiOnly", value).apply() }
    var accent: Accent
        get() = Accent.entries.find { it.name == prefs.getString("accent", null) } ?: Accent.MINT
        set(value) { prefs.edit().putString("accent", value.name).apply() }
    var downloadedThrough: Int
        get() = prefs.getInt("downloadedThrough", 0)
        private set(value) { prefs.edit().putInt("downloadedThrough", value).commit() }
    fun rememberDownloaded(chapter: Int) = synchronized(Library.LOCK) {
        if (chapter > downloadedThrough) downloadedThrough = chapter
    }
}

@Composable
internal fun ReaderTheme(settings: AppSettings, content: @Composable () -> Unit) {
    var accent by remember { mutableStateOf(settings.accent) }
    DisposableEffect(settings) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "accent") accent = settings.accent
        }
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val primary = Color(accent.argb)
    val container = primary.copy(alpha = .16f).compositeOver(Color(0xFF192024))
    MaterialTheme(colorScheme = darkColorScheme(primary = primary, secondary = primary, tertiary = primary,
        onPrimary = Color(0xFF162024), onSecondary = Color(0xFF162024), onTertiary = Color(0xFF162024),
        primaryContainer = container, onPrimaryContainer = primary,
        secondaryContainer = container, onSecondaryContainer = primary,
        tertiaryContainer = container, onTertiaryContainer = primary,
        background = Color(0xFF101416), surface = Color(0xFF192024),
        surfaceContainerLowest = Color(0xFF101416), surfaceContainerLow = Color(0xFF151B1F),
        surfaceContainer = Color(0xFF192024), surfaceContainerHigh = Color(0xFF20292E),
        surfaceContainerHighest = Color(0xFF253035), surfaceVariant = Color(0xFF253035)), content = content)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(settings: AppSettings, onClose: () -> Unit,
    onAutomaticChange: (Boolean, Boolean) -> Unit, notificationsAllowed: Boolean,
    onNotifications: () -> Unit) {
    var enabled by remember { mutableStateOf(settings.keepCurrent) }
    var wifi by remember { mutableStateOf(settings.wifiOnly) }
    var accent by remember { mutableStateOf(settings.accent) }
    BackHandler(onBack = onClose)
    Scaffold(topBar = { TopAppBar(title = { Text("Einstellungen") }, actions = {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Einstellungen schließen") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(Space.medium), verticalArrangement = Arrangement.spacedBy(Space.large)) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                Text("Neue Kapitel", style = MaterialTheme.typography.titleMedium)
                Card {
                    SettingsToggle("Halte mich aktuell",
                        "Ohne Kapitel wird das neueste geladen, sonst alle nach deiner höchsten Kapitelnummer.", enabled) {
                        enabled = it; onAutomaticChange(enabled, wifi)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = Space.medium))
                    SettingsToggle("Nur im WLAN herunterladen", "Verwendet eine ungetaktete Verbindung.", wifi,
                        enabled = enabled) { wifi = it; onAutomaticChange(enabled, wifi) }
                }
                Text("Prüft ungefähr stündlich. Android kann die Prüfung verzögern. Bereits geladene und später gelöschte Kapitel werden nicht erneut geladen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (enabled && !notificationsAllowed) {
                    Text("Benachrichtigungen sind ausgeschaltet. Automatische Downloads funktionieren trotzdem.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onNotifications) { Text("Benachrichtigungen erlauben") }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                Text("Akzentfarbe", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small),
                    verticalArrangement = Arrangement.spacedBy(Space.small)) {
                    Accent.entries.forEach { choice ->
                        FilterChip(selected = accent == choice, onClick = { accent = choice; settings.accent = choice },
                            label = { Text(choice.label) }, colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(choice.argb), selectedLabelColor = Color(0xFF162024)))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean,
    enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(Space.medium), horizontalArrangement = Arrangement.spacedBy(Space.medium),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.tiny)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
