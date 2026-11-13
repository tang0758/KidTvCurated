package com.family.curated.data

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * OPT-29: TV 端轻量 HTTP 扫码配网与 <1s 实时推送服务。
 * 监听在端口 8899，提供：
 *  - 手机扫码直达网页配网，免去遥控器笨拙输入
 *  - console.py 保存清单时 POST /api/nudge，TV 端 <1s 毫秒级刷新
 */
object PairingServer {
    private const val TAG = "PairingServer"
    const val PORT = 8899

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        try {
            val ss = ServerSocket(PORT)
            serverSocket = ss
            Log.i(TAG, "配网服务已启动，端口: $PORT")
            pool.execute {
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        pool.execute { handle(client) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "配网服务启动失败: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    fun getLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.isNotBlank()) return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun pairingUrl(): String = "http://${getLocalIp()}:$PORT/pair"

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return
            val parts = line.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.lowercase().startsWith("content-length:")) {
                    contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                body = String(buf, 0, read)
            }

            when {
                path == "/api/ping" -> {
                    responseJson(sock, 200, """{"ok":true,"name":"KidTvCurated"}""")
                }
                path == "/api/nudge" && method == "POST" -> {
                    PlaylistSync.nudge()
                    responseJson(sock, 200, """{"ok":true,"action":"nudged"}""")
                }
                (path == "/pair" || path == "/") && method == "GET" -> {
                    val cfg = Store.nas()
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                        <title>小剧场 · 电视配网</title>
                        <style>
                          body{font-family:sans-serif;background:#12161F;color:#E0E0E0;padding:20px;max-width:480px;margin:auto}
                          h2{color:#FFB800}
                          input{width:100%;box-sizing:border-box;padding:10px;margin:6px 0 16px;background:#1E2433;border:1px solid #3A445D;color:#FFF;border-radius:6px;font-size:16px}
                          button{width:100%;padding:12px;background:#FFB800;color:#000;font-weight:bold;border:none;border-radius:6px;font-size:16px;cursor:pointer}
                        </style>
                        </head>
                        <body>
                          <h2>📺 小剧场 NAS 配网</h2>
                          <form method="POST" action="/api/config">
                            <label>NAS IP 或主机名</label>
                            <input name="host" value="${cfg?.host ?: ""}" required />
                            <label>端口 (默认 445)</label>
                            <input name="port" type="number" value="${cfg?.port ?: 445}" />
                            <label>用户名</label>
                            <input name="user" value="${cfg?.user ?: ""}" />
                            <label>密码</label>
                            <input name="password" type="password" value="${cfg?.password ?: ""}" />
                            <label>媒体共享名</label>
                            <input name="share" value="${cfg?.share ?: ""}" required />
                            <button type="submit">保存到电视</button>
                          </form>
                        </body>
                        </html>
                    """.trimIndent()
                    responseHtml(sock, 200, html)
                }
                (path == "/api/config" || path == "/pair") && method == "POST" -> {
                    try {
                        val host: String
                        val port: Int
                        val user: String
                        val pass: String
                        val share: String
                        if (body.startsWith("{")) {
                            val json = JSONObject(body)
                            host = json.getString("host")
                            port = json.optInt("port", 445)
                            user = json.optString("user", "")
                            pass = json.optString("password", "")
                            share = json.getString("share")
                        } else {
                            val params = parseForm(body)
                            host = params["host"] ?: ""
                            port = params["port"]?.toIntOrNull() ?: 445
                            user = params["user"] ?: ""
                            pass = params["password"] ?: ""
                            share = params["share"] ?: ""
                        }
                        if (host.isNotBlank() && share.isNotBlank()) {
                            val cfg = NasConfig(host = host, port = port, user = user, password = pass, share = share)
                            Store.saveNas(cfg)
                            PlaylistSync.nudge()
                            responseHtml(sock, 200, "<h2 style='color:#0F0;font-family:sans-serif;padding:30px'>Saved! Refreshing playlist...</h2>")
                        } else {
                            responseJson(sock, 400, """{"ok":false,"error":"host and share required"}""")
                        }
                    } catch (e: Exception) {
                        responseJson(sock, 500, """{"ok":false,"error":"${e.message}"}""")
                    }
                }
                else -> {
                    responseJson(sock, 404, """{"ok":false,"error":"not found"}""")
                }
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun parseForm(body: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        body.split('&').forEach { param ->
            val kv = param.split('=')
            if (kv.size == 2) {
                val k = java.net.URLDecoder.decode(kv[0], "UTF-8")
                val v = java.net.URLDecoder.decode(kv[1], "UTF-8")
                out[k] = v
            }
        }
        return out
    }

    private fun responseHtml(sock: Socket, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        val head = "HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun responseJson(sock: Socket, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
