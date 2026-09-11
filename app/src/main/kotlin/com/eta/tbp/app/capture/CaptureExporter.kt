package com.eta.tbp.app.capture

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes [examples] to a cache file and hands it to the system share sheet
 * — the user picks where it goes (Drive, email, Files, ...) themselves.
 * No storage permission needed (cache dir + [FileProvider] is scoped-storage
 * safe), and nothing leaves the device through this app's own code.
 */
fun exportCaptures(
    context: Context,
    examples: List<CapturedExample>,
) {
    if (examples.isEmpty()) return
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "captures-${System.currentTimeMillis()}.json")
    file.writeText(examples.toCaptureJson())
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, "Export captured strokes"))
}
