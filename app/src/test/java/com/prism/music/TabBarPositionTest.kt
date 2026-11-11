package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.TabBarPosition
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #264 (1.8.0): tablets can have the tabs at the bottom. Nobody's layout changes on the update: the default is the side,
 * as before; it's a device choice, so it never travels in a Look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TabBarPositionTest {
    @Test fun tabletsKeepTheSideRailUnlessAsked() {
        assertEquals(TabBarPosition.SIDE, AppSettings().behavior.tabBarPosition)
    }

    @Test fun itIsNotPartOfALook() {
        val r = com.prism.music.data.prefs.SettingsRepository(
            org.robolectric.RuntimeEnvironment.getApplication(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        assertEquals(true, "tab_bar_position" in r.notLookNames)
        assertEquals(false, "tab_bar_position" in r.lookKinds.keys)
    }
}
