package com.prism.music.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Lossless mode asks decoders for float output so 24-bit files keep their resolution. Samsung's FLAC decoder
 * (`c2.sec.flac.decoder`) takes that request for 16-bit files but still writes 16-bit samples, which then play
 * as float: twice as fast and as harsh noise (#25, 44.1 kHz / 16-bit FLAC on a Galaxy A55; 24-bit files were fine).
 * A 16-bit file gains nothing from float decoding, so FLAC is only asked for float when it's known to be deeper.
 */
@UnstableApi
class FlacFloatGuardRenderer(
    context: Context,
    selector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    handler: Handler?,
    listener: AudioRendererEventListener?,
    sink: AudioSink,
) : MediaCodecAudioRenderer(context, selector, enableDecoderFallback, handler, listener, sink) {

    override fun getMediaFormat(format: Format, codecMimeType: String, codecMaxInputSize: Int, codecOperatingRate: Float): MediaFormat {
        val mediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate)
        if (format.sampleMimeType == MimeTypes.AUDIO_FLAC && !isDeep(format.pcmEncoding) &&
            mediaFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
        ) {
            if (Build.VERSION.SDK_INT >= 29) mediaFormat.removeKey(MediaFormat.KEY_PCM_ENCODING)
            else mediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        return mediaFormat
    }

    private fun isDeep(encoding: Int) = encoding == C.ENCODING_PCM_24BIT || encoding == C.ENCODING_PCM_32BIT ||
        encoding == C.ENCODING_PCM_FLOAT || encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN

    companion object {
        /** Swaps the stock audio renderer [DefaultRenderersFactory.buildAudioRenderers] made for this one. */
        fun install(
            out: ArrayList<Renderer>,
            context: Context,
            selector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            sink: AudioSink,
            handler: Handler,
            listener: AudioRendererEventListener,
        ) {
            val i = out.indexOfFirst { it is MediaCodecAudioRenderer }
            if (i >= 0) out[i] = FlacFloatGuardRenderer(context, selector, enableDecoderFallback, handler, listener, sink)
        }
    }
}
