package dev.personal.backup

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val Pine = Color(0xFF196B52)
private val Ink = Color(0xFF182D25)
private val Paper = Color(0xFFF6F7F3)
private val Muted = Color(0xFF64746C)
private val scheme = lightColorScheme(primary = Pine, onPrimary = Color.White, primaryContainer = Color(0xFFDCEEE2), secondary = Muted,
    surface = Paper, background = Paper, onSurface = Ink, onBackground = Ink, surfaceVariant = Color(0xFFE7ECE5), outlineVariant = Color(0xFFD8E0D6))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = scheme) { BackupScreen() } }
    }
}
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val dao = BackupDatabase.get(app).media()
    val totals = dao.totals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Totals(0, 0, 0, 0, 0, 0))
    val missingFailures = dao.missingFailures().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val filter = MutableStateFlow("all")
    val media = filter.flatMapLatest { f -> Pager(PagingConfig(pageSize = 50, prefetchDistance = 15, maxSize = 200, enablePlaceholders = false)) { dao.page(f) }.flow }.cachedIn(viewModelScope)
    val pairing = MutableStateFlow(settings.pairing())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val candidates = MutableStateFlow<List<Media>>(emptyList())
    val approved = MutableStateFlow<List<Media>>(emptyList())
    private var cleanupWasPaused = false
    var cleanupLaunched = false
    fun pair(text: String) = task {
        val data = JSONObject(text.trim())
        require(data.getInt("protocol") == 1) { "Unsupported pairing code" }
        val p = Pairing.from(data)
        require(data.getString("secret").matches(Regex("[a-f0-9]{64}"))) { "Invalid pairing secret" }
        require(Api.wifi(getApplication()) != null) { "Connect the phone and Mac to the same Wi-Fi" }
        val oldPaused = settings.paused
        settings.paused = true; Schedule.stop(getApplication())
        try {
            SyncEngine.gate.withLock {
                val api = Api(getApplication(), p, Api.wifi(getApplication()))
                try {
                    val response = api.request("POST", "/v1/pair", JSONObject().put("secret", data.getString("secret")).put("name", android.os.Build.MODEL))
                    check(response.getString("library_id") == p.library) { "Mac library identity differs from the code" }
                    val saved = p.copy(token = response.getString("token"))
                    if (settings.pairing()?.library != saved.library) dao.resetLibrary()
                    settings.savePairing(saved); pairing.value = saved; settings.forceScan()
                } finally { api.close() }
            }
        } finally { settings.paused = oldPaused }
        Schedule.soon(getApplication())
    }
    fun setAddress(address: String) = task {
        val old = settings.pairing() ?: error("Pair with your Mac first")
        val updated = old.copy(url = address.trim().trimEnd('/'))
        val api = Api(getApplication(), updated, Api.wifi(getApplication()))
        try { check(api.status().getString("library_id") == old.library) { "This is a different library" }; settings.savePairing(updated); pairing.value = updated }
        finally { api.close() }
    }
    fun retry() = task { dao.retryFailures(); Schedule.soon(getApplication()) }
    fun dismissMissingFailures() = task {
        val count = dao.dismissMissingFailures()
        SyncLive.state.update { it.copy(message = "$count unavailable ${if (count == 1) "item" else "items"} cleared from this phone’s backup list") }
    }
    fun cleanupCandidates() = task {
        val library = pairing.value?.library ?: error("Pair with your Mac first")
        candidates.value = dao.cleanupCandidates(library)
        if (candidates.value.isEmpty()) error.value = "No verified local files are ready for cleanup."
    }
    fun verifyCleanup(items: List<Media>) = task {
        cleanupWasPaused = settings.paused
        settings.paused = true; Schedule.stop(getApplication())
        try { cleanupLaunched = false; approved.value = SyncEngine(getApplication()).verifyCleanup(items) }
        catch (e: Exception) { settings.paused = cleanupWasPaused; throw e }
    }
    fun cleanupResult(success: Boolean) = task {
        if (success) {
            // Only record items actually absent from the non-trashed MediaStore view.
            val removed = approved.value.filter { Library(getApplication()).snapshot(it.uri) == null }.map { it.key }
            dao.markRemoved(removed)
        }
        approved.value = emptyList(); candidates.value = emptyList(); cleanupLaunched = false
        settings.paused = cleanupWasPaused; settings.forceScan(); Schedule.soon(getApplication())
        SyncLive.state.update { it.copy(message = if (success) "Selected files moved to phone trash · Mac backups retained" else "Cleanup cancelled · phone files kept") }
    }
    fun task(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "Operation could not be completed" }
            finally { busy.value = false }
        }
    }
}

