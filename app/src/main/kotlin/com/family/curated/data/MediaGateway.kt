package com.family.curated.data

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * OPT-24: MediaGateway 双模路由层（HTTP 优先，SMB 兜底）。
 *
 * 优先走 NAS 端 console.py 提供的原生 HTTP 206 流媒体服务；网关不可达时无感降级 SMB。
 *
 * 2026-11-15 修复：探测原先在 `PlayerActivity.onCreate` 的**主线程**直接发
 * `HttpURLConnection`，Android 会抛 `NetworkOnMainThreadException`，而它被
 * `catch (_: Exception)` 静默吞掉 → 结果恒为 false → 8765 在线也永远走 SMB。
 * 现在真正的网络请求只跑在 [probePool] 后台线程，主线程仅读缓存或限时等结果；
 * 缓存状态机抽到 [GatewayMath] 便于纯 JVM 单测（R-38）。
 */
object MediaGateway {
    private const val TAG = "MediaGateway"

    @Volatile
    private var state = GatewayMath.State()

    /** 单条 daemon 线程：网络探测绝不落到主线程，也不会留下 App 退出后还在跑的线程 */
    private val probePool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gateway-probe").apply { isDaemon = true }
    }

    /** 真正发请求；只允许在 [probePool] 里调用 */
    private fun probe(cfg: NasConfig): Boolean {
        val port = cfg.gatewayPort
        val ok = try {
            val conn = URL("http://${cfg.host}:$port/api/desktop/ping")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
        state = GatewayMath.recorded(ok, System.currentTimeMillis())
        Log.i(TAG, "HTTP 媒体网关探测 host=${cfg.host}:$port available=$ok")
        return ok
    }

    /**
     * 主线程安全：缓存新鲜直接返回；否则把探测交给后台线程，主线程只限时等待结果
     * （`Future.get` 不是网络操作，不会触发主线程网络限制），超时按 SMB 兜底。
     */
    fun isHttpAvailable(cfg: NasConfig): Boolean {
        val snap = state
        val now = System.currentTimeMillis()
        if (GatewayMath.fresh(snap, now)) return snap.available == true
        val future = probePool.submit(Callable { probe(cfg) })
        return try {
            future.get(GatewayMath.PROBE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            state = GatewayMath.invalidated(System.currentTimeMillis())
            false
        } catch (_: Exception) {
            state = GatewayMath.invalidated(System.currentTimeMillis())
            false
        }
    }

    /** 播放出错时主动降级，TTL 内不再重试 HTTP（与修复前语义一致） */
    fun markHttpUnavailable() {
        state = GatewayMath.invalidated(System.currentTimeMillis())
    }

    fun resolveMediaUri(cfg: NasConfig, file: String, share: String = cfg.share): Uri {
        val useHttp = isHttpAvailable(cfg)
        val uri = if (useHttp) {
            val encodedPath = file.split("/").joinToString("/") { Uri.encode(it) }
            val port = cfg.gatewayPort
            Uri.parse("http://${cfg.host}:$port/stream/${Uri.encode(share)}/$encodedPath")
        } else {
            Uri.parse("smb://nas/" + Uri.encode(file))
        }
        // 运维取证：一眼看出这一集到底走了 HTTP 206 还是 SMB 兜底
        Log.i(TAG, "播放源 ${if (useHttp) "HTTP" else "SMB"} uri=$uri")
        return uri
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
