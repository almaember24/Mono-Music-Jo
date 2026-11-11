package com.prism.music

import com.prism.music.data.prefs.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppSettings' copy and default constructors take every setting as an argument, and the JVM allows a method 255 argument
 * slots (a Long or Double takes two). Past that the class compiles but can't load (1.7.7 hit it: "Too many arguments in
 * method signature"). New groups of settings go in a nested class (like `ShapeLook`); this fails while there's still room.
 */
class AppSettingsSizeTest {
    @Test
    fun appSettingsLeavesRoomUnderTheJvmsLimit() {
        val ctor = AppSettings::class.java.declaredConstructors.maxBy { it.parameterCount }
        val slots = ctor.parameterTypes.sumOf { if (it == java.lang.Long.TYPE || it == java.lang.Double.TYPE) 2 else 1 }
        println("[settings] AppSettings: ${AppSettings::class.java.declaredFields.size} fields, longest constructor $slots slots")
        assertTrue("AppSettings' constructor takes $slots of the JVM's 255 slots: put new settings in a nested class", slots <= 245)
        // And it loads and copies.
        assertTrue(AppSettings().copy() == AppSettings())
    }
}
