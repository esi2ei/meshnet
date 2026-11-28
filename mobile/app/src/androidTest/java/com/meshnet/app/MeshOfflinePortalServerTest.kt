package com.meshnet.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket

class MeshOfflinePortalServerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var library: MeshOfflineLibrary
    private var portal: MeshOfflinePortalServer? = null

    @Before fun setUp() {
        root = File(context.filesDir, "meshnet-library")
        root.deleteRecursively()
        source = File(context.cacheDir, "portal-test.txt").apply { writeText("offline portal bytes") }
        library = MeshOfflineLibrary(context)
        library.publish(Uri.fromFile(source))
    }

    @After fun tearDown() {
        portal?.close()
        root.deleteRecursively()
        source.delete()
    }

    @Test fun servesCatalogueAndOnlyContentAddressedPublishedFiles() {
        portal = MeshOfflinePortalServer("127.0.0.1", library, 0, allowLoopbackForTests = true)
        assertTrue(portal!!.start())
        val catalogue = request("/api/catalog")
        assertTrue(catalogue.contains("portal-test.txt"))
        val item = library.items().single { it.local }
        val content = request("/content/${item.id}")
        assertTrue(content.contains("Content-Disposition: attachment; filename=\"portal-test.txt\""))
        assertTrue(content.endsWith("offline portal bytes"))
        assertTrue(request("/content/../../private").contains("404 Not Found"))
    }

    private fun request(path: String): String = Socket("127.0.0.1", portal!!.port).use { socket ->
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
    @Test(expected = IllegalArgumentException::class)
    fun refusesWildcardOrPublicAddressBindingOutsideTestMode() {
        MeshOfflinePortalServer("0.0.0.0", library)
    }

}
