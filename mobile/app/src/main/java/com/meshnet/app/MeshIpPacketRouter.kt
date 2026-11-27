package com.meshnet.app

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

internal object MeshIpAddress {
    fun forNodeId(nodeId: String): String {
        require(nodeId.matches(Regex("[A-Za-z0-9_-]{16,32}"))) { "Invalid mesh node identity" }
        val digest = MessageDigest.getInstance("SHA-256").digest(nodeId.toByteArray(Charsets.UTF_8))
        val rawHost = (((digest[0].toInt() and 0xff) shl 14) or
            ((digest[1].toInt() and 0xff) shl 6) or
            ((digest[2].toInt() and 0xff) ushr 2)) and 0x3fffff
        val host = if (rawHost <= 1) rawHost + 2 else rawHost
        return "100.${64 + ((host ushr 16) and 0x3f)}.${(host ushr 8) and 0xff}.${host and 0xff}"
    }

    fun isOverlayAddress(address: String): Boolean {
        val bytes = parse(address) ?: return false
        return (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xff) in 64..127
    }

    fun dnsNameForNodeId(nodeId: String): String {
        val bytes = Base64.decode(nodeId, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(bytes.size == 16)
        val canonical = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(canonical == nodeId) { "Node ID is not a canonical device-key fingerprint" }
        val label = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "node-$label.mesh"
    }

    fun nodeIdFromDnsName(name: String): String? {
        val label = name.lowercase().removeSuffix(".mesh").removePrefix("node-")
        if (!label.matches(Regex("[a-f0-9]{32}"))) return null
        val bytes = ByteArray(16) { index -> label.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun parse(address: String): ByteArray? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return ByteArray(4) { values[it].toByte() }
    }

    fun format(bytes: ByteArray, offset: Int = 0): String {
        if (offset < 0 || offset + 4 > bytes.size) return ""
        return (0..3).joinToString(".") { (bytes[offset + it].toInt() and 0xff).toString() }
    }
}

/**
 * Split-tunnel IPv4 overlay for node-to-node traffic in 100.64.0.0/10.
 * Each IP packet is fragmented into bounded signed ephemeral MeshNet frames.
 */
internal class MeshIpPacketRouter(
    private val localNodeId: String,
    val localIp: String,
    private val sendToNode: (String, String) -> Boolean,
    private val resolveName: (String) -> String? = { null },
    private val forwardDnsQuery: (ByteArray) -> ByteArray? = { null },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val deliverPacket: (ByteArray) -> Unit
) {
    private data class Assembly(val count: Int, val parts: Array<ByteArray?>, var bytes: Int, val createdAt: Long)
    private data class NodeAddressRecord(val nodeId: String, val lastSeenAt: Long)
    private val nodesByIp = linkedMapOf<String, NodeAddressRecord>()
    private val conflicts = linkedSetOf<String>()
    private val assemblies = linkedMapOf<String, Assembly>()
    private val random = SecureRandom()

    init {
        require(localNodeId.matches(Regex("[A-Za-z0-9_-]{16,32}")))
        require(MeshIpAddress.isOverlayAddress(localIp))
        require(localIp == MeshIpAddress.forNodeId(localNodeId)) { "Mesh IP does not match the device identity" }
    }

    fun announcement(): String = "~IP1A|$localIp"

    @Synchronized
    fun accept(origin: String, message: String): Boolean {
        pruneKnownNodes()
        if (message.startsWith("~IP1A|")) {
            val fields = message.split('|')
            if (fields.size == 2 && MeshIpAddress.isOverlayAddress(fields[1])) observeNode(origin, fields[1])
            return true
        }
        if (!message.startsWith("~IP1P|")) return false
        val fields = message.split('|', limit = 5)
        if (fields.size != 5) return true
        val packetId = fields[1]
        val index = fields[2].toIntOrNull()
        val count = fields[3].toIntOrNull()
        if (!packetId.matches(Regex("[A-Za-z0-9_-]{12,20}")) ||
            index == null || count == null || count !in 1..MAX_CHUNKS || index !in 0 until count
        ) return true
        val chunk = runCatching {
            Base64.decode(fields[4], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return true
        if (chunk.isEmpty() || chunk.size > FRAGMENT_BYTES) return true
        pruneAssemblies()
        val key = "$origin:$packetId"
        var assembly = assemblies[key]
        if (assembly == null) {
            if (assemblies.size >= MAX_ASSEMBLIES) {
                assemblies.minByOrNull { it.value.createdAt }?.key?.let(assemblies::remove)
            }
            assembly = Assembly(count, arrayOfNulls(count), 0, nowMillis())
            assemblies[key] = assembly
        }
        if (assembly.count != count) { assemblies.remove(key); return true }
        val existing = assembly.parts[index]
        if (existing != null && !existing.contentEquals(chunk)) { assemblies.remove(key); return true }
        if (existing == null) {
            assembly.parts[index] = chunk
            assembly.bytes += chunk.size
        }
        if (assembly.bytes > MAX_PACKET_BYTES) { assemblies.remove(key); return true }
        if (assembly.parts.all { it != null }) {
            val packet = ByteArray(assembly.bytes)
            var offset = 0
            assembly.parts.forEach { part ->
                val data = part ?: return true
                data.copyInto(packet, offset)
                offset += data.size
            }
            assemblies.remove(key)
            val header = parseHeader(packet) ?: return true
            if (localIp !in conflicts && header.destination == localIp &&
                nodesByIp[header.source]?.nodeId == origin
            ) {
                refreshKnownNode(origin)
                deliverPacket(packet)
            }
        }
        return true
    }

    @Synchronized
    fun send(packet: ByteArray): Boolean {
        pruneKnownNodes()
        val header = parseHeader(packet) ?: return false
        if (header.source != localIp || !MeshIpAddress.isOverlayAddress(header.destination) || localIp in conflicts) return false
        if (header.destination == DNS_SERVICE_IP && header.protocol == IP_PROTOCOL_UDP && isDnsQuery(packet, header)) {
            buildDnsResponse(packet, header, DNS_SERVICE_IP)?.let(deliverPacket)
            return true
        }
        if (header.destination == localIp) {
            if (header.protocol == IP_PROTOCOL_UDP && isDnsQuery(packet, header)) {
                buildDnsResponse(packet, header)?.let(deliverPacket)
            } else {
                deliverPacket(packet)
            }
            return true
        }
        if (header.destination in conflicts) return false
        val target = nodesByIp[header.destination]?.nodeId ?: return false
        val id = Base64.encodeToString(ByteArray(9).also(random::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val count = (packet.size + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES
        if (count !in 1..MAX_CHUNKS) return false
        var accepted = true
        for (index in 0 until count) {
            val start = index * FRAGMENT_BYTES
            val end = minOf(packet.size, start + FRAGMENT_BYTES)
            val chunk = Base64.encodeToString(
                packet.copyOfRange(start, end), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val message = "~IP1P|$id|$index|$count|$chunk"
            if (message.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES || !sendToNode(target, message)) accepted = false
        }
        return accepted
    }

    @Synchronized
    fun knownNodeCount(): Int {
        pruneKnownNodes()
        return nodesByIp.size
    }

    @Synchronized fun hasAddressConflict(): Boolean = localIp in conflicts

    @Synchronized
    fun addressForNode(nodeId: String): String? {
        pruneKnownNodes()
        return nodesByIp.entries.firstOrNull { it.value.nodeId == nodeId }?.key
    }

    private fun observeNode(origin: String, address: String) {
        if (!origin.matches(Regex("[A-Za-z0-9_-]{16,32}")) || origin == localNodeId ||
            address != MeshIpAddress.forNodeId(origin)
        ) return
        if (address == localIp) { conflicts.add(localIp); nodesByIp.remove(localIp); return }
        val existing = nodesByIp[address]
        if (existing != null && existing.nodeId != origin) {
            nodesByIp.remove(address)
            conflicts.add(address)
            return
        }
        if (address in conflicts) return
        nodesByIp.entries.removeAll { it.value.nodeId == origin && it.key != address }
        nodesByIp[address] = NodeAddressRecord(origin, nowMillis())
        while (nodesByIp.size > MAX_NODES) {
            nodesByIp.minByOrNull { it.value.lastSeenAt }?.key?.let(nodesByIp::remove)
        }
    }

    private fun refreshKnownNode(origin: String) {
        val entry = nodesByIp.entries.firstOrNull { it.value.nodeId == origin } ?: return
        nodesByIp[entry.key] = entry.value.copy(lastSeenAt = nowMillis())
    }

    private fun pruneKnownNodes(now: Long = nowMillis()) {
        nodesByIp.entries.removeAll { now - it.value.lastSeenAt > NODE_ADDRESS_TTL_MS }
    }

    private fun parseHeader(packet: ByteArray): Header? {
        if (packet.size !in IPV4_HEADER_BYTES..MAX_PACKET_BYTES) return null
        if ((packet[0].toInt() ushr 4) != 4) return null
        val headerLength = (packet[0].toInt() and 0x0f) * 4
        if (headerLength < IPV4_HEADER_BYTES || headerLength > packet.size) return null
        val total = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        if (total != packet.size || total < headerLength) return null
        val source = MeshIpAddress.format(packet, 12)
        val destination = MeshIpAddress.format(packet, 16)
        if (!MeshIpAddress.isOverlayAddress(source) || !MeshIpAddress.isOverlayAddress(destination)) return null
        return Header(source, destination, packet[9].toInt() and 0xff, headerLength)
    }

    private fun isDnsQuery(packet: ByteArray, header: Header): Boolean {
        val udp = header.headerLength
        if (packet.size < udp + UDP_HEADER_BYTES) return false
        val destinationPort = readU16(packet, udp + 2)
        return destinationPort == DNS_PORT
    }

    private fun buildDnsResponse(packet: ByteArray, header: Header, resolverAddress: String = localIp): ByteArray? {
        val udpOffset = header.headerLength
        val udpLength = readU16(packet, udpOffset + 4)
        if (udpLength < UDP_HEADER_BYTES + DNS_HEADER_BYTES || udpOffset + udpLength > packet.size) return null
        val queryOffset = udpOffset + UDP_HEADER_BYTES
        val query = packet.copyOfRange(queryOffset, udpOffset + udpLength)
        if (query.size < DNS_HEADER_BYTES) return null
        val requestFlags = readU16(query, 2)
        if ((requestFlags and DNS_QR) != 0 || (requestFlags and DNS_OPCODE_MASK) != 0 || readU16(query, 4) != 1) {
            return null
        }
        var cursor = DNS_HEADER_BYTES
        val labels = mutableListOf<String>()
        while (cursor < query.size) {
            val length = query[cursor++].toInt() and 0xff
            if (length == 0) break
            if (length > 63 || cursor + length > query.size) return null
            labels.add(String(query, cursor, length, Charsets.US_ASCII))
            cursor += length
        }
        if (cursor + 4 > query.size || labels.isEmpty()) return null
        val questionEnd = cursor + 4
        val question = query.copyOfRange(DNS_HEADER_BYTES, questionEnd)
        val name = labels.joinToString(".").lowercase()
        val qtype = readU16(query, cursor)
        val qclass = readU16(query, cursor + 2)
        val isMeshName = name.endsWith(".mesh")
        if (!isMeshName) {
            val forwarded = runCatching { forwardDnsQuery(query) }.getOrNull()
            if (forwarded != null && forwarded.size in DNS_HEADER_BYTES..MAX_DNS_PAYLOAD_BYTES) {
                return buildDnsIpPacket(packet, header, resolverAddress, forwarded)
            }
        }
        val resolved = if (isMeshName && qtype == DNS_TYPE_A && qclass == DNS_CLASS_IN) resolveName(name) else null
        val answer = resolved?.let(MeshIpAddress::parse)
        val rcode = when {
            !isMeshName -> DNS_RCODE_SERVER_FAILURE
            qclass != DNS_CLASS_IN -> DNS_RCODE_NOT_IMPLEMENTED
            qtype != DNS_TYPE_A -> DNS_RCODE_NO_ERROR
            answer == null -> DNS_RCODE_NAME_ERROR
            else -> DNS_RCODE_NO_ERROR
        }
        val dns = ByteArray(DNS_HEADER_BYTES + question.size + if (answer != null) DNS_ANSWER_BYTES else 0)
        query.copyInto(dns, 0, 0, 2)
        val queryFlags = readU16(query, 2)
        writeU16(dns, 2, DNS_QR or (queryFlags and DNS_RD) or DNS_RA or rcode)
        writeU16(dns, 4, 1)
        writeU16(dns, 6, if (answer != null) 1 else 0)
        question.copyInto(dns, DNS_HEADER_BYTES)
        if (answer != null) {
            var out = DNS_HEADER_BYTES + question.size
            dns[out++] = 0xC0.toByte(); dns[out++] = 0x0C
            writeU16(dns, out, DNS_TYPE_A); out += 2
            writeU16(dns, out, DNS_CLASS_IN); out += 2
            dns[out++] = 0; dns[out++] = 0; dns[out++] = 0; dns[out++] = 60
            writeU16(dns, out, 4); out += 2
            answer.copyInto(dns, out)
        }
        return buildDnsIpPacket(packet, header, resolverAddress, dns)
    }

    private fun buildDnsIpPacket(
        packet: ByteArray,
        header: Header,
        resolverAddress: String,
        dnsPayload: ByteArray
    ): ByteArray? {
        if (dnsPayload.size !in DNS_HEADER_BYTES..MAX_DNS_PAYLOAD_BYTES) return null
        val udpOffset = header.headerLength
        val sourcePort = readU16(packet, udpOffset)
        val response = ByteArray(IPV4_HEADER_BYTES + UDP_HEADER_BYTES + dnsPayload.size)
        response[0] = 0x45
        writeU16(response, 2, response.size)
        writeU16(response, 4, random.nextInt(0xffff))
        response[8] = 64
        response[9] = IP_PROTOCOL_UDP.toByte()
        MeshIpAddress.parse(resolverAddress)!!.copyInto(response, 12)
        MeshIpAddress.parse(header.source)!!.copyInto(response, 16)
        writeU16(response, 10, ipv4Checksum(response, IPV4_HEADER_BYTES))
        writeU16(response, IPV4_HEADER_BYTES, DNS_PORT)
        writeU16(response, IPV4_HEADER_BYTES + 2, sourcePort)
        writeU16(response, IPV4_HEADER_BYTES + 4, UDP_HEADER_BYTES + dnsPayload.size)
        dnsPayload.copyInto(response, IPV4_HEADER_BYTES + UDP_HEADER_BYTES)
        return response
    }

    private fun ipv4Checksum(packet: ByteArray, length: Int): Int {
        var sum = 0L
        var index = 0
        while (index < length) {
            if (index != 10) sum += readU16(packet, index).toLong()
            index += 2
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 1] = (value and 0xff).toByte()
    }

    private fun pruneAssemblies(now: Long = nowMillis()) {
        assemblies.entries.removeAll { now - it.value.createdAt > ASSEMBLY_TTL_MS }
    }

    private data class Header(val source: String, val destination: String, val protocol: Int, val headerLength: Int)

    companion object {
        const val IPV4_HEADER_BYTES = 20
        const val MAX_PACKET_BYTES = 1500
        const val FRAGMENT_BYTES = 64
        const val MAX_PAYLOAD_BYTES = 120
        const val DNS_SERVICE_IP = "100.64.0.1"
        const val MAX_DNS_PAYLOAD_BYTES = 1_472
        private const val IP_PROTOCOL_UDP = 17
        private const val DNS_PORT = 53
        private const val DNS_TYPE_A = 1
        private const val DNS_CLASS_IN = 1
        private const val DNS_HEADER_BYTES = 12
        private const val DNS_ANSWER_BYTES = 16
        private const val UDP_HEADER_BYTES = 8
        private const val DNS_QR = 0x8000
        private const val DNS_RD = 0x0100
        private const val DNS_OPCODE_MASK = 0x7800
        private const val DNS_RA = 0x0080
        private const val DNS_RCODE_NO_ERROR = 0
        private const val DNS_RCODE_SERVER_FAILURE = 2
        private const val DNS_RCODE_NAME_ERROR = 3
        private const val DNS_RCODE_NOT_IMPLEMENTED = 4
        private const val MAX_CHUNKS = (MAX_PACKET_BYTES + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES
        private const val MAX_ASSEMBLIES = 64
        private const val MAX_NODES = 1024
        private const val NODE_ADDRESS_TTL_MS = 180_000L
        private const val ASSEMBLY_TTL_MS = 15_000L
    }
}
