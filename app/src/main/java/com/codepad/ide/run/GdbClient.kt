package com.codepad.ide.run

import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

/**
 * 极简 GDB Remote Serial Protocol 客户端（够用版）：
 * qSupported / ? / Z0 / z0 / c / s / g / m / k
 *
 * 单步是「一条条指令」走的，所以读寄存器（g）和读 PC 会被频繁调用，
 * 这里都按 gdbserver 的原始回包处理（含 RLE 解码）。
 */
class GdbClient(private val socket: Socket) {

    private val input = BufferedInputStream(socket.getInputStream())
    private val out: OutputStream = socket.getOutputStream()

    /** 目标进程自己往 RSP 通道写的输出（O 包），一般走 PTY，这里兜底 */
    @Volatile
    var onConsole: ((String) -> Unit)? = null

    private fun checksum(s: String): Int {
        var sum = 0
        for (ch in s) sum = (sum + ch.code) and 0xff
        return sum
    }

    fun send(command: String): String {
        val packet = "$$command#%02x".format(checksum(command))
        out.write(packet.toByteArray(Charsets.US_ASCII))
        out.flush()
        return readPacket()
    }

    /** 读一个「真正的回复」：跳过 O 包（目标输出）和 % 通知包 */
    private fun readPacket(): String {
        while (true) {
            val body = readRawPacket()
            when {
                // 注意 "OK" 是标准回复，不是 O 包（O 包后面跟的是十六进制）
                body.startsWith("O") && body != "OK" -> {
                    onConsole?.invoke(hexToText(body.substring(1)))
                }
                body.startsWith("%") -> {
                    // 通知包（例如线程创建/退出），这里不需要
                }
                else -> return body
            }
        }
    }

    private fun readRawPacket(): String {
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("gdbserver 连接已关闭")
            when (c) {
                '+'.code -> continue
                '-'.code -> continue
                '$'.code -> {
                    val sb = StringBuilder()
                    while (true) {
                        val b = input.read()
                        if (b < 0) throw IOException("gdbserver 连接已关闭")
                        if (b == '#'.code) break
                        sb.append(b.toChar())
                    }
                    input.read()
                    input.read()
                    out.write('+'.code)
                    out.flush()
                    return sb.toString()
                }
                else -> continue
            }
        }
    }

    /** 读全部通用寄存器（g 包，RLE 解码后前 34 个 8 字节；aarch64 顺序 x0..x30, sp, pc, cpsr） */
    fun readRegisters(): LongArray? {
        val raw = send("g")
        if (raw.isEmpty() || raw[0] == 'E') return null
        val hex = decodeRle(raw)
        val count = minOf(34, hex.length / 16)
        if (count == 0) return null
        val regs = LongArray(count)
        for (i in 0 until count) {
            regs[i] = parseLittleEndian(hex.substring(i * 16, (i + 1) * 16))
        }
        return regs
    }

    fun readMemory(addr: Long, len: Int): ByteArray? {
        if (len <= 0) return null
        val reply = send("m%x,%x".format(addr, len))
        if (reply.isEmpty() || reply[0] == 'E') return null
        val hex = decodeRle(reply)
        val n = minOf(len, hex.length / 2)
        if (n <= 0) return null
        val out = ByteArray(n)
        for (i in 0 until n) {
            val b = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = b.toByte()
        }
        return out
    }

    fun readLong(addr: Long): Long? {
        val bytes = readMemory(addr, 8) ?: return null
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[i].toLong() and 0xff) shl (8 * i))
        return v
    }

    /** GDB remote 的游程编码：c*<n> 表示把 c 再重复 (n-29) 次（c 本身已经出现一次） */
    private fun decodeRle(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '*' && sb.isNotEmpty() && i + 1 < text.length) {
                val count = text[i + 1].code - 29
                val last = sb[sb.length - 1]
                repeat(count.coerceAtLeast(0)) { sb.append(last) }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun hexToText(hex: String): String {
        val sb = StringBuilder(hex.length / 2)
        var i = 0
        while (i + 1 < hex.length) {
            val b = hex.substring(i, i + 2).toIntOrNull(16) ?: break
            sb.append(b.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun parseLittleEndian(hex: String): Long {
        var value = 0L
        var i = 0
        var byteIndex = 0
        while (i + 1 < hex.length && byteIndex < 8) {
            val b = hex.substring(i, i + 2).toIntOrNull(16) ?: return -1
            value = value or (b.toLong() shl (8 * byteIndex))
            i += 2
            byteIndex++
        }
        return if (byteIndex == 0) -1 else value
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }
}
