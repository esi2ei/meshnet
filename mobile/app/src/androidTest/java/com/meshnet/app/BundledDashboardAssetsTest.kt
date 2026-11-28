package com.meshnet.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.net.Uri
import androidx.webkit.WebViewAssetLoader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledDashboardAssetsTest {
    @Test
    fun dashboardIsServedThroughLocalWebViewAssetLoaderOrigin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        val response = loader.shouldInterceptRequest(
            Uri.parse("https://appassets.androidplatform.net/assets/web/index.html")
        )
        assertNotNull("The virtual appassets origin must resolve directly from APK assets", response)
        assertTrue("The local HTML response must have an HTML MIME type", response!!.mimeType?.contains("html") == true)
    }

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
        val remoteUnsplashRequest = Regex(
            """(?:src|href)\s*=\s*["']https?://images\.unsplash\.com|url\(\s*["']?https?://images\.unsplash\.com|fetch\(\s*["']https?://images\.unsplash\.com""",
            RegexOption.IGNORE_CASE
        )
        assertFalse(
            "The runtime bundle must not actively request remote Unsplash images",
            remoteUnsplashRequest.containsMatchIn(js)
        )
        assertFalse(
            "The production APK must not expose a repository source browser",
            js.contains("Bundled project source") || js.contains("Application source code")
        )
        listOf(
            "MeshPeerRegistry.kt",
            "MeshRouteRegistry.kt",
            "MeshRouter.kt",
            "MeshRouteRegistryTest.kt",
            "ARCHITECTURE.md",
            "legacy_mesh_driver_simulation.ts"
        ).forEach { marker ->
            assertFalse("The dashboard bundle must not embed repository source marker: $marker", js.contains(marker))
        }
        assertFalse("Source maps must not be packaged with dashboard assets", assetNames.any { it.endsWith(".map") })
    }
}
