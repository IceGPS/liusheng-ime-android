package com.icegps.voiceime.service.clip

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.icegps.voiceime.service.MdnsDiscovery
import com.icegps.voiceime.util.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 剪贴板局域网同步管理器（设计文档第 7 节）。
 *
 * 传输层决策（7.1 只定了 JSON 格式，传输二选一）：
 * 采用 **WebSocket 长连接**，连到 mDNS 发现的同一 host:port（与语音转写同一
 * 服务端），JSON 文本帧即第 7 节定义的消息。这是"复用语音链路优先"最自然
 * 的实现：Windows 端在已有 WebSocket 服务上新增消息类型即可，无需新端口。
 * 请求头与语音一致：`Sec-WebSocket-Protocol: binary`。
 *
 * 消息（文本帧，一帧一个 JSON）：
 * - 配对：{"type":"pair_req","id","name","key"} → {"type":"pair_ok"}
 * - 同步：{"type":"clip","from","ts","data"}（data=AES-GCM，见 ClipCrypto）
 * - 解绑：{"type":"unpair"}
 *
 * 防回环（7.3/7.4）：只推送本机用户主动复制的内容；收到的 PC 内容只存历史，
 * 永不转发、永不写入系统剪贴板。
 *
 * 进程级单例：输入法 Service 与设置 Activity 共用；连接随进程存活，
 * 不随输入视图重建而中断。
 */
object ClipSyncManager {
    private const val TAG = "ClipSync"

    /** mDNS 发现到的支持剪贴板的 PC */
    data class DiscoveredPc(
        val serviceName: String,
        val deviceId: String,
        val name: String,
        val host: String,
        val port: Int,
    )

    /** 已配对设备（持久化在 Prefs） */
    data class PairedDevice(
        val id: String,
        val name: String,
        val keyB64: String,
        val host: String,
        val port: Int,
    )

    interface Listener {
        /** 已配对设备列表变化（配对成功/取消配对/收到 unpair） */
        fun onPairedDevicesChanged() {}
        /** 历史有新内容（收到 PC 推送） */
        fun onHistoryChanged() {}
    }

