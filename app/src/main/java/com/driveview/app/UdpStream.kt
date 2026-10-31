package com.driveview.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue

/**
 * Receives the car's video over UDP.
 *
 * The app sends a small keep-alive packet to the car every half second ("L<light level>").
 * The car answers by streaming each JPEG frame as small UDP packets:
 *   [frameId u16][packetIndex u16][packetCount u16][up to 1400 bytes of JPEG]   (little-endian)
 * UDP never waits for confirmations, so a slow Wi-Fi link can't hold the video back. A frame that
 * loses a packet is simply dropped, and the newest complete frame is always the one shown.
 */
class UdpStream(
    private val host: String,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var address: InetAddress? = null
    @Volatile private var lightLevel = 0
    @Volatile private var shown = false
    @Volatile private var lastFrameAt = 0L

    private var receiveThread: Thread? = null
    private var decodeThread: Thread? = null
    private val jpegQueue = ArrayBlockingQueue<ByteArray>(1)   // holds only the newest complete frame
    private val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

    fun start() {
        if (running) return
        running = true
        receiveThread = Thread({ receiveLoop() }, "udp-receive").also { it.start() }
        decodeThread = Thread({ decodeLoop() }, "udp-decode").also { it.start() }
    }

    fun stop() {
        running = false
        val s = socket
        receiveThread?.interrupt()
        decodeThread?.interrupt()
        receiveThread = null
        decodeThread = null
        if (s != null) Thread { try { s.close() } catch (_: Exception) {} }.start()
    }

    /** Sets the car's light (0 = off, 255 = full). It is also re-sent with every keep-alive. */
    fun setLight(level: Int) {
        lightLevel = level.coerceIn(0, 255)
        val s = socket
        val a = address
        if (s != null && a != null) {
            Thread { sendText(s, a, "L$lightLevel") }.start()
        }
    }

    private fun sendText(sock: DatagramSocket, addr: InetAddress, text: String) {
        try {
            val d = text.toByteArray()
            sock.send(DatagramPacket(d, d.size, addr, PORT))
        } catch (_: Exception) {
        }
    }

    // a is newer than b (16-bit counters that wrap around)
    private fun isNewer(a: Int, b: Int): Boolean {
        val diff = (a - b) and 0xFFFF
        return diff in 1..0x7FFF
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        while (running) {
            var sock: DatagramSocket? = null
            try {
                val addr = InetAddress.getByName(host)
                address = addr
                sock = DatagramSocket()
                try { sock.receiveBufferSize = 1 shl 20 } catch (_: Exception) {}
                sock.soTimeout = 200
                socket = sock
                shown = false

                val packet = DatagramPacket(buf, buf.size)
                var frameId = -1
                var count = 0
                var received = 0
                var lastLen = 0
                var frame = ByteArray(0)
                var seen = BooleanArray(0)
                var lastHello = 0L

                while (running) {
                    val now = System.currentTimeMillis()
                    if (now - lastHello >= 500) {
                        sendText(sock, addr, "L$lightLevel")      // keep-alive + light state
                        lastHello = now
                    }
                    if (shown && now - lastFrameAt > 2000) {
                        shown = false
                        onStatus("Connecting… (join the car's Wi-Fi first)")
                    }

                    try {
                        packet.length = buf.size
                        sock.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }

                    val n = packet.length
                    if (n <= HEADER) continue
                    val id = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
                    val idx = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
                    val cnt = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
                    val len = n - HEADER
                    if (cnt == 0 || cnt > 400 || idx >= cnt || len > PAYLOAD) continue

                    if (id != frameId) {
                        if (frameId != -1 && !isNewer(id, frameId)) continue   // late packet of an old frame
                        frameId = id                                           // a newer frame starts: drop any unfinished one
                        count = cnt
                        received = 0
                        lastLen = 0
                        if (frame.size < cnt * PAYLOAD) frame = ByteArray(cnt * PAYLOAD)
                        seen = BooleanArray(cnt)
                    }
                    if (cnt != count || seen[idx]) continue

                    seen[idx] = true
                    System.arraycopy(buf, HEADER, frame, idx * PAYLOAD, len)
                    if (idx == count - 1) lastLen = len
                    received++

                    if (received == count) {
                        val total = (count - 1) * PAYLOAD + lastLen
                        jpegQueue.clear()
                        jpegQueue.offer(frame.copyOf(total))
                    }
                }
            } catch (e: Exception) {
                // fall through and retry
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
            if (running) {
                onStatus("Connecting… (join the car's Wi-Fi first)")
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun decodeLoop() {
        while (running) {
            try {
                val jpeg = jpegQueue.take()
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: continue
                lastFrameAt = System.currentTimeMillis()
                if (!shown) {
                    shown = true
                    onStatus("")
                }
                onFrame(bmp)
            } catch (_: InterruptedException) {
            }
        }
    }

    companion object {
        const val PORT = 8888
        const val PAYLOAD = 1400
        const val HEADER = 6
    }
}
