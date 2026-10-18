package com.spotmy.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

private val Black = Color(0xFF121212)
private val Panel = Color(0xFF242424)
private val PanelLight = Color(0xFF343434)
private val Gray = Color(0xFFB3B3B3)
private val Green = Color(0xFF1ED760)
private val White = Color.White

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private var permissionGranted by mutableStateOf(false)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) refreshTracks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        permissionGranted = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) refreshTracks()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Black, surface = Panel, onBackground = White, onSurface = White)) {
                SpotmyApp(tracks, permissionGranted, controller,
                    { permissionLauncher.launch(audioPermission) }, ::refreshTracks, ::playTrack)
            }
        }
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({ controller = runCatching { future.get() }.getOrNull() }, mainExecutor)
        }
    }

    private fun refreshTracks() { tracks = runCatching { loadTracks(this) }.getOrDefault(emptyList()) }

    private fun playTrack(track: Track) {
        val player = controller ?: return
        val items = tracks.map { item ->
            MediaItem.Builder().setUri(item.uri).setMediaMetadata(
                MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist)
                    .setAlbumTitle(item.album).setArtworkUri(item.artworkUri).build()
            ).build()
        }
        player.setMediaItems(items, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0), 0L)
        player.prepare()
        player.play()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

private enum class Tab { HOME, SEARCH, LIBRARY, SETTINGS }

@Composable
private fun SpotmyApp(
    tracks: List<Track>, hasPermission: Boolean, player: MediaController?,
    onPermission: () -> Unit, onRefresh: () -> Unit, onPlay: (Track) -> Unit
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

    if (expandedPlayer && player != null && player.currentMediaItem != null) {
        NowPlaying(title, artist, album, artwork, player) { expandedPlayer = false }
    } else {
        Scaffold(
            containerColor = Black,
            bottomBar = {
                Column {
                    if (player?.currentMediaItem != null) MiniPlayer(title, artist, artwork, player) { expandedPlayer = true }
                    NavigationBar(containerColor = Color.Black, tonalElevation = 0.dp, modifier = Modifier.height(64.dp)) {
                        listOf(Triple(Tab.HOME, "Início", Icons.Default.Home),
                            Triple(Tab.SEARCH, "Buscar", Icons.Default.Search),
                            Triple(Tab.LIBRARY, "Sua Biblioteca", Icons.Default.LibraryMusic), Triple(Tab.SETTINGS, "Configurações", Icons.Default.Settings)).forEach { item ->
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
                    Tab.HOME -> HomeScreen(tracks, hasPermission, onPermission, { onPlay(it); expandedPlayer = true }, { tab = Tab.SEARCH }, { tab = Tab.SETTINGS })
                    Tab.SEARCH -> SearchScreen(query, { query = it }, filtered, hasPermission, onPermission) { onPlay(it); expandedPlayer = true }
                    Tab.LIBRARY -> LibraryScreen(tracks, hasPermission, onPermission, onRefresh) { onPlay(it); expandedPlayer = true }
                    Tab.SETTINGS -> SettingsScreen(player)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(tracks: List<Track>, allowed: Boolean, onPermission: () -> Unit, onPlay: (Track) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    var filter by remember { mutableStateOf("Tudo") }
    val albums = tracks.distinctBy { if (it.album.isBlank()) it.id.toString() else it.album + "|" + it.artist }
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
                        tracks.take(6).chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                pair.forEach { track ->
                                    Row(Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(4.dp)).background(PanelLight).clickable { onPlay(track) },
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Cover(track.artworkUri, Modifier.size(58.dp), false)
                                        Text(track.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
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
            items(tracks.take(20), key = { "home-" + it.id }) { TrackRow(it, { onPlay(it) }, Modifier.padding(horizontal = 16.dp)) }
        }
    }
}

@Composable
private fun SearchScreen(query: String, onQuery: (String) -> Unit, tracks: List<Track>, allowed: Boolean, onPermission: () -> Unit, onPlay: (Track) -> Unit) {
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
            else LazyColumn { items(tracks, key = { it.id }) { TrackRow(it, { onPlay(it) }, Modifier.padding(horizontal = 16.dp)) } }
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
private fun LibraryScreen(tracks: List<Track>, allowed: Boolean, onPermission: () -> Unit, onRefresh: () -> Unit, onPlay: (Track) -> Unit) {
    var filter by remember { mutableStateOf("Playlists") }
    var alphabetical by remember { mutableStateOf(false) }
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
                Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).background(PanelLight), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = White, modifier = Modifier.size(30.dp)) }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Criar playlist", color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Organize suas músicas favoritas", color = Gray, fontSize = 12.sp)
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
                    } else TrackRow(track, { onPlay(track) }, Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(title: String, artist: String, album: String, artwork: Uri?, player: MediaController, onBack: () -> Unit) {
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var position by remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember(player) { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var liked by remember { mutableStateOf(false) }
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
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3D4540), Color(0xFF202320), Black)))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Minimizar", tint = White, modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TOCANDO AGORA", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text(album.ifBlank { "Músicas locais" }, color = Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {}) { Icon(Icons.Default.MoreHoriz, "Mais opções", tint = White) }
            }
            Spacer(Modifier.weight(0.6f))
            Cover(artwork, Modifier.fillMaxWidth().aspectRatio(1f), false)
            Spacer(Modifier.weight(0.6f))
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
                IconButton(onClick = {}) { Icon(Icons.Default.Shuffle, "Aleatório", tint = Gray) }
                IconButton(onClick = { player.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "Anterior", tint = White, modifier = Modifier.size(35.dp)) }
                Box(Modifier.size(62.dp).clip(CircleShape).background(White).clickable { if (player.isPlaying) player.pause() else player.play() }, contentAlignment = Alignment.Center) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproduzir", tint = Color.Black, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Próxima", tint = White, modifier = Modifier.size(35.dp)) }
                IconButton(onClick = {}) { Icon(Icons.Default.Repeat, "Repetir", tint = Gray) }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(Icons.Default.Devices, "Dispositivos", tint = Green)
                Icon(Icons.Default.Share, "Compartilhar", tint = Gray)
                Icon(Icons.Default.QueueMusic, "Fila", tint = Gray)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}


@Composable
private fun SettingsScreen(player: MediaController?) {
    var shuffle by remember(player) { mutableStateOf(player?.shuffleModeEnabled ?: false) }
    var repeat by remember(player) { mutableIntStateOf(player?.repeatMode ?: androidx.media3.common.Player.REPEAT_MODE_OFF) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.fillMaxSize().background(Black)) {
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
        Text("SPOTMY 2.5.1.0", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Text("Player de músicas locais. Recursos online abrem serviços externos.", color = Gray, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(track.artworkUri, Modifier.size(54.dp), false)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, color = White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • "), color = Gray, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        IconButton(onClick = onClick) { Icon(Icons.Default.MoreHoriz, "Mais opções", tint = Gray) }
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
