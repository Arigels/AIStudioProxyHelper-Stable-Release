package com.aistudio.proxyhelper.net

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
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
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = input.readLine() ?: return
                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                val isPost = requestLine.startsWith("POST")
                val body = if (isPost && contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read)
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
}
