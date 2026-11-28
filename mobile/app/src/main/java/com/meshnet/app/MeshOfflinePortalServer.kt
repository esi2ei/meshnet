package com.meshnet.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small bounded HTTP portal bound only to the VPN overlay address. It exposes explicitly
 * published library items, never arbitrary filesystem paths or the device's whole storage.
 */
internal class MeshOfflinePortalServer(
    private val bindAddress: String,
    private val library: MeshOfflineLibrary,
    private val requestedPort: Int = DEFAULT_PORT,
    private val onEvent: (String) -> Unit = {},
    private val allowLoopbackForTests: Boolean = false
) : Closeable {
    init {
        require(
            MeshIpAddress.isOverlayAddress(bindAddress) ||
                (allowLoopbackForTests && bindAddress == "127.0.0.1")
        ) { "Offline portal must bind to the private mesh overlay address" }
        require(requestedPort in 1..65_535 || (allowLoopbackForTests && requestedPort == 0)) {
            "Invalid offline portal port"
        }
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = ThreadPoolExecutor(
        2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "meshnet-portal-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    val port: Int get() = server?.localPort ?: requestedPort
    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        val socket = ServerSocket()
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bindAddress, requestedPort), 16)
            server = socket
            running.set(true)
            acceptThread = Thread({ acceptLoop(socket) }, "meshnet-offline-portal").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            runCatching { socket.close() }
            onEvent("Offline portal could not bind to $bindAddress:$requestedPort: ${error.message}")
            false
        }
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (running.get()) {
            val client = try { listener.accept() } catch (_: Exception) { break }
            try {
                client.soTimeout = SOCKET_TIMEOUT_MS
                workers.execute { handle(client) }
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input, MAX_REQUEST_LINE_BYTES) ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size != 3 || parts[2] != "HTTP/1.1" && parts[2] != "HTTP/1.0") {
                respond(client, 400, "Bad Request", "text/plain; charset=utf-8", "Bad request".toByteArray())
                return
            }
            var headerBytes = 0
            var headerLines = 0
            while (true) {
                val line = readLine(input, MAX_HEADER_BYTES) ?: break
                headerBytes += line.length
                headerLines++
                if (headerBytes > MAX_HEADER_BYTES || headerLines > MAX_HEADER_LINES) {
                    respond(client, 431, "Request Header Fields Too Large", "text/plain", ByteArray(0))
                    return
                }
                if (line.isEmpty()) break
            }
            if (parts[0] != "GET" && parts[0] != "HEAD") {
                respond(client, 405, "Method Not Allowed", "text/plain; charset=utf-8", "GET only".toByteArray())
                return
            }
            val path = runCatching { URI(parts[1]).rawPath ?: "/" }.getOrDefault("/")
            when {
                path == "/" -> {
                    val body = indexHtml().toByteArray(StandardCharsets.UTF_8)
                    respond(client, 200, "OK", "text/html; charset=utf-8", body, headOnly = parts[0] == "HEAD")
                }
                path == "/api/catalog" -> {
                    val body = catalogJson().toByteArray(StandardCharsets.UTF_8)
                    respond(client, 200, "OK", "application/json; charset=utf-8", body, headOnly = parts[0] == "HEAD")
                }
                path.matches(Regex("/content/[a-f0-9]{64}")) -> {
                    val id = path.substringAfterLast('/')
                    val item = library.items().firstOrNull { it.id == id && it.local }
                    val file = library.localFile(id)
                    if (file == null || item == null) {
                        respond(client, 404, "Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
                    } else {
                        streamFile(client, file, parts[0] == "HEAD", item.name)
                    }
                }
                else -> respond(client, 404, "Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
            }
        }
    }

    private fun indexHtml(): String {
        val entries = library.items().filter { it.local }
        val list = if (entries.isEmpty()) "<p>No content has been published on this node.</p>" else
            entries.joinToString("") {
                "<li><a href=\"/content/${it.id}\">${escape(it.name)}</a> <small>(${it.size} bytes)</small></li>"
            }
        return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MeshNet Offline Library</title><style>body{font:16px system-ui;max-width:760px;margin:3rem auto;padding:0 1rem;background:#0b1220;color:#e5e7eb}a{color:#67e8f9}li{margin:1rem 0}small{color:#94a3b8}</style>
<h1>MeshNet Offline Library</h1><p>This page is served by a nearby mesh node. Only files explicitly published by this device appear here.</p>
<ul>$list</ul><p><a href="/api/catalog">JSON catalogue</a></p></html>"""
    }

    private fun catalogJson(): String = JSONArray().apply {
        library.items().filter { it.local }.forEach {
            put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("createdAt", it.createdAt))
        }
    }.toString()

    private fun streamFile(socket: Socket, file: File, headOnly: Boolean, displayName: String) {
        val output = BufferedOutputStream(socket.getOutputStream())
        val name = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "offline-content.bin" }
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nContent-Disposition: attachment; filename=\"$name\"\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) file.inputStream().use { it.copyTo(output, IO_BUFFER_BYTES) }
        output.flush()
    }

    private fun respond(
        socket: Socket,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean = false
    ) {
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write(("HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) output.write(body)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= limit) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.UTF_8)
            if (value == 10) break
            if (value != 13) bytes.add(value.toByte())
        }
        if (bytes.size > limit) return null
        return bytes.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    @Synchronized
    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        workers.shutdownNow()
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val MAX_REQUEST_LINE_BYTES = 2_048
        private const val MAX_HEADER_BYTES = 8_192
        private const val MAX_HEADER_LINES = 32
        private const val IO_BUFFER_BYTES = 8_192
    }
}
