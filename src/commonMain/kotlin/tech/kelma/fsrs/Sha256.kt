package tech.kelma.fsrs

@OptIn(ExperimentalUnsignedTypes::class)
internal class Sha256 {
    private val state = uintArrayOf(
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u,
    )
    private val block = ByteArray(64)
    private var blockSize = 0
    private var byteCount = 0L

    fun update(text: String): Sha256 = update(text.encodeToByteArray())

    fun update(bytes: ByteArray): Sha256 {
        var offset = 0
        byteCount += bytes.size
        while (offset < bytes.size) {
            val count = minOf(64 - blockSize, bytes.size - offset)
            bytes.copyInto(block, blockSize, offset, offset + count)
            blockSize += count
            offset += count
            if (blockSize == 64) {
                transform(block)
                blockSize = 0
            }
        }
        return this
    }

    fun hexDigest(): String {
        val bitCount = byteCount * 8L
        update(byteArrayOf(0x80.toByte()))
        while (blockSize != 56) {
            update(byteArrayOf(0))
        }
        val length = ByteArray(8)
        for (index in 0..7) {
            length[7 - index] = (bitCount ushr (index * 8)).toByte()
        }
        update(length)
        return buildString(64) {
            state.forEach { word ->
                for (shift in 24 downTo 0 step 8) {
                    append(((word shr shift) and 0xffu).toString(16).padStart(2, '0'))
                }
            }
        }
    }

    private fun transform(input: ByteArray) {
        val words = UIntArray(64)
        for (index in 0 until 16) {
            val offset = index * 4
            words[index] =
                (input[offset].toUByte().toUInt() shl 24) or
                (input[offset + 1].toUByte().toUInt() shl 16) or
                (input[offset + 2].toUByte().toUInt() shl 8) or
                input[offset + 3].toUByte().toUInt()
        }
        for (index in 16 until 64) {
            val s0 = rotateRight(words[index - 15], 7) xor
                rotateRight(words[index - 15], 18) xor
                (words[index - 15] shr 3)
            val s1 = rotateRight(words[index - 2], 17) xor
                rotateRight(words[index - 2], 19) xor
                (words[index - 2] shr 10)
            words[index] = words[index - 16] + s0 + words[index - 7] + s1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (index in 0 until 64) {
            val sum1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choose = (e and f) xor (e.inv() and g)
            val temp1 = h + sum1 + choose + RoundConstants[index] + words[index]
            val sum0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = sum0 + majority
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    private fun rotateRight(value: UInt, bits: Int): UInt =
        (value shr bits) or (value shl (32 - bits))

    private companion object {
        val RoundConstants = uintArrayOf(
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
            0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
            0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
            0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
            0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
            0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
            0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
            0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
            0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
            0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
            0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
            0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
            0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
            0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
        )
    }
}

internal fun sha256Hex(text: String): String = Sha256().update(text).hexDigest()
