package com.spotmy.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Color as AndroidColor
import androidx.core.content.FileProvider
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

private var Black = Color(0xFF121212)
private var Panel = Color(0xFF242424)
private var PanelLight = Color(0xFF343434)
private var Gray = Color(0xFFB3B3B3)
private var Green = Color(0xFF1ED760)
private var White = Color.White

private fun typographyFor(font: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = font),
        displayMedium = base.displayMedium.copy(fontFamily = font),
        displaySmall = base.displaySmall.copy(fontFamily = font),
        headlineLarge = base.headlineLarge.copy(fontFamily = font),
        headlineMedium = base.headlineMedium.copy(fontFamily = font),
        headlineSmall = base.headlineSmall.copy(fontFamily = font),
        titleLarge = base.titleLarge.copy(fontFamily = font),
        titleMedium = base.titleMedium.copy(fontFamily = font),
        titleSmall = base.titleSmall.copy(fontFamily = font),
        bodyLarge = base.bodyLarge.copy(fontFamily = font),
        bodyMedium = base.bodyMedium.copy(fontFamily = font),
        bodySmall = base.bodySmall.copy(fontFamily = font),
        labelLarge = base.labelLarge.copy(fontFamily = font),
        labelMedium = base.labelMedium.copy(fontFamily = font),
        labelSmall = base.labelSmall.copy(fontFamily = font)
    )
}


