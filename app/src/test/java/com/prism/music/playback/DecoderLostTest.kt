package com.prism.music.playback

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.9's night check: a decoder the phone took away plays the song on; other failures go their own ways. */
class DecoderLostTest {
    private fun error(code: Int, cause: Throwable? = null) = PlaybackException("test", cause, code)

    @Test fun aReleasedDecoderIsRetried() {
        val released = IllegalStateException("releaseOutputBuffer() is valid only at Executing states; currently at Released state")
        assertTrue(decoderLost(error(PlaybackException.ERROR_CODE_DECODING_FAILED, released)))
        assertTrue(decoderLost(error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)))
    }

    @Test fun unreadableBytesAndOtherFailuresAreNot() {
        val unreadable = ParserException.createForMalformedContainer("bad atom", null)
        assertFalse(decoderLost(error(PlaybackException.ERROR_CODE_DECODING_FAILED, unreadable)))
        assertFalse(decoderLost(error(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)))
        assertFalse(decoderLost(error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
    }
}
