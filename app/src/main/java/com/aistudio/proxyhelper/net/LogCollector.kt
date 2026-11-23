package com.aistudio.proxyhelper.net

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * 极简本地日志收集器：只监听 127.0.0.1，供 Termux 里的命令用 curl 回传输出。
 * 不做任何外网暴露。
 */
object LogCollector {

    private const val TAG = "LogCollector"
    const val PORT = 8099
    private const val MAX_LINES = 400
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 512 * 1024

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private val lines = Collections.synchronizedList(ArrayList<String>())

    @Volatile
    var lastError: String? = null
        private set

    fun start() {
        if (thread?.isAlive == true) return
        thread = Thread {
            try {
                val socket = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                lastError = null
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    Thread { handle(client) }.start()
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "start failed: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        thread = null
    }

    fun append(text: String) {
        val newLines = text.split('\n').filter { it.isNotBlank() }
        synchronized(lines) {
            lines.addAll(newLines)
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }

    fun getLog(maxLines: Int = 120): String {
        synchronized(lines) {
            if (lines.isEmpty()) return ""
            return lines.takeLast(maxLines).joinToString("\n")
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 4000
                val rawIn = socket.getInputStream()
                val headerText = readHeaders(rawIn) ?: return
                val headerLines = headerText.split("\r\n", "\n")
                val requestLine = headerLines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return

                var contentLength = 0
                for (i in 1 until headerLines.size) {
                    val lower = headerLines[i].lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = (lower.substringAfter(":").trim().toIntOrNull() ?: 0)
                            .coerceIn(0, MAX_BODY_BYTES)
                    }
                }

                val isPost = requestLine.startsWith("POST")
                val body = if (isPost && contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = rawIn.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                if (isPost) {
                    append(body)
                }

                val payload = if (isPost) "OK" else getLog()
                val bytes = payload.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val out: OutputStream = socket.getOutputStream()
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
                // 忽略该次异常
            }
        }
    }

    private fun readHeaders(input: InputStream): String? {
        val baos = ByteArrayOutputStream(256)
        var state = 0 // 1:\r, 2:\r\n, 3:\r\n\r
        var count = 0
        while (count < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) break
            baos.write(b)
            count++
            when (b) {
                '\r'.code -> state = if (state == 2) 3 else 1
                '\n'.code -> {
                    if (state == 3 || state == 2) break
                    state = 2
                }
                else -> state = 0
            }
        }
        return if (baos.size() == 0) null else baos.toString(Charsets.ISO_8859_1.name())
    }
}
