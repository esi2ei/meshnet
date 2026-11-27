package com.meshnet.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledDashboardAssetsTest {
    @Test
    fun dashboardIsPackagedLocallyAndHasNoRemoteRuntimeAssets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val html = context.assets.open("web/index.html").bufferedReader().use { it.readText() }

        assertTrue("The local dashboard entry point must be included in the APK", html.contains("id=\"root\""))
        assertTrue("The dashboard must load a relative bundled script", html.contains("./assets/"))
        assertTrue("The dashboard must ship with a restrictive content security policy", html.contains("connect-src 'none'"))

        val assetNames = context.assets.list("web/assets") ?: emptyArray()
        val javascript = assetNames.firstOrNull { it.endsWith(".js") }
            ?: throw AssertionError("No bundled dashboard JavaScript asset was packaged")
        val stylesheet = assetNames.firstOrNull { it.endsWith(".css") }
            ?: throw AssertionError("No bundled dashboard CSS asset was packaged")

        val js = context.assets.open("web/assets/$javascript").bufferedReader().use { it.readText() }
        val css = context.assets.open("web/assets/$stylesheet").bufferedReader().use { it.readText() }
        assertTrue("The UI bundle must expose the native runtime controls", js.contains("MeshNetNative"))
        assertFalse("The packaged stylesheet must not fetch Google Fonts", css.contains("fonts.googleapis.com"))
        assertFalse("The packaged UI must not depend on remote Unsplash avatars", js.contains("images.unsplash.com"))
    }
}