    private var appCtx: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接：不设读超时
            .build()
    }

    /** deviceId → 活跃连接 */
    private val connections = mutableMapOf<String, WebSocket>()
    /** 已启动连接循环的 deviceId（防重复） */
    private val loopRunning = mutableSetOf<String>()
    private val shutdown = AtomicBoolean(false)

    /** 本机设备名（配对时展示给 PC） */
    val deviceName: String
        get() = Build.MODEL ?: "Android"

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        shutdown.set(false)
        getPaired().forEach { ensureConnectionLoop(it) }
    }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    fun shutdown() {
        shutdown.set(true)
        synchronized(connections) {
            connections.values.forEach { try { it.close(1000, "shutdown") } catch (_: Exception) {} }
            connections.clear()
        }
        loopRunning.clear()
    }

    // ---------- 配对信息持久化 ----------

    fun getPaired(): List<PairedDevice> {
        val ctx = appCtx ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(Prefs.getClipPairedJson(ctx))
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                PairedDevice(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    keyB64 = o.getString("key"),
                    host = o.getString("host"),
                    port = o.getInt("port"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePaired(list: List<PairedDevice>) {
        val ctx = appCtx ?: return
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("key", it.keyB64)
                    .put("host", it.host)
                    .put("port", it.port),
            )
        }
        Prefs.setClipPairedJson(ctx, arr.toString())
        mainHandler.post { listeners.toList().forEach { it.onPairedDevicesChanged() } }
    }

    private fun addPairing(dev: PairedDevice) {
        val list = getPaired().filter { it.id != dev.id } + dev
        savePaired(list)
    }

    private fun removePairing(deviceId: String) {
        savePaired(getPaired().filter { it.id != deviceId })
        synchronized(connections) {
            connections.remove(deviceId)?.let {
                try { it.close(1000, "unpaired") } catch (_: Exception) {}
            }
        }
    }

    // ---------- mDNS 发现 ----------

    /**
     * 一次性发现支持剪贴板的 PC（timeoutMs 后自动停止并回调）。
     * 回调在主线程执行。
     */
    fun discover(timeoutMs: Long = 10000L, cb: (List<DiscoveredPc>) -> Unit) {
        val ctx = appCtx ?: run { cb(emptyList()); return }
        val found = mutableMapOf<String, DiscoveredPc>()
        val done = AtomicBoolean(false)
        val discovery = MdnsDiscovery(ctx)
        fun finish() {
            if (!done.compareAndSet(false, true)) return
            try { discovery.stop() } catch (_: Exception) {}
            val list = found.values.toList()
            mainHandler.post { cb(list) }
        }
        discovery.start { servers ->
            servers.filter { it.clipSupported && it.deviceId.isNotEmpty() }
                .forEach {
                    found[it.deviceId] = DiscoveredPc(
                        serviceName = it.name,
                        deviceId = it.deviceId,
                        name = it.deviceName.ifEmpty { it.name },
                        host = it.host,
                        port = it.port,
                    )
                }
        }
        mainHandler.postDelayed({ finish() }, timeoutMs)
    }

    // ---------- 配对（7.2） ----------

    /**
     * 向 PC 发起配对：生成 AES-256 密钥 → 建 WS → 发 pair_req → 等 pair_ok。
     * 用户需在 PC 端弹窗点"同意"。结果回调在主线程执行。
     */
    fun pair(pc: DiscoveredPc, cb: (ok: Boolean, msg: String) -> Unit) {
        Thread({
            try {
                val ctx = appCtx ?: throw IllegalStateException("未初始化")
                val phoneId = Prefs.getClipDeviceId(ctx)
                val keyB64 = ClipCrypto.generateKeyB64()
                val pairOkLatch = CountDownLatch(1)
                val ws = openWs(pc.host, pc.port, object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            if (JSONObject(text).optString("type") == "pair_ok") {
                                pairOkLatch.countDown()
                            }
                        } catch (_: Exception) {
                        }
                    }
                }) ?: throw IllegalStateException("连接 ${pc.host}:${pc.port} 失败")
                try {
                    val req = JSONObject()
                        .put("type", "pair_req")
                        .put("id", phoneId)
                        .put("name", deviceName)
                        .put("key", keyB64)
                        .toString()
                    if (!ws.send(req)) throw IllegalStateException("发送配对请求失败")
                    // 等 PC 端用户点同意（给足操作时间）
                    val ok = pairOkLatch.await(120, TimeUnit.SECONDS)
                    if (!ok) {
                        mainHandler.post { cb(false, "电脑未确认（超时），请在电脑上点同意后重试") }
                        return@Thread
                    }
                    addPairing(PairedDevice(pc.deviceId, pc.name, keyB64, pc.host, pc.port))
                    // 4.6：首次配对成功后自动开启总开关
                    Prefs.setClipEnabled(ctx, true)
                    ensureConnectionLoop(getPaired().first { it.id == pc.deviceId })
                    mainHandler.post { cb(true, "已与 ${pc.name} 配对，剪贴板共享已开启") }
                } finally {
                    try { ws.close(1000, "pair done") } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "配对失败", e)
                mainHandler.post { cb(false, "配对失败：${e.message}".take(80)) }
            }
        }, "clip-pair").apply { isDaemon = true }.start()
    }

    /** 取消配对：先发 unpair 通知对方，再删本地记录、断连接 */
    fun unpair(deviceId: String) {
        Thread({
            val dev = getPaired().find { it.id == deviceId } ?: return@Thread
            synchronized(connections) { connections[deviceId] }?.let { ws ->
                try {
                    ws.send(JSONObject().put("type", "unpair").toString())
                } catch (_: Exception) {
                }
            }
            removePairing(deviceId)
        }, "clip-unpair").apply { isDaemon = true }.start()
    }

    // ---------- 推送本机复制（7.3） ----------

    /** 本机用户主动复制 → 推送给所有已配对且在线的 PC（best-effort） */
    fun pushToPaired(text: String) {
        val ctx = appCtx ?: return
        if (!Prefs.isClipEnabled(ctx) || text.isBlank()) return
        val phoneId = Prefs.getClipDeviceId(ctx)
        Thread({
            getPaired().forEach { dev ->
                val ws = synchronized(connections) { connections[dev.id] } ?: return@forEach
                val data = ClipCrypto.encrypt(dev.keyB64, text) ?: return@forEach
                try {
                    val msg = JSONObject()
                        .put("type", "clip")
                        .put("from", phoneId)
                        .put("ts", System.currentTimeMillis())
                        .put("data", data)
                        .toString()
                    ws.send(msg)
                } catch (_: Exception) {
                }
            }
        }, "clip-push").apply { isDaemon = true }.start()
    }

    // ---------- 长连接与重连（7.5） ----------

    private fun ensureConnectionLoop(dev: PairedDevice) {
        synchronized(loopRunning) {
            if (!loopRunning.add(dev.id)) return
        }
        Thread({ connectionLoop(dev) }, "clip-conn-${dev.id.take(8)}").apply {
            isDaemon = true
        }.start()
    }

    private fun connectionLoop(dev: PairedDevice) {
        var backoffMs = 2000L
        while (!shutdown.get() && getPaired().any { it.id == dev.id }) {
            var ws: WebSocket? = null
            // 先放 latch 再建连：建连成功瞬间断开也能被 listener 捕获，不会卡死
            val closed = CountDownLatch(1)
            persistentCloseLatches[dev.id] = closed
            try {
                // 7.5：每次重连前先 mDNS 重发现（DHCP 换 IP 后能跟上）
                val target = resolve(dev) ?: dev.host to dev.port
                ws = openWs(target.first, target.second, persistentListener(dev))
                if (ws != null) {
                    synchronized(connections) { connections[dev.id] = ws }
                    Log.i(TAG, "剪贴板长连接已建立：${dev.name}")
                    backoffMs = 2000L
                    closed.await() // 阻塞到连接断开
                }
            } catch (_: Exception) {
            } finally {
                persistentCloseLatches.remove(dev.id)
                synchronized(connections) { connections.remove(dev.id) }
                try { ws?.close(1000, "retry") } catch (_: Exception) {}
            }
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            backoffMs = minOf(backoffMs * 2, 60000L)
        }
        synchronized(loopRunning) { loopRunning.remove(dev.id) }
    }

    private val persistentCloseLatches = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()

    /** 按设备 id 做一次 mDNS 重发现，返回最新 host:port */
    private fun resolve(dev: PairedDevice): Pair<String, Int>? {
        val ctx = appCtx ?: return null
        val found = CountDownLatch(1)
        var result: Pair<String, Int>? = null
        val discovery = MdnsDiscovery(ctx)
        discovery.start { servers ->
            servers.firstOrNull { it.clipSupported && it.deviceId == dev.id }?.let {
                result = it.host to it.port
                found.countDown()
            }
        }
        try {
            found.await(8, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        try { discovery.stop() } catch (_: Exception) {}
        return result
    }

    /** 长连接的消息监听：收 clip → 解密 → 存历史（永不转发）；收 unpair → 解绑 */
    private fun persistentListener(dev: PairedDevice) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val o = JSONObject(text)
                when (o.optString("type")) {
                    "clip" -> {
                        val from = o.optString("from")
                        // 只接受已配对设备的消息；from 必须就是这条连接的对端
                        if (from != dev.id) {
                            Log.w(TAG, "收到未知设备 $from 的 clip，已忽略")
                            return
                        }
                        val plain = ClipCrypto.decrypt(dev.keyB64, o.optString("data"))
                        if (plain.isNullOrBlank()) {
                            Log.w(TAG, "clip 解密失败，已忽略")
                            return
                        }
                        // 7.4：只存历史，不转发、不写系统剪贴板
                        ClipStore.add(plain, dev.name)
                        mainHandler.post {
                            listeners.toList().forEach { it.onHistoryChanged() }
                        }
                    }
                    "unpair" -> {
                        Log.i(TAG, "对端解除配对：${dev.name}")
                        removePairing(dev.id)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "clip 消息解析失败", e)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            persistentCloseLatches[dev.id]?.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            persistentCloseLatches[dev.id]?.countDown()
        }
    }

    /** 同步建连（后台线程调用），失败返回 null */
    private fun openWs(host: String, port: Int, listener: WebSocketListener): WebSocket? {
        return try {
            val connected = CountDownLatch(1)
            var socket: WebSocket? = null
            val request = Request.Builder()
                .url("ws://$host:$port")
                .addHeader("Sec-WebSocket-Protocol", "binary")
                .build()
            val wrapping = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    connected.countDown()
                    listener.onOpen(webSocket, response)
                }

                override fun onMessage(webSocket: WebSocket, text: String) =
                    listener.onMessage(webSocket, text)

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) =
                    listener.onMessage(webSocket, bytes)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
                    listener.onClosing(webSocket, code, reason)

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    listener.onClosed(webSocket, code, reason)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected.countDown()
                    listener.onFailure(webSocket, t, response)
                }
            }
            client.newWebSocket(request, wrapping)
            if (!connected.await(12, TimeUnit.SECONDS)) {
                try { socket?.close(1000, "timeout") } catch (_: Exception) {}
                return null
            }
            socket
        } catch (_: Exception) {
            null
        }
    }
}
