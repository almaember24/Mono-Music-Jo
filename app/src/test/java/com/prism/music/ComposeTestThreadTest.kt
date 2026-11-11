package com.prism.music

import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Why the TV's in-app tests failed with "multithreaded access to SnapshotStateObserver" and "Only the original thread
 * that created a view hierarchy can touch its views" (1.8.0): the test rules from androidx.compose.ui.test.junit4 run
 * the composition's coroutines on an UnconfinedTestDispatcher, so an effect that does some work in the background
 * (withContext(Dispatchers.Default), as Prism's pages do) carries on on that background thread, and the composition's
 * next frame with it. The v2 rules run them on a StandardTestDispatcher, which the test clock runs on the main thread:
 * what the app itself does (its effects always come back to the main thread). The TV's in-app tests that wait only
 * through the rule (Ambient, Browse, Lyrics, Shell, Sing) use the v2 rules; the ones that also wait on the player by
 * sleeping (Link, Playback, Video, Home customize) keep the old ones: with v2, an effect runs only when the test waits
 * through the rule. So does Settings: there Home's first focus, a moment late under v2, took focus back from the side
 * navigation now and then on the slow TV.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComposeTestThreadTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createComposeRule()

    @Test fun theOldRulesCarryOnOnTheBackgroundThread() {
        val main = Thread.currentThread()
        var after: Thread? = null
        compose.setContent { LaunchedEffect(Unit) { withContext(Dispatchers.Default) { Thread.sleep(50) }; after = Thread.currentThread() } }
        compose.waitUntil(5_000) { after != null }
        assertTrue("carried on on ${after?.name}", after !== main)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComposeTestThreadV2Test {
    @get:Rule val compose = androidx.compose.ui.test.junit4.v2.createComposeRule()

    @Test fun theV2RulesComeBackToTheMainThread() {
        val main = Thread.currentThread()
        var after: Thread? = null
        compose.setContent { LaunchedEffect(Unit) { withContext(Dispatchers.Default) { Thread.sleep(50) }; after = Thread.currentThread() } }
        compose.waitUntil(5_000) { after != null }
        assertEquals(main, after)
    }
}
