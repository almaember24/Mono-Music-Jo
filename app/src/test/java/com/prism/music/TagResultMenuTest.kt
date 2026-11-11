package com.prism.music

import com.prism.music.data.model.PlaylistItem
import com.prism.music.ui.components.playable
import com.prism.music.ui.screens.TAG_PAGE
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Review of 1.8.3: a tag in Search's results is a playlist item with a "tag:" id. Its long-press menu treated it as a
 * YouTube playlist (Play, Save, Pin to Home all asked YouTube for "tag:gym"); it now has no such menu, and a tap still
 * opens the tag's page.
 */
class TagResultMenuTest {
    @Test fun aTagResultHasNoYouTubePlaylistMenu() {
        assertNull(PlaylistItem(TAG_PAGE + "gym", "gym", "Tag · 3 songs", null).playable())
        assertNotNull("a real playlist keeps its menu", PlaylistItem("PLabc", "Mix", "Playlist", null).playable())
    }
}
