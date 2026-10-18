package com.spotmy.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
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

private val AppBlack = Color(0xFF121212)
private val CardGray = Color(0xFF242424)
private val SoftGray = Color(0xFFB3B3B3)
private val SpotifyGreenLike = Color(0xFF1ED760)

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
            MaterialTheme(colorScheme = darkColorScheme(
                primary = SpotifyGreenLike,
                background = AppBlack,
                surface = CardGray,
                onBackground = Color.White,
                onSurface = Color.White
            )) {
                SpotmyApp(
                    tracks = tracks,
                    hasPermission = permissionGranted,
                    player = controller,
                    onRequestPermission = { permissionLauncher.launch(audioPermission) },
                    onRefresh = ::refreshTracks,
                    onPlay = ::playTrack
                )
            }
        }

        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({ controller = runCatching { future.get() }.getOrNull() }, mainExecutor)
        }
    }

    private fun refreshTracks() {
        tracks = runCatching { loadTracks(this) }.getOrDefault(emptyList())
    }

    private fun playTrack(track: Track) {
        val player = controller ?: return
        val items = tracks.map { item ->
            MediaItem.Builder().setUri(item.uri).setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setAlbumTitle(item.album)
                    .setArtworkUri(item.artworkUri)
                    .build()
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

@Composable
private fun SpotmyApp(
    tracks: List<Track>,
    hasPermission: Boolean,
    player: MediaController?,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Track) -> Unit
) {
    var tab by remember { mutableStateOf("Início") }
    var search by remember { mutableStateOf("") }
    var currentTitle by remember { mutableStateOf("Escolha uma música") }
    var currentArtist by remember { mutableStateOf("SPOTMY") }
    var currentArtwork by remember { mutableStateOf<android.net.Uri?>(null) }
    val filtered = tracks.filter {
        it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true)
    }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: "Escolha uma música"
                currentArtist = mediaItem?.mediaMetadata?.artist?.toString() ?: "SPOTMY"
                currentArtwork = mediaItem?.mediaMetadata?.artworkUri
            }
        }
        player?.addListener(listener)
        player?.currentMediaItem?.let {
            currentTitle = it.mediaMetadata.title?.toString() ?: currentTitle
            currentArtist = it.mediaMetadata.artist?.toString() ?: currentArtist
            currentArtwork = it.mediaMetadata.artworkUri
        }
        onDispose { player?.removeListener(listener) }
    }

    Scaffold(
        containerColor = AppBlack,
        bottomBar = {
            Column {
                if (player != null && player.currentMediaItem != null) {
                    MiniPlayer(currentTitle, currentArtist, currentArtwork, player)
                }
                NavigationBar(
                    containerColor = Color(0xFF000000),
                    contentColor = SoftGray,
                    tonalElevation = 0.dp
                ) {
                    listOf("Início", "Buscar", "Sua Biblioteca").forEach { label ->
                        NavigationBarItem(
                            selected = tab == label,
                            onClick = { tab = label },
                            icon = {
                                Icon(
                                    when (label) {
                                        "Buscar" -> Icons.Default.Search
                                        "Sua Biblioteca" -> Icons.Default.LibraryMusic
                                        else -> Icons.Default.Home
                                    },
                                    contentDescription = label,
                                    modifier = Modifier.size(25.dp)
                                )
                            },
                            label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = Color.White,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = SoftGray,
                                unselectedTextColor = SoftGray
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().background(AppBlack).padding(padding)) {
            when (tab) {
                "Buscar" -> SearchScreen(
                    query = search,
                    onQueryChange = { search = it },
                    tracks = filtered,
                    hasPermission = hasPermission,
                    onRequestPermission = onRequestPermission,
                    onPlay = onPlay
                )
                "Sua Biblioteca" -> LibraryScreen(
                    tracks = tracks,
                    hasPermission = hasPermission,
                    onRequestPermission = onRequestPermission,
                    onRefresh = onRefresh,
                    onPlay = onPlay
                )
                else -> HomeScreen(
                    tracks = tracks,
                    hasPermission = hasPermission,
                    onRequestPermission = onRequestPermission,
                    onPlay = onPlay,
                    onOpenSearch = { tab = "Buscar" }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    tracks: List<Track>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPlay: (Track) -> Unit,
    onOpenSearch: () -> Unit
) {
    var filter by remember { mutableStateOf("Tudo") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF3D2929), AppBlack)))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF3B6E45)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.GraphicEq, null, tint = Color.White)
                }
                Text("SPOTMY", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSearch) { Icon(Icons.Default.Search, "Buscar", tint = Color.White) }
                IconButton(onClick = {}) { Icon(Icons.Default.History, "Ouvidos recentemente", tint = Color.White) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Tudo", "Músicas", "Álbuns").forEach { value ->
                    val selected = filter == value
                    Surface(
                        color = if (selected) Color.White else Color(0xFF2A2A2A),
                        shape = CircleShape,
                        modifier = Modifier.clickable { filter = value }
                    ) {
                        Text(value, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                }
            }
        }
        if (!hasPermission) {
            item {
                PermissionCard(onRequestPermission)
            }
        } else if (tracks.isEmpty()) {
            item {
                Text("Não encontramos músicas no aparelho.", color = SoftGray)
                Text("Adicione arquivos de áudio ao celular e atualize a biblioteca.", color = SoftGray, fontSize = 13.sp)
            }
        } else {
            if (filter == "Tudo" || filter == "Músicas") {
                item {
                    Text("Ouça novamente", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
                item {
                    val picks = tracks.take(6)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        picks.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { track ->
                                    Row(
                                        Modifier.weight(1f).height(62.dp).clip(RoundedCornerShape(5.dp)).background(CardGray).clickable { onPlay(track) },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TrackCover(track.artworkUri, Modifier.size(62.dp), rounded = false)
                                        Text(track.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (filter == "Tudo" || filter == "Álbuns") {
                item {
                    Text("Álbuns e artistas", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
                item {
                    val albums = tracks.distinctBy { if (it.album.isBlank()) it.id.toString() else it.album + "|" + it.artist }.take(20)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(albums, key = { it.id }) { track ->
                            Column(Modifier.width(145.dp).clickable { onPlay(track) }) {
                                TrackCover(track.artworkUri, Modifier.fillMaxWidth().height(145.dp), rounded = false)
                                Text(track.album.ifBlank { track.title }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                Text(track.artist, color = SoftGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item {
                Text("Todas as músicas", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            items(tracks.take(15), key = { "home-${it.id}" }) { track ->
                TrackRow(track, onClick = { onPlay(track) })
            }
        }
    }
}

@Composable
private fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    tracks: List<Track>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPlay: (Track) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Buscar", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 16.dp, bottom = 16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("O que você quer ouvir?", color = Color(0xFF555555)) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Black) },
            singleLine = true,
            shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color.Black,
                cursorColor = Color.Black,
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.White
            )
        )
        Spacer(Modifier.height(18.dp))
        when {
            !hasPermission -> PermissionCard(onRequestPermission)
            query.isBlank() -> Text("Busque por músicas, artistas ou álbuns da sua biblioteca.", color = SoftGray, fontSize = 14.sp)
            tracks.isEmpty() -> Text("Nenhuma música encontrada.", color = SoftGray)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(tracks, key = { it.id }) { track -> TrackRow(track, onClick = { onPlay(track) }) }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    tracks: List<Track>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Track) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 18.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF454545)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Text("Sua Biblioteca", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).padding(start = 10.dp))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Atualizar biblioteca", tint = Color.White) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            Surface(color = CardGray, shape = CircleShape) { Text("Playlists", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
            Surface(color = CardGray, shape = CircleShape) { Text("Artistas", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
            Surface(color = CardGray, shape = CircleShape) { Text("Álbuns", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
        }
        if (!hasPermission) PermissionCard(onRequestPermission)
        else if (tracks.isEmpty()) Text("Nenhuma música encontrada no aparelho.", color = SoftGray)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(tracks, key = { it.id }) { track -> TrackRow(track, onClick = { onPlay(track) }) }
        }
    }
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackCover(track.artworkUri, Modifier.size(54.dp), rounded = false)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • "),
                color = SoftGray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        IconButton(onClick = onClick) { Icon(Icons.Default.MoreHoriz, "Tocar música", tint = SoftGray) }
    }
}

@Composable
private fun TrackCover(uri: android.net.Uri?, modifier: Modifier = Modifier, rounded: Boolean = true) {
    val shape = if (rounded) RoundedCornerShape(8.dp) else RoundedCornerShape(2.dp)
    Box(modifier.clip(shape).background(Color(0xFF282828)), contentAlignment = Alignment.Center) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = "Capa do álbum",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.Album, contentDescription = null, tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CardGray).padding(18.dp)) {
        Text("Sua música começa aqui", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Permita o acesso à biblioteca de áudio para mostrar as faixas e suas capas.", color = SoftGray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
        Button(onClick = onRequestPermission, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreenLike, contentColor = Color.Black)) {
            Text("Permitir acesso", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MiniPlayer(
    title: String,
    artist: String,
    artworkUri: android.net.Uri?,
    player: MediaController
) {
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color(0xFF333333),
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 3.dp
    ) {
        Row(Modifier.padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackCover(artworkUri, Modifier.size(42.dp), rounded = false)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, color = Color.White, maxLines = 1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis)
                Text(artist, color = Color(0xFFDDDDDD), maxLines = 1, fontSize = 10.sp, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { if (playing) player.pause() else player.play() }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproduzir ou pausar", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { player.seekToNextMediaItem() }) {
                Icon(Icons.Default.SkipNext, "Próxima música", tint = Color.White, modifier = Modifier.size(25.dp))
            }
        }
    }
}
