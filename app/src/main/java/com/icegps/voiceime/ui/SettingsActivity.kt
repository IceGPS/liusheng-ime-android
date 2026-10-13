package com.icegps.voiceime.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.icegps.voiceime.service.ModelDownloader
import com.icegps.voiceime.service.asr.SherpaAsrBackend
import com.icegps.voiceime.util.Prefs

/**
 * 设置页：转写引擎、端侧模型下载、服务端 IP/端口、悬浮球外观。
 *
 * V2 新增：
 *  - 转写引擎：端侧优先（默认）/ 仅局域网
 *  - 端侧模型状态：未下载 / 下载中 x% / 已就绪（大小）
 *  - 手动下载 / 重新下载按钮（仅 WiFi，~230MB，SHA256 校验）
 */
class SettingsActivity : Activity() {

    private lateinit var engineGroup: RadioGroup
    private lateinit var radioOnDevice: RadioButton
    private lateinit var radioLan: RadioButton
    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var modelProgressText: TextView
    private lateinit var downloadBtn: Button
    private lateinit var modelUrlEdit: EditText
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText

    @Volatile private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        // ---------- 转写引擎 ----------
        root.addView(sectionTitle("转写引擎"))
        engineGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        radioOnDevice = RadioButton(this).apply {
            text = "端侧优先（推荐）：手机本地转写，不断网也能用"
            id = View.generateViewId()
        }
        radioLan = RadioButton(this).apply {
            text = "仅局域网：发往 PC 服务端转写（V1 模式）"
            id = View.generateViewId()
        }
        engineGroup.addView(radioOnDevice)
        engineGroup.addView(radioLan)
        if (Prefs.getEngine(this) == Prefs.ENGINE_LAN_ONLY) {
            radioLan.isChecked = true
        } else {
            radioOnDevice.isChecked = true
        }
        engineGroup.setOnCheckedChangeListener { _, _ -> saveEngine() }
        root.addView(engineGroup)

