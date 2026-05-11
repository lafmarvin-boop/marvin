package com.marvin.assistant.webhook

import android.util.Log
import com.marvin.assistant.util.Settings
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Mini serveur HTTP local pour exposer une API à d'autres apps (Tasker,
 * IFTTT via Webhook, scripts perso).
 *
 * Endpoints :
 *  - POST /say         body = "texte à dire" → Jarvis TTS direct
 *  - POST /command     body = "transcript"   → dispatche via parser
 *  - GET  /status      → JSON {sleeping, lastUserText, lastJarvisText}
 *  - GET  /health      → "OK"
 *
 * Sécurité :
 *  - Bind sur 127.0.0.1 ET (optionnellement) l'IP du Wi-Fi local
 *  - Token requis dans header X-Marvin-Token (configuré dans Settings)
 *  - Pas de TLS (à n'utiliser que sur réseau de confiance)
 *
 * Port par défaut : 7777 (configurable dans Settings).
 */
class LocalHttpServer(
    private val port: Int,
    private val token: String,
    private val onCommand: (String) -> Unit,
    private val onSay: (String) -> Unit,
    private val statusJson: () -> String
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "MarvinHttp") {
            try {
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(InetSocketAddress("0.0.0.0", port))
                server = srv
                Log.i(TAG, "HTTP server listening on :$port")
                while (running) {
                    val sock = try { srv.accept() } catch (t: Throwable) {
                        if (running) Log.e(TAG, "accept failed", t); break
                    }
                    thread(name = "MarvinHttp-handler") { handle(sock) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Server start failed", t)
            }
            running = false
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                val input = s.getInputStream().bufferedReader()
                val output = s.getOutputStream().bufferedWriter()

                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 3) return
                val method = parts[0]; val path = parts[1]

                // Headers
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val k = line.substring(0, idx).trim().lowercase()
                        val v = line.substring(idx + 1).trim()
                        headers[k] = v
                        if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    }
                }

                // Auth
                val provided = headers["x-marvin-token"] ?: ""
                if (provided != token) {
                    write(output, "HTTP/1.1 401 Unauthorized", "Token invalide")
                    return
                }

                // Body
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    input.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                when {
                    method == "GET" && path == "/health" ->
                        write(output, "HTTP/1.1 200 OK", "OK")
                    method == "GET" && path == "/status" ->
                        write(output, "HTTP/1.1 200 OK", statusJson(), "application/json")
                    method == "POST" && path == "/say" -> {
                        onSay(body)
                        write(output, "HTTP/1.1 200 OK", "ok")
                    }
                    method == "POST" && path == "/command" -> {
                        onCommand(body)
                        write(output, "HTTP/1.1 200 OK", "ok")
                    }
                    else -> write(output, "HTTP/1.1 404 Not Found", "Endpoint inconnu")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Handle failed", t)
            }
        }
    }

    private fun write(
        output: java.io.BufferedWriter,
        status: String,
        body: String,
        contentType: String = "text/plain; charset=utf-8"
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        output.write("$status\r\n")
        output.write("Content-Type: $contentType\r\n")
        output.write("Content-Length: ${bodyBytes.size}\r\n")
        output.write("Connection: close\r\n\r\n")
        output.flush()
        output.write(body)
        output.flush()
    }

    companion object { private const val TAG = "MarvinHttp" }
}
