package org.guaka.radio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun SpeakerVolumeControls(volumes: List<SpeakerVolume>, enabled: Boolean, onVolume: (String, Int) -> Unit) {
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        volumes.forEach { state ->
            key(state.speaker.id) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                LaunchedEffect(enabled, state.level) { if (!enabled || state.level == null) dragging = null }
                Text(state.speaker.name)
                val level = dragging?.roundToInt() ?: state.level
                Text(level?.let { "$it%" } ?: "Volume unavailable", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = dragging ?: (state.level ?: 0).toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.roundToInt()?.let { onVolume(state.speaker.id, it) }
                        dragging = null
                    },
                    valueRange = 0f..100f,
                    enabled = enabled && state.level != null,
                    modifier = Modifier.semantics { contentDescription = "Volume ${state.speaker.name}" },
                )
            }
        }
    }
}