        // ---------- VAD（语音活动检测） ----------
        root.addView(sectionTitle("语音输入方式"))
        val vadSwitch = Switch(this).apply {
            text = "VAD 模式：点按开始，静音自动停止（默认关闭为按住说话）"
            isChecked = Prefs.isVadEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setVadEnabled(this@SettingsActivity, checked)
                toast(if (checked) "VAD 已启用" else "VAD 已关闭")
            }
        }
        root.addView(vadSwitch)

        // ---------- 端侧模型 ----------
        root.addView(sectionTitle("端侧模型（SenseVoice-Small INT8，约 230MB）"))
        modelStatus = label("")
        root.addView(modelStatus)
        modelProgressText = label("")
        root.addView(modelProgressText)
        modelProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(modelProgress)
        downloadBtn = Button(this).apply {
            text = "下载模型"
            setOnClickListener { startDownload() }
        }
        root.addView(downloadBtn)
        root.addView(label("模型下载地址（默认官方 release 占位，实际 hosting 确定后可改）："))
        modelUrlEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Prefs.getModelUrl(this@SettingsActivity))
        }
        root.addView(modelUrlEdit)

        // ---------- 服务端（局域网备用） ----------
        root.addView(sectionTitle("局域网服务端（备用）"))

        // mDNS 自动发现
        val mdnsStatus = label("未搜索")
        val mdnsBtn = Button(this).apply {
            text = "🔍 搜索局域网服务端"
            setOnClickListener {
                text = "搜索中…"
                isEnabled = false
                val discovery = com.icegps.voiceime.service.MdnsDiscovery(this@SettingsActivity)
                discovery.start { servers ->
                    runOnUiThread {
                        isEnabled = true
                        text = "🔍 搜索局域网服务端"
                        if (servers.isEmpty()) {
                            mdnsStatus.text = "未发现服务端（PC 端需先发布 mDNS 服务）"
                        } else {
                            // 自动选第一个，填入 IP/端口
                            val s = servers[0]
                            hostEdit.setText(s.host)
                            portEdit.setText(s.port.toString())
                            Prefs.setHost(this@SettingsActivity, s.host)
                            Prefs.setPort(this@SettingsActivity, s.port)
                            mdnsStatus.text = "发现 ${servers.size} 台：${servers.joinToString { "${it.name}(${it.host}:${it.port})" }}，已自动填入第一台"
                            toast("已填入 ${s.name}")
                        }
                        discovery.stop()
                    }
                }
                // 10 秒超时
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runOnUiThread {
                        if (!isEnabled) {
                            isEnabled = true
                            text = "🔍 搜索局域网服务端"
                            if (mdnsStatus.text.toString() == "未搜索") {
                                mdnsStatus.text = "搜索超时，未发现服务端"
                            }
                            discovery.stop()
                        }
                    }
                }, 10000)
            }
        }
        root.addView(mdnsBtn)
        root.addView(mdnsStatus)

        root.addView(label("PC 服务端 IP（手机与 PC 在同一 WiFi）："))
        hostEdit = EditText(this).apply {
            hint = "例如 192.168.1.100"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Prefs.getHost(this@SettingsActivity))
        }
        root.addView(hostEdit)

        root.addView(label("端口："))
        portEdit = EditText(this).apply {
            hint = "默认 6016"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.getPort(this@SettingsActivity).toString())
        }
        root.addView(portEdit)

        root.addView(Button(this).apply {
            text = "保存"
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            p.topMargin = dp(20)
            layoutParams = p
            setOnClickListener { save() }
        })

        refreshModelStatus()
    }

    override fun onResume() {
        super.onResume()
        if (!downloading) refreshModelStatus()
    }

    // ---------- 引擎选择 ----------

    private fun saveEngine() {
        val v = if (radioLan.isChecked) Prefs.ENGINE_LAN_ONLY else Prefs.ENGINE_ONDEVICE_FIRST
        Prefs.setEngine(this, v)
        toast("转写引擎：${if (v == Prefs.ENGINE_LAN_ONLY) "仅局域网" else "端侧优先"}")
    }

    // ---------- 模型状态 ----------

    private fun hasAllFilesAccess(): Boolean {
        return android.os.Build.VERSION.SDK_INT < 30 ||
               android.os.Environment.isExternalStorageManager()
    }

    private fun requestAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                )
                startActivity(intent)
            }
        }
    }

    private fun refreshModelStatus() {
        // Android 11+ 需要所有文件访问权限才能读写 Download 备份
        if (!hasAllFilesAccess()) {
            modelStatus.text = "模型状态：需要「所有文件访问权限」才能备份/恢复模型"
            downloadBtn.text = "授权所有文件访问"
            downloadBtn.setOnClickListener { requestAllFilesAccess() }
            return
        }
        // 先尝试从备份恢复（卸载重装后自动恢复，无需重新下载）
        if (!SherpaAsrBackend.isModelReady(this)) {
            SherpaAsrBackend.restoreFromBackup(this)
        }
        // 恢复按钮的原始点击事件（可能之前被设成了授权）
        downloadBtn.setOnClickListener { startDownload() }
        val ready = Prefs.isModelReady(this) && SherpaAsrBackend.isModelReady(this)
        val sizeMb = SherpaAsrBackend.modelSizeBytes(this) / 1024 / 1024
        modelStatus.text = when {
            ready -> "模型状态：已就绪（${sizeMb}MB）"
            sizeMb > 0 -> "模型状态：下载不完整（${sizeMb}MB），请重新下载"
            else -> "模型状态：未下载"
        }
        downloadBtn.text = if (ready) "重新下载模型" else "下载模型"
    }

    // ---------- 模型下载 ----------

    private fun startDownload() {
        if (downloading) {
            toast("正在下载，请稍候")
            return
        }
        if (!ModelDownloader.isWifiConnected(this)) {
            toast("请连接 WiFi 后下载（模型约 230MB）")
            return
        }
        val url = modelUrlEdit.text.toString().trim()
        if (url.isEmpty()) {
            toast("请填写模型下载地址")
            return
        }
        Prefs.setModelUrl(this, url)
        Prefs.setModelReady(this, false)
        ModelDownloader.cleanTemp(this)
        downloading = true
        downloadBtn.isEnabled = false
        modelProgress.visibility = View.VISIBLE
        modelProgressText.text = "准备下载…"

        Thread({
            try {
                ModelDownloader.downloadAll(this, url) { file, done, total, index, count ->
                    runOnUiThread {
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        modelProgress.isIndeterminate = total <= 0
                        modelProgress.progress = pct
                        val doneMb = done / 1024 / 1024
                        val totalMb = if (total > 0) "${total / 1024 / 1024}MB" else "未知大小"
                        modelProgressText.text = "下载中 ($index/$count)：$file $pct%（$doneMb/$totalMb MB）"
                    }
                }
                runOnUiThread {
                    downloading = false
                    downloadBtn.isEnabled = true
                    modelProgress.visibility = View.GONE
                    modelProgressText.text = ""
                    Prefs.setModelReady(this, true)
                    refreshModelStatus()
                    toast("模型下载完成并校验通过，端侧转写已就绪")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    downloadBtn.isEnabled = true
                    modelProgress.visibility = View.GONE
                    modelProgressText.text = ""
                    refreshModelStatus()
                    toast("下载失败：${e.message}".take(100))
                }
            }
        }, "voice-ime-model-download").start()
    }

    // ---------- 保存 ----------

    private fun save() {
        val host = hostEdit.text.toString().trim()
        val port = portEdit.text.toString().trim().toIntOrNull() ?: 6016
        Prefs.setHost(this, host)
        Prefs.setPort(this, port)
        Prefs.setModelUrl(this, modelUrlEdit.text.toString().trim())
        toast("已保存")
        finish()
    }

    // ---------- 小工具 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTextColor(Color.BLACK)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
