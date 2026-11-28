package com.meshnet.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Explicitly published, content-addressed offline files and bounded remote catalogue. */
internal class MeshOfflineLibrary(context: Context) {
    data class Item(val id:String,val name:String,val size:Long,val createdAt:Long,val local:Boolean,val origin:String?,val pending:Boolean=false)
    private val appContext=context.applicationContext
    private val root=File(appContext.filesDir,"meshnet-library").apply{mkdirs()}
    private val index=File(root,"catalog.json")
    private val atomicIndex=AtomicFile(index)
    private val local=linkedMapOf<String,Item>()
    private val remote=linkedMapOf<String,Item>()
    private val pending=linkedSetOf<String>()
    @Volatile private var catalogMetadataCorrupt = false
    @Volatile private var catalogOverQuota = false
    private val corruptBlobIds = linkedSetOf<String>()
    val isCatalogHealthy: Boolean
        get() = !catalogMetadataCorrupt && !catalogOverQuota && corruptBlobIds.isEmpty()
    val isCatalogCorrupt: Boolean get() = catalogMetadataCorrupt
    val isCatalogOverQuota: Boolean get() = catalogOverQuota
    val corruptBlobCount: Int get() = corruptBlobIds.size

    private fun requireTrustedCatalog() {
        check(!catalogMetadataCorrupt) {
            "Offline library catalogue metadata is damaged; content files were preserved and destructive recovery is disabled"
        }
    }

    private fun requireHealthyCatalog() {
        requireTrustedCatalog()
        check(!catalogOverQuota && corruptBlobIds.isEmpty()) {
            "Offline library needs recovery: remove over-quota items or explicitly replace/remove corrupt content before adding more"
        }
    }

    private fun refreshQuotaState() {
        catalogOverQuota = local.size > MAX_LOCAL_ITEMS ||
            local.values.sumOf { it.size } > MAX_STORAGE_BYTES
    }

    init { load() }

    @Synchronized fun publish(uri:Uri):Item {
        requireHealthyCatalog()
        val temp=File(root,"publish-${System.nanoTime()}.tmp")
        val hash=MessageDigest.getInstance("SHA-256")
        var size=0L
        try {
            val input=appContext.contentResolver.openInputStream(uri) ?: error("Unable to read selected offline content")
            input.use { source -> FileOutputStream(temp).use { output ->
                val buffer=ByteArray(IO_BUFFER_BYTES)
                while(true){val n=source.read(buffer);if(n<0)break;if(n==0)continue
                    size+=n;require(size in 1..MAX_FILE_BYTES.toLong()){ "Offline content must be between 1 byte and 10 MiB" }
                    hash.update(buffer,0,n);output.write(buffer,0,n)
                }
                output.fd.sync()
            }}
            val id=hash.digest().toHex()
            local[id]?.let { existing ->
                if (localFile(id) != null) {
                    temp.delete()
                    return existing
                }
            }
            requireTrustedCatalog()
            if (id in corruptBlobIds) {
                // Re-publishing the same verified content is an explicit repair of this blob.
                corruptBlobIds.remove(id)
                local.remove(id)
                refreshQuotaState()
            }
            requireHealthyCatalog()
            val name=safeName(queryName(uri))
            val dest=fileFor(id)
            ensureCapacity(size, if (dest.isFile) dest.length() else 0L)
            if (dest.exists() && (dest.length() != size || runCatching { digest(dest) }.getOrNull() != id)) {
                check(dest.delete()) { "Unable to replace corrupt offline content" }
            }
            if (dest.exists()) temp.delete()
            else check(temp.renameTo(dest)) { "Unable to commit offline content" }
            check(dest.length() == size && digest(dest) == id) { "Published content failed integrity verification" }
            val item=Item(id,name,size,System.currentTimeMillis(),true,null)
            local[id]=item;remote.remove(id);persist();return item
        } catch(e:Exception){temp.delete();throw e}
    }