class MainActivity : ComponentActivity() {
    private var appearanceVersion by mutableIntStateOf(0)
    private var lightTheme by mutableStateOf(false)
    private var accentArgb by mutableLongStateOf(0xFF1ED760L)
    private var controller by mutableStateOf<MediaController?>(null)
    private var customFontFamilies by mutableStateOf<Map<String, FontFamily>>(emptyMap())
    private var selectedFontArea by mutableStateOf("home")
    private val fontPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input -> openFileOutput("spotmy_font_$selectedFontArea", MODE_PRIVATE).use { output -> input.copyTo(output) } }
                    ?: error("Não foi possível ler o arquivo")
                val family = FontFamily(Typeface.createFromFile(java.io.File(filesDir, "spotmy_font_$selectedFontArea")))
                customFontFamilies = customFontFamilies + (selectedFontArea to family)
                android.widget.Toast.makeText(this, "Fonte aplicada à área selecionada.", android.widget.Toast.LENGTH_LONG).show()
            }.onFailure {
                android.widget.Toast.makeText(this, "Não foi possível importar essa fonte. Use um arquivo TTF ou OTF.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private var permissionGranted by mutableStateOf(false)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsTick = object : Runnable {
        override fun run() {
            val player = controller
            if (player?.isPlaying == true) {
                val item = player.currentMediaItem
                val title = item?.mediaMetadata?.title?.toString().orEmpty()
                val artist = item?.mediaMetadata?.artist?.toString().orEmpty()
                if (title.isNotBlank()) {
                    val prefs = getSharedPreferences("spotmy_capsule", MODE_PRIVATE)
                    val now = System.currentTimeMillis()
                    val month = java.time.YearMonth.now().toString()
                    val delta = 10_000L
                    prefs.edit()
                        .putLong("total_ms", prefs.getLong("total_ms", 0L) + delta)
                        .putLong("month:$month", prefs.getLong("month:$month", 0L) + delta)
                        .putLong("track:$title|$artist", prefs.getLong("track:$title|$artist", 0L) + delta)
                        .putLong("artist:$artist", prefs.getLong("artist:$artist", 0L) + delta)
                        .putLong("last_played_at", now)
                        .apply()
                }
            }
            statsHandler.postDelayed(this, 10_000L)
        }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) refreshTracks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statsHandler.postDelayed(statsTick, 10_000L)
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        val appearancePrefs = getSharedPreferences("spotmy_appearance", MODE_PRIVATE)
        lightTheme = appearancePrefs.getBoolean("light_theme", false)
        accentArgb = appearancePrefs.getLong("accent_argb", 0xFF1ED760L)
        applyAppearance()
        permissionGranted = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) refreshTracks()
        runCatching {
            val areas = listOf("app", "home", "search", "library", "settings", "player", "lyrics")
            customFontFamilies = areas.mapNotNull { area -> val fontFile = java.io.File(filesDir, "spotmy_font_$area"); if (fontFile.exists()) runCatching { area to FontFamily(Typeface.createFromFile(fontFile)) }.getOrNull() else null }.toMap()
        }
        setContent {
            MaterialTheme(
                colorScheme = if (lightTheme) lightColorScheme(primary = Green, background = Black, surface = Panel, onBackground = White, onSurface = White) else darkColorScheme(primary = Green, background = Black, surface = Panel, onBackground = White, onSurface = White),
                typography = typographyFor(customFontFamilies["app"] ?: FontFamily.Default)
            ) {
                SpotmyApp(tracks, permissionGranted, controller, customFontFamilies, lightTheme, accentArgb,
                    { permissionLauncher.launch(audioPermission) }, ::refreshTracks, ::playTrack, ::playTracks, ::queueNext,
                    { light, accent -> lightTheme = light; accentArgb = accent; getSharedPreferences("spotmy_appearance", MODE_PRIVATE).edit().putBoolean("light_theme", light).putLong("accent_argb", accent).apply(); applyAppearance(); appearanceVersion++ },
                    { area -> selectedFontArea = area; fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream")) })
            }
        }
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({ controller = runCatching { future.get() }.getOrNull() }, mainExecutor)
        }
    }

    private fun applyAppearance() {
        Green = Color(accentArgb.toInt())
        if (lightTheme) {
            Black = Color(0xFFF5F6F5); Panel = Color(0xFFFFFFFF); PanelLight = Color(0xFFE7E9E7)
            Gray = Color(0xFF5C625E); White = Color(0xFF171A18)
        } else {
            Black = Color(0xFF121212); Panel = Color(0xFF242424); PanelLight = Color(0xFF343434)
            Gray = Color(0xFFB3B3B3); White = Color.White
        }
    }

    private fun refreshTracks() { tracks = runCatching { loadTracks(this) }.getOrDefault(emptyList()) }

    private fun playTrack(track: Track) {
        playTracks(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
    }

    private fun mediaItemFor(track: Track) = MediaItem.Builder().setUri(track.uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setAlbumTitle(track.album).setArtworkUri(track.artworkUri).build()
    ).build()

    private fun playTracks(queue: List<Track>, startIndex: Int = 0) {
        val player = controller ?: return
        if (queue.isEmpty()) return
        player.setMediaItems(queue.map(::mediaItemFor), startIndex.coerceIn(0, queue.lastIndex), 0L)
        player.prepare()
        player.play()
    }

    private fun queueNext(track: Track) {
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        if (player.currentMediaItem == null || index == androidx.media3.common.C.INDEX_UNSET) {
            playTracks(listOf(track))
            return
        }
        player.addMediaItem((index + 1).coerceAtMost(player.mediaItemCount), mediaItemFor(track))
        android.widget.Toast.makeText(this, "${track.title} será a próxima música.", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsTick)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

private enum class Tab { HOME, SEARCH, LIBRARY, CAPSULE, SETTINGS }

@Composable
private fun SpotmyApp(
    tracks: List<Track>, hasPermission: Boolean, player: MediaController?, fontFamilies: Map<String, FontFamily>, lightTheme: Boolean, accentArgb: Long,
    onPermission: () -> Unit, onRefresh: () -> Unit, onPlay: (Track) -> Unit, onPlayQueue: (List<Track>) -> Unit, onQueueNext: (Track) -> Unit, onAppearanceChange: (Boolean, Long) -> Unit, onImportFont: (String) -> Unit
) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var query by remember { mutableStateOf("") }
    var expandedPlayer by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("Escolha uma música") }
    var artist by remember { mutableStateOf("SPOTMY") }
    var album by remember { mutableStateOf("") }
    var artwork by remember { mutableStateOf<Uri?>(null) }
    val filtered = tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                title = mediaItem?.mediaMetadata?.title?.toString() ?: "Escolha uma música"
                artist = mediaItem?.mediaMetadata?.artist?.toString() ?: "SPOTMY"
                album = mediaItem?.mediaMetadata?.albumTitle?.toString() ?: ""
                artwork = mediaItem?.mediaMetadata?.artworkUri
            }
        }
        player?.addListener(listener)
        player?.currentMediaItem?.let {
            title = it.mediaMetadata.title?.toString() ?: title
            artist = it.mediaMetadata.artist?.toString() ?: artist
            album = it.mediaMetadata.albumTitle?.toString() ?: album
            artwork = it.mediaMetadata.artworkUri
        }
        onDispose { player?.removeListener(listener) }
    }

    androidx.activity.compose.BackHandler(enabled = expandedPlayer) { expandedPlayer = false }

    if (expandedPlayer && player != null && player.currentMediaItem != null) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Black, surface = Panel, onBackground = White, onSurface = White), typography = typographyFor(fontFamilies["player"] ?: fontFamilies["app"] ?: FontFamily.Default)) { NowPlaying(title, artist, album, artwork, player, fontFamilies["lyrics"]) { expandedPlayer = false } }
    } else {
        Scaffold(
            containerColor = Black,
            bottomBar = {
                Column {
                    if (player?.currentMediaItem != null) MiniPlayer(title, artist, artwork, player) { expandedPlayer = true }
                    NavigationBar(containerColor = Color.Black, tonalElevation = 0.dp, modifier = Modifier.height(64.dp)) {
                        listOf(Triple(Tab.HOME, "Início", Icons.Default.Home),
                            Triple(Tab.SEARCH, "Buscar", Icons.Default.Search),
                            Triple(Tab.LIBRARY, "Sua Biblioteca", Icons.Default.LibraryMusic), Triple(Tab.CAPSULE, "Cápsula", Icons.Default.History), Triple(Tab.SETTINGS, "Configurações", Icons.Default.Settings)).forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.first, onClick = { tab = item.first },
                                icon = { Icon(item.third, item.second, Modifier.size(24.dp)) },
                                label = { Text(item.second, fontSize = 10.sp, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = White, selectedTextColor = White, indicatorColor = Color.Transparent,
                                    unselectedIconColor = Color(0xFF9B9B9B), unselectedTextColor = Color(0xFF9B9B9B))
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().background(Black).padding(padding)) {
                when (tab) {
                    Tab.HOME -> MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Black, surface = Panel, onBackground = White, onSurface = White), typography = typographyFor(fontFamilies["home"] ?: fontFamilies["app"] ?: FontFamily.Default)) { HomeScreen(tracks, hasPermission, onPermission, { onPlay(it); expandedPlayer = true }, { onPlayQueue(it); expandedPlayer = true }, onQueueNext, { tab = Tab.SEARCH }, { tab = Tab.SETTINGS }) }
                    Tab.SEARCH -> MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Black, surface = Panel, onBackground = White, onSurface = White), typography = typographyFor(fontFamilies["search"] ?: fontFamilies["app"] ?: FontFamily.Default)) { SearchScreen(query, { query = it }, filtered, hasPermission, onPermission, onQueueNext) { onPlay(it); expandedPlayer = true } }
                    Tab.LIBRARY -> MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Black, surface = Panel, onBackground = White, onSurface = White), typography = typographyFor(fontFamilies["library"] ?: fontFamilies["app"] ?: FontFamily.Default)) { LibraryScreen(tracks, hasPermission, onPermission, onRefresh, onQueueNext) { onPlay(it); expandedPlayer = true } }
                    Tab.CAPSULE -> CapsuleScreen()
                    Tab.SETTINGS -> MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Black, surface = Panel, onBackground = White, onSurface = White), typography = typographyFor(fontFamilies["settings"] ?: fontFamilies["app"] ?: FontFamily.Default)) { SettingsScreen(player, fontFamilies, lightTheme = lightTheme, accentArgb = accentArgb, onAppearanceChange = onAppearanceChange, onImportFont = onImportFont) }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(tracks: List<Track>, allowed: Boolean, onPermission: () -> Unit, onPlay: (Track) -> Unit, onPlayQueue: (List<Track>) -> Unit, onQueueNext: (Track) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    var filter by remember { mutableStateOf("Tudo") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val homePrefs = remember { context.getSharedPreferences("spotmy_home", android.content.Context.MODE_PRIVATE) }
    val playlistPrefs = remember { context.getSharedPreferences("spotmy_playlists", android.content.Context.MODE_PRIVATE) }
    var shortcuts by remember { mutableStateOf((0..5).associateWith { slot -> homePrefs.getString("slot_$slot", "") ?: "" }) }
    var editingSlot by remember { mutableIntStateOf(-1) }
    val albums = tracks.distinctBy { if (it.album.isBlank()) it.id.toString() else it.album + "|" + it.artist }
    val savedPlaylists = remember(tracks, shortcuts) {
        runCatching {
            val json = org.json.JSONObject(playlistPrefs.getString("items", "{}") ?: "{}")
            buildMap<String, List<Track>> {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val ids = json.optJSONArray(name) ?: org.json.JSONArray()
                    val idSet = (0 until ids.length()).mapNotNull { ids.optLong(it).takeIf { id -> id > 0L } }.toSet()
                    put(name, tracks.filter { it.id in idSet })
                }
            }
        }.getOrDefault(emptyMap())
    }
    fun saveShortcut(slot: Int, value: String) {
        homePrefs.edit().putString("slot_$slot", value).apply()
        shortcuts = shortcuts + (slot to value)
        editingSlot = -1
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF3D2929), Color(0xFF211819), Black)))
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF555555)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, "Perfil", tint = White)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onSearch) { Icon(Icons.Default.History, "Recentes", tint = White) }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Configurações", tint = White) }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Tudo", "Músicas", "Álbuns").forEach { value ->
                        Surface(color = if (filter == value) Green else PanelLight, shape = CircleShape,
                            modifier = Modifier.clickable { filter = value }) {
                            Text(value, color = if (filter == value) Color.Black else White, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                        }
                    }
                }
            }
        }
        if (!allowed) item { PermissionCard(onPermission, Modifier.padding(horizontal = 16.dp)) }
        else if (tracks.isEmpty()) item { EmptyCard("Sua música começa aqui", "As músicas salvas no aparelho aparecerão nesta tela.", "Permitir acesso", onPermission, Modifier.padding(horizontal = 16.dp)) }
        else {
            if (filter != "Álbuns") {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("Feito para você", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        (0..5).chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                pair.forEach { slot ->
                                    val key = shortcuts[slot].orEmpty()
                                    val selectedTrack = if (key.startsWith("track:")) tracks.firstOrNull { it.id.toString() == key.removePrefix("track:") } else null
                                    val selectedPlaylist = if (key.startsWith("playlist:")) savedPlaylists[key.removePrefix("playlist:")] else null
                                    val shownTrack = selectedTrack ?: selectedPlaylist?.firstOrNull() ?: if (key.isBlank()) tracks.getOrNull(slot) else null
                                    val cardTitle = when {
                                        key.startsWith("playlist:") -> key.removePrefix("playlist:")
                                        shownTrack != null -> shownTrack.title
                                        else -> "Escolher música"
                                    }
                                    val cardSubtitle = when {
                                        key.startsWith("playlist:") -> "Playlist • ${selectedPlaylist?.size ?: 0} músicas"
                                        shownTrack != null -> shownTrack.artist
                                        else -> "Personalizar atalho"
                                    }
                                    Row(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(4.dp)).background(PanelLight), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(56.dp).clickable {
                                            when {
                                                key.startsWith("playlist:") && !selectedPlaylist.isNullOrEmpty() -> onPlayQueue(selectedPlaylist)
                                                shownTrack != null -> onPlay(shownTrack)
                                                else -> editingSlot = slot
                                            }
                                        }) {
                                            Cover(shownTrack?.artworkUri, Modifier.fillMaxSize(), false)
                                            if (key.startsWith("playlist:")) Icon(Icons.Default.QueueMusic, null, tint = White, modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(17.dp))
                                        }
                                        Column(Modifier.weight(1f).clickable {
                                            when {
                                                key.startsWith("playlist:") && !selectedPlaylist.isNullOrEmpty() -> onPlayQueue(selectedPlaylist)
                                                shownTrack != null -> onPlay(shownTrack)
                                                else -> editingSlot = slot
                                            }
                                        }.padding(start = 7.dp, end = 1.dp)) {
                                            Text(cardTitle, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(cardSubtitle, color = Gray, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        IconButton(onClick = { editingSlot = slot }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Edit, "Escolher conteúdo", tint = Gray, modifier = Modifier.size(16.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (filter != "Músicas") {
                item {
                    Column {
                        Text("Seus álbuns", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(12.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(albums.take(20), key = { it.id }) { track ->
                                Column(Modifier.width(142.dp).clickable { onPlay(track) }) {
                                    Cover(track.artworkUri, Modifier.fillMaxWidth().aspectRatio(1f), false)
                                    Text(track.album.ifBlank { track.title }, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                    Text(track.artist, color = Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            item { Text("Tocadas recentemente", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(tracks.take(20), key = { "home-" + it.id }) { TrackRow(it, { onPlay(it) }, Modifier.padding(horizontal = 16.dp), { onQueueNext(it) }) }
        }
    }
    if (editingSlot >= 0) {
        AlertDialog(
            onDismissRequest = { editingSlot = -1 },
            containerColor = Panel,
            title = { Text("Personalizar atalho ${editingSlot + 1}", color = White) },
            text = {
                Column(Modifier.heightIn(max = 460.dp)) {
                    Text("Escolha uma música ou playlist para aparecer na tela inicial.", color = Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    LazyColumn {
                        item { Text("MÚSICAS", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                        items(tracks, key = { "home-pick-${it.id}" }) { track ->
                            Row(Modifier.fillMaxWidth().clickable { saveShortcut(editingSlot, "track:${track.id}") }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Cover(track.artworkUri, Modifier.size(42.dp), false)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(track.title, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, color = Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        item { Text("PLAYLISTS", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                        items(savedPlaylists.keys.sorted(), key = { "home-playlist-${it}" }) { name ->
                            Row(Modifier.fillMaxWidth().clickable { saveShortcut(editingSlot, "playlist:$name") }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(42.dp).background(Color(0xFF315C42)), contentAlignment = Alignment.Center) { Icon(Icons.Default.QueueMusic, null, tint = White) }
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(name, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${savedPlaylists[name]?.size ?: 0} músicas", color = Gray, fontSize = 11.sp)
                                }
                            }
                        }
                        item { TextButton(onClick = { saveShortcut(editingSlot, "") }) { Text("Limpar este atalho", color = Color(0xFFFF7777)) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editingSlot = -1 }) { Text("Fechar", color = Green) } }
        )
    }
}

@Composable
private fun SearchScreen(query: String, onQuery: (String) -> Unit, tracks: List<Track>, allowed: Boolean, onPermission: () -> Unit, onQueueNext: (Track) -> Unit, onPlay: (Track) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Buscar", color = White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp))
        OutlinedTextField(
            value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("O que você quer ouvir?", color = Color(0xFF686868), fontWeight = FontWeight.SemiBold) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Black) }, singleLine = true, shape = RoundedCornerShape(5.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = White, unfocusedContainerColor = White,
                focusedTextColor = Color.Black, unfocusedTextColor = Color.Black, cursorColor = Color.Black,
                focusedBorderColor = White, unfocusedBorderColor = White)
        )
        Spacer(Modifier.height(20.dp))
        if (!allowed) PermissionCard(onPermission, Modifier.padding(horizontal = 16.dp))
        else if (query.isNotBlank()) {
            if (tracks.isEmpty()) Text("Nenhum resultado encontrado", color = Gray, modifier = Modifier.padding(16.dp))
            else LazyColumn { items(tracks, key = { it.id }) { TrackRow(it, { onPlay(it) }, Modifier.padding(horizontal = 16.dp), { onQueueNext(it) }) } }
        } else {
            Text("Navegar por todas as seções", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            val categories = listOf("Músicas" to 0xFF8D67AB, "Podcasts" to 0xFF006450, "Feito para você" to 0xFF8400E7,
                "Lançamentos" to 0xFFE8115B, "Pop" to 0xFF148A08, "Hip-hop" to 0xFFBC5900, "Rock" to 0xFFE91429, "Brasil" to 0xFF0D73EC)
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(categories) { category ->
                    Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(7.dp)).background(Color(category.second))
                        .clickable { onQuery(category.first) }.padding(12.dp)) {
                        Text(category.first, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.MusicNote, null, tint = White.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.BottomEnd).size(42.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(tracks: List<Track>, allowed: Boolean, onPermission: () -> Unit, onRefresh: () -> Unit, onQueueNext: (Track) -> Unit, onPlay: (Track) -> Unit) {
    var filter by remember { mutableStateOf("Playlists") }
    var alphabetical by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("spotmy_playlists", android.content.Context.MODE_PRIVATE) }
    var playlists by remember {
        mutableStateOf(runCatching {
            val json = org.json.JSONObject(prefs.getString("items", "{}") ?: "{}")
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val ids = json.optJSONArray(name) ?: org.json.JSONArray()
                    put(name, (0 until ids.length()).mapNotNull { ids.optLong(it).takeIf { id -> id > 0L } }.toSet())
                }
            }
        }.getOrDefault(emptyMap()))
    }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var editingPlaylist by remember { mutableStateOf<String?>(null) }
    fun persistPlaylists(value: Map<String, Set<Long>>) {
        val json = org.json.JSONObject()
        value.forEach { (name, ids) -> json.put(name, org.json.JSONArray(ids.toList())) }
        prefs.edit().putString("items", json.toString()).apply()
        playlists = value
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF5B2A87)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, "Perfil", tint = White, modifier = Modifier.size(25.dp))
            }
            Text("Sua Biblioteca", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 10.dp))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Atualizar", tint = White) }
            IconButton(onClick = { alphabetical = !alphabetical }) { Icon(Icons.Default.Sort, "Ordenar", tint = White) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.width(8.dp))
            listOf("Playlists", "Artistas", "Álbuns", "Podcasts", "Músicas").forEach { value ->
                Surface(color = if (filter == value) Green else PanelLight, shape = CircleShape, modifier = Modifier.clickable { filter = value }) {
                    Text(value, color = if (filter == value) Color.Black else White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
        if (!allowed) PermissionCard(onPermission, Modifier.padding(horizontal = 16.dp))
        else if (tracks.isEmpty()) EmptyCard("Sua biblioteca está vazia", "As músicas locais aparecerão aqui quando houver arquivos de áudio no aparelho.", "Atualizar", onRefresh, Modifier.padding(horizontal = 16.dp))
        else {
            if (filter == "Playlists") {
                Row(Modifier.fillMaxWidth().clickable { playlistName = ""; showCreatePlaylist = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).background(PanelLight), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = White, modifier = Modifier.size(30.dp)) }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Criar playlist", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Organize suas músicas favoritas", color = Gray, fontSize = 12.sp)
                    }
                }
                playlists.keys.sorted().forEach { name ->
                    val count = playlists[name]?.size ?: 0
                    Row(Modifier.fillMaxWidth().clickable { editingPlaylist = name }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).background(Color(0xFF315C42)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.QueueMusic, null, tint = White, modifier = Modifier.size(28.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(name, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("$count músicas • Toque para editar", color = Gray, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            val selected = tracks.filter { it.id in (playlists[name] ?: emptySet()) }
                            if (selected.isNotEmpty()) onPlay(selected.first())
                            else android.widget.Toast.makeText(context, "Adicione músicas a esta playlist primeiro.", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.PlayArrow, "Reproduzir playlist", tint = Green) }
                    }
                }
            }
            val list = when (filter) {
                "Álbuns" -> tracks.distinctBy { it.album }
                "Artistas" -> tracks.distinctBy { it.artist }
                else -> tracks
            }.let { if (alphabetical) it.sortedBy { t -> t.title.lowercase() } else it }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { "lib-" + it.id }) { track ->
                    if (filter == "Álbuns" || filter == "Artistas") {
                        Row(Modifier.fillMaxWidth().clickable { onPlay(track) }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(track.artworkUri, Modifier.size(54.dp), filter == "Artistas")
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(if (filter == "Artistas") track.artist else track.album.ifBlank { track.title }, color = White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(if (filter == "Artistas") "Artista" else "Álbum • " + track.artist, color = Gray, fontSize = 12.sp)
                            }
                        }
                    } else TrackRow(track, { onPlay(track) }, Modifier.padding(horizontal = 16.dp), { onQueueNext(track) })
                }
            }
        }
    }
    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            containerColor = Panel,
            title = { Text("Nova playlist", color = White) },
            text = {
                OutlinedTextField(value = playlistName, onValueChange = { playlistName = it },
                    label = { Text("Nome da playlist") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = playlistName.trim()
                    if (name.isNotBlank() && name !in playlists) {
                        persistPlaylists(playlists + (name to emptySet()))
                        editingPlaylist = name
                    }
                    showCreatePlaylist = false
                }) { Text("Criar", color = Green) }
            },
            dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancelar", color = White) } }
        )
    }
    editingPlaylist?.let { name ->
        val selectedIds = playlists[name] ?: emptySet()
        AlertDialog(
            onDismissRequest = { editingPlaylist = null },
            containerColor = Panel,
            title = { Text(name, color = White) },
            text = {
                Column(Modifier.heightIn(max = 440.dp)) {
                    Text("Marque as músicas que deseja incluir.", color = Gray, fontSize = 12.sp)
                    LazyColumn {
                        items(tracks, key = { "pl-" + it.id }) { track ->
                            Row(Modifier.fillMaxWidth().clickable {
                                val updated = if (track.id in selectedIds) selectedIds - track.id else selectedIds + track.id
                                persistPlaylists(playlists + (name to updated))
                            }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = track.id in selectedIds, onCheckedChange = { checked ->
                                    val updated = if (checked) selectedIds + track.id else selectedIds - track.id
                                    persistPlaylists(playlists + (name to updated))
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, color = Gray, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editingPlaylist = null }) { Text("Concluir", color = Green) } },
            dismissButton = { TextButton(onClick = {
                persistPlaylists(playlists - name)
                editingPlaylist = null
            }) { Text("Excluir", color = Color(0xFFFF7777)) } }
        )
    }
}

@Composable
private fun NowPlaying(title: String, artist: String, album: String, artwork: Uri?, player: MediaController, lyricsFont: FontFamily?, onBack: () -> Unit) {
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var position by remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember(player) { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var liked by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var lyricsText by remember { mutableStateOf("") }
    var lyricsStatus by remember { mutableStateOf("Buscando letras...") }
    var showPasteLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var pastedLyrics by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val lyricsPicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Arquivo vazio") }.onSuccess { imported -> lyricsText = imported.replace(Regex("\\[(\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?)\\]"), "").trim(); lyricsStatus = "Letra importada do aparelho • sem sincronização"; context.getSharedPreferences("spotmy_lyrics", android.content.Context.MODE_PRIVATE).edit().putString(title + "|" + artist, imported).apply() }.onFailure { android.widget.Toast.makeText(context, "Não foi possível abrir a letra.", android.widget.Toast.LENGTH_SHORT).show() }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) { position = player.currentPosition.coerceAtLeast(0L); duration = player.duration.coerceAtLeast(0L); playing = player.isPlaying; delay(500) }
    }
    LaunchedEffect(showLyrics, title, artist, album, duration) {
        if (showLyrics) {
            lyricsText = ""
            lyricsStatus = "Buscando letras..."
            val result = withContext(Dispatchers.IO) {
                val exact = runCatching {
                    val query = "track_name=" + URLEncoder.encode(title, "UTF-8") +
                        "&artist_name=" + URLEncoder.encode(artist, "UTF-8") +
                        (if (album.isNotBlank()) "&album_name=" + URLEncoder.encode(album, "UTF-8") else "") +
                        (if (duration > 0L) "&duration=" + (duration / 1000L).coerceAtLeast(1L) else "")
                    val connection = (URL("https://lrclib.net/api/get?$query").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Lrclib-Client", "SPOTMY/2.6 (https://github.com/luizlobogg-sys/SPOTMY-)")
                    }
                    try {
                        if (connection.responseCode in 200..299) JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) else null
                    } finally { connection.disconnect() }
                }.getOrNull()
                if (exact != null) exact else runCatching {
                    val searchQuery = "track_name=" + URLEncoder.encode(title, "UTF-8") +
                        "&artist_name=" + URLEncoder.encode(artist, "UTF-8")
                    val searchConnection = (URL("https://lrclib.net/api/search?$searchQuery").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("Lrclib-Client", "SPOTMY/2.6 (https://github.com/luizlobogg-sys/SPOTMY-)")
                    }
                    try {
                        if (searchConnection.responseCode !in 200..299) null else {
                            val matches = org.json.JSONArray(searchConnection.inputStream.bufferedReader().use { it.readText() })
                            val candidates = (0 until matches.length()).mapNotNull { matches.optJSONObject(it) }
                                .filter { it.optString("plainLyrics").isNotBlank() || it.optString("syncedLyrics").isNotBlank() }
                            candidates.minByOrNull { candidate ->
                                val candidateDuration = candidate.optLong("duration", 0L) * 1000L
                                if (duration > 0L && candidateDuration > 0L) kotlin.math.abs(candidateDuration - duration) else 0L
                            }
                        }
                    } finally { searchConnection.disconnect() }
                }.getOrNull()
            }
            val localLyrics = context.getSharedPreferences("spotmy_lyrics", android.content.Context.MODE_PRIVATE).getString(title + "|" + artist, null)
            val lyrics = localLyrics ?: result?.optString("plainLyrics").orEmpty().ifBlank { result?.optString("syncedLyrics").orEmpty() }
            if (lyrics.isNotBlank() && lyrics != "null") {
                lyricsText = lyrics.replace(Regex("\\[(\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?)\\]"), "").replace(Regex("\\n{3,}"), "\\n\\n").trim()
                lyricsStatus = if (localLyrics != null) "Letra importada do aparelho • sem sincronização" else "Letra no SPOTMY • sem sincronização"
            } else {
                lyricsStatus = "Letra não encontrada para esta gravação. Tente pesquisar pelo título e artista."
            }
        }
    }
        if (showQueue) {
            Dialog(onDismissRequest = { showQueue = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Column(Modifier.fillMaxSize().background(Black).windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showQueue = false }) { Icon(Icons.Default.ArrowBack, "Voltar", tint = White) }
                        Text("Fila de reprodução", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("${player.mediaItemCount} músicas na fila", color = Gray, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
                    LazyColumn {
                        items((0 until player.mediaItemCount).toList(), key = { "queue-$it" }) { index ->
                            val item = player.getMediaItemAt(index)
                            val current = index == player.currentMediaItemIndex
                            Row(Modifier.fillMaxWidth().clickable { player.seekToDefaultPosition(index); player.play(); showQueue = false }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (current) Icon(Icons.Default.VolumeUp, "Tocando", tint = Green, modifier = Modifier.size(24.dp))
                                else Text("${index + 1}", color = Gray, modifier = Modifier.width(24.dp))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(item.mediaMetadata.title?.toString() ?: "Faixa sem título", color = if (current) Green else White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.mediaMetadata.artist?.toString() ?: "Artista desconhecido", color = Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (!current) IconButton(onClick = { player.removeMediaItem(index) }) { Icon(Icons.Default.Close, "Remover da fila", tint = Gray) }
                            }
                        }
                    }
                }
            }
        }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).background(Brush.verticalGradient(listOf(Color(0xFF3D4540), Color(0xFF202320), Black)))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Minimizar", tint = White, modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TOCANDO AGORA", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text(album.ifBlank { "Músicas locais" }, color = Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { showLyrics = true }) { Icon(Icons.Default.Lyrics, "Ver letra", tint = White) }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Cover(artwork, Modifier.fillMaxWidth().aspectRatio(1f).heightIn(max = 360.dp), false)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artist, color = Gray, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                }
                IconButton(onClick = { liked = !liked }) {
                    Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Curtir", tint = if (liked) Green else White, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Slider(value = if (duration > 0) position.coerceIn(0L, duration).toFloat() else 0f,
                onValueChange = { position = it.toLong() }, onValueChangeFinished = { if (duration > 0) player.seekTo(position.coerceIn(0L, duration)) },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), colors = SliderDefaults.colors(thumbColor = White, activeTrackColor = White, inactiveTrackColor = Color(0xFF777777)),
                modifier = Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeLabel(position), color = Gray, fontSize = 11.sp)
                Text("-" + timeLabel((duration - position).coerceAtLeast(0L)), color = Gray, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { player.shuffleModeEnabled = !player.shuffleModeEnabled }) { Icon(Icons.Default.Shuffle, "Aleatório", tint = if (player.shuffleModeEnabled) Green else Gray) }
                IconButton(onClick = { player.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "Anterior", tint = White, modifier = Modifier.size(35.dp)) }
                Box(Modifier.size(62.dp).clip(CircleShape).background(White).clickable { if (player.isPlaying) player.pause() else player.play() }, contentAlignment = Alignment.Center) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproduzir", tint = Color.Black, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Próxima", tint = White, modifier = Modifier.size(35.dp)) }
                IconButton(onClick = { player.repeatMode = when (player.repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
                    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                } }) { Icon(Icons.Default.Repeat, "Repetir", tint = if (player.repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) Gray else Green) }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(Icons.Default.Devices, "Dispositivos", tint = Green)
                val context = androidx.compose.ui.platform.LocalContext.current
                IconButton(onClick = {
                    runCatching {
                        val imageFile = createStoryShareImage(context, title, artist, album, artwork)
                        val imageUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, imageUri)
                            putExtra(Intent.EXTRA_TEXT, "Estou ouvindo $title — $artist no SPOTMY.")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Compartilhar card da música"))
                    }.onFailure {
                        android.widget.Toast.makeText(context, "Não foi possível criar o card de compartilhamento.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.Default.Share, "Compartilhar card", tint = White) }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "Fila", tint = Gray) }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (showLyrics) {
            Dialog(onDismissRequest = { showLyrics = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Black, surface = Panel, onBackground = White, onSurface = White), typography = typographyFor(lyricsFont ?: FontFamily.Default)) {
                    Column(Modifier.fillMaxSize().background(Black).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp)) {
                        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showLyrics = false }) { Icon(Icons.Default.ArrowBack, "Voltar", tint = White) }
                            Column(Modifier.weight(1f)) { Text("LETRA", color = Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp); Text(title, color = White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            IconButton(onClick = { lyricsPicker.launch(arrayOf("text/plain", "application/octet-stream")) }) { Icon(Icons.Default.FileOpen, "Importar letra", tint = Green) }
                        }
                        Text(artist, color = Gray, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
                        Divider(color = PanelLight)
                        Text(lyricsStatus, color = Gray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp))
                        if (lyricsText.isBlank()) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(color = Green); Spacer(Modifier.height(16.dp)); Text("Buscando a letra…", color = Gray)
                                TextButton(onClick = { showPasteLyrics = true; pastedLyrics = "" }) { Icon(Icons.Default.ContentPaste, null, tint = Green); Spacer(Modifier.width(6.dp)); Text("Colar letra", color = Green) }
                                TextButton(onClick = { lyricsPicker.launch(arrayOf("text/plain", "application/octet-stream")) }) { Text("Importar arquivo .TXT ou .LRC", color = Green) }
                                Text("A letra também é procurada automaticamente pelo título e artista.", color = Gray, fontSize = 11.sp)
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                                items(lyricsText.lines().filter { it.isNotBlank() }) { line -> Text(line, color = White, fontSize = 19.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                TextButton(onClick = { pastedLyrics = lyricsText; showPasteLyrics = true }) { Icon(Icons.Default.ContentPaste, null, tint = Green); Spacer(Modifier.width(6.dp)); Text("Editar / colar", color = Green) }
                                TextButton(onClick = { lyricsPicker.launch(arrayOf("text/plain", "application/octet-stream")) }) { Icon(Icons.Default.FileOpen, null, tint = Green); Spacer(Modifier.width(6.dp)); Text("Importar arquivo", color = Green) }
                            }
                        }
                    }
                }
            }
        }
        if (showPasteLyrics) {
            AlertDialog(
                onDismissRequest = { showPasteLyrics = false },
                containerColor = Panel,
                title = { Text("Colar letra da música", color = White) },
                text = {
                    Column {
                        Text("Copie a letra de onde você já a tem e cole aqui. O SPOTMY salva para esta música.", color = Gray, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = pastedLyrics,
                            onValueChange = { pastedLyrics = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                            placeholder = { Text("Toque e segure aqui para Colar") },
                            minLines = 7,
                            maxLines = 14
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val cleaned = pastedLyrics.trim()
                        if (cleaned.isNotBlank()) {
                            context.getSharedPreferences("spotmy_lyrics", android.content.Context.MODE_PRIVATE).edit()
                                .putString(title + "|" + artist, cleaned).apply()
                            lyricsText = cleaned.replace(Regex("\\[(\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?)\\]"), "").trim()
                            lyricsStatus = "Letra salva nesta música • sem sincronização"
                            showPasteLyrics = false
                        }
                    }) { Text("Salvar letra", color = Green) }
                },
                dismissButton = { TextButton(onClick = { showPasteLyrics = false }) { Text("Cancelar", color = Gray) } }
            )
        }
    }
}


private fun wrapCanvasText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val lines = mutableListOf<String>()
    var line = ""
    for (word in words) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
            lines.add(line)
            line = word
        } else line = candidate
    }
    if (line.isNotEmpty()) lines.add(line)
    return lines
}

private fun createStoryShareImage(context: android.content.Context, title: String, artist: String, album: String, artwork: Uri?): java.io.File {
    val width = 1080
    val height = 1920
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            AndroidColor.rgb(34, 75, 52), AndroidColor.rgb(8, 8, 8), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
    val left = 78f
    val right = 1002f
    val coverTop = 250f
    val coverSize = 924f
    val accent = AndroidColor.rgb(30, 215, 96)
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(26, 30, 215, 96) }
    canvas.drawCircle(960f, 220f, 240f, halo)
    canvas.drawCircle(70f, 1190f, 190f, halo)
    val coverRect = RectF(left, coverTop, right, coverTop + coverSize)
    val cover = runCatching {
        artwork?.let { uri -> context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }
    }.getOrNull()
    val coverPath = android.graphics.Path().apply { addRoundRect(coverRect, 34f, 34f, android.graphics.Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(coverPath)
    if (cover != null) {
        val scale = maxOf(coverSize / cover.width, coverSize / cover.height)
        val scaled = Bitmap.createScaledBitmap(cover, (cover.width * scale).toInt(), (cover.height * scale).toInt(), true)
        val srcLeft = ((scaled.width - coverSize) / 2f).toInt()
        val srcTop = ((scaled.height - coverSize) / 2f).toInt()
        canvas.drawBitmap(scaled, android.graphics.Rect(srcLeft, srcTop, srcLeft + coverSize.toInt(), srcTop + coverSize.toInt()),
            coverRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (scaled !== cover) scaled.recycle()
        cover.recycle()
    } else {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(45, 70, 52) }
        canvas.drawRect(coverRect, p)
        p.color = AndroidColor.WHITE; p.textSize = 180f; p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        canvas.drawText("SPOTMY", 300f, coverTop + 510f, p)
    }
    canvas.restore()
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(90, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
    canvas.drawRoundRect(coverRect, 34f, 34f, border)
    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    whitePaint.textSize = 38f
    canvas.drawText("SPOTMY", left, 128f, whitePaint)
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 27f; typeface = Typeface.DEFAULT_BOLD }
    canvas.drawText("SUA TRILHA SONORA", left, 184f, label)
    whitePaint.textSize = 57f
    val titleLines = wrapCanvasText(title, whitePaint, 900f).take(2)
    var titleY = 1285f
    titleLines.forEach { line -> canvas.drawText(line, left, titleY, whitePaint); titleY += 66f }
    whitePaint.textSize = 40f
    whitePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    canvas.drawText(wrapCanvasText(artist, whitePaint, 900f).firstOrNull().orEmpty(), left, titleY + 4f, whitePaint)
    val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.LTGRAY; textSize = 28f }
    canvas.drawText(wrapCanvasText(album.ifBlank { "Música local" }, muted, 900f).firstOrNull().orEmpty(), left, titleY + 55f, muted)
    val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    canvas.drawRoundRect(RectF(left, 1510f, left + 66f, 1576f), 33f, 33f, green)
    val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.BLACK; textSize = 42f; typeface = Typeface.DEFAULT_BOLD }
    canvas.drawText("♪", left + 18f, 1555f, note)
    whitePaint.textSize = 32f
    canvas.drawText("Compartilhado pelo SPOTMY", left + 88f, 1552f, whitePaint)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(110, 255, 255, 255); strokeWidth = 2f }
    canvas.drawLine(left, 1640f, right, 1640f, linePaint)
    val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.LTGRAY; textSize = 26f }
    canvas.drawText("OUÇA • DESCUBRA • REPITA", left, 1700f, footer)
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(dir, "spotmy_story.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return file
}


@Composable
private fun CapsuleScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var snapshot by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = context.getSharedPreferences("spotmy_capsule", android.content.Context.MODE_PRIVATE).all
            delay(10_000L)
        }
    }
    val month = java.time.YearMonth.now().toString()
    val total = (snapshot["total_ms"] as? Long) ?: 0L
    val monthTotal = (snapshot["month:$month"] as? Long) ?: 0L
    val artists = snapshot.filterKeys { it.startsWith("artist:") }.entries.sortedByDescending { it.value as? Long ?: 0L }.take(5)
    val songs = snapshot.filterKeys { it.startsWith("track:") }.entries.sortedByDescending { it.value as? Long ?: 0L }.take(5)
    fun duration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0L) / 60_000L
        return if (minutes >= 60L) "${minutes / 60} h ${minutes % 60} min" else "${minutes} min"
    }
    Column(Modifier.fillMaxSize().background(Black).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp)) {
        Text("Cápsula do Tempo", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Sua história musical, registrada neste aparelho.", color = Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp)) {
                Icon(Icons.Default.Headphones, null, tint = Green)
                Text(duration(monthTotal), color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Text("Este mês", color = Gray, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp)) {
                Icon(Icons.Default.History, null, tint = Green)
                Text(duration(total), color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Text("Desde a ativação", color = Gray, fontSize = 12.sp)
            }
        }
        Text("ARTISTAS MAIS OUVIDOS", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        if (artists.isEmpty()) Text("As estatísticas aparecem enquanto você ouve músicas com o SPOTMY aberto ou em segundo plano.", color = Gray, fontSize = 13.sp)
        artists.forEachIndexed { index, entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) { Text(entry.key.removePrefix("artist:"), color = White, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(duration(entry.value as? Long ?: 0L), color = Gray, fontSize = 12.sp) }
            }
        }
        Text("FAIXAS MAIS OUVIDAS", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        songs.forEachIndexed { index, entry ->
            val parts = entry.key.removePrefix("track:").split("|", limit = 2)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) { Text(parts.firstOrNull().orEmpty(), color = White, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(parts.getOrNull(1).orEmpty(), color = Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(duration(entry.value as? Long ?: 0L), color = Gray, fontSize = 11.sp)
            }
        }
        Text("Os registros começam nesta versão; reproduções anteriores não podem ser recuperadas. O histórico fica salvo localmente.", color = Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 24.dp, bottom = 20.dp))
    }
}

@Composable
private fun SettingsScreen(player: MediaController?, fontFamilies: Map<String, FontFamily>, lightTheme: Boolean, accentArgb: Long, onAppearanceChange: (Boolean, Long) -> Unit, onImportFont: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playbackPrefs = remember { context.getSharedPreferences("spotmy_playback", android.content.Context.MODE_PRIVATE) }
    var crossfadeEnabled by remember { mutableStateOf(playbackPrefs.getBoolean("crossfade_enabled", true)) }
    var crossfadeSeconds by remember { mutableFloatStateOf(playbackPrefs.getInt("crossfade_seconds", 5).coerceIn(2, 12).toFloat()) }
    var shuffle by remember(player) { mutableStateOf(player?.shuffleModeEnabled ?: false) }
    var repeat by remember(player) { mutableIntStateOf(player?.repeatMode ?: androidx.media3.common.Player.REPEAT_MODE_OFF) }
    var bass by remember { mutableFloatStateOf(playbackPrefs.getInt("bass_level", 0).toFloat()) }
    var treble by remember { mutableFloatStateOf(playbackPrefs.getInt("treble_level", 0).toFloat()) }
    var audioProfile by remember { mutableStateOf(playbackPrefs.getString("audio_profile", "custom") ?: "custom") }
    var timerMinutes by remember { mutableIntStateOf(0) }
    var stopAtTrackEnd by remember { mutableStateOf(playbackPrefs.getBoolean("stop_on_track_end", false)) }
    var animationsEnabled by remember { mutableStateOf(context.getSharedPreferences("spotmy_appearance", android.content.Context.MODE_PRIVATE).getBoolean("animations_enabled", true)) }
    val appearancePrefs = remember { context.getSharedPreferences("spotmy_appearance", android.content.Context.MODE_PRIVATE) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var fontArea by remember { mutableStateOf("app") }
    Column(Modifier.fillMaxSize().background(Black).verticalScroll(rememberScrollState())) {
        Text("Configurações", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 18.dp))
        Text("REPRODUÇÃO", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().clickable { shuffle = !shuffle; player?.shuffleModeEnabled = shuffle }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Shuffle, null, tint = if (shuffle) Green else White)
            Column(Modifier.weight(1f).padding(start = 16.dp)) { Text("Reprodução aleatória", color = White); Text("Embaralhar a fila atual", color = Gray, fontSize = 12.sp) }
            Switch(checked = shuffle, onCheckedChange = { shuffle = it; player?.shuffleModeEnabled = it })
        }
        Row(Modifier.fillMaxWidth().clickable {
            repeat = when (repeat) { androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL; androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE; else -> androidx.media3.common.Player.REPEAT_MODE_OFF }
            player?.repeatMode = repeat
        }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Repeat, null, tint = if (repeat == androidx.media3.common.Player.REPEAT_MODE_OFF) White else Green)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("Repetição", color = White)
                Text(when (repeat) { androidx.media3.common.Player.REPEAT_MODE_ALL -> "Repetir fila inteira"; androidx.media3.common.Player.REPEAT_MODE_ONE -> "Repetir música atual"; else -> "Desativada" }, color = Gray, fontSize = 12.sp)
            }
            Text(when (repeat) { androidx.media3.common.Player.REPEAT_MODE_ALL -> "Tudo"; androidx.media3.common.Player.REPEAT_MODE_ONE -> "1"; else -> "Off" }, color = Green)
        }
        Divider(color = PanelLight, modifier = Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null, tint = if (crossfadeEnabled) Green else White)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("Mesclar músicas (crossfade)", color = White)
                Text("Sobrepõe o final de uma faixa ao início da próxima", color = Gray, fontSize = 12.sp)
            }
            Switch(checked = crossfadeEnabled, onCheckedChange = {
                crossfadeEnabled = it
                playbackPrefs.edit().putBoolean("crossfade_enabled", it).apply()
            })
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Duração da transição", color = White, modifier = Modifier.weight(1f))
                Text("${crossfadeSeconds.toInt()} s", color = Green, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = crossfadeSeconds,
                onValueChange = {
                    crossfadeSeconds = it
                    playbackPrefs.edit().putInt("crossfade_seconds", it.toInt().coerceIn(2, 12)).apply()
                },
                valueRange = 2f..12f,
                steps = 9,
                enabled = crossfadeEnabled
            )
            Text("Funciona automaticamente entre faixas da fila. Padrão: 5 segundos.", color = Gray, fontSize = 11.sp)
        }
        Divider(color = PanelLight, modifier = Modifier.padding(vertical = 8.dp))
        Text("ÁUDIO", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Text("Perfil de som", color = White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("custom" to "Personalizado", "bass" to "Graves", "vocal" to "Voz", "treble" to "Agudos").forEach { (key, label) ->
                FilterChip(selected = audioProfile == key, onClick = {
                    audioProfile = key
                    playbackPrefs.edit().putString("audio_profile", key).apply()
                }, label = { Text(label) })
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Graves", color = White, modifier = Modifier.weight(1f)); Text("${bass.toInt()}", color = Green) }
            Slider(value = bass, onValueChange = { bass = it; audioProfile = "custom"; playbackPrefs.edit().putInt("bass_level", it.toInt()).putString("audio_profile", "custom").apply() }, valueRange = -100f..100f, enabled = audioProfile == "custom")
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Agudos", color = White, modifier = Modifier.weight(1f)); Text("${treble.toInt()}", color = Green) }
            Slider(value = treble, onValueChange = { treble = it; audioProfile = "custom"; playbackPrefs.edit().putInt("treble_level", it.toInt()).putString("audio_profile", "custom").apply() }, valueRange = -100f..100f, enabled = audioProfile == "custom")
            Text("O efeito depende do suporte do dispositivo e é aplicado durante a reprodução.", color = Gray, fontSize = 11.sp)
        }
        Divider(color = PanelLight, modifier = Modifier.padding(vertical = 8.dp))
        Text("DESLIGAMENTO AUTOMÁTICO", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Text("Desligar após", color = White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 30, 45, 60, 90).forEach { minutes ->
                FilterChip(selected = timerMinutes == minutes, onClick = {
                    timerMinutes = minutes
                    playbackPrefs.edit().putLong("sleep_timer_end", System.currentTimeMillis() + minutes * 60_000L).apply()
                }, label = { Text("${minutes} min") })
            }
            FilterChip(selected = timerMinutes == 0, onClick = {
                timerMinutes = 0
                playbackPrefs.edit().remove("sleep_timer_end").apply()
            }, label = { Text("Cancelar") })
        }
        Row(Modifier.fillMaxWidth().clickable {
            stopAtTrackEnd = !stopAtTrackEnd
            playbackPrefs.edit().putBoolean("stop_on_track_end", stopAtTrackEnd).apply()
        }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Ao terminar a faixa", color = White); Text("Parar quando a música atual terminar", color = Gray, fontSize = 12.sp) }
            Switch(checked = stopAtTrackEnd, onCheckedChange = {
                stopAtTrackEnd = it
                playbackPrefs.edit().putBoolean("stop_on_track_end", it).apply()
            })
        }
        Divider(color = PanelLight, modifier = Modifier.padding(vertical = 8.dp))
        Text("LETRAS E IDENTIFICAÇÃO", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().clickable { uriHandler.openUri("https://www.google.com/search?q=identificar+m%C3%BAsica+online") }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null, tint = White)
            Column(Modifier.weight(1f).padding(start = 16.dp)) { Text("Identificar uma música", color = White); Text("Abrir busca de reconhecimento musical", color = Gray, fontSize = 12.sp) }
            Icon(Icons.Default.OpenInNew, null, tint = Gray)
        }
        Row(Modifier.fillMaxWidth().clickable { uriHandler.openUri("https://www.google.com/search?q=letra+da+m%C3%BAsica") }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lyrics, null, tint = White)
            Column(Modifier.weight(1f).padding(start = 16.dp)) { Text("Encontrar letras", color = White); Text("Pesquisar letras na internet", color = Gray, fontSize = 12.sp) }
            Icon(Icons.Default.OpenInNew, null, tint = Gray)
        }
        Divider(color = PanelLight, modifier = Modifier.padding(vertical = 8.dp))
        Text("APARÊNCIA", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().clickable { onAppearanceChange(!lightTheme, accentArgb) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (lightTheme) Icons.Default.LightMode else Icons.Default.DarkMode, null, tint = Green)
            Column(Modifier.weight(1f).padding(start = 14.dp)) { Text("Tema claro", color = White); Text("Alternar entre aparência clara e escura", color = Gray, fontSize = 12.sp) }
            Switch(checked = lightTheme, onCheckedChange = { onAppearanceChange(it, accentArgb) })
        }
        Text("Cor de destaque", color = White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(0xFF1ED760L, 0xFF5B9DFFL, 0xFFFF6B6BL, 0xFFB084F5L, 0xFFFFB547L).forEach { colorValue ->
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(colorValue.toInt())).clickable { onAppearanceChange(lightTheme, colorValue) },
                    contentAlignment = Alignment.Center) {
                    if (accentArgb == colorValue) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable {
            animationsEnabled = !animationsEnabled
            appearancePrefs.edit().putBoolean("animations_enabled", animationsEnabled).apply()
        }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Animações", color = White); Text("Preferência de movimento da interface", color = Gray, fontSize = 12.sp) }
            Switch(checked = animationsEnabled, onCheckedChange = { animationsEnabled = it; appearancePrefs.edit().putBoolean("animations_enabled", it).apply() })
        }
        Text("Escolha uma área específica ou altere a tipografia do aplicativo inteiro.", color = Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        var areaMenu by remember { mutableStateOf(false) }
        Box(Modifier.padding(horizontal = 20.dp)) {
            OutlinedButton(onClick = { areaMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(when(fontArea) { "app" -> "Aplicativo inteiro"; "home" -> "Início"; "search" -> "Buscar"; "library" -> "Biblioteca"; "settings" -> "Configurações"; "player" -> "Player"; else -> "Letras" }, color = White); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null, tint = White) }
            DropdownMenu(expanded = areaMenu, onDismissRequest = { areaMenu = false }) { listOf("app" to "Aplicativo inteiro", "home" to "Início", "search" to "Buscar", "library" to "Biblioteca", "settings" to "Configurações", "player" to "Player", "lyrics" to "Letras").forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { fontArea = key; areaMenu = false }) } }
        }
        Row(Modifier.fillMaxWidth().clickable { onImportFont(fontArea) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TextFields, null, tint = White)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("Importar fonte para esta área", color = White)
                Text((if (fontFamilies.containsKey(fontArea)) "Fonte personalizada aplicada" else "Fonte padrão") + " • TTF ou OTF", color = Gray, fontSize = 12.sp)
            }
            Icon(Icons.Default.FileOpen, null, tint = Gray)
        }
        Divider(color = PanelLight, modifier = Modifier.padding(vertical = 8.dp))
        Text("SPOTMY 2.5.1.0", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Text("Player de músicas locais. Recursos online abrem serviços externos.", color = Gray, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier, onQueue: (() -> Unit)? = null) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(track.artworkUri, Modifier.size(54.dp), false)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, color = White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • "), color = Gray, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Box {
            IconButton(onClick = { if (onQueue != null) menuExpanded = true else onClick() }) { Icon(Icons.Default.MoreHoriz, "Mais opções", tint = Gray) }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Tocar em seguida") }, leadingIcon = { Icon(Icons.Default.QueueMusic, null) }, onClick = { menuExpanded = false; onQueue?.invoke() })
                DropdownMenuItem(text = { Text("Reproduzir agora") }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) }, onClick = { menuExpanded = false; onClick() })
            }
        }
    }
}