@Composable
fun BackupScreen(vm: BackupViewModel = viewModel()) {
    val c = androidx.compose.ui.platform.LocalContext.current
    val totals by vm.totals.collectAsStateWithLifecycle()
    val live by SyncLive.state.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val approved by vm.approved.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pairDialog by rememberSaveable { mutableStateOf(false) }
    var pairText by rememberSaveable { mutableStateOf("") }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(vm.settings.paused) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRefresh++; vm.settings.forceScan(); Schedule.soon(c)
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> if (result.contents != null) { pairText = result.contents; pairDialog = true } }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> vm.cleanupResult(result.resultCode == Activity.RESULT_OK) }
    LaunchedEffect(approved) {
        if (approved.isNotEmpty() && !vm.cleanupLaunched) {
            vm.cleanupLaunched = true
            try {
                val request = MediaStore.createTrashRequest(c.contentResolver, approved.map { Uri.parse(it.uri) }, true)
                trash.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (e: Exception) { vm.cleanupResult(false); vm.error.value = e.message ?: "Android could not open trash confirmation" }
        }
    }
    fun permissions() {
        val list = mutableListOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        if (android.os.Build.VERSION.SDK_INT >= 37) list += "android.permission.ACCESS_LOCAL_NETWORK"
        permissionLauncher.launch(list.toTypedArray())
    }
    val hasAll = remember(permissionRefresh) { Library(c).fullAccess() && Library(c).originalAccess() &&
        (android.os.Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(c, "android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED) }
    Scaffold(containerColor = Paper, bottomBar = {
        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
            listOf("Overview" to Icons.Outlined.Home, "Library" to Icons.Outlined.PhotoLibrary, "Settings" to Icons.Outlined.Tune).forEachIndexed { index, item ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Backup, null, tint = Pine, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(10.dp))
                Text("Personal Backup", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (error != null) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error!!, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                    IconButton(onClick = { vm.error.value = null }) { Icon(Icons.Outlined.Close, "Dismiss error") }
                }
            }
            when (tab) {
                0 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(if (pairing == null) "Your library.\nAt home." else "A place for\nevery memory.", fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    if (pairing == null) {
                        Text("Keep original photos and videos on your own Mac. Back up over Wi-Fi, a little at a time.", color = Muted, lineHeight = 23.sp)
                        Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Step("1", "Start the Mac server", "Choose a folder on your external drive, then display its pairing code.")
                                Step("2", "Connect on home Wi-Fi", "Keep your phone and Mac on the same network.")
                                Step("3", "Pair once. Back up gradually.", "Scan the code or paste the pairing text from your Mac.")
                                OutlinedButton(onClick = { permissions() }, modifier = Modifier.fillMaxWidth()) { Text("Allow library & network access") }
                                Button(onClick = { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the code displayed by your Mac").setBeepEnabled(false).setOrientationLocked(false)) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan Mac code") }
                                OutlinedButton(onClick = { pairDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Paste pairing text") }
                            }
                        }
                    } else {
                        Surface(color = Pine, contentColor = Color.White, shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Verified, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("VERIFIED ON YOUR MAC", fontSize = 11.sp, letterSpacing = 1.sp) }
                                Text("${totals.backed}", fontSize = 48.sp, fontWeight = FontWeight.Medium, lineHeight = 54.sp)
                                Text("of ${totals.items} photos and videos${if (live.scanning) " · scanning" else ""}", color = Color(0xFFD9EBDD))
                                LinearProgressIndicator(progress = { if (totals.bytes == 0L) 0f else (totals.backedBytes.toDouble() / totals.bytes).toFloat().coerceIn(0f, 1f) }, color = Color(0xFFD1E9A6), trackColor = Color(0xFF3B826B), modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${bytes(totals.backedBytes)} backed up", fontSize = 12.sp); Text("${bytes((totals.bytes - totals.backedBytes).coerceAtLeast(0))} left", fontSize = 12.sp) }
                            }
                        }
                        if (!hasAll) Surface(color = Color(0xFFF3E9CE), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Your library needs permission", fontWeight = FontWeight.SemiBold)
                                Text("Allow all photos, videos and original metadata for a complete backup.", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                                TextButton(onClick = { permissions() }) { Text("Grant access") }
                            }
                        }
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (live.running) Icons.Outlined.Sync else Icons.Outlined.Wifi, null, tint = Pine)
                            Column { Text(live.message, fontWeight = FontWeight.Medium); Text(if (live.speed > 0) "${bytes(live.speed)}/s · ${eta((totals.bytes - totals.backedBytes - totals.transferred).coerceAtLeast(0), live.speed)} remaining" else "Wi-Fi only · originals stay on your phone", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                try { paused = false; vm.settings.paused = false; Schedule.manual(c) }
                                catch (e: Exception) { vm.error.value = e.message }
                            }, enabled = !busy && hasAll && !live.running, modifier = Modifier.weight(1f)) { Text("Sync now") }
                            OutlinedButton(onClick = { paused = !vm.settings.paused; vm.settings.paused = paused; if (paused) { Schedule.stop(c); SyncLive.state.update { it.copy(message = "Paused") } } else Schedule.soon(c) }, modifier = Modifier.weight(1f)) { Text(if (paused) "Resume auto sync" else "Pause") }
                        }
                        if (live.active.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("IN PROGRESS", fontSize = 11.sp, color = Muted, letterSpacing = 1.sp)
                            live.active.values.forEach { a ->
                                Column { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp); Text("${a.phase} · ${bytes(a.done)} / ${bytes(a.size)}", color = Muted, fontSize = 12.sp); LinearProgressIndicator(progress = { (a.done.toDouble() / maxOf(1, a.size)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) }
                            }
                        }
                        Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row { Icon(Icons.Outlined.Storage, null, tint = Muted); Spacer(Modifier.width(10.dp)); Text("Your Mac", fontWeight = FontWeight.Medium) }
                                Text(pairing!!.url, fontSize = 13.sp, color = Muted)
                                Text(if (live.freeBytes >= 0) "${bytes(live.freeBytes)} available · 5 GiB kept free" else "Storage updates when the Mac connects", fontSize = 12.sp, color = Muted)
                            }
                        }
                        if (totals.failed > 0) TextButton(onClick = { vm.filter.value = "failed"; tab = 1 }) { Text("Review ${totals.failed} items needing attention") }
                        OutlinedButton(onClick = { vm.cleanupCandidates() }, enabled = !busy && totals.backed > 0, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.CleaningServices, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Free up phone space") }
                        Text("Cleanup always checks both copies and asks for confirmation. Deleting a phone file never deletes its Mac backup.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
                1 -> LibraryScreen(vm)
                2 -> SettingsScreen(vm, pairing, busy, onPermissions = { permissions() }, onPair = { pairText = ""; pairDialog = true })
            }
        }
    }
    if (pairDialog) AlertDialog(onDismissRequest = { if (!busy) pairDialog = false }, title = { Text("Pair with your Mac") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Use the code from the server’s pair command. It expires after 10 minutes.", fontSize = 14.sp)
            OutlinedTextField(pairText, { pairText = it }, label = { Text("Pairing text") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { vm.pair(pairText); pairDialog = false; pairText = "" }, enabled = pairText.isNotBlank() && !busy) { Text("Pair Mac") } }, dismissButton = { TextButton(onClick = { pairDialog = false; pairText = "" }) { Text("Cancel") } })
    if (candidates.isNotEmpty() && approved.isEmpty()) CleanupDialog(candidates, busy, dismiss = { if (!busy) vm.candidates.value = emptyList() }, confirm = { vm.verifyCleanup(it) })
}

@Composable
private fun Step(number: String, title: String, subtitle: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(28.dp)) { Box(contentAlignment = Alignment.Center) { Text(number, color = Pine, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) } }
        Column { Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp); Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}
@Composable
private fun LibraryScreen(vm: BackupViewModel) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()
    val missingFailures by vm.missingFailures.collectAsStateWithLifecycle()
    val media = vm.media.collectAsLazyPagingItems()
    var confirmMissingDismissal by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your library", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.settings.forceScan(); Schedule.soon(vm.getApplication()) }) { Icon(Icons.Outlined.Refresh, "Rescan library") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "All", "pending" to "Waiting", "uploading" to "Transferring", "verifying" to "Verifying", "backed_up" to "Backed up", "failed" to "Failed").forEach { (value, label) ->
                FilterChip(selected = filter == value, onClick = { vm.filter.value = value }, label = { Text(label) })
            }
        }
        if (filter == "failed" && totals.failed > 0) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.retry() }) { Text("Retry available items") }
            if (missingFailures > 0) TextButton(onClick = { confirmMissingDismissal = true }) { Text("Clear $missingFailures unavailable") }
        }
        if (media.itemCount == 0) Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.PhotoLibrary, null, tint = Muted, modifier = Modifier.size(40.dp))
                Text(if (filter == "failed") "No failed items" else "No items here yet", fontWeight = FontWeight.Medium)
                Text(if (filter == "failed") "Unavailable items you clear will no longer count as failures." else "Pair your Mac and allow library access to scan. Items appear as they are discovered.", color = Muted, fontSize = 13.sp)
            }
        } else LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(media.itemCount, key = media.itemKey { it.key }) { index -> media[index]?.let { item -> MediaRow(item) } }
        }
    }
    if (confirmMissingDismissal) AlertDialog(
        onDismissRequest = { confirmMissingDismissal = false },
        title = { Text("Clear unavailable items?") },
        text = { Text("These files are no longer accessible on this phone. Clearing them removes the warnings and adjusts the backup totals. It does not delete anything from your Mac. If a file returns to the phone’s media library, a later scan can add it again.") },
        confirmButton = { TextButton(onClick = { confirmMissingDismissal = false; vm.dismissMissingFailures() }) { Text("Clear items") } },
        dismissButton = { TextButton(onClick = { confirmMissingDismissal = false }) { Text("Keep reviewing") } }
    )
}
@Composable
private fun MediaRow(item: Media) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE4EAE1)), contentAlignment = Alignment.Center) {
            Icon(if (item.mime.startsWith("video")) Icons.Outlined.Videocam else Icons.Outlined.Image, null, tint = Muted)
            if (item.present) AsyncImage(model = item.uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${bytes(item.size)} · ${item.folder.trimEnd('/')}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val label = when (item.state) { "backed_up" -> if (item.present) "Verified on Mac" else "On Mac · removed from phone"; "hashing" -> "Reading original · ${bytes(item.hashOffset)}"; "uploading" -> "Uploading · ${bytes(item.offset)}"; "verifying" -> "Mac is verifying"; "failed" -> item.error; else -> if (item.error.isBlank()) "Waiting to back up" else "Retry scheduled · ${item.error}" }
            Text(label, color = if (item.state == "failed") MaterialTheme.colorScheme.error else Pine, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (item.state == "backed_up") Icon(Icons.Outlined.CheckCircle, "Backed up", tint = Pine, modifier = Modifier.size(20.dp))
    }
}
@Composable
private fun SettingsScreen(vm: BackupViewModel, pairing: Pairing?, busy: Boolean, onPermissions: () -> Unit, onPair: () -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current
    var charging by remember { mutableStateOf(vm.settings.chargingOnly) }
    var exclusions by remember { mutableStateOf(vm.settings.exclusions) }
    var bandwidth by remember { mutableStateOf(vm.settings.bandwidthKiB.takeIf { it > 0 }?.toString() ?: "") }
    var address by remember(pairing?.url) { mutableStateOf(pairing?.url ?: "") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Make it yours", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("BACKUP PREFERENCES", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Only while charging", fontWeight = FontWeight.Medium); Text("Wi-Fi is always required", color = Muted, fontSize = 12.sp) }
            Switch(checked = charging, onCheckedChange = { charging = it; vm.settings.chargingOnly = it; Schedule.soon(c) })
        }
        OutlinedTextField(bandwidth, { bandwidth = it.filter(Char::isDigit).take(6) }, label = { Text("Bandwidth limit · KiB/s") }, supportingText = { Text("Leave blank for unlimited. Applies across both uploads.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(exclusions, { exclusions = it }, label = { Text("Excluded folders") }, supportingText = { Text("One path per line, for example Pictures/Screenshots. Subfolders are excluded too.") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.settings.bandwidthKiB = bandwidth.toIntOrNull() ?: 0; vm.settings.exclusions = exclusions; Schedule.soon(c); SyncLive.state.update { it.copy(message = "Preferences saved · library will be rescanned") } }, modifier = Modifier.fillMaxWidth()) { Text("Save preferences") }
        HorizontalDivider()
        Text("YOUR MAC", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
        if (pairing != null) {
            OutlinedTextField(address, { address = it }, label = { Text("Server address") }, supportingText = { Text("Use https:// followed by the Mac’s Wi-Fi IP and port. Its paired identity stays protected.") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = { vm.setAddress(address) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Test and save address") }
            Text("Library ${pairing.library.take(12)}…", fontSize = 12.sp, color = Muted)
        }
        OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(if (pairing == null) "Pair Mac" else "Pair again or change Mac") }
        TextButton(onClick = onPermissions) { Text("Manage photo and network access") }
        Text("The Mac must be awake with the server running and the drive attached. Automatic work follows Android’s scheduling; open the app after a force-stop. Sync now starts a longer transfer session.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        Text("Personal Backup 1.0.1 · Android → Mac", fontSize = 11.sp, color = Muted)
    }
}
@Composable
private fun CleanupDialog(items: List<Media>, busy: Boolean, dismiss: () -> Unit, confirm: (List<Media>) -> Unit) {
    var selected by remember(items) { mutableStateOf(emptySet<String>()) }
    val chosen = items.filter { it.key in selected }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Free up phone space") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Select up to 100 files. We’ll recheck both copies, then Android will ask to move these files to trash.", fontSize = 13.sp)
            Text("Your Mac copy remains. Phone trash may keep using space until you empty it.", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { selected = if (selected.size == items.size) emptySet() else items.map { it.key }.toSet() }, enabled = !busy) { Text(if (selected.size == items.size) "Deselect all" else "Select all shown") }
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(items, key = { it.key }) { item ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = if (item.key in selected) selected - item.key else selected + item.key }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.key in selected, onCheckedChange = { selected = if (it) selected + item.key else selected - item.key }, enabled = !busy)
                        Column { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp); Text(bytes(item.size), color = Muted, fontSize = 11.sp) }
                    }
                }
            }
            Text("${chosen.size} selected · ${bytes(chosen.sumOf { it.size })}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Verifying originals on phone and Mac…", fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(onClick = { confirm(chosen) }, enabled = chosen.isNotEmpty() && !busy) { Text("Verify & continue") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Keep files") } })
}
fun bytes(value: Long): String { val units = listOf("B", "KiB", "MiB", "GiB", "TiB"); var n = value.toDouble(); var i = 0; while (n >= 1024 && i < units.lastIndex) { n /= 1024; i++ }; return if (i == 0) "$value B" else String.format(Locale.US, "%.1f %s", n, units[i]) }
fun eta(remaining: Long, speed: Long): String { if (speed <= 0) return "Estimating"; val seconds = remaining / speed; return when { seconds < 60 -> "<1 min"; seconds < 3600 -> "${seconds / 60} min"; else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min" } }
