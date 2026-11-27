package com.meshnet.app
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/** Bounded group file transfer over encrypted MeshRouter text messages. */
class MeshFileTransfer(
    private val context: Context,
    private val sendMessage: (String) -> Boolean,
    private val onEvent: (String) -> Unit,
    private val canQueueBatch: (Int) -> Boolean = { true }
) {
    private data class Incoming(val name:String,val size:Int,val chunks:Int,val digest:String,val file:File,val meta:File,val received:BitSet=BitSet(),var receivedBytes:Int=0)
    private val incoming=ConcurrentHashMap<String,Incoming>()
    private val pending=ConcurrentHashMap<String,MutableMap<Int,Pair<Int,ByteArray>>>()
    private var pendingBytes=0
    private val root=File(context.filesDir,"meshnet-received").apply{
        mkdirs()
        listFiles()?.filter { (it.name.endsWith(".part") || it.name.endsWith(".meta")) &&
            System.currentTimeMillis()-it.lastModified()>PARTIAL_TTL_MS }?.forEach { it.delete() }
    }
    private val outgoingRoot=File(context.filesDir,"meshnet-outgoing").apply{mkdirs()}

    @Synchronized fun send(uri:Uri):Boolean {
        val name=queryName(uri).take(MAX_NAME_CHARS).ifBlank{"mesh-file.bin"}.replace(Regex("[^A-Za-z0-9._-]"),"_")
        val bytes=context.contentResolver.openInputStream(uri)?.use { input ->
            val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(CHUNK_BYTES)
            while(true){ val n=input.read(buffer); if(n<0) break
                require(output.size()+n<=MAX_FILE_BYTES){"File exceeds the 16 KiB transfer limit"}
                output.write(buffer,0,n)
            }; output.toByteArray()
        } ?: throw IllegalArgumentException("Unable to open selected file")
        require(bytes.isNotEmpty()){"Empty files are not supported"}
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val id=transferIdFor(digest,name)
        val count=(bytes.size+CHUNK_BYTES-1)/CHUNK_BYTES
        if (!canQueueBatch(count + 1)) {
            onEvent("File not queued: outbox does not have room for all ${count + 1} file frames")
            return false
        }
        val safeName=Base64.encodeToString(name.toByteArray(Charsets.UTF_8),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        if(!sendMessage("~F1M|$id|${bytes.size}|$count|$digest|$safeName")) return false
        for(index in 0 until count){
            val start=index*CHUNK_BYTES
            val encoded=Base64.encodeToString(bytes.copyOfRange(start,minOf(start+CHUNK_BYTES,bytes.size)),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            if(!sendMessage("~F1C|$id|$index|$count|$encoded")){onEvent("File transfer $id paused at chunk $index/$count");return false}
        }
        onEvent("File queued: $name (${bytes.size} bytes, $count encrypted chunks)")
        return true
    }

    @Synchronized fun accept(message:String):Boolean {
        if(!message.startsWith("~F1")) return false
        val f=message.split('|')
        return when(f.firstOrNull()){ "~F1M"->manifest(f); "~F1C"->chunk(f); else->true }
    }
    private fun manifest(f:List<String>):Boolean {
        if(f.size!=6)return true
        val id=f[1]; if(!id.matches(Regex("[A-Za-z0-9_-]{8,24}")))return true
        val size=f[2].toIntOrNull()?:return true; val count=f[3].toIntOrNull()?:return true; val hash=f[4]
        if(size !in 1..MAX_FILE_BYTES || count !in 1..MAX_CHUNKS || count!=(size+CHUNK_BYTES-1)/CHUNK_BYTES ||
            !hash.matches(Regex("[a-f0-9]{64}")) || (incoming.size>=MAX_ACTIVE && !incoming.containsKey(id)))return true
        val decoded=try{String(Base64.decode(f[5],Base64.URL_SAFE or Base64.NO_WRAP),Charsets.UTF_8)}catch(_:Exception){"mesh-file.bin"}
        val name=decoded.take(MAX_NAME_CHARS).replace(Regex("[^A-Za-z0-9._-]"),"_").ifBlank{"mesh-file.bin"}
        val partial=File(root,"$id.part")
        val meta=File(root,"$id.meta")
        try {
            val restored=if(partial.exists() && meta.exists()) runCatching {
                val saved=JSONObject(meta.readText())
                if(saved.optString("name")==name && saved.optInt("size")==size &&
                    saved.optInt("chunks")==count && saved.optString("digest")==hash) {
                    BitSet.valueOf(Base64.decode(saved.optString("received"),Base64.DEFAULT))
                } else null
            }.getOrNull() else null
            val bits=restored ?: run {
                partial.delete();meta.delete()
                RandomAccessFile(partial,"rw").use{it.setLength(size.toLong())}
                BitSet()
            }
            var storedBytes=0
            var bit=bits.nextSetBit(0)
            while(bit>=0 && bit<count) {
                storedBytes+=minOf(CHUNK_BYTES,size-bit*CHUNK_BYTES)
                bit=bits.nextSetBit(bit+1)
            }
            val state=Incoming(name,size,count,hash,partial,meta,bits,storedBytes)
            incoming[id]=state
            persistMetadata(state)
            onEvent(if(bits.isEmpty) "Incoming file: $name ($size bytes)" else "Resuming file: $name (${bits.cardinality()}/$count chunks already stored)")
        } catch(_:Exception) {
            partial.delete();meta.delete();onEvent("Unable to create private transfer file")
        }
        val early=pending.remove(id)
        if(early!=null){for((index,pair) in early){pendingBytes-=pair.second.size;storeChunk(id,index,pair.first,pair.second)}}
        return true
    }
    private fun chunk(f:List<String>):Boolean {
        if(f.size!=5)return true
        val id=f[1];val index=f[2].toIntOrNull()?:return true;val count=f[3].toIntOrNull()?:return true
        val data=try{Base64.decode(f[4],Base64.URL_SAFE or Base64.NO_WRAP)}catch(_:Exception){return true}
        val s=incoming[id]
        if(s==null){
            if(count !in 1..MAX_CHUNKS || index !in 0 until count || data.size !in 1..CHUNK_BYTES ||
                pendingBytes+data.size>MAX_PENDING_BYTES || (pending.size>=MAX_ACTIVE && !pending.containsKey(id)))return true
            val chunks=pending.getOrPut(id){linkedMapOf()}
            if(!chunks.containsKey(index)){chunks[index]=count to data;pendingBytes+=data.size}
            return true
        }
        storeChunk(id,index,count,data)
        return true
    }
    private fun storeChunk(id:String,index:Int,count:Int,data:ByteArray){
        val s=incoming[id]?:return
        if(count!=s.chunks || index !in 0 until s.chunks || s.received[index] ||
            data.size!=minOf(CHUNK_BYTES,s.size-index*CHUNK_BYTES))return
        try{
            RandomAccessFile(s.file,"rw").use{it.seek((index*CHUNK_BYTES).toLong());it.write(data)}
            s.received.set(index);s.receivedBytes+=data.size
            persistMetadata(s)
            if(s.received.cardinality()==s.chunks && s.receivedBytes==s.size){
                val actual=MessageDigest.getInstance("SHA-256").digest(s.file.readBytes()).joinToString(""){"%02x".format(it)}
                if(actual!=s.digest){s.file.delete();s.meta.delete();incoming.remove(id);onEvent("File integrity check failed: ${s.name}");return}
                val dest=uniqueDestination(s.name)
                if(s.file.renameTo(dest)){s.meta.delete();onEvent("File received and verified: ${dest.name} (${s.size} bytes)")} else onEvent("Verified file could not be moved")
                incoming.remove(id)
            }
        }catch(_:Exception){onEvent("File chunk could not be stored")}
    }
    private fun persistMetadata(state:Incoming) {
        val temp=File(root,"${state.file.name}.meta.tmp")
        val json=JSONObject()
            .put("name",state.name)
            .put("size",state.size)
            .put("chunks",state.chunks)
            .put("digest",state.digest)
            .put("received",Base64.encodeToString(state.received.toByteArray(),Base64.NO_WRAP))
        temp.writeText(json.toString())
        if(state.meta.exists()) state.meta.delete()
        if(!temp.renameTo(state.meta)) { temp.delete(); throw IllegalStateException("Unable to persist transfer resume state") }
    }

    private fun transferIdFor(digest:String,name:String):String {
        val key=MessageDigest.getInstance("SHA-256").digest("$digest|$name".toByteArray(Charsets.UTF_8))
            .joinToString(""){"%02x".format(it)}
        val file=File(outgoingRoot,"$key.id")
        if(file.exists()) {
            val saved=file.readText().trim()
            if(saved.matches(Regex("[A-Za-z0-9_-]{8,24}"))) return saved
        }
        val id=ByteArray(9).also(java.security.SecureRandom()::nextBytes)
            .let{Base64.encodeToString(it,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}
        file.writeText(id)
        return id
    }

    private fun uniqueDestination(name:String):File{
        var f=File(root,name);var n=1
        while(f.exists()){val dot=name.lastIndexOf('.');f=if(dot>0)File(root,name.substring(0,dot)+"-$n"+name.substring(dot))else File(root,"$name-$n");n++}
        return f
    }
    private fun queryName(uri:Uri):String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "voice-note.m4a"
        return try {
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)
                ?.use{c->if(c.moveToFirst())c.getString(0)?:"mesh-file.bin" else "mesh-file.bin"}?:"mesh-file.bin"
        } catch(_:Exception) { "mesh-file.bin" }
    }
    companion object{const val MAX_FILE_BYTES=16*1024;private const val CHUNK_BYTES=48;private const val MAX_CHUNKS=MAX_FILE_BYTES/CHUNK_BYTES+1;private const val MAX_NAME_CHARS=20;private const val MAX_ACTIVE=4;private const val MAX_PENDING_BYTES=MAX_FILE_BYTES*MAX_ACTIVE;private const val PARTIAL_TTL_MS=24L*60L*60L*1000L}
}