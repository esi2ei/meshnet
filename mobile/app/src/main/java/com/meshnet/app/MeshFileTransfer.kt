package com.meshnet.app
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/** Bounded group file transfer over encrypted MeshRouter text messages. */
class MeshFileTransfer(private val context: Context, private val sendMessage: (String) -> Boolean, private val onEvent: (String) -> Unit) {
    private data class Incoming(val name:String,val size:Int,val chunks:Int,val digest:String,val file:File,val received:BitSet=BitSet(),var receivedBytes:Int=0)
    private val incoming=ConcurrentHashMap<String,Incoming>()
    private val pending=ConcurrentHashMap<String,MutableMap<Int,Pair<Int,ByteArray>>>()
    private var pendingBytes=0
    private val root=File(context.filesDir,"meshnet-received").apply{mkdirs()}

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
        val id=ByteArray(9).also(java.security.SecureRandom()::nextBytes).let{Base64.encodeToString(it,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).take(16).joinToString(""){"%02x".format(it)}
        val count=(bytes.size+CHUNK_BYTES-1)/CHUNK_BYTES
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
            !hash.matches(Regex("[a-f0-9]{32}")) || (incoming.size>=MAX_ACTIVE && !incoming.containsKey(id)))return true
        val decoded=try{String(Base64.decode(f[5],Base64.URL_SAFE or Base64.NO_WRAP),Charsets.UTF_8)}catch(_:Exception){"mesh-file.bin"}
        val name=decoded.take(MAX_NAME_CHARS).replace(Regex("[^A-Za-z0-9._-]"),"_").ifBlank{"mesh-file.bin"}
        val partial=File(root,"$id.part")
        try{RandomAccessFile(partial,"rw").use{it.setLength(size.toLong())};incoming[id]=Incoming(name,size,count,hash,partial);onEvent("Incoming file: $name ($size bytes)")}
        catch(_:Exception){partial.delete();onEvent("Unable to create private transfer file")}
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
                pendingBytes+data.size>MAX_PENDING_BYTES || pending.size>=MAX_ACTIVE)return true
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
            if(s.received.cardinality()==s.chunks && s.receivedBytes==s.size){
                val actual=MessageDigest.getInstance("SHA-256").digest(s.file.readBytes()).take(16).joinToString(""){"%02x".format(it)}
                if(actual!=s.digest){s.file.delete();incoming.remove(id);onEvent("File integrity check failed: ${s.name}");return}
                val dest=uniqueDestination(s.name)
                if(s.file.renameTo(dest))onEvent("File received and verified: ${dest.name} (${s.size} bytes)") else onEvent("Verified file could not be moved")
                incoming.remove(id)
            }
        }catch(_:Exception){onEvent("File chunk could not be stored")}
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
    companion object{const val MAX_FILE_BYTES=16*1024;private const val CHUNK_BYTES=48;private const val MAX_CHUNKS=MAX_FILE_BYTES/CHUNK_BYTES+1;private const val MAX_NAME_CHARS=32;private const val MAX_ACTIVE=4;private const val MAX_PENDING_BYTES=MAX_FILE_BYTES*MAX_ACTIVE}
}