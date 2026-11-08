package cn.jlu.schedule.auth

/**
 * 吉大统一认证（cas.jlu.edu.cn TPASS）登录页 des.js 的 Kotlin 移植。
 *
 * 算法要点：字符串按 UTF-16 码元切 4 个一组成 64 位块（与 JS charCodeAt 语义一致，
 * 中文按其码元值参与运算）；密钥同样分块；三密钥模式即 EDE 三重 DES。
 * 提交登录时 rsa = strEnc(用户名 + 密码 + lt, "1", "2", "3")。
 */
object TpassDes {

    fun encrypt(data: String, firstKey: String, secondKey: String, thirdKey: String): String {
        val firstBt = keyBytes(firstKey)
        val secondBt = keyBytes(secondKey)
        val thirdBt = keyBytes(thirdKey)
        val builder = StringBuilder()

        if (data.isEmpty()) return ""
        if (data.length < 4) {
            var block = strToBt(data)
            firstBt.forEach { block = enc(block, it) }
            secondBt.forEach { block = enc(block, it) }
            thirdBt.forEach { block = enc(block, it) }
            builder.append(bt64ToHex(block))
        } else {
            val iterator = data.length / 4
            val remainder = data.length % 4
            for (i in 0 until iterator) {
                var block = strToBt(data.substring(i * 4, i * 4 + 4))
                firstBt.forEach { block = enc(block, it) }
                secondBt.forEach { block = enc(block, it) }
                thirdBt.forEach { block = enc(block, it) }
                builder.append(bt64ToHex(block))
            }
            if (remainder > 0) {
                var block = strToBt(data.substring(iterator * 4))
                firstBt.forEach { block = enc(block, it) }
                secondBt.forEach { block = enc(block, it) }
                thirdBt.forEach { block = enc(block, it) }
                builder.append(bt64ToHex(block))
            }
        }
        return builder.toString()
    }

    fun decrypt(data: String, firstKey: String, secondKey: String, thirdKey: String): String {
        val firstBt = keyBytes(firstKey)
        val secondBt = keyBytes(secondKey)
        val thirdBt = keyBytes(thirdKey)
        val builder = StringBuilder()
        val iterator = data.length / 16
        for (i in 0 until iterator) {
            val hex = data.substring(i * 16, i * 16 + 16)
            var block = hexToBt64(hex)
            for (x in thirdBt.indices.reversed()) block = dec(block, thirdBt[x])
            for (y in secondBt.indices.reversed()) block = dec(block, secondBt[y])
            for (z in firstBt.indices.reversed()) block = dec(block, firstBt[z])
            builder.append(byteToString(block))
        }
        return builder.toString()
    }

    private fun keyBytes(key: String): List<IntArray> {
        val result = mutableListOf<IntArray>()
        val iterator = key.length / 4
        val remainder = key.length % 4
        for (i in 0 until iterator) {
            result += strToBt(key.substring(i * 4, i * 4 + 4))
        }
        if (remainder > 0) {
            result += strToBt(key.substring(iterator * 4))
        }
        return result
    }

    private fun strToBt(str: String): IntArray {
        val bt = IntArray(64)
        val length = str.length
        if (length < 4) {
            for (i in 0 until length) {
                val k = str[i].code
                for (j in 0 until 16) {
                    var pow = 1
                    for (m in 15 downTo j + 1) pow *= 2
                    bt[16 * i + j] = (k / pow) % 2
                }
            }
            for (p in length until 4) {
                for (q in 0 until 16) {
                    bt[16 * p + q] = 0
                }
            }
        } else {
            for (i in 0 until 4) {
                val k = str[i].code
                for (j in 0 until 16) {
                    var pow = 1
                    for (m in 15 downTo j + 1) pow *= 2
                    bt[16 * i + j] = (k / pow) % 2
                }
            }
        }
        return bt
    }

    private fun byteToString(byteData: IntArray): String {
        val builder = StringBuilder()
        for (i in 0 until 4) {
            var count = 0
            for (j in 0 until 16) {
                var pow = 1
                for (m in 15 downTo j + 1) pow *= 2
                count += byteData[16 * i + j] * pow
            }
            if (count != 0) builder.append(count.toChar())
        }
        return builder.toString()
    }

    private fun bt64ToHex(byteData: IntArray): String {
        val hex = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
        val builder = StringBuilder()
        for (i in 0 until 16) {
            var index = 0
            for (j in 0 until 4) index = (index shl 1) + byteData[i * 4 + j]
            builder.append(hex[index])
        }
        return builder.toString()
    }

    private fun hexToBt64(hex: String): IntArray {
        val binary = IntArray(64)
        for (i in 0 until 16) {
            val value = Character.digit(hex[i], 16)
            for (j in 0 until 4) binary[i * 4 + j] = (value shr (3 - j)) and 1
        }
        return binary
    }

    private fun enc(dataByte: IntArray, keyByte: IntArray): IntArray {
        val keys = generateKeys(keyByte)
        val ipByte = initPermute(dataByte)
        val ipLeft = IntArray(32)
        val ipRight = IntArray(32)
        for (k in 0 until 32) {
            ipLeft[k] = ipByte[k]
            ipRight[k] = ipByte[32 + k]
        }
        for (i in 0 until 16) {
            val tempLeft = ipLeft.copyOf()
            for (j in 0 until 32) ipLeft[j] = ipRight[j]
            val tempRight = xor(
                pPermute(sBoxPermute(xor(expandPermute(ipRight), keys[i]))),
                tempLeft
            )
            for (n in 0 until 32) ipRight[n] = tempRight[n]
        }
        val finalData = IntArray(64)
        for (i in 0 until 32) {
            finalData[i] = ipRight[i]
            finalData[32 + i] = ipLeft[i]
        }
        return finallyPermute(finalData)
    }

    private fun dec(dataByte: IntArray, keyByte: IntArray): IntArray {
        val keys = generateKeys(keyByte)
        val ipByte = initPermute(dataByte)
        val ipLeft = IntArray(32)
        val ipRight = IntArray(32)
        for (k in 0 until 32) {
            ipLeft[k] = ipByte[k]
            ipRight[k] = ipByte[32 + k]
        }
        for (i in 15 downTo 0) {
            val tempLeft = ipLeft.copyOf()
            for (j in 0 until 32) ipLeft[j] = ipRight[j]
            val tempRight = xor(
                pPermute(sBoxPermute(xor(expandPermute(ipRight), keys[i]))),
                tempLeft
            )
            for (n in 0 until 32) ipRight[n] = tempRight[n]
        }
        val finalData = IntArray(64)
        for (i in 0 until 32) {
            finalData[i] = ipRight[i]
            finalData[32 + i] = ipLeft[i]
        }
        return finallyPermute(finalData)
    }

    private fun initPermute(originalData: IntArray): IntArray {
        val ipByte = IntArray(64)
        var m = 1
        var n = 0
        for (i in 0 until 4) {
            for (j in 7 downTo 0) {
                ipByte[i * 8 + (7 - j)] = originalData[j * 8 + m]
                ipByte[i * 8 + (7 - j) + 32] = originalData[j * 8 + n]
            }
            m += 2
            n += 2
        }
        return ipByte
    }

    private fun expandPermute(rightData: IntArray): IntArray {
        val epByte = IntArray(48)
        for (i in 0 until 8) {
            epByte[i * 6 + 0] = if (i == 0) rightData[31] else rightData[i * 4 - 1]
            epByte[i * 6 + 1] = rightData[i * 4 + 0]
            epByte[i * 6 + 2] = rightData[i * 4 + 1]
            epByte[i * 6 + 3] = rightData[i * 4 + 2]
            epByte[i * 6 + 4] = rightData[i * 4 + 3]
            epByte[i * 6 + 5] = if (i == 7) rightData[0] else rightData[i * 4 + 4]
        }
        return epByte
    }

    private fun xor(byteOne: IntArray, byteTwo: IntArray): IntArray {
        return IntArray(byteOne.size) { byteOne[it] xor byteTwo[it] }
    }

    private fun sBoxPermute(expandByte: IntArray): IntArray {
        val sBoxByte = IntArray(32)
        for (m in 0 until 8) {
            val i = expandByte[m * 6 + 0] * 2 + expandByte[m * 6 + 5]
            val j = (expandByte[m * 6 + 1] shl 3) or (expandByte[m * 6 + 2] shl 2) or
                (expandByte[m * 6 + 3] shl 1) or expandByte[m * 6 + 4]
            val value = S_BOX[m][i][j]
            for (k in 0 until 4) sBoxByte[m * 4 + k] = (value shr (3 - k)) and 1
        }
        return sBoxByte
    }

    private fun pPermute(sBoxByte: IntArray): IntArray {
        return IntArray(32) { sBoxByte[P_BOX[it]] }
    }

    private fun finallyPermute(endByte: IntArray): IntArray {
        return IntArray(64) { endByte[FP_BOX[it]] }
    }

    private fun generateKeys(keyByte: IntArray): List<IntArray> {
        val key = IntArray(56)
        for (i in 0 until 7) {
            for (j in 0 until 8) {
                key[i * 8 + j] = keyByte[8 * (7 - j) + i]
            }
        }
        val keys = mutableListOf<IntArray>()
        for (round in 0 until 16) {
            repeat(LOOP_SHIFTS[round]) {
                val tempLeft = key[0]
                val tempRight = key[28]
                for (k in 0 until 27) {
                    key[k] = key[k + 1]
                    key[28 + k] = key[29 + k]
                }
                key[27] = tempLeft
                key[55] = tempRight
            }
            keys += IntArray(48) { key[PC2_BOX[it]] }
        }
        return keys
    }

    private val S_BOX = arrayOf(
        arrayOf(
            intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7),
            intArrayOf(0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8),
            intArrayOf(4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0),
            intArrayOf(15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13)
        ),
        arrayOf(
            intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10),
            intArrayOf(3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5),
            intArrayOf(0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15),
            intArrayOf(13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9)
        ),
        arrayOf(
            intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8),
            intArrayOf(13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1),
            intArrayOf(13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7),
            intArrayOf(1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12)
        ),
        arrayOf(
            intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15),
            intArrayOf(13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9),
            intArrayOf(10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4),
            intArrayOf(3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14)
        ),
        arrayOf(
            intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9),
            intArrayOf(14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6),
            intArrayOf(4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14),
            intArrayOf(11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3)
        ),
        arrayOf(
            intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11),
            intArrayOf(10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8),
            intArrayOf(9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6),
            intArrayOf(4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13)
        ),
        arrayOf(
            intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1),
            intArrayOf(13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6),
            intArrayOf(1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2),
            intArrayOf(6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12)
        ),
        arrayOf(
            intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7),
            intArrayOf(1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2),
            intArrayOf(7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8),
            intArrayOf(2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11)
        )
    )

    private val P_BOX = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16,
        0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8,
        18, 12, 29, 5, 21, 10, 3, 24
    )

    private val FP_BOX = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31,
        38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29,
        36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27,
        34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25,
        32, 0, 40, 8, 48, 16, 56, 24
    )

    private val PC2_BOX = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27,
        14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55,
        33, 52, 45, 41, 49, 35, 28, 31
    )

    private val LOOP_SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
}