    @Synchronized fun observeRemote(origin:String,id:String,size:Long,name:String,now:Long=System.currentTimeMillis()):Boolean {
        requireHealthyCatalog()
        require(origin.matches(Regex("[A-Za-z0-9_-]{1,32}"))){ "Invalid content provider identity" }
        require(id.matches(Regex("[a-f0-9]{64}"))){ "Invalid content digest" }
        require(size in 1..MAX_FILE_BYTES.toLong()){ "Invalid advertised content size" }
        val currentTime = System.currentTimeMillis()
        require(now in 1..(currentTime + MAX_FUTURE_CLOCK_SKEW_MS)) { "Invalid advertisement timestamp" }
        if (currentTime - now > REMOTE_TTL_MS) return false
        if(local.containsKey(id))return false
        val previous=remote[id]
        remote[id]=Item(id,safeName(name),size,now,false,origin)
        pruneRemote(now)
        while(remote.size>MAX_REMOTE_ITEMS){val oldest=remote.values.minByOrNull{it.createdAt}?:break;remote.remove(oldest.id)}
        persist();return previous==null
    }

    @Synchronized fun remoteItem(id:String):Item? { pruneRemote(System.currentTimeMillis()); return remote[id] }
    @Synchronized fun localFile(id:String):File? {
        val item=local[id]?:return null
        val file=fileFor(id)
        if(!file.isFile||file.length()!=item.size||runCatching{digest(file)}.getOrNull()!=id){
            // Preserve a damaged or missing blob for explicit recovery; never delete it
            // merely because an integrity check failed during a read.
            corruptBlobIds.add(id)
            return null
        }
        return file
    }
    @Synchronized fun markPending(id:String):Boolean {
        requireHealthyCatalog()
        if(!remote.containsKey(id)||id in pending||pending.size>=MAX_PENDING_DOWNLOADS)return false
        pending.add(id);persist();return true
    }
    @Synchronized fun cancelPending(id:String){requireHealthyCatalog();pending.remove(id);persist()}
    @Synchronized fun completeDownload(id:String,received:File):Boolean {
        requireHealthyCatalog()
        val advertised=remote[id] ?: run { pending.remove(id); persist(); return false }
        if(id !in pending||!received.isFile||received.length()!=advertised.size||runCatching{digest(received)}.getOrNull()!=id){
            pending.remove(id);persist();return false
        }
        val existingLocalIsValid = local[id]?.let { localFile(id) != null } == true
        if (!existingLocalIsValid) {
            requireTrustedCatalog()
            if (id in corruptBlobIds) {
                corruptBlobIds.remove(id)
                local.remove(id)
                refreshQuotaState()
            }
            val temp=File(root,"$id.tmp")
            val dest=fileFor(id)
            requireHealthyCatalog()
            ensureCapacity(advertised.size, if (dest.isFile) dest.length() else 0L)
            try {
                received.inputStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output, IO_BUFFER_BYTES)
                        output.fd.sync()
                    }
                }
                check(temp.length() == advertised.size && digest(temp) == id) {
                    "Downloaded content changed while being committed"
                }
                if (dest.exists() && (dest.length() != advertised.size || runCatching { digest(dest) }.getOrNull() != id)) {
                    check(dest.delete()) { "Unable to replace corrupt offline content" }
                }
                if (dest.exists()) temp.delete()
                else check(temp.renameTo(dest)) { "Unable to commit offline content" }
                check(dest.length() == advertised.size && digest(dest) == id) {
                    "Committed offline content failed integrity verification"
                }
            } catch (error: Exception) {
                temp.delete()
                throw error
            }
            local[id]=advertised.copy(createdAt=System.currentTimeMillis(),local=true,origin=null,pending=false)
            refreshQuotaState()
        }
        pending.remove(id);remote.remove(id);persist();return true
    }
    @Synchronized fun removeLocal(id:String):Boolean {
        requireTrustedCatalog()
        if(local[id]==null)return false
        val file = fileFor(id)
        check(!file.exists() || file.delete()) { "Unable to remove the selected offline content item" }
        local.remove(id)
        corruptBlobIds.remove(id)
        refreshQuotaState()
        persist()
        return true
    }
    @Synchronized fun items(now:Long=System.currentTimeMillis()):List<Item>{
        pruneRemote(now)
        return (local.values.map{it.copy(pending=false)}+remote.values.map{it.copy(pending=it.id in pending)})
            .sortedWith(compareByDescending<Item>{it.local}.thenByDescending{it.createdAt}.thenBy{it.name})
    }
    @Synchronized fun toJson():JSONArray=JSONArray().apply{items().forEach{put(JSONObject()
        .put("id",it.id).put("name",it.name).put("size",it.size).put("createdAt",it.createdAt)
        .put("local",it.local).put("origin",it.origin?:JSONObject.NULL).put("pending",it.pending))}}

    private fun load(){
        val raw = try {
            atomicIndex.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: java.io.FileNotFoundException) {
            return
        }
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            // Preserve all content blobs when the catalogue itself cannot be trusted.
            catalogMetadataCorrupt = true
            return
        }
        val localValue = json.opt("local")
        if (localValue !is JSONArray) {
            catalogMetadataCorrupt = true
            return
        }
        val remoteValue = json.opt("remote")
        val pendingValue = json.opt("pending")
        if ((remoteValue != null && remoteValue !is JSONArray) ||
            (pendingValue != null && pendingValue !is JSONArray)
        ) {
            catalogMetadataCorrupt = true
            return
        }

        val now = System.currentTimeMillis()
        val locals = localValue
        for (i in 0 until locals.length()) {
            val entry = try {
                locals.getJSONObject(i)
            } catch (_: Exception) {
                catalogMetadataCorrupt = true
                local.clear()
                return
            }
            val id = try { entry.getString("id") } catch (_: Exception) {
                catalogMetadataCorrupt = true; local.clear(); return
            }
            val size = try { entry.getLong("size") } catch (_: Exception) {
                catalogMetadataCorrupt = true; local.clear(); return
            }
            val createdAt = try { entry.getLong("createdAt") } catch (_: Exception) {
                catalogMetadataCorrupt = true; local.clear(); return
            }
            if (!id.matches(Regex("[a-f0-9]{64}")) ||
                size !in 1..MAX_FILE_BYTES.toLong() ||
                createdAt !in 1..(now + MAX_FUTURE_CLOCK_SKEW_MS) ||
                id in local
            ) {
                // Invalid ownership metadata or duplicate IDs make the index untrusted.
                catalogMetadataCorrupt = true
                local.clear()
                return
            }

            val file = fileFor(id)
            if (!file.isFile || file.length() != size || runCatching { digest(file) }.getOrNull() != id) {
                // Keep the catalogue row and file path; the owner can explicitly remove
                // or replace the corrupt item. Never delete it as a side effect of startup.
                corruptBlobIds.add(id)
            }
            local[id] = Item(id, safeName(entry.optString("name")), size, createdAt, true, null)
        }
        refreshQuotaState()

        val remotes = remoteValue as? JSONArray ?: JSONArray()
        for (i in 0 until remotes.length()) {
            // Remote catalogue records are untrusted peer advertisements. Ignore only the
            // malformed advertisement, not subsequent valid entries or local file ownership.
            runCatching {
                val entry = remotes.getJSONObject(i)
                val id = entry.getString("id")
                val origin = entry.getString("origin")
                val size = entry.getLong("size")
                val createdAt = entry.getLong("createdAt")
                require(id.matches(Regex("[a-f0-9]{64}")))
                require(origin.matches(Regex("[A-Za-z0-9_-]{1,32}")))
                require(size in 1..MAX_FILE_BYTES.toLong())
                require(createdAt in 1..(now + MAX_FUTURE_CLOCK_SKEW_MS))
                require(now - createdAt <= REMOTE_TTL_MS)
                if (!local.containsKey(id) && remote.size < MAX_REMOTE_ITEMS) {
                    remote[id] = Item(id, safeName(entry.getString("name")), size, createdAt, false, origin)
                }
            }
        }

        val pendingEntries = pendingValue as? JSONArray ?: JSONArray()
        for (i in 0 until pendingEntries.length()) {
            val id = pendingEntries.optString(i)
            if (remote.containsKey(id) && pending.size < MAX_PENDING_DOWNLOADS) pending.add(id)
        }
        pruneRemote(now)
        if (!isCatalogHealthy) return
        pruneOrphanedFiles()
        persist()
    }

    private fun pruneOrphanedFiles() {
        // A .blob without an index row may be the result of a crash after the blob commit
        // and before the AtomicFile index commit. Preserve it so a later explicit publish
        // of the same content can re-index it. Temporary partial writes are safe to remove.
        root.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) runCatching { file.delete() }
        }
    }
    private fun persist(){
        requireTrustedCatalog()
        val json = JSONObject().put("local", JSONArray().apply {
            local.values.forEach { put(itemJson(it)) }
        }).put("remote", JSONArray().apply {
            remote.values.forEach { put(itemJson(it)) }
        }).put("pending", JSONArray().apply {
            pending.forEach { put(it) }
        })
        val stream = atomicIndex.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomicIndex.finishWrite(stream)
        } catch (error: Exception) {
            atomicIndex.failWrite(stream)
            throw error
        }
    }
    private fun itemJson(item: Item): JSONObject = JSONObject()
        .put("id", item.id)
        .put("name", item.name)
        .put("size", item.size)
        .put("createdAt", item.createdAt)
        .put("local", item.local)
        .put("origin", item.origin ?: JSONObject.NULL)
        .put("pending", item.pending)

    private fun ensureCapacity(incoming:Long, replacingBlobBytes:Long = 0L){
        require(incoming in 1..MAX_FILE_BYTES.toLong())
        require(replacingBlobBytes >= 0L)
        val physicalBlobBytes = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".blob") }
            ?.sumOf { it.length() } ?: 0L
        val projectedBytes = (physicalBlobBytes - replacingBlobBytes).coerceAtLeast(0L) + incoming
        require(local.size < MAX_LOCAL_ITEMS && projectedBytes <= MAX_STORAGE_BYTES) {
            "Offline library is full; remove a selected item or re-index recovered content before adding more"
        }
    }
    private fun pruneRemote(now:Long){
        val expired=remote.values.filter {
            it.createdAt > now + MAX_FUTURE_CLOCK_SKEW_MS || now - it.createdAt > REMOTE_TTL_MS
        }.map{it.id}.toSet()
        remote.entries.removeAll{it.key in expired}
        if(expired.isNotEmpty())pending.removeAll(expired)
    }
    private fun fileFor(id:String)=File(root,"$id.blob")
    private fun safeName(value:String):String{
        val safe=value.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"),"_").take(MAX_NAME_CHARS)
        return safe.takeIf{it.isNotBlank()&&it!="."&&it!=".."}?:"offline-content.bin"
    }
    private fun queryName(uri:Uri):String{
        if(uri.scheme=="file")return uri.lastPathSegment?:"offline-content.bin"
        return runCatching{appContext.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()?:"offline-content.bin"
    }
    private fun digest(file:File):String{
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use{input->val buffer=ByteArray(IO_BUFFER_BYTES);while(true){val n=input.read(buffer);if(n<0)break;if(n>0)hash.update(buffer,0,n)}}
        return hash.digest().toHex()
    }
    private fun ByteArray.toHex()=joinToString(""){"%02x".format(it)}
    companion object{
        const val MAX_FILE_BYTES=10*1024*1024
        const val MAX_STORAGE_BYTES=100L*1024L*1024L
        const val MAX_LOCAL_ITEMS=32
        const val MAX_REMOTE_ITEMS=256
        const val MAX_PENDING_DOWNLOADS=8
        private const val MAX_NAME_CHARS=32
        private const val IO_BUFFER_BYTES=8192
        private const val REMOTE_TTL_MS=7L*24L*60L*60L*1000L
        private const val MAX_FUTURE_CLOCK_SKEW_MS=5L*60L*1000L
    }
}