@Composable
private fun Cover(uri: Uri?, modifier: Modifier = Modifier, rounded: Boolean = true) {
    val shape = if (rounded) RoundedCornerShape(8.dp) else RoundedCornerShape(2.dp)
    Box(modifier.clip(shape).background(Color(0xFF282828)), contentAlignment = Alignment.Center) {
        if (uri != null) AsyncImage(model = uri, contentDescription = "Capa do álbum", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Album, null, tint = Color(0xFF777777), modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun MiniPlayer(title: String, artist: String, artwork: Uri?, player: MediaController, onExpand: () -> Unit) {
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable { onExpand() }, color = Color(0xFF333333), shape = RoundedCornerShape(6.dp)) {
        Column {
            Row(Modifier.padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(artwork, Modifier.size(42.dp), false)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(title, color = White, maxLines = 1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis)
                    Text(artist, color = Color(0xFFDDDDDD), maxLines = 1, fontSize = 10.sp, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { if (playing) player.pause() else player.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproduzir ou pausar", tint = White) }
                IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Próxima", tint = White) }
            }
            val progress = if (player.duration > 0) (player.currentPosition.toFloat() / player.duration).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF535353))) { Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Green)) }
        }
    }
}

@Composable
private fun PermissionCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    EmptyCard("Acesse suas músicas", "Permita o acesso à biblioteca de áudio para exibir faixas e capas.", "Permitir acesso", onClick, modifier)
}

@Composable
private fun EmptyCard(title: String, subtitle: String, action: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(18.dp)) {
        Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(subtitle, color = Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black)) {
            Text(action, fontWeight = FontWeight.Bold)
        }
    }
}

private fun timeLabel(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L).toInt()
    return (seconds / 60).toString() + ":" + (seconds % 60).toString().padStart(2, '0')
}
