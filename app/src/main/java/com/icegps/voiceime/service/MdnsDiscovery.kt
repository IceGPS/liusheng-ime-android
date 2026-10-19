package com.icegps.voiceime.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * mDNS 服务发现：自动查找局域网内的 voice-ime PC 服务端。
 *
 * 服务类型：_voiceime._tcp.
 * PC 端需发布此服务（Windows 版待实现）。
 * TXT 记录可包含：model=Qwen3-ASR-1.7B, host=PC名称
 */
class MdnsDiscovery(private val ctx: Context) {
    companion object {
        private const val TAG = "MdnsDiscovery"
        const val SERVICE_TYPE = "_voiceime._tcp."
    }

    data class Server(
        val name: String,
        val host: String,
        val port: Int,
        val model: String = "",
        /** TXT clip=1：该 PC 支持剪贴板共享（V69） */
        val clipSupported: Boolean = false,
        /** TXT id：PC 的设备 UUID（V69） */
        val deviceId: String = "",
        /** TXT name：PC 的设备名（V69） */
        val deviceName: String = "",
    )

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val foundServers = mutableMapOf<String, Server>()
    private var onUpdate: ((List<Server>) -> Unit)? = null

    fun start(onUpdate: (List<Server>) -> Unit) {
        this.onUpdate = onUpdate
        foundServers.clear()
        nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            Log.w(TAG, "NSD 不可用")
            return
        }
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "mDNS 发现已启动: $regType")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "发现服务: ${service.serviceName}")
                nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, ec: Int) {
                        Log.w(TAG, "解析失败: ${si.serviceName} ($ec)")
                    }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val host = si.host?.hostAddress ?: return
                        val attrs = si.attributes
                        val server = Server(
                            name = si.serviceName,
                            host = host,
                            port = si.port,
                            model = attrs["model"]?.let { String(it) } ?: "",
                            clipSupported = attrs["clip"]?.let { String(it) } == "1",
                            deviceId = attrs["id"]?.let { String(it) } ?: "",
                            deviceName = attrs["name"]?.let { String(it) } ?: "",
                        )
                        foundServers[si.serviceName] = server
                        Log.i(TAG, "解析成功: $server")
                        onUpdate(foundServers.values.toList())
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                foundServers.remove(service.serviceName)
                onUpdate(foundServers.values.toList())
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "发现启动失败 ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        }
        discoveryListener = null
        nsdManager = null
        foundServers.clear()
    }
}
