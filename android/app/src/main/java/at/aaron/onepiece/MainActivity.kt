package at.aaron.onepiece

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Ink = Color(0xFF101416)
private val AccentColor: Color @Composable get() = MaterialTheme.colorScheme.primary

class MainActivity : ComponentActivity() {
    var notificationChapter by mutableStateOf<String?>(null)
        private set
    fun consumeChapter() { notificationChapter = null }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        notificationChapter = intent.getStringExtra(AutoUpdates.OPEN_CHAPTER)
        intent.removeExtra(AutoUpdates.OPEN_CHAPTER)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notificationChapter = intent.getStringExtra(AutoUpdates.OPEN_CHAPTER)
        intent.removeExtra(AutoUpdates.OPEN_CHAPTER)
        val settings = AppSettings(this)
        AutoUpdates.schedule(this)
        setContent { ReaderTheme(settings) { ReaderApp(this, settings) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderApp(activity: MainActivity, settings: AppSettings) {
    val context = LocalContext.current
    val library = remember { Library(context) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var files by remember { mutableStateOf(library.files()) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !settings.prefs.getBoolean("notificationPermissionRequested", false)) {
            settings.prefs.edit().putBoolean("notificationPermissionRequested", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    var readerSession by remember { mutableIntStateOf(0) }
    var readerName by rememberSaveable { mutableStateOf<String?>(null) }
    var readerPage by rememberSaveable { mutableIntStateOf(0) }
    var readerFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var showSort by remember { mutableStateOf(false) }
    var downloads by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var selectedNames by rememberSaveable(stateSaver = listSaver<Set<String>, String>(
        save = { it.toList() }, restore = { it.toSet() }
    )) { mutableStateOf(emptySet<String>()) }
    var pendingBulkDelete by remember { mutableStateOf<List<File>?>(null) }
    var actionFile by remember { mutableStateOf<File?>(null) }
    var rename by remember { mutableStateOf<File?>(null) }
    var newName by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    val workManager = remember { WorkManager.getInstance(context) }
    val infos by remember { workManager.getWorkInfosForUniqueWorkFlow("chapter-download") }.collectAsState(initial = emptyList())
    val work = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
    val active = work != null && !work.state.isFinished
    // Report every terminal result once, using Material's accessible snackbar duration.
    // Historical WorkManager records never reappear when the app is opened again.
    val observedDownloads = remember { mutableSetOf<java.util.UUID>() }
    LaunchedEffect(work?.id, work?.state) {
        val info = work ?: return@LaunchedEffect
        if (!info.state.isFinished) {
            observedDownloads.add(info.id)
        } else if (observedDownloads.remove(info.id)) {
            val text = if (info.state == WorkInfo.State.CANCELLED) "Download abgebrochen"
                else info.outputData.getString("message") ?: "Download beendet"
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short)
        }
    }
    fun refresh() { files = library.files(); libraryRevision++; notificationsAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() }
    fun message(text: String) { scope.launch {
        snack.currentSnackbarData?.dismiss()
        snack.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short)
    } }
    fun open(file: File) { readerSession++; readerFiles = library.files(); readerPage = library.savedPage(file); readerName = file.name }
    LaunchedEffect(activity.notificationChapter) {
        val name = activity.notificationChapter ?: return@LaunchedEffect
        val file = library.files().find { it.name == name }
        if (file != null) { showSettings = false; open(file) } else message("Kapitel ist nicht mehr in der Bibliothek")
        activity.consumeChapter()
    }
    fun leaveSelection() { editing = false; selectedNames = emptySet(); pendingBulkDelete = null }
    BackHandler(enabled = editing && readerName == null) { if (!busy) leaveSelection() }
    LaunchedEffect(files) { selectedNames = selectedNames.intersect(files.map { it.name }.toSet()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(work?.state, work?.progress) { refresh() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            busy = true
            var added = 0; val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                uris.forEach { uri -> runCatching { library.importPdf(uri) }.onSuccess { added++ }.onFailure { errors += it.message ?: "Import fehlgeschlagen" } }
            }
            refresh(); busy = false
            message(if (errors.isEmpty()) "$added PDF(s) importiert" else "$added importiert · ${errors.first()}")
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = exportFile
        if (uri != null && file != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("Exportziel nicht verfügbar")
            } }
            message(result.fold({ "PDF exportiert" }, { it.message ?: "Export fehlgeschlagen" }))
        }
    }
    DisposableEffect(readerName != null) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (readerName != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else { controller.show(WindowInsetsCompat.Type.systemBars()); activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()); activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    if (readerName != null) {
        val ordered = if (readerFiles.isEmpty()) files else readerFiles
        val file = ordered.find { it.name == readerName }
        if (file != null) key(readerSession) {
            ReaderScreen(ordered, file, readerPage,
                onPosition = { f, page, total -> library.savePage(f, page, total); readerName = f.name; readerPage = page },
                onClose = { readerName = null; refresh() })
        }
        else LaunchedEffect(Unit) { readerName = null }
        return
    }
    if (showSettings) {
        SettingsScreen(settings, onClose = { showSettings = false }, notificationsAllowed = notificationsAllowed,
            onNotifications = { requestNotifications() }, onAutomaticChange = { enabled, wifi ->
                val wasEnabled = settings.keepCurrent
                if (enabled && !wasEnabled) {
                    files.mapNotNull { ReadingOrder.chapterNumber(it.name) }.maxOrNull()?.let(settings::rememberDownloaded)
                }
                if (settings.wifiOnly != wifi) workManager.cancelAllWorkByTag(AutoUpdates.TAG)
                settings.keepCurrent = enabled; settings.wifiOnly = wifi
                AutoUpdates.schedule(context)
                if (enabled && !wasEnabled && !notificationsAllowed) requestNotifications()
            })
        return
    }
    val visible = remember(files, query, sort, filter, libraryRevision) {
        val filtered = files.filter { it.name.contains(query, ignoreCase = true) && (filter == null || library.status(it).name == filter) }
        when (sort) {
            1 -> filtered.reversed()
            2 -> filtered.sortedByDescending { it.lastModified() }
            3 -> filtered.sortedByDescending { it.length() }
            else -> filtered
        }
    }
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snack) },
        topBar = { if (editing) TopAppBar(
            title = { Text("${selectedNames.size} ausgewählt", maxLines = 1) },
            navigationIcon = { IconButton(onClick = { leaveSelection() }, enabled = !busy) {
                Icon(Icons.Rounded.Close, "Auswahlmodus verlassen")
            } },
            actions = {
                val allSelected = visible.isNotEmpty() && visible.all { it.name in selectedNames }
                IconButton(enabled = !busy && visible.isNotEmpty(), onClick = {
                    val names = visible.map { it.name }.toSet()
                    selectedNames = if (allSelected) selectedNames - names else selectedNames + names
                }) { Icon(if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                    if (allSelected) "Alle angezeigten Kapitel abwählen" else "Alle angezeigten Kapitel auswählen") }
                IconButton(enabled = !busy && selectedNames.isNotEmpty(), onClick = {
                    pendingBulkDelete = files.filter { it.name in selectedNames }
                }) { Icon(Icons.Rounded.DeleteOutline, "Ausgewählte Kapitel löschen") }
            }
        ) else TopAppBar(title = {
            Column { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelSmall, color = AccentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Deine Bibliothek", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }, actions = {
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, "Einstellungen") }
            IconButton(onClick = { importer.launch(arrayOf("application/pdf")) }, enabled = !busy) { Icon(Icons.Rounded.Add, "PDFs importieren") }
            Box {
                IconButton(onClick = { showSort = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sortieren") }
                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                    listOf("Kapitel aufsteigend", "Kapitel absteigend", "Zuletzt hinzugefügt", "Größte zuerst").forEachIndexed { i, name ->
                        DropdownMenuItem(text = { Text(if (sort == i) "✓ $name" else name) }, onClick = { sort = i; showSort = false })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Gelesene Kapitel löschen") }, enabled = files.any { library.status(it) == ReadStatus.READ },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = {
                            showSort = false; pendingBulkDelete = files.filter { library.status(it) == ReadStatus.READ }
                        })
                }
            }
        }) },
        floatingActionButton = { if (!editing) ExtendedFloatingActionButton(onClick = { downloads = true }, containerColor = AccentColor,
            icon = { Icon(Icons.Rounded.Download, null) }, text = { Text("Kapitel laden") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Space.medium).padding(top = Space.medium)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                enabled = !editing && !busy, placeholder = { Text("Kapitel oder Dateiname suchen") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true, shape = RoundedCornerShape(Space.medium))
            Spacer(Modifier.height(16.dp))
            library.lastFile()?.let { last ->
                Card(onClick = { open(last) }, enabled = !editing && !busy, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = Space.medium)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoStories, null, tint = AccentColor)
                        Column(Modifier.weight(1f).padding(horizontal = Space.medium)) {
                            Text("WEITERLESEN", style = MaterialTheme.typography.labelSmall, color = AccentColor)
                            Text(last.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("Seite ${library.savedPage(last) + 1}", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = AccentColor)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, enabled = !editing && !busy, label = { Text("Alle") })
                ReadStatus.entries.forEach { state ->
                    FilterChip(selected = filter == state.name, onClick = { filter = state.name }, enabled = !editing && !busy,
                        label = { Text(state.label) })
                }
            }
            Spacer(Modifier.height(Space.small))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
            work?.takeIf { !it.state.isFinished }?.let { info ->
                Card(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val status = when (info.state) {
                                WorkInfo.State.CANCELLED -> "Download abgebrochen"
                                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Wartet auf Internet …"
                                WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> info.outputData.getString("message") ?: "Download beendet"
                                else -> info.progress.getString("message") ?: "Download startet …"
                            }
                            Text(status, style = MaterialTheme.typography.bodySmall)
                            if (active) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                        if (active) IconButton(onClick = { workManager.cancelUniqueWork("chapter-download") }) { Icon(Icons.Rounded.Close, "Download abbrechen") }
                    }
                }
            }
            if (visible.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(bottom = 88.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.AutoStories, null, tint = AccentColor, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(if (files.isEmpty()) "Dein nächstes Kapitel wartet." else "Keine Treffer", style = MaterialTheme.typography.titleMedium)
                    Text(if (files.isEmpty()) "Importiere deine PDFs oder lade Kapitel direkt."
                        else "Ändere den Filter oder Suchbegriff.", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                    if (files.isEmpty()) TextButton(onClick = { importer.launch(arrayOf("application/pdf")) }) { Text("PDFs importieren") }
                }
            } else ChapterGrid(
                files = visible, selected = selectedNames, editing = editing, enabled = !busy,
                savedPage = { library.savedPage(it) }, readStatus = { library.status(it) }, onOpen = { open(it) }, onMore = { actionFile = it },
                onStartSelection = { editing = true }, onSelection = { selectedNames = it }
            )
        }
    }
    pendingBulkDelete?.let { targets ->
        AlertDialog(onDismissRequest = { if (!busy) pendingBulkDelete = null },
            title = { Text("${targets.size} Kapitel löschen?") },
            text = { Text("Die ausgewählten PDFs werden aus der App entfernt. Ursprünglich importierte Dateien bleiben erhalten.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val failures = withContext(Dispatchers.IO) {
                        targets.filter { file -> runCatching { library.delete(file) }.isFailure }
                    }
                    val removed = targets.size - failures.size
                    refresh(); busy = false; pendingBulkDelete = null
                    if (failures.isEmpty()) leaveSelection()
                    else selectedNames = failures.map { it.name }.toSet()
                    message(if (failures.isEmpty()) "$removed Kapitel gelöscht"
                        else "$removed gelöscht · ${failures.size} konnten nicht gelöscht werden")
                }
            }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingBulkDelete = null }) { Text("Abbrechen") } })
    }
    actionFile?.let { file ->
        AlertDialog(onDismissRequest = { actionFile = null }, title = { Text(file.nameWithoutExtension) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { actionFile = null; open(file) }) { Text("Im Reader öffnen") }
                TextButton(onClick = { rename = file; newName = file.nameWithoutExtension; actionFile = null }) { Text("Umbenennen") }
                TextButton(onClick = {
                    actionFile = null
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, "PDF teilen"))
                    }.onFailure { message(it.message ?: "Teilen fehlgeschlagen") }
                }) { Text("Teilen") }
                TextButton(onClick = {
                    actionFile = null
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, "PDF extern öffnen"))
                    }.onFailure { message("Kein externer PDF-Reader verfügbar. Nutze den internen Reader.") }
                }) { Text("Extern öffnen") }
                TextButton(onClick = { exportFile = file; actionFile = null; exporter.launch(file.name) }) { Text("PDF exportieren") }
                ReadStatus.entries.forEach { state ->
                    TextButton(onClick = { library.markStatus(file, state); actionFile = null; refresh() }) {
                        Text("Als ${state.label.lowercase()} markieren")
                    }
                }
                TextButton(onClick = { delete = file; actionFile = null }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(onClick = { actionFile = null }) { Text("Schließen") } })
    }
    rename?.let { file ->
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("PDF umbenennen") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("Dateiname ohne .pdf") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                runCatching { library.rename(file, newName) }.onSuccess { rename = null; refresh() }.onFailure { message(it.message ?: "Fehler") }
            }) { Text("Speichern") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("Abbrechen") } })
    }
    delete?.let { file ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("PDF löschen?") },
            text = { Text("${file.name} wird aus der App entfernt. Deine ursprünglich importierte Datei bleibt erhalten.") },
            confirmButton = { TextButton(onClick = {
                runCatching { library.delete(file) }.onSuccess { delete = null; refresh() }.onFailure { message(it.message ?: "Fehler") }
            }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Behalten") } })
    }
    if (downloads) DownloadDialog(active, ReadingOrder.nextChapterNumber(files.map { it.name }), onClose = { downloads = false }, onStart = { mode, start, end ->
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("mode" to mode, "start" to start, "end" to end))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        workManager.enqueueUniqueWork("chapter-download", ExistingWorkPolicy.KEEP, request)
        downloads = false
    })
}

