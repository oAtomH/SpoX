package io.github.oatomh.spox.engine

import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket

/** A small WebSocket client (text messages only), enough for Soloist's local control API. */
class Ws private constructor(private val sock: Socket) {
    private val input = DataInputStream(sock.getInputStream().buffered(65536))
    private val out: OutputStream = sock.getOutputStream()
    private val rnd = java.util.Random()

    companion object {
        fun open(port: Int): Ws {
            val s = Socket("127.0.0.1", port)
            s.tcpNoDelay = true
            val key = android.util.Base64.encodeToString(ByteArray(16).also { java.util.Random().nextBytes(it) }, android.util.Base64.NO_WRAP)
            s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            val w = Ws(s)
            // response headers, up to the blank line
            val line = StringBuilder()
            var last4 = 0
            while (true) {
                val b = w.input.read()
                if (b < 0) throw java.io.IOException("closed")
                line.append(b.toChar())
                last4 = (last4 shl 8) or b
                if (last4 == 0x0d0a0d0a) break
            }
            if (!line.startsWith("HTTP/1.1 101")) throw java.io.IOException(line.lineSequence().first())
            return w
        }
    }

    @Synchronized
    fun send(text: String) {
        runCatching {
            val data = text.toByteArray()
            val head = java.io.ByteArrayOutputStream()
            head.write(0x81)
            when {
                data.size < 126 -> head.write(0x80 or data.size)
                data.size < 65536 -> { head.write(0x80 or 126); head.write(data.size shr 8); head.write(data.size and 255) }
                else -> { head.write(0x80 or 127); for (i in 7 downTo 0) head.write(((data.size.toLong() shr (8 * i)) and 255).toInt()) }
            }
            val mask = ByteArray(4).also { rnd.nextBytes(it) }
            head.write(mask)
            for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            out.write(head.toByteArray() + data)
            out.flush()
        }
    }

    /** The next text message, or null when the connection ends. */
    fun read(): String? {
        val msg = java.io.ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.readUnsignedByte()
            var len = (b1 and 127).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong()
            else if (len == 127L) len = input.readLong()
            val mask = if (b1 and 128 != 0) ByteArray(4).also { input.readFully(it) } else null
            val data = ByteArray(len.toInt())
            input.readFully(data)
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (b0 and 15) {
                8 -> return null
                9 -> sendRaw(0x8A, data)
                10 -> {}
                else -> {
                    msg.write(data)
                    if (b0 and 128 != 0) return msg.toString("UTF-8")
                }
            }
        }
    }

    @Synchronized
    private fun sendRaw(op: Int, data: ByteArray) {
        runCatching {
            val mask = ByteArray(4).also { rnd.nextBytes(it) }
            val d = data.copyOf(); for (i in d.indices) d[i] = (d[i].toInt() xor mask[i and 3].toInt()).toByte()
            out.write(byteArrayOf(op.toByte(), (0x80 or d.size).toByte()) + mask + d); out.flush()
        }
    }

    fun close() { runCatching { sock.close() } }
}
