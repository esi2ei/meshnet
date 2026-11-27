package com.meshnet.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaDevnetTransferTest {
    @Test
    fun parsesSolAmountsToExactLamports() {
        assertEquals(1_000_000_000L, SolanaDevnetTransfer.parseLamports("1"))
        assertEquals(12_345_678L, SolanaDevnetTransfer.parseLamports("0.012345678"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSubLamportPrecision() {
        SolanaDevnetTransfer.parseLamports("0.0000000001")
    }

    @Test
    fun base58RoundTripsSystemProgramAddress() {
        val address = "11111111111111111111111111111111"
        assertEquals(32, SolanaDevnetTransfer.decodeBase58(address).size)
        assertEquals(address, SolanaDevnetTransfer.encodeBase58(SolanaDevnetTransfer.decodeBase58(address)))
    }

    @Test
    fun buildsUnsignedLegacySystemTransferForWalletSigning() {
        val payer = ByteArray(32) { (it + 1).toByte() }
        val recipient = ByteArray(32) { (it + 33).toByte() }
        val blockhash = ByteArray(32) { (it + 65).toByte() }
        val lamports = 987_654_321L

        val transaction = SolanaDevnetTransfer.buildUnsignedTransfer(payer, recipient, blockhash, lamports)

        assertEquals(215, transaction.size)
        assertEquals(1, transaction[0].toInt())
        assertTrue(transaction.copyOfRange(1, 65).all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(1, 0, 1), transaction.copyOfRange(65, 68))
        assertArrayEquals(payer, transaction.copyOfRange(69, 101))
        assertArrayEquals(recipient, transaction.copyOfRange(101, 133))
        assertArrayEquals(blockhash, transaction.copyOfRange(165, 197))
        assertEquals(2, transaction[203].toInt())
        assertEquals(lamports, transaction.copyOfRange(207, 215).foldIndexed(0L) { index, value, byte ->
            value or ((byte.toLong() and 0xffL) shl (index * 8))
        })
    }
}
