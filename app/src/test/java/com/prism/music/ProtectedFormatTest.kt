package com.prism.music

import com.prism.music.data.stream.StreamResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedFormatTest {
    private fun format(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun aFormatWithDrmFamiliesIsProtected() {
        assertTrue(StreamResolver.isProtected(format("""{"itag":141,"drmFamilies":["WIDEVINE","PLAYREADY"]}""")))
    }

    @Test fun anOrdinaryFormatIsNot() {
        assertFalse(StreamResolver.isProtected(format("""{"itag":251}""")))
        assertFalse(StreamResolver.isProtected(format("""{"itag":251,"drmFamilies":[]}""")))
    }
}
