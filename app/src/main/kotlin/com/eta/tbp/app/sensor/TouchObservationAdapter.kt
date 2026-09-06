package com.eta.tbp.app.sensor

import androidx.compose.ui.geometry.Offset
import com.eta.tbp.lib.sensor.RawPoint

/** Maps Compose's [Offset] to `lib`'s plain [RawPoint] — the only conversion `lib` allows in. */
fun List<Offset>.toRawPoints(): List<RawPoint> = map { RawPoint(it.x, it.y) }
