package com.schweik.nmdl.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * 网络层：全局限流 + 反限流退避，对应桌面版的 `net.py`。
 *
 * 网易云的公开接口按 IP 限流，触发后会返回 HTTP 200 但 body 是
 * `{"code":405,"msg":"操作频繁，请稍候再试"}`；而且封禁期间继续请求会不断续期，
 * 所以这里做两件事：
 *
 * 1. 全局令牌桶：所有并发任务共用一个最小请求间隔（只限 music.163.com 的接口，
 *    图片 CDN 不受限）。
 * 2. 撞到 405 时让**所有**任务一起停下来冷却，冷却时间指数递增，成功后复位。
 *
 * 桌面版里配合 Clash 换 IP 的那套在手机上没有对应场景，这里只保留等待冷却。
 */
object Http {

    /** 命中这些 code 说明被限流了（HTTP 状态码仍然是 200）。 */
    private val THROTTLE_CODES = setOf(405, 406, -460, -447, 50000005)

    class Throttled(message: String) : RuntimeException(message)

    private val nmtid = Random.nextLong(1_000_000_000_000_000L, 9_999_999_999_999_999L)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun newRequest(url: String): Request = Request.Builder()
        .url(url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        .header("Referer", "https://music.163.com/")
        .header("Accept", "*/*")
        .header("Accept-Language", "zh-CN,zh;q=0.9")
        .header("Cookie", "os=pc; appver=8.9.70; osver=; deviceId=; NMTID=$nmtid")
        .build()

    fun buildUrl(base: String, params: Map<String, Any>): String {
        if (params.isEmpty()) return base
        val q = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v.toString(), "UTF-8")}"
        }
        return if (base.contains('?')) "$base&$q" else "$base?$q"
    }

    /**
     * 跨协程的最小间隔限流 + 全局冷却。
     *
     * @param rps 每秒请求数上限，实测 1.0 稳定
     * @param cooldown 撞限流后的基础冷却秒数，会逐次翻倍
     * @param onWait 需要等待时的回调，用来往界面日志里写一行提示
     */
    class RateLimiter(
        rps: Double = 1.0,
        private val baseCooldown: Double = 60.0,
        private val maxCooldown: Double = 600.0,
        private val onWait: (Double) -> Unit = {},
    ) {
        private val interval = if (rps > 0) 1.0 / rps else 0.0
        private val mutex = Mutex()
        private var nextAt = 0.0
        private var blockedUntil = 0.0
        private var strikes = 0

        private fun now(): Double = System.nanoTime() / 1e9

        /** 取得一次请求许可；必要时挂起等待。 */
        suspend fun acquire() {
            while (true) {
                // Pair(等待秒数, 是不是在冷却期)
                val (wait, cooling) = mutex.withLock {
                    val t = now()
                    if (t < blockedUntil) {
                        Pair(blockedUntil - t, true)
                    } else {
                        val slot = maxOf(t, nextAt)
                        nextAt = slot + interval
                        Pair(slot - t, false)
                    }
                }
                if (!cooling) {
                    if (wait > 0) delay((wait * 1000).toLong())
                    return
                }
                onWait(wait)
                // 分段睡，取消协程时能及时响应
                delay((min(wait, 5.0) * 1000).toLong())
            }
        }

        /** 记一次限流，返回还需要冷却的秒数。 */
        suspend fun hitThrottle(): Double = mutex.withLock {
            strikes += 1
            var secs = baseCooldown
            repeat(strikes - 1) { secs *= 2 }
            secs = min(secs, maxCooldown)
            blockedUntil = maxOf(blockedUntil, now() + secs)
            secs
        }

        suspend fun ok() = mutex.withLock { strikes = 0 }
    }

    /**
     * 请求一个返回 JSON 的网易云接口，自动处理限流与重试。
     *
     * 限流重试和普通重试分开计数：撞限流不算「失败」，只是要等（等待本身由
     * [RateLimiter.acquire] 统一执行，避免多个协程各睡各的、又把封禁续上）。
     */
    suspend fun apiJson(
        url: String,
        params: Map<String, Any> = emptyMap(),
        limiter: RateLimiter? = null,
        retries: Int = 3,
        throttleRetries: Int = 4,
    ): JSONObject = withContext(Dispatchers.IO) {
        val full = buildUrl(url, params)
        var last: String? = null
        var tries = 0
        var throttles = 0

        while (tries < retries && throttles < throttleRetries) {
            limiter?.acquire()
            try {
                client.newCall(newRequest(full)).execute().use { resp ->
                    if (resp.code != 200) {
                        tries += 1
                        last = "HTTP ${resp.code}"
                    } else {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val code = json.optInt("code", 200)
                        val msg = json.optString("msg", "")
                        if (code in THROTTLE_CODES || msg.contains("频繁")) {
                            throttles += 1
                            val secs = limiter?.hitThrottle() ?: 60.0
                            last = "限流(code=%d)，冷却 %.0fs".format(code, secs)
                            return@use          // 回到 while，acquire() 会替我们等
                        }
                        limiter?.ok()
                        return@withContext json
                    }
                }
            } catch (e: Exception) {            // 超时 / 连接重置 / JSON 解析失败
                tries += 1
                last = "${e.javaClass.simpleName}: ${e.message}"
            }
            if (tries > 0) delay((1000L * tries) + Random.nextLong(1000))
        }
        val reason = last ?: "request failed"
        if (reason.startsWith("限流")) throw Throttled(reason)
        throw RuntimeException(reason)
    }

    /** 下载二进制（封面图走 CDN，不占接口限流额度）。 */
    suspend fun getBytes(url: String, retries: Int = 3): ByteArray =
        withContext(Dispatchers.IO) {
            var last: String? = null
            for (attempt in 0 until retries) {
                try {
                    client.newCall(newRequest(url)).execute().use { resp ->
                        val body = resp.body?.bytes()
                        if (resp.code == 200 && body != null && body.isNotEmpty()) {
                            return@withContext body
                        }
                        last = "HTTP ${resp.code}"
                    }
                } catch (e: Exception) {
                    last = "${e.javaClass.simpleName}: ${e.message}"
                }
                delay(1000L * (attempt + 1))
            }
            throw RuntimeException(last ?: "download failed")
        }

    /** 供限流日志使用：把秒数说得像人话。 */
    fun humanSeconds(secs: Double): String =
        if (abs(secs) < 60) "%.0f 秒".format(secs) else "%.1f 分钟".format(secs / 60)
}
