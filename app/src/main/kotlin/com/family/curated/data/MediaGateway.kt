package com.family.curated.data

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * OPT-24: MediaGateway 双模路由层（HTTP/WebDAV 优先，SMB 兜底）。
 *
 * 优先探测并走 NAS 端由 console.py 或 WebDAV 提供的原生 HTTP 206 流媒体服务；
 * 当 HTTP 网关不可达（离线、未启动服务或超时）时，无感透明降级为 SMB (SmbDataSource)。
 */
object MediaGateway {
    private const val TAG = "MediaGateway"

    @Volatile
    private var httpAvailable: Boolean? = null
    @Volatile
    private var lastProbeTime: Long = 0L
    private const val PROBE_TTL_MS = 60_000L

    fun isHttpAvailable(cfg: NasConfig): Boolean {
        val now = System.currentTimeMillis()
        val cached = httpAvailable
        if (cached != null && now - lastProbeTime < PROBE_TTL_MS) {
            return cached
        }
        val port = cfg.gatewayPort
        val ok = try {
            val url = URL("http://${cfg.host}:$port/api/desktop/ping")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
        httpAvailable = ok
        lastProbeTime = now
        Log.i(TAG, "HTTP 媒体网关探测 host=${cfg.host}:$port available=$ok")
        return ok
    }

    fun markHttpUnavailable() {
        httpAvailable = false
        lastProbeTime = System.currentTimeMillis()
    }

    fun resolveMediaUri(cfg: NasConfig, file: String, share: String = cfg.share): Uri {
        return if (isHttpAvailable(cfg)) {
            val encodedPath = file.split("/").joinToString("/") { Uri.encode(it) }
            val port = cfg.gatewayPort
            Uri.parse("http://${cfg.host}:$port/stream/${Uri.encode(share)}/$encodedPath")
        } else {
            Uri.parse("smb://nas/" + Uri.encode(file))
        }
    }

    fun createDataSourceFactory(): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
        val smbFactory = SmbDataSource.Factory()

        return DataSource.Factory {
            object : DataSource {
                private var activeSource: DataSource? = null

                override fun open(dataSpec: DataSpec): Long {
                    val scheme = dataSpec.uri.scheme
                    val source = if (scheme == "http" || scheme == "https") {
                        httpFactory.createDataSource()
                    } else {
                        smbFactory.createDataSource()
                    }
                    activeSource = source
                    return source.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return activeSource?.read(buffer, offset, length) ?: -1
                }

                override fun getUri(): Uri? = activeSource?.uri

                override fun close() {
                    try {
                        activeSource?.close()
                    } finally {
                        activeSource = null
                    }
                }

                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                    // optional
                }
            }
        }
    }
}
