package dev.omarchy.gliff

import android.app.Activity
import android.graphics.Rect
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

/** Reports actual tabletop hinge bounds in window coordinates; never infers a phone model. */
data class FoldPosture(val horizontalBounds: Rect?,val tabletop: Boolean,val inner: Boolean)

class FoldLayoutObserver(private val activity: Activity, private val changed: (FoldPosture) -> Unit) {
    private val tracker = WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity))
    private val consumer = Consumer<WindowLayoutInfo> { layout ->
        val folds = layout.displayFeatures.filterIsInstance<FoldingFeature>()
        val metadata = folds.joinToString(";") { "orientation=${it.orientation},state=${it.state},bounds=${it.bounds.toShortString()},separating=${it.isSeparating}" }
        android.util.Log.i("GliffFlex","reportedFeatures=${layout.displayFeatures.size} folds=${folds.size} ${if (metadata.isEmpty()) "none" else metadata}")
        val fold = folds.firstOrNull { it.orientation == FoldingFeature.Orientation.HORIZONTAL }
        changed(FoldPosture(fold?.bounds?.let { Rect(it) },fold?.state == FoldingFeature.State.HALF_OPENED,folds.isNotEmpty()))
    }
    fun start() { tracker.addWindowLayoutInfoListener(activity,activity.mainExecutor,consumer) }
    fun stop() { tracker.removeWindowLayoutInfoListener(consumer) }
}
