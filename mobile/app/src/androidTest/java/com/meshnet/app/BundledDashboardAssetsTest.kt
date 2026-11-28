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
        assertTrue("The source browser must bundle the Android peer registry", js.contains("MeshPeerRegistry.kt"))
        assertTrue("The source browser must bundle route registry and router sources", js.contains("MeshRouteRegistry.kt") && js.contains("MeshRouter.kt"))
        assertTrue("The source browser must bundle instrumentation tests and architecture docs", js.contains("MeshRouteRegistryTest.kt") && js.contains("ARCHITECTURE.md"))
        assertTrue("Legacy mock code must remain clearly isolated as a fixture", js.contains("legacy_mesh_driver_simulation.ts"))
    }
    @Test
    fun webViewNativeBridgePolicyAllowsOnlyCanonicalBundledAssets() {
        assertTrue(MeshWebViewSecurityPolicy.isBundledAsset(
            Uri.parse("https://appassets.androidplatform.net/assets/web/index.html")
        ))
        assertTrue(MeshWebViewSecurityPolicy.isBundledAsset(
            Uri.parse("https://appassets.androidplatform.net/assets/web/assets/app.js")
        ))
        assertFalse(MeshWebViewSecurityPolicy.isBundledAsset(
            Uri.parse("http://appassets.androidplatform.net/assets/web/index.html")
        ))
        assertFalse(MeshWebViewSecurityPolicy.isBundledAsset(
            Uri.parse("https://example.com/assets/web/index.html")
        ))
        assertFalse(MeshWebViewSecurityPolicy.isBundledAsset(
            Uri.parse("https://appassets.androidplatform.net/assets/other.js")
        ))
        assertFalse(MeshWebViewSecurityPolicy.isBundledAsset(
            Uri.parse("https://appassets.androidplatform.net:8443/assets/web/index.html")
        ))
        assertFalse(MeshWebViewSecurityPolicy.isBundledAsset(
            Uri.parse("https://appassets.androidplatform.net/assets/web/../private.html")
        ))
    }

}
