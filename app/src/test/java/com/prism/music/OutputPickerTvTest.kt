package com.prism.music

import com.prism.music.connect.Renderer
import com.prism.music.connect.RendererKind
import com.prism.music.ui.player.rendererHost
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The owner (1.8.0): casting to the TV went to its own player, not Prism on it. The output picker tells a TV's Cast or
 * DLNA player from its Prism by the address both answer on.
 */
class OutputPickerTvTest {
    private fun renderer(location: String, kind: RendererKind = RendererKind.UPNP) = Renderer("id", "Living room TV", "", "", location, null, kind = kind)

    @Test fun aCastPlayersAddressIsItsHost() {
        assertEquals("192.168.1.216", rendererHost(renderer("googlecast://192.168.1.216:8009", RendererKind.GOOGLE_CAST)))
    }

    @Test fun aDlnaPlayersAddressIsItsDescriptionsHost() {
        assertEquals("192.168.1.216", rendererHost(renderer("http://192.168.1.216:49152/description.xml")))
    }
}
