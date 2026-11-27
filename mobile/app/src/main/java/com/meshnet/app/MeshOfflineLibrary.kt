package com.meshnet.app

import android.content.Context
import android.net.Uri
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
    private val local=linkedMapOf<String,Item>()
    private val remote=linkedMapOf<String,Item>()
    private val pending=linkedSetOf<String>()
    init { load() }

    @Synchronized fun publish(uri:Uri):Item {
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
            local[id]?.let{temp.delete();return it}
            ensureCapacity(size)
            val name=safeName(queryName(uri))
            val dest=fileFor(id)
            if(dest.exists())temp.delete() else check(temp.renameTo(dest)){ "Unable to commit offline content" }
            val item=Item(id,name,size,System.currentTimeMillis(),true,null)
            local[id]=item;remote.remove(id);persist();return item
        } catch(e:Exception){temp.delete();throw e}
    }

    @Synchronized fun observeRemote(origin:String,id:String,size:Long,name:String,now:Long=System.currentTimeMillis()):Boolean {
        require(origin.matches(Regex("[A-Za-z0-9_-]{1,32}"))){ "Invalid content provider identity" }
        require(id.matches(Regex("[a-f0-9]{64}"))){ "Invalid content digest" }
        require(size in 1..MAX_FILE_BYTES.toLong()){ "Invalid advertised content size" }
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
        if(!file.isFile||file.length()!=item.size||runCatching{digest(file)}.getOrNull()!=id){local.remove(id);persist();return null}
        return file
    }
    @Synchronized fun markPending(id:String):Boolean {
        if(!remote.containsKey(id)||id in pending||pending.size>=MAX_PENDING_DOWNLOADS)return false
        pending.add(id);persist();return true
    }
    @Synchronized fun cancelPending(id:String){pending.remove(id);persist()}
    @Synchronized fun completeDownload(id:String,received:File):Boolean {
        val advertised=remote[id]?:return false
        if(id !in pending||!received.isFile||received.length()!=advertised.size||runCatching{digest(received)}.getOrNull()!=id){
            pending.remove(id);persist();return false
        }
        if(!local.containsKey(id)){
            ensureCapacity(advertised.size)
            val temp=File(root,"$id.tmp")
            received.inputStream().use{input->FileOutputStream(temp).use{output->input.copyTo(output,IO_BUFFER_BYTES);output.fd.sync()}}
            val dest=fileFor(id)
            if(dest.exists())temp.delete() else check(temp.renameTo(dest)){ "Unable to commit downloaded content" }
            local[id]=advertised.copy(createdAt=System.currentTimeMillis(),local=true,origin=null,pending=false)
        }
        pending.remove(id);remote.remove(id);persist();return true
    }
    @Synchronized fun removeLocal(id:String):Boolean {
        if(local.remove(id)==null)return false
        fileFor(id).delete();persist();return true
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
        if(!index.isFile)return
        runCatching{
            val json=JSONObject(index.readText());val locals=json.optJSONArray("local")?:JSONArray()
            for(i in 0 until locals.length()){
                val e=locals.getJSONObject(i);val id=e.getString("id")
                if(!id.matches(Regex("[a-f0-9]{64}")))continue
                val file=fileFor(id)
                val item=Item(id,safeName(e.getString("name")),e.getLong("size"),e.getLong("createdAt"),true,null)
                if(file.isFile&&file.length()==item.size&&runCatching{digest(file)}.getOrNull()==id)local[id]=item
            }
            val remotes=json.optJSONArray("remote")?:JSONArray()
            for(i in 0 until remotes.length()){
                val e=remotes.getJSONObject(i)
                val id=e.getString("id")
                val origin=e.getString("origin")
                val size=e.getLong("size")
                val createdAt=e.getLong("createdAt")
                if(id.matches(Regex("[a-f0-9]{64}")) &&
                    origin.matches(Regex("[A-Za-z0-9_-]{1,32}")) &&
                    size in 1..MAX_FILE_BYTES.toLong() && !local.containsKey(id)
                ) remote[id]=Item(id,safeName(e.getString("name")),size,createdAt,false,origin)
            }
            val pend=json.optJSONArray("pending")?:JSONArray()
            for(i in 0 until pend.length()){val id=pend.optString(i);if(remote.containsKey(id)&&pending.size<MAX_PENDING_DOWNLOADS)pending.add(id)}
        }
        persist()
    }
    private fun persist(){
        val json=JSONObject().put("local",JSONArray().apply{local.values.forEach{put(itemJson(it))}})
            .put("remote",JSONArray().apply{remote.values.forEach{put(itemJson(it))}})
            .put("pending",JSONArray().apply{pending.forEach{put(it)}})
        val temp=File(root,"catalog.json.tmp")
        FileOutputStream(temp).use{it.write(json.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
        if(index.exists())check(index.delete()){ "Unable to replace offline catalogue" }
        check(temp.renameTo(index)){ "Unable to persist offline catalogue" }
    }
    private fun itemJson(i:Item)=JSONObject().put("id",i.id).put("name",i.name).put("size",i.size).put("createdAt",i.createdAt).put("origin",i.origin?:JSONObject.NULL)
    private fun ensureCapacity(incoming:Long){
        require(incoming in 1..MAX_FILE_BYTES.toLong())
        require(local.size<MAX_LOCAL_ITEMS&&local.values.sumOf{it.size}+incoming<=MAX_STORAGE_BYTES){
            "Offline library is full; remove an item before publishing or downloading more content"
        }
    }
    private fun pruneRemote(now:Long){remote.entries.removeAll{now-it.value.createdAt>REMOTE_TTL_MS}}
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
    }
}
