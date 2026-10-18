package com.programmieramt.inklauncher.pen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.programmieramt.inklauncher.day.Panel
import com.programmieramt.inklauncher.day.Stroke
import java.time.LocalDate

/** [PenSurfaceView] in Compose, following the activity's resume/pause. */
@Composable
fun PenSurface(
    date: LocalDate,
    panel: Panel,
    scale: Float,
    strokes: List<Stroke>,
    tool: PenTool,
    onStrokesChanged: (LocalDate, List<Stroke>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var view by remember { mutableStateOf<PenSurfaceView?>(null) }

    AndroidView(
        factory = { context -> PenSurfaceView(context).also { view = it } },
        modifier = modifier,
        update = {
            it.onStrokesChanged = onStrokesChanged
            it.tool = tool
            it.show(date, panel, scale, strokes)
        },
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, view) {
        val penView = view ?: return@DisposableEffect onDispose {}
        // Adding replays the current state, so a view created while resumed is resumed right away.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> penView.onResume()
                Lifecycle.Event.ON_PAUSE -> penView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            penView.onPause()
        }
    }
}
