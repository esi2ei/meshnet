package com.meshnet.app

import android.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MeshIpPacketRouterTest {
    @Test fun nodeAddressIsStableAndInsideTheOverlayPrefix() {
        val node = "AAAAAAAAAAAAAAAAAAAAAA"
        assertEquals(MeshIpAddress.forNodeId(node), MeshIpAddress.forNodeId(node))
        assertTrue(MeshIpAddress.isOverlayAddress(MeshIpAddress.forNodeId(node)))
        assertFalse(MeshIpAddress.isOverlayAddress("8.8.8.8"))
    }

    @Test fun fragmentsAndReassemblesIpv4PacketOutOfOrder() {
        val a = "source_node_12345678"
        val b = "target_node_12345678"
        val aIp = MeshIpAddress.forNodeId(a)
        val bIp = MeshIpAddress.forNodeId(b)
        val frames = mutableListOf<String>()
        val delivered = mutableListOf<ByteArray>()
        val sender = MeshIpPacketRouter(a, aIp, { _, m -> frames.add(m); true }) {}
        val receiver = MeshIpPacketRouter(b, bIp, { _, _ -> true }) { delivered.add(it) }
        sender.accept(b, receiver.announcement())
        receiver.accept(a, sender.announcement())
        val packet = ipv4Packet(aIp, bIp, 400)
        assertTrue(sender.send(packet))
        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.toByteArray(Charsets.UTF_8).size <= MeshIpPacketRouter.MAX_PAYLOAD_BYTES })
        frames.reversed().forEach { receiver.accept(a, it) }
        assertEquals(1, delivered.size)
        assertArrayEquals(packet, delivered.single())
    }

    @Test fun rejectsPacketWhenTransportOriginDoesNotOwnItsSourceIp() {
        val a = "source_node_12345678"
        val attacker = "attacker_node_12345678"
        val b = "target_node_12345678"
        val aIp = MeshIpAddress.forNodeId(a)
        val bIp = MeshIpAddress.forNodeId(b)
        val frames = mutableListOf<String>()
        val delivered = mutableListOf<ByteArray>()
        val sender = MeshIpPacketRouter(a, aIp, { _, m -> frames.add(m); true }) {}
        val receiver = MeshIpPacketRouter(b, bIp, { _, _ -> true }) { delivered.add(it) }
        sender.accept(b, receiver.announcement())
        receiver.accept(a, sender.announcement())
        assertTrue(sender.send(ipv4Packet(aIp, bIp, 32)))
        frames.forEach { receiver.accept(attacker, it) }
        assertTrue(delivered.isEmpty())
    }

    @Test fun conflictingNodeAddressesAreNotRouted() {
        val local = "local_node_12345678"
        val a = "remote_node_12345678"
        val b = "remote_node_87654321"
        val localIp = MeshIpAddress.forNodeId(local)
        val shared = MeshIpAddress.forNodeId(a)
        val router = MeshIpPacketRouter(local, localIp, { _, _ -> true }) {}
        router.accept(a, "~IP1A|$shared")
        router.accept(b, "~IP1A|$shared")
        assertEquals(0, router.knownNodeCount())
        assertFalse(router.send(ipv4Packet(localIp, shared, 8)))
    }

    @Test fun localPacketsAreLoopedBackIntoTheTunStack() {
        val local = "local_node_12345678"
        val localIp = MeshIpAddress.forNodeId(local)
        val delivered = mutableListOf<ByteArray>()
        val router = MeshIpPacketRouter(local, localIp, { _, _ -> true }) { delivered.add(it) }
        val packet = ipv4Packet(localIp, localIp, 8)
        assertTrue(router.send(packet))
        assertArrayEquals(packet, delivered.single())
    }

    private fun ipv4Packet(source: String, destination: String, payloadSize: Int): ByteArray {
        val packet = ByteArray(20 + 8 + payloadSize)
        packet[0] = 0x45
        packet[2] = ((packet.size ushr 8) and 0xff).toByte()
        packet[3] = (packet.size and 0xff).toByte()
        packet[8] = 64
        packet[9] = 17
        MeshIpAddress.parse(source)!!.copyInto(packet, 12)
        MeshIpAddress.parse(destination)!!.copyInto(packet, 16)
        packet[20] = 0x12
        packet[21] = 0x34
        packet[22] = 0x56
        packet[23] = 0x78
        val udpLength = 8 + payloadSize
        packet[24] = ((udpLength ushr 8) and 0xff).toByte()
        packet[25] = (udpLength and 0xff).toByte()
        return packet
    }

    @Test fun resolvesMeshDnsNamesToOverlayIpv4Addresses() {
        val localNode = "AAAAAAAAAAAAAAAAAAAAAA"
        val remoteNode = "BAAAAAAAAAAAAAAAAAAAAA"
        val localIp = MeshIpAddress.forNodeId(localNode)
        val remoteIp = MeshIpAddress.forNodeId(remoteNode)
        val responses = mutableListOf<ByteArray>()
        val router = MeshIpPacketRouter(
            localNode, localIp, { _, _ -> true }, { responses.add(it) },
            resolveName = { name ->
                when (name) {
                    MeshIpAddress.dnsNameForNodeId(localNode) -> localIp
                    MeshIpAddress.dnsNameForNodeId(remoteNode) -> remoteIp
                    else -> null
                }
            }
        )
        val query = dnsQueryPacket(localIp, MeshIpPacketRouter.DNS_SERVICE_IP, MeshIpAddress.dnsNameForNodeId(remoteNode))
        assertTrue(router.send(query))
        val response = responses.single()
        assertEquals(MeshIpPacketRouter.DNS_SERVICE_IP, MeshIpAddress.format(response, 12))
        assertEquals(localIp, MeshIpAddress.format(response, 16))
        assertEquals(53, u16(response, 20))
        assertEquals(50_000, u16(response, 22))
        assertEquals(remoteIp, dnsAnswerAddress(response))
    }

    private fun dnsQueryPacket(sourceIp: String, resolverIp: String, name: String): ByteArray {
        val labels = name.split('.')
        val question = ArrayList<Byte>()
        labels.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            question.add(bytes.size.toByte())
            bytes.forEach(question::add)
        }
        question.add(0)
        question.add(0); question.add(1) // A
        question.add(0); question.add(1) // IN
        val dns = ByteArray(12 + question.size)
        dns[0] = 0x12; dns[1] = 0x34
        dns[2] = 1; dns[5] = 1
        question.toByteArray().copyInto(dns, 12)
        val packet = ByteArray(20 + 8 + dns.size)
        packet[0] = 0x45
        packet[2] = ((packet.size ushr 8) and 0xff).toByte()
        packet[3] = (packet.size and 0xff).toByte()
        packet[8] = 64; packet[9] = 17
        MeshIpAddress.parse(sourceIp)!!.copyInto(packet, 12)
        MeshIpAddress.parse(resolverIp)!!.copyInto(packet, 16)
        packet[20] = 0xC3.toByte(); packet[21] = 0x50 // 50000
        packet[22] = 0; packet[23] = 53
        val udpLength = 8 + dns.size
        packet[24] = ((udpLength ushr 8) and 0xff).toByte()
        packet[25] = (udpLength and 0xff).toByte()
        dns.copyInto(packet, 28)
        return packet
    }

    private fun dnsAnswerAddress(packet: ByteArray): String {
        val dnsOffset = 28
        val dns = packet.copyOfRange(dnsOffset, packet.size)
        var cursor = 12
        while (cursor < dns.size && dns[cursor].toInt() != 0) {
            cursor += 1 + (dns[cursor].toInt() and 0xff)
        }
        cursor += 5 // zero terminator, QTYPE and QCLASS
        cursor += 2 // compressed answer name
        cursor += 8 // TYPE, CLASS, TTL
        val length = u16(dns, cursor); cursor += 2
        assertEquals(4, length)
        return MeshIpAddress.format(dns, cursor)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)


    @Test fun forwardsNonMeshDnsQueriesOnlyThroughTheInjectedUpstreamResolver() {
        val node = "AAAAAAAAAAAAAAAAAAAAAA"
        val localIp = MeshIpAddress.forNodeId(node)
        var forwardedQuery: ByteArray? = null
        val upstreamAnswer = ByteArray(12).apply {
            this[0] = 0x12
            this[1] = 0x34
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[5] = 1
        }
        val responses = mutableListOf<ByteArray>()
        val router = MeshIpPacketRouter(
            node, localIp, { _, _ -> true }, { responses.add(it) },
            resolveName = { null },
            forwardDnsQuery = { query -> forwardedQuery = query.copyOf(); upstreamAnswer }
        )
        val queryPacket = dnsQueryPacket(localIp, MeshIpPacketRouter.DNS_SERVICE_IP, "example.com")
        assertTrue(router.send(queryPacket))
        assertTrue(forwardedQuery != null)
        val response = responses.single()
        assertContentEquals(
            upstreamAnswer,
            response.copyOfRange(28, response.size)
        )
        assertEquals(MeshIpPacketRouter.DNS_SERVICE_IP, MeshIpAddress.format(response, 12))
    }

    @Test fun returnsServFailForPublicDnsWhenNoUpstreamResolverExists() {
        val node = "AAAAAAAAAAAAAAAAAAAAAA"
        val localIp = MeshIpAddress.forNodeId(node)
        val responses = mutableListOf<ByteArray>()
        val router = MeshIpPacketRouter(node, localIp, { _, _ -> true }, { responses.add(it) })
        assertTrue(router.send(dnsQueryPacket(localIp, MeshIpPacketRouter.DNS_SERVICE_IP, "example.com")))
        val dns = responses.single().copyOfRange(28, responses.single().size)
        val flags = u16(dns, 2)
        assertTrue((flags and 0x8000) != 0)
        assertEquals(2, flags and 0x000f)
    }


}
