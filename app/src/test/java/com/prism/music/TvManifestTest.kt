package com.prism.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Prism TV (1.8.0, plan §3): one APK installs on phones and TVs. A TV needs every hardware feature Prism's permissions
 * imply marked not required (else Android TV hides or refuses the app), a banner, and an activity in the TV launcher.
 */
class TvManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private fun elements(tag: String) = doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    @Test fun noFeatureATvLacksIsRequired() {
        val features = elements("uses-feature").associate { it.getAttributeNS(ns, "name") to it.getAttributeNS(ns, "required") }
        for (f in listOf(
            "android.software.leanback", "android.hardware.touchscreen", "android.hardware.wifi", "android.hardware.camera",
            "android.hardware.camera.autofocus", "android.hardware.microphone",
        )) assertEquals("$f must be declared required=false", "false", features[f])
        // Permissions that would imply more hardware (telephony, location, Bluetooth) need it declared too.
        val permissions = elements("uses-permission").map { it.getAttributeNS(ns, "name") }
        for (p in permissions) assertTrue(
            "$p implies hardware a TV may lack: declare its uses-feature required=false",
            listOf("BLUETOOTH", "LOCATION", "CALL_PHONE", "READ_PHONE", "SMS", "NFC").none { p.contains(it) },
        )
    }

    @Test fun theTvLauncherOpensTvActivity() {
        val app = elements("application").single()
        assertEquals("@drawable/tv_banner", app.getAttributeNS(ns, "banner"))
        val tv = elements("activity").single { it.getAttributeNS(ns, "name") == ".TvActivity" }
        assertEquals("true", tv.getAttributeNS(ns, "exported"))
        val categories = tv.getElementsByTagName("category").let { l -> (0 until l.length).map { (l.item(it) as Element).getAttributeNS(ns, "name") } }
        assertTrue(categories.contains("android.intent.category.LEANBACK_LAUNCHER"))
        // The phone's launcher entries stay on the phone's screen only.
        for (a in elements("activity") + elements("activity-alias")) {
            val cats = a.getElementsByTagName("category").let { l -> (0 until l.length).map { (l.item(it) as Element).getAttributeNS(ns, "name") } }
            if ("android.intent.category.LEANBACK_LAUNCHER" in cats) assertEquals(".TvActivity", a.getAttributeNS(ns, "name"))
        }
        assertTrue(File("src/main/res/drawable-xhdpi/tv_banner.png").isFile)
    }

    @Test fun prismIsAScreensaverOnlySystemCanBind() {
        val dream = elements("service").single { it.getAttributeNS(ns, "name") == ".ui.tv.PrismDream" }
        assertEquals("android.permission.BIND_DREAM_SERVICE", dream.getAttributeNS(ns, "permission"))
        val actions = dream.getElementsByTagName("action").let { l -> (0 until l.length).map { (l.item(it) as Element).getAttributeNS(ns, "name") } }
        assertTrue(actions.contains("android.service.dreams.DreamService"))
    }
}
