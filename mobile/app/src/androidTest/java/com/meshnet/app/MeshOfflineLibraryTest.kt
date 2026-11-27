package com.meshnet.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class MeshOfflineLibraryTest {
    private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var root:File
    private lateinit var source:File
    private lateinit var library:MeshOfflineLibrary

    @Before fun setUp(){
        root=File(context.filesDir,"meshnet-library");root.deleteRecursively()
        source=File(context.cacheDir,"mesh-library-test.txt").apply{writeText("offline mesh content")}
        library=MeshOfflineLibrary(context)
    }
    @After fun tearDown(){root.deleteRecursively();source.delete()}

    @Test fun publishingIsExplicitAndProducesStableContentAddress(){
        assertTrue(library.items().isEmpty())
        val item=library.publish(Uri.fromFile(source))
        val expected=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)}
        assertEquals(expected,item.id);assertTrue(item.local)
        assertArrayEquals(source.readBytes(),library.localFile(item.id)!!.readBytes())
    }
    @Test fun remoteAdvertisementIsBoundedAndExpires(){
        val id="a".repeat(64)
        val now = System.currentTimeMillis()
        assertTrue(library.observeRemote("node_remote",id,100,"manual.pdf",now))
        assertEquals("node_remote",library.remoteItem(id)!!.origin)
        assertFalse(runCatching{library.observeRemote("invalid node","b".repeat(64),100,"bad.pdf")}.isSuccess)
        assertTrue(library.items(now + 8L*24L*60L*60L*1000L).isEmpty())
    }
    @Test fun downloadedContentIsOnlyPromotedAfterRequestedDigestMatches(){
        val item=library.publish(Uri.fromFile(source))
        root.deleteRecursively();library=MeshOfflineLibrary(context)
        assertTrue(library.observeRemote("node_remote",item.id,item.size,item.name))
        assertTrue(library.markPending(item.id))
        val bad=File(context.cacheDir,"mesh-library-bad.txt").apply{writeText("tampered")}
        assertFalse(library.completeDownload(item.id,bad));bad.delete()
        assertTrue(library.markPending(item.id));assertTrue(library.completeDownload(item.id,source))
        assertTrue(library.localFile(item.id)!!.isFile);assertNull(library.remoteItem(item.id))
    }
    @Test fun catalogueAndPendingDownloadsSurviveRecreation(){
        val item=library.publish(Uri.fromFile(source));val remote="c".repeat(64)
        assertTrue(library.observeRemote("node_remote",remote,120,"guide.txt"));assertTrue(library.markPending(remote))
        val restarted=MeshOfflineLibrary(context)
        assertNotNull(restarted.localFile(item.id));assertEquals("node_remote",restarted.remoteItem(remote)!!.origin)
        assertTrue(restarted.toJson().getJSONObject(1).getBoolean("pending"))
    }
    @Test fun localLibraryEnforcesPerItemSizeLimit(){
        val oversized=File(context.cacheDir,"mesh-library-oversized.bin")
        oversized.writeBytes(ByteArray(MeshOfflineLibrary.MAX_FILE_BYTES+1))
        try{assertThrows(IllegalArgumentException::class.java){library.publish(Uri.fromFile(oversized))}}
        finally{oversized.delete()}
    }
}
