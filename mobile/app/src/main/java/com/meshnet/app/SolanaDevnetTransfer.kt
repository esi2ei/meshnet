package com.meshnet.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.math.BigInteger

/**
 * Minimal, narrowly-scoped Solana legacy SystemProgram transfer serializer.
 * The resulting unsigned transaction must be reviewed and signed by the connected wallet.
 */
object SolanaDevnetTransfer {
    private const val LAMPORTS_PER_SOL = 1_000_000_000L
    private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
    private val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun parseLamports(solAmount: String): Long {
        val decimal = BigDecimal(solAmount.trim())
        require(decimal.signum() > 0) { "SOL amount must be positive" }
        require(decimal.scale() <= 9) { "SOL amount supports at most 9 decimal places" }
        val lamports = decimal.movePointRight(9).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        require(lamports > 0L) { "SOL amount is below one lamport" }
        return lamports
    }

    fun decodeBase58(value: String): ByteArray {
        require(value.isNotEmpty()) { "Base58 value is empty" }
        var number = BigInteger.ZERO
        val radix = BigInteger.valueOf(58L)
        value.forEach { character ->
            val digit = BASE58.indexOf(character)
            require(digit >= 0) { "Invalid Base58 character" }
            number = number.multiply(radix).add(BigInteger.valueOf(digit.toLong()))
        }
        val encoded = number.toByteArray().let {
            if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        val leadingZeroes = value.takeWhile { it == '1' }.length
        return ByteArray(leadingZeroes + encoded.size).also {
            System.arraycopy(encoded, 0, it, leadingZeroes, encoded.size)
        }
    }

    fun encodeBase58(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var number = BigInteger(1, bytes)
        val radix = BigInteger.valueOf(58L)
        val result = StringBuilder()
        while (number.signum() > 0) {
            val parts = number.divideAndRemainder(radix)
            result.append(BASE58[parts[1].toInt()])
            number = parts[0]
        }
        val leadingZeroes = bytes.takeWhile { it == 0.toByte() }.size
        repeat(leadingZeroes) { result.append('1') }
        return result.reverse().toString()
    }

    fun buildUnsignedTransfer(
        feePayer: ByteArray,
        recipient: ByteArray,
        recentBlockhash: ByteArray,
        lamports: Long
    ): ByteArray {
        require(feePayer.size == 32) { "Fee payer public key must be 32 bytes" }
        require(recipient.size == 32) { "Recipient public key must be 32 bytes" }
        require(recentBlockhash.size == 32) { "Recent blockhash must be 32 bytes" }
        require(!feePayer.contentEquals(recipient)) { "Sender and recipient must be different accounts" }
        require(lamports > 0L) { "Transfer amount must be positive" }

        val systemProgram = decodeBase58(SYSTEM_PROGRAM)
        require(systemProgram.size == 32)
        val instructionData = byteArrayOf(2, 0, 0, 0) + ByteArray(8) { index ->
            (lamports ushr (index * 8)).toByte()
        }
        val instruction = byteArrayOf(2) +
            shortVector(2) + byteArrayOf(0, 1) +
            shortVector(instructionData.size) + instructionData

        val message = byteArrayOf(1, 0, 1) +
            shortVector(3) + feePayer + recipient + systemProgram +
            recentBlockhash +
            shortVector(1) + instruction

        // Legacy transaction: one required signature, represented by 64 zero bytes until MWA signs it.
        return shortVector(1) + ByteArray(64) + message
    }

    private fun shortVector(value: Int): ByteArray {
        require(value >= 0)
        var remaining = value
        val result = ArrayList<Byte>()
        do {
            var element = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) element = element or 0x80
            result.add(element.toByte())
        } while (remaining != 0)
        return result.toByteArray()
    }
}
