package com.corosdjistudio
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class BatchReviewActivity : ComponentActivity() {
 @OptIn(ExperimentalMaterial3Api::class)
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
  val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
  setContent {
   val context = LocalContext.current
   val exoPlayer = remember { ExoPlayer.Builder(context).build() }
   var selected by remember { mutableIntStateOf(0) }
   DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
   LaunchedEffect(uris, selected) { uris.getOrNull(selected)?.let { exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(it))); exoPlayer.prepare(); exoPlayer.playWhenReady = false } }
   MaterialTheme {
    Scaffold(topBar = { TopAppBar(title = { Text("Xem lại kết quả (${uris.size})") }, navigationIcon = { TextButton(onClick = { finish() }) { Text("Đóng") } }) }) { padding ->
     Column(Modifier.fillMaxSize().padding(padding).padding(vertical = 8.dp)) {
      if (uris.isEmpty()) Text("Không có video hoàn tất trong batch này.") else {
       AndroidView(
        factory = { PlayerView(it).apply { player = exoPlayer; useController = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
        update = { it.player = exoPlayer; it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT },
        modifier = Modifier.fillMaxWidth().weight(1.35f)
       )
       Spacer(Modifier.height(6.dp))
       Text(names.getOrNull(selected) ?: "Video ${selected + 1}", modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1)
       Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = { selected = (selected - 1).coerceAtLeast(0) }, enabled = selected > 0) { Text("← Trước") }
        Button(onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }) { Text("▶ / Ⅱ") }
        OutlinedButton(onClick = { selected = (selected + 1).coerceAtMost(uris.lastIndex) }, enabled = selected < uris.lastIndex) { Text("Tiếp →") }
       }
       HorizontalDivider()
       LazyColumn(Modifier.weight(0.65f).padding(horizontal = 12.dp)) { itemsIndexed(uris) { index, _ -> ListItem(headlineContent = { Text(names.getOrNull(index) ?: "Video ${index + 1}") }, supportingContent = { Text(if (index == selected) "Đang xem" else "Chạm để phát") }, modifier = Modifier.clickable { selected = index }) } }
      }
     }
    }
   }
  }
 }
 companion object { const val EXTRA_URIS = "review_uris"; const val EXTRA_NAMES = "review_names" }
}
