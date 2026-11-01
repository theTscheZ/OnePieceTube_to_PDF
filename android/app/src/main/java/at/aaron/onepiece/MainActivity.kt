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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
private val Mint = Color(0xFF77DBC8)
private val Scheme = darkColorScheme(primary = Mint, onPrimary = Color(0xFF00382F), background = Ink,
    surface = Color(0xFF192024), surfaceVariant = Color(0xFF253035), secondary = Color(0xFFD9C3A0))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = Scheme) { ReaderApp(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderApp(activity: MainActivity) {
    val context = LocalContext.current
    val library = remember { Library(context) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var files by remember { mutableStateOf(library.files()) }
    var readerName by rememberSaveable { mutableStateOf<String?>(null) }
    var readerPage by rememberSaveable { mutableIntStateOf(0) }
    var readerFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var showSort by remember { mutableStateOf(false) }
    var downloads by rememberSaveable { mutableStateOf(false) }
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
    fun refresh() { files = library.files() }
    fun message(text: String) { scope.launch { snack.showSnackbar(text) } }
    fun open(file: File) { readerFiles = library.files(); readerPage = library.savedPage(file); readerName = file.name }
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
        if (file != null) ReaderScreen(ordered, file, readerPage,
            onPosition = { f, page -> library.savePage(f, page); readerName = f.name; readerPage = page },
            onClose = { readerName = null; refresh() })
        else LaunchedEffect(Unit) { readerName = null }
        return
    }
    val visible = remember(files, query, sort) {
        val filtered = files.filter { it.name.contains(query, ignoreCase = true) }
        when (sort) {
            1 -> filtered.reversed()
            2 -> filtered.sortedByDescending { it.lastModified() }
            3 -> filtered.sortedByDescending { it.length() }
            else -> filtered
        }
    }
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(title = {
            Column { Text("GRAND LINE", style = MaterialTheme.typography.labelSmall, color = Mint)
                Text("Deine Bibliothek", fontWeight = FontWeight.Bold) }
        }, actions = {
            IconButton(onClick = { importer.launch(arrayOf("application/pdf")) }, enabled = !busy) { Icon(Icons.Rounded.Add, "PDFs importieren") }
            Box {
                IconButton(onClick = { showSort = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sortieren") }
                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                    listOf("Kapitel aufsteigend", "Kapitel absteigend", "Zuletzt hinzugefügt", "Größte zuerst").forEachIndexed { i, name ->
                        DropdownMenuItem(text = { Text(if (sort == i) "✓ $name" else name) }, onClick = { sort = i; showSort = false })
                    }
                }
            }
        }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { downloads = true }, containerColor = Mint,
            icon = { Icon(Icons.Rounded.Download, null) }, text = { Text("Kapitel laden") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Text("${files.size} PDFs · Offline lesen · Rechts → links", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Kapitel oder Dateiname suchen") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true, shape = RoundedCornerShape(18.dp))
            Spacer(Modifier.height(16.dp))
            library.lastFile()?.let { last ->
                Card(onClick = { open(last) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF163D36)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoStories, null, tint = Mint)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("WEITERLESEN", style = MaterialTheme.typography.labelSmall, color = Mint)
                            Text(last.nameWithoutExtension, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("Seite ${library.savedPage(last) + 1}", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Mint)
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
            work?.let { info ->
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
                                val total = info.progress.getInt("total", 0)
                                if (total > 0) LinearProgressIndicator(progress = { info.progress.getInt("done", 0).toFloat() / total }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                        if (active) IconButton(onClick = { workManager.cancelUniqueWork("chapter-download") }) { Icon(Icons.Rounded.Close, "Download abbrechen") }
                    }
                }
            }
            if (visible.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(bottom = 88.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.AutoStories, null, tint = Mint, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(if (files.isEmpty()) "Dein nächstes Kapitel wartet." else "Keine Treffer", style = MaterialTheme.typography.titleMedium)
                    Text(if (files.isEmpty()) "Importiere deine PDFs oder lade Kapitel direkt."
                        else "Versuche einen anderen Suchbegriff.", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                    if (files.isEmpty()) TextButton(onClick = { importer.launch(arrayOf("application/pdf")) }) { Text("PDFs importieren") }
                }
            } else LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 100.dp)) {
                items(visible, key = { it.name }) { file ->
                    PdfCard(file, library.savedPage(file), onOpen = { open(file) }, onMore = { actionFile = file })
                }
            }
        }
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
    if (downloads) DownloadDialog(active, onClose = { downloads = false }, onStart = { mode, start, end ->
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("mode" to mode, "start" to start, "end" to end))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        workManager.enqueueUniqueWork("chapter-download", ExistingWorkPolicy.KEEP, request)
        downloads = false
    })
}

@Composable
private fun PdfCard(file: File, savedPage: Int, onOpen: () -> Unit, onMore: () -> Unit) {
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
    Card(onClick = onOpen, shape = RoundedCornerShape(18.dp)) {
        Box(Modifier.fillMaxWidth().height(174.dp).background(Color(0xFF273338)), contentAlignment = Alignment.Center) {
            cover?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: Icon(Icons.Rounded.PictureAsPdf, null, tint = Mint, modifier = Modifier.size(40.dp))
            if (savedPage > 0) Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(8.dp), color = Ink.copy(alpha = .9f)) {
                Text("Seite ${savedPage + 1}", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.nameWithoutExtension, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(String.format(java.util.Locale.GERMAN, "%.1f MB", file.length() / 1048576.0), style = MaterialTheme.typography.labelSmall, color = Mint)
            }
            IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Aktionen für ${file.name}") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadDialog(active: Boolean, onClose: () -> Unit, onStart: (String, Int, Int) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("new") }
    var start by rememberSaveable { mutableStateOf("420") }
    var end by rememberSaveable { mutableStateOf("430") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val modes = listOf("new" to "Neuestes", "single" to "Ein Kapitel", "range" to "Bereich", "from" to "Ab Kapitel", "all" to "Alle ab 420")
    val startNumber = start.toIntOrNull() ?: 0; val endNumber = end.toIntOrNull() ?: 0
    val valid = when (mode) { "new", "all" -> true; "range" -> startNumber in 1..100000 && endNumber in startNumber..100000; else -> startNumber in 1..100000 }
    AlertDialog(onDismissRequest = onClose, title = { Text("Neue Kapitel") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Direkt auf dein Handy. Vorhandene Kapitel werden übersprungen.")
            modes.forEach { (value, title) ->
                Row(Modifier.fillMaxWidth().clickable { mode = value }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == value, onClick = { mode = value }); Text(title)
                }
            }
            if (mode in listOf("single", "range", "from")) OutlinedTextField(start, { start = it.filter(Char::isDigit).take(6) },
                label = { Text(if (mode == "single") "Kapitel" else "Von Kapitel") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            if (mode == "range") OutlinedTextField(end, { end = it.filter(Char::isDigit).take(6) },
                label = { Text("Bis Kapitel") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                isError = !valid, supportingText = { if (!valid) Text("Das Ende muss mindestens dem Start entsprechen.") })
            if (active) Text("Ein Download läuft bereits. Du kannst ihn in der Bibliothek abbrechen.", color = Mint)
        } },
        confirmButton = { Button(enabled = valid && !active, onClick = {
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            onStart(mode, startNumber, endNumber)
        }) { Text("Herunterladen") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Schließen") } })
}

@Composable
private fun ReaderScreen(files: List<File>, initial: File, initialPage: Int, onPosition: (File, Int) -> Unit, onClose: () -> Unit) {
    var chrome by rememberSaveable { mutableStateOf(false) }
    var title by remember { mutableStateOf(initial.nameWithoutExtension) }
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
                this.onPosition = { file, p, total -> title = file.nameWithoutExtension; page = p; count = total; currentPosition(file, p) }
                onLoading = { loading = it }; onError = { error = it }; onBoundary = { boundary = it }
                view = this; open(files, initial, initialPage)
            }
        }, modifier = Modifier.fillMaxSize())
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (chrome) {
            Row(Modifier.fillMaxWidth().background(Ink.copy(alpha = .94f)).statusBarsPadding().align(Alignment.TopCenter).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zur Bibliothek") }
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    Text("Rechts wischen → nächste Seite", style = MaterialTheme.typography.labelSmall, color = Mint)
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
    }
    if (jump) AlertDialog(onDismissRequest = { jump = false }, title = { Text("Zu Seite springen") },
        text = { OutlinedTextField(pageInput, { pageInput = it.filter(Char::isDigit).take(6) }, label = { Text("Seite (1–$count)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) },
        confirmButton = { TextButton(enabled = (pageInput.toIntOrNull() ?: 0) in 1..count, onClick = {
            view?.jumpToPage(pageInput.toInt() - 1); jump = false
        }) { Text("Öffnen") } }, dismissButton = { TextButton(onClick = { jump = false }) { Text("Abbrechen") } })
    error?.let { text -> AlertDialog(onDismissRequest = { error = null }, title = { Text("PDF nicht lesbar") }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onClose) { Text("Zur Bibliothek") } },
        dismissButton = { TextButton(onClick = { error = null; view?.skipChapter() }) { Text("Nächstes PDF") } }) }
    boundary?.let { text -> AlertDialog(onDismissRequest = { boundary = null }, title = { Text(text) },
        text = { Text("Du kannst weitere Kapitel in der Bibliothek importieren oder herunterladen.") },
        confirmButton = { TextButton(onClick = { boundary = null }) { Text("Weiterlesen") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Bibliothek") } }) }
}
