package io.github.xororz.localdream.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.xororz.localdream.ui.theme.Motion

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SmoothLinearWavyProgressIndicator(progress: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = Motion.Progress,
        label = "linearProgress",
    )
    LinearWavyProgressIndicator(
        progress = { animated },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SmoothCircularWavyProgressIndicator(progress: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = Motion.Progress,
        label = "circularProgress",
    )
    CircularWavyProgressIndicator(
        progress = { animated },
        modifier = modifier,
    )
}
