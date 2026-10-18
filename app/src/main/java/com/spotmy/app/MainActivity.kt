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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.awaitCancellation

private val Ink = Color(0xFF090B0D)
private val Panel = Color(0xFF171B1F)
private val Accent = Color(0xFFB6F36B)
private val Muted = Color(0xFF9BA3A9)

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
                primary = Accent, background = Ink, surface = Panel,
                onBackground = Color.White, onSurface = Color.White
            )) {
                SpotmyScreen(
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
                MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).setAlbumTitle(item.album).build()
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
private fun SpotmyScreen(
    tracks: List<Track>,
    hasPermission: Boolean,
    player: MediaController?,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Track) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("Início") }
    var nowTitle by remember { mutableStateOf("Escolha uma música") }
    var nowArtist by remember { mutableStateOf("Sua biblioteca, do seu jeito") }
    val filtered = tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                nowTitle = mediaItem?.mediaMetadata?.title?.toString() ?: "Escolha uma música"
                nowArtist = mediaItem?.mediaMetadata?.artist?.toString() ?: "Sua biblioteca, do seu jeito"
            }
        }
        player?.addListener(listener)
        player?.currentMediaItem?.let {
            nowTitle = it.mediaMetadata.title?.toString() ?: nowTitle
            nowArtist = it.mediaMetadata.artist?.toString() ?: nowArtist
        }
        onDispose { player?.removeListener(listener) }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            Column {
                if (player != null) MiniPlayer(nowTitle, nowArtist, player)
                NavigationBar(containerColor = Panel) {
                    listOf("Início", "Biblioteca", "Cápsula").forEach { label ->
                        NavigationBarItem(
                            selected = tab == label,
                            onClick = { tab = label },
                            icon = { Icon(when (label) {
                                "Biblioteca" -> Icons.Default.LibraryMusic
                                "Cápsula" -> Icons.Default.AutoAwesome
                                else -> Icons.Default.Home
                            }, contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent, selectedTextColor = Accent,
                                indicatorColor = Color(0xFF283321), unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Ink).padding(padding).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SPOTMY", fontSize = 27.sp, fontWeight = FontWeight.Black, color = Accent, letterSpacing = 2.sp)
                    Text("SOM NA SUA FREQUÊNCIA", fontSize = 10.sp, color = Muted, letterSpacing = 1.4.sp)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Atualizar biblioteca", tint = Color.White) }
            }

            when (tab) {
                "Cápsula" -> CapsuleScreen(tracks.size)
                "Biblioteca" -> {
                    Text("Sua biblioteca", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Text("${tracks.size} faixas encontradas no dispositivo", color = Muted, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp))
                }
                else -> {
                    Text("Sua trilha sonora.", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Tudo o que você baixou. Sem algoritmos.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp, bottom = 18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        QuickCard("Sua música", "${tracks.size} faixas", Modifier.weight(1f))
                        QuickCard("Feito para você", "100% local", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Buscar faixa ou artista", color = Muted) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF30363B),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Accent
                        )
                    )
                    Spacer(Modifier.height(18.dp))
                }
            }

            if (tab != "Cápsula") {
                if (!hasPermission) {
                    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(20.dp)) {
                        Text("Encontre suas músicas", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("A permissão serve apenas para listar os arquivos de áudio do aparelho.", color = Muted, modifier = Modifier.padding(vertical = 10.dp))
                        Button(onClick = onRequestPermission, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) { Text("Permitir acesso") }
                    }
                } else if (filtered.isEmpty()) {
                    Text(if (tracks.isEmpty()) "Nenhuma música encontrada no aparelho." else "Nenhum resultado para essa busca.", color = Muted, modifier = Modifier.padding(top = 16.dp))
                } else {
                    Text("SUAS FAIXAS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(filtered, key = { it.id }) { track ->
                            Row(Modifier.fillMaxWidth().clickable { onPlay(track) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(48.dp).background(Color(0xFF22282C), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MusicNote, null, tint = Accent)
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(track.title, color = Color.White, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                    Text(track.artist, color = Muted, maxLines = 1, fontSize = 12.sp)
                                }
                                IconButton(onClick = { onPlay(track) }) { Icon(Icons.Default.PlayArrow, "Tocar", tint = Accent) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapsuleScreen(count: Int) {
    Text("Cápsula do Tempo", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text("Sua história em músicas", color = Muted, modifier = Modifier.padding(top = 5.dp, bottom = 20.dp))
    Column(Modifier.fillMaxWidth().background(Color(0xFF202C1A), RoundedCornerShape(22.dp)).padding(20.dp)) {
        Text("SEU UNIVERSO MUSICAL", color = Accent, fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        Text("As lembranças estão começando.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("O histórico real de escuta será usado para calcular suas estatísticas.", color = Muted, modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickCard("Biblioteca", "$count faixas", Modifier.weight(1f))
        QuickCard("Histórico", "Próxima etapa", Modifier.weight(1f))
    }
}

@Composable
private fun QuickCard(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(16.dp)).padding(15.dp)) {
        Text(title, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiniPlayer(title: String, artist: String, player: MediaController) {
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFF242B2F), shape = RoundedCornerShape(14.dp), shadowElevation = 8.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Album, null, tint = Accent, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, color = Color.White, maxLines = 1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(artist, color = Muted, maxLines = 1, fontSize = 11.sp)
            }
            IconButton(onClick = { if (playing) player.pause() else player.play() }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproduzir ou pausar", tint = Accent)
            }
        }
    }
}
