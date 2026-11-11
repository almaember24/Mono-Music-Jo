package com.prism.music

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.3 (#388's log): ONNX Runtime's library brings a content provider that ran at every start-up to set up its
 * telemetry and failed there (its native library only comes with Sing). It's left out of the app's manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StartupProvidersTest {
    @Test fun onnxRuntimesTelemetryProviderIsLeftOut() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val providers = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty().map { it.name }
        assertTrue("the manifest's providers were read: $providers", providers.isNotEmpty())
        assertFalse(providers.toString(), providers.any { it.contains("onnxruntime", ignoreCase = true) })
    }
}
