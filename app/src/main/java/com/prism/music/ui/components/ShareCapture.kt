package com.prism.music.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Draws the content as usual and also records a copy [widthPx] wide into [layer], so a card that's small on screen
 * is shared as a full-size picture (story cards are 1080 px wide, like the big apps').
 */
fun Modifier.recordInto(layer: GraphicsLayer, widthPx: Int = 1080): Modifier = drawWithContent {
    val s = widthPx / size.width
    layer.record(size = IntSize(widthPx, (size.height * s).roundToInt())) {
        scale(s, s, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
    }
    drawContent()
}

/**
 * Shares what [layer] holds as a PNG (through the app's FileProvider) with [text] beside it.
 * Returns false when the picture couldn't be made.
 */
suspend fun shareLayer(context: Context, layer: GraphicsLayer, fileName: String, text: String, chooserTitle: String): Boolean = runCatching {
    val bmp = layer.toImageBitmap().asAndroidBitmap()
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "share").apply { mkdirs() }.resolve(fileName).also { f ->
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, chooserTitle))
}.isSuccess
