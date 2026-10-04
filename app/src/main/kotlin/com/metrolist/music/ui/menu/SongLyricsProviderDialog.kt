package com.metrolist.music.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.di.LyricsHelperEntryPoint
import com.metrolist.music.lyrics.LyricsProviderRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val providerLabels =
    mapOf(
        "BetterLyrics" to "Better Lyrics",
        "YouTubeSubtitle" to "YouTube Subtitles",
        "Musixmatch" to "Musixmatch (beta)",
    )

@Composable
fun SongLyricsProviderDialog(
    mediaId: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val helper =
        remember {
            EntryPointAccessors
                .fromApplication(context.applicationContext, LyricsHelperEntryPoint::class.java)
                .lyricsHelper()
        }
    var current by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaId) { current = helper.getSongProvider(mediaId) }

    val choose: (String?) -> Unit = { name ->
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            helper.setSongProvider(mediaId, name)
            val existing = database.lyrics(mediaId).first()
            if (existing != null) database.query { delete(existing) }
        }
        onDone()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyric provider for this song") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Only the chosen provider is used when this song plays.")
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { choose(null) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == null, onClick = { choose(null) })
                    Text("Automatic (your normal order)")
                }
                LyricsProviderRegistry.providerNames.forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { choose(name) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == name, onClick = { choose(name) })
                        Text(providerLabels[name] ?: name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
