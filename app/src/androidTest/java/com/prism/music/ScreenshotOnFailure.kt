package com.prism.music

import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** A failed test leaves the screen it failed on in /sdcard/prism-tests/ (Class.test-failed.png). */
class ScreenshotOnFailure : TestWatcher() {
    override fun failed(e: Throwable?, description: Description) {
        runCatching { Fixture.screenshot("${description.testClass.simpleName}.${description.methodName}-failed") }
    }
}
