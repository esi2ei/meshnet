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
    @Test fun publishReplacesCorruptBlobAtTheExpectedContentAddress() {
        val expected = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { "%02x".format(it) }
        File(root, "$expected.blob").writeText("corrupted prior file")

        val item = library.publish(Uri.fromFile(source))

        assertEquals(expected, item.id)
        assertArrayEquals(source.readBytes(), library.localFile(item.id)!!.readBytes())
    }

    @Test fun malformedRemoteCatalogueEntryDoesNotHideLaterValidEntries() {
        val validId = "d".repeat(64)
        val now = System.currentTimeMillis()
        val catalogue = org.json.JSONObject()
            .put("local", org.json.JSONArray())
            .put("remote", org.json.JSONArray()
                .put(org.json.JSONObject()
                    .put("id", "invalid")
                    .put("origin", "bad origin")
                    .put("size", 0)
                    .put("createdAt", now))
                .put(org.json.JSONObject()
                    .put("id", validId)
                    .put("origin", "node_valid")
                    .put("size", 64)
                    .put("name", "valid.txt")
                    .put("createdAt", now)))
            .put("pending", org.json.JSONArray())
        File(root, "catalog.json").writeText(catalogue.toString())

        val recovered = MeshOfflineLibrary(context)

        assertEquals("node_valid", recovered.remoteItem(validId)!!.origin)
        assertEquals(1, recovered.items().size)
    }

    @Test fun staleAndFarFutureRemoteAdvertisementsAreRejected() {
        val id = "e".repeat(64)
        val now = System.currentTimeMillis()
        assertFalse(library.observeRemote("node_stale", id, 64, "stale.txt", now - 8L * 24L * 60L * 60L * 1000L))
        assertFalse(library.items().any { it.id == id })
        assertThrows(IllegalArgumentException::class.java) {
            library.observeRemote("node_future", id, 64, "future.txt", now + 60L * 60L * 1000L)
        }
        assertFalse(library.items().any { it.id == id })
    }

    @Test fun corruptedExistingBlobIsRemovedAndSameContentCanBePublishedAgain() {
        val first = library.publish(Uri.fromFile(source))
        library.localFile(first.id)!!.writeText("tampered bytes")

        assertNull(library.localFile(first.id))
        val repaired = library.publish(Uri.fromFile(source))

        assertEquals(first.id, repaired.id)
        assertArrayEquals(source.readBytes(), library.localFile(repaired.id)!!.readBytes())
    }

    @Test fun recoveryEnforcesLocalItemCountAndDeletesUnindexedBlobs() {
        val entries = org.json.JSONArray()
        var overflowId: String? = null
        val now = System.currentTimeMillis()
        repeat(MeshOfflineLibrary.MAX_LOCAL_ITEMS + 1) { index ->
            val bytes = byteArrayOf(index.toByte())
            val id = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            File(root, "$id.blob").writeBytes(bytes)
            entries.put(org.json.JSONObject()
                .put("id", id)
                .put("name", "item-$index.bin")
                .put("size", bytes.size)
                .put("createdAt", now))
            if (index == MeshOfflineLibrary.MAX_LOCAL_ITEMS) overflowId = id
        }
        File(root, "catalog.json").writeText(org.json.JSONObject()
            .put("local", entries)
            .put("remote", org.json.JSONArray())
            .put("pending", org.json.JSONArray())
            .toString())

        val recovered = MeshOfflineLibrary(context)

        assertEquals(MeshOfflineLibrary.MAX_LOCAL_ITEMS, recovered.items().count { it.local })
        assertFalse(File(root, "$overflowId.blob").exists())
    }

    @Test fun localLibraryEnforcesPerItemSizeLimit(){
        val oversized=File(context.cacheDir,"mesh-library-oversized.bin")
        oversized.writeBytes(ByteArray(MeshOfflineLibrary.MAX_FILE_BYTES+1))
        try{assertThrows(IllegalArgumentException::class.java){library.publish(Uri.fromFile(oversized))}}
        finally{oversized.delete()}
    }
}