@Composable
internal fun PdfCard(file: File, savedPage: Int, readStatus: ReadStatus, onOpen: () -> Unit, onMore: () -> Unit,
    editing: Boolean = false, isSelected: Boolean = false, onLongPress: () -> Unit = {}) {
    val cover by produceState<Bitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) { runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page ->
                    val height = (180f * page.height / page.width).toInt().coerceIn(1, 320)
                    Bitmap.createBitmap(180, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(AndroidColor.WHITE); page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } }
        }.getOrNull() }
    }
    Card(shape = RoundedCornerShape(Space.medium),
        border = if (isSelected) BorderStroke(2.dp, AccentColor) else null,
        modifier = Modifier.semantics(mergeDescendants = true) {
            role = if (editing) Role.Checkbox else Role.Button
            if (editing) selected = isSelected
            onClick(if (editing) "Auswahl ändern" else "Kapitel öffnen") { onOpen(); true }
            onLongClick("Kapitel auswählen") { onLongPress(); true }
        }) {
        Box(Modifier.fillMaxWidth().height(174.dp).background(Color(0xFF273338)), contentAlignment = Alignment.Center) {
            cover?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: Icon(Icons.Rounded.PictureAsPdf, null, tint = AccentColor, modifier = Modifier.size(40.dp))
            if (editing) {
                if (isSelected) Box(Modifier.fillMaxSize().background(AccentColor.copy(alpha = .18f)))
                Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(50),
                    color = Ink.copy(alpha = .9f)) {
                    Icon(if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        if (isSelected) "Ausgewählt" else "Nicht ausgewählt", tint = AccentColor,
                        modifier = Modifier.padding(4.dp).size(24.dp))
                }
            }
            if (savedPage > 0) Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(8.dp), color = Ink.copy(alpha = .9f)) {
                Text(if (readStatus == ReadStatus.READ) "Gelesen" else "Seite ${savedPage + 1}", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = Space.small), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.nameWithoutExtension, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(readStatus.label + " · " + String.format(java.util.Locale.GERMAN, "%.1f MB", file.length() / 1048576.0), style = MaterialTheme.typography.labelSmall, color = AccentColor)
            }
            if (!editing) IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Aktionen für ${file.name}") }
            else Spacer(Modifier.width(48.dp).height(48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadDialog(active: Boolean, defaultChapter: Int, onClose: () -> Unit, onStart: (String, Int, Int) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("new") }
    var start by rememberSaveable { mutableStateOf(defaultChapter.toString()) }
    var rangeStart by rememberSaveable { mutableStateOf("420") }
    var end by rememberSaveable { mutableStateOf("430") }
    val modes = listOf("new" to "Neuestes", "single" to "Ein Kapitel", "range" to "Bereich", "from" to "Ab Kapitel", "all" to "Alle ab 420")
    val displayedStart = if (mode == "range") rangeStart else start
    val startNumber = displayedStart.toIntOrNull() ?: 0; val endNumber = end.toIntOrNull() ?: 0
    val valid = when (mode) { "new", "all" -> true; "range" -> startNumber in 1..100000 && endNumber in startNumber..100000; else -> startNumber in 1..100000 }
    AlertDialog(onDismissRequest = onClose, title = { Text("Neue Kapitel") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            Text("Direkt auf dein Handy. Vorhandene Kapitel werden übersprungen.")
            modes.forEach { (value, title) ->
                Row(Modifier.fillMaxWidth().clickable { mode = value }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == value, onClick = { mode = value }); Text(title)
                }
            }
            if (mode in listOf("single", "range", "from")) OutlinedTextField(displayedStart, {
                val digits = it.filter(Char::isDigit).take(6)
                if (mode == "range") rangeStart = digits else start = digits
            },
                label = { Text(if (mode == "single") "Kapitel" else "Von Kapitel") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            if (mode == "range") OutlinedTextField(end, { end = it.filter(Char::isDigit).take(6) },
                label = { Text("Bis Kapitel") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                isError = !valid, supportingText = { if (!valid) Text("Das Ende muss mindestens dem Start entsprechen.") })
            if (active) Text("Ein Download läuft bereits. Du kannst ihn in der Bibliothek abbrechen.", color = AccentColor)
        } },
        confirmButton = { Button(enabled = valid && !active, onClick = {
            onStart(mode, startNumber, endNumber)
        }) { Text("Herunterladen") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Schließen") } })
}

@Composable
private fun ReaderScreen(files: List<File>, initial: File, initialPage: Int, onPosition: (File, Int, Int) -> Unit, onClose: () -> Unit) {
    val readerSnack = remember { SnackbarHostState() }
    var chrome by rememberSaveable { mutableStateOf(false) }
    fun chapterTitle(file: File) = ReadingOrder.chapterNumber(file.name)?.let { "Kapitel $it" } ?: "Kapitel"
    var title by remember { mutableStateOf(chapterTitle(initial)) }
    var page by remember { mutableIntStateOf(initialPage) }
    var count by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var boundary by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<MangaPageView?>(null) }
    var jump by remember { mutableStateOf(false) }
    var pageInput by remember { mutableStateOf("") }
    val currentPosition by rememberUpdatedState(onPosition)
    BackHandler { if (chrome) chrome = false else onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { context ->
            MangaPageView(context).apply {
                onTap = { chrome = !chrome }
                this.onPosition = { file, p, total -> title = chapterTitle(file); page = p; count = total; currentPosition(file, p, total) }
                onLoading = { loading = it }; onError = { error = it }; onBoundary = { boundary = it }
                view = this; open(files, initial, initialPage)
            }
        }, modifier = Modifier.fillMaxSize())
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (chrome) {
            Box(Modifier.fillMaxWidth().background(Ink.copy(alpha = .94f)).statusBarsPadding()
                .align(Alignment.TopCenter).padding(8.dp).heightIn(min = 48.dp)) {
                Text(title, modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Rounded.Close, "Reader schließen")
                }
            }
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Ink.copy(alpha = .94f)).navigationBarsPadding().padding(16.dp)) {
                Text("Pinch oder Doppeltippen zum Zoomen. Tippen blendet die Leisten aus.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { view?.turn(true) }, enabled = !loading) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Nächste Seite") }
                    TextButton(onClick = { pageInput = (page + 1).toString(); jump = true }, enabled = !loading) { Text("${page + 1} / $count") }
                    IconButton(onClick = { view?.turn(false) }, enabled = !loading) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Vorherige Seite") }
                }
            }
        }
        SnackbarHost(readerSnack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(bottom = if (chrome) 140.dp else 16.dp))
    }
    if (jump) AlertDialog(onDismissRequest = { jump = false }, title = { Text("Zu Seite springen") },
        text = { OutlinedTextField(pageInput, { pageInput = it.filter(Char::isDigit).take(6) }, label = { Text("Seite (1–$count)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) },
        confirmButton = { TextButton(enabled = (pageInput.toIntOrNull() ?: 0) in 1..count, onClick = {
            view?.jumpToPage(pageInput.toInt() - 1); jump = false
        }) { Text("Öffnen") } }, dismissButton = { TextButton(onClick = { jump = false }) { Text("Abbrechen") } })
    LaunchedEffect(error) {
        val text = error ?: return@LaunchedEffect
        chrome = true
        readerSnack.currentSnackbarData?.dismiss()
        val result = readerSnack.showSnackbar(text, actionLabel = "Nächstes PDF", withDismissAction = true,
            duration = SnackbarDuration.Short)
        error = null
        if (result == SnackbarResult.ActionPerformed) view?.skipChapter()
    }
    LaunchedEffect(boundary) {
        val text = boundary ?: return@LaunchedEffect
        readerSnack.currentSnackbarData?.dismiss()
        readerSnack.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short)
        boundary = null
    }
}
