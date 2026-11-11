package com.prism.music

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #339 (1.8.1.1): Smart Downloads is off on a new install; someone already using Prism who never touched the switch
 * had it on by the old default and keeps it; a choice already made stays; it all happens once.
 */
class SmartDownloadsDefaultTest {
    private val smart = booleanPreferencesKey("smart_downloads")
    private val onboarded = booleanPreferencesKey("onboarded")
    private val cookie = stringPreferencesKey("cookie")

    @Test fun aNewInstallStartsWithItOff() {
        assertFalse(AppSettings().smartDownloads)
        val p = mutablePreferencesOf()
        SettingsRepository.keepSmartDownloads(p)
        assertNull("a new install isn't given an answer: Home offers it", p[smart])
    }

    @Test fun someoneAlreadyUsingPrismKeepsItOn() {
        val set = mutablePreferencesOf(onboarded to true)
        SettingsRepository.keepSmartDownloads(set)
        assertEquals(true, set[smart])
        val signedIn = mutablePreferencesOf(cookie to "SAPISID=x")
        SettingsRepository.keepSmartDownloads(signedIn)
        assertEquals(true, signedIn[smart])
    }

    @Test fun aChoiceAlreadyMadeStaysAndItRunsOnce() {
        val off = mutablePreferencesOf(onboarded to true, smart to false)
        SettingsRepository.keepSmartDownloads(off)
        assertEquals(false, off[smart])
        // A new install that sets up later isn't switched on by a second run.
        val fresh = mutablePreferencesOf()
        SettingsRepository.keepSmartDownloads(fresh)
        fresh[onboarded] = true
        SettingsRepository.keepSmartDownloads(fresh)
        assertNull(fresh[smart])
    }
}
