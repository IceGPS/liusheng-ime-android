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
import android.app.AlertDialog
import android.widget.Toast
import com.icegps.voiceime.service.ModelDownloader
import com.icegps.voiceime.service.asr.SherpaAsrBackend
import com.icegps.voiceime.service.clip.ClipSyncManager
import com.icegps.voiceime.util.Prefs

/**
 * 设置页：转写引擎、端侧模型下载、服务端 IP/端口、悬浮球外观。
 *
 * V2 新增：
 *  - 转写引擎：端侧优先（默认）/ 仅局域网
 *  - 端侧模型状态：未下载 / 下载中 x% / 已就绪（大小）
 *  - 手动下载 / 重新下载按钮（~230MB，SHA256 校验；WiFi 直接下载，手机流量需用户确认）
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
    private lateinit var pairedContainer: LinearLayout
    private lateinit var userDictEntryBtn: Button

    /** 配对列表变化时刷新（例如电脑端主动取消配对） */
    private val clipSyncListener = object : ClipSyncManager.Listener {
        override fun onPairedDevicesChanged() {
            runOnUiThread { if (::pairedContainer.isInitialized) refreshPairedList() }
        }
    }

    @Volatile private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        // ---------- 标题 ----------
        root.addView(TextView(this).apply {
            text = "留声离线语音输入法"
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(8))
        })

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

        // ---------- 拼音方案 ----------
        root.addView(sectionTitle("拼音方案"))
        val schemeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val radioQuanpin = RadioButton(this).apply {
            text = "全拼"
            id = View.generateViewId()
        }
        val radioXiaohe = RadioButton(this).apply {
            text = "小鹤双拼"
            id = View.generateViewId()
        }
        schemeGroup.addView(radioQuanpin)
        schemeGroup.addView(radioXiaohe)
        if (Prefs.getPinyinScheme(this) == "xiaohe") {
            radioXiaohe.isChecked = true
        } else {
            radioQuanpin.isChecked = true
        }
        schemeGroup.setOnCheckedChangeListener { _, checkedId ->
            val scheme = if (checkedId == radioXiaohe.id) "xiaohe" else "quanpin"
            Prefs.setPinyinScheme(this, scheme)
            toast(if (scheme == "xiaohe") "已切换为小鹤双拼" else "已切换为全拼")
        }
        root.addView(schemeGroup)

        // ---------- 剪贴板历史（V69） ----------
        root.addView(sectionTitle("剪贴板历史"))
        val clipSwitch = Switch(this).apply {
            text = "剪贴板历史（本机记录 + 局域网共享）"
            isChecked = Prefs.isClipEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setClipEnabled(this@SettingsActivity, checked)
                ClipSyncManager.init(this@SettingsActivity)
                toast(if (checked) "剪贴板历史已开启" else "剪贴板历史已关闭")
            }
        }
        root.addView(clipSwitch)
        root.addView(label("仅保存在本机；局域网共享需与电脑配对确认，全程 AES 加密，不经过互联网。"))

        val clipStatus = label("")
        val discoverBtn = Button(this).apply {
            text = "🔍 搜索可配对的电脑"
            setOnClickListener { discoverClipPcs(clipStatus, this) }
        }
        root.addView(discoverBtn)
        root.addView(clipStatus)

        root.addView(label("已配对的电脑："))
        pairedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(pairedContainer)
        ClipSyncManager.init(this)
        ClipSyncManager.addListener(clipSyncListener)
        refreshPairedList()

        // ---------- 个人词典（V73：独立管理页，入口放设置页） ----------
        root.addView(sectionTitle("个人词典（自学习）"))
        root.addView(label("点选候选词会被记住（选 2 次以上置顶）；连续点选单字自动造词。长按候选栏的词也可删除。"))
        userDictEntryBtn = Button(this).apply {
            setOnClickListener {
                startActivity(android.content.Intent(this@SettingsActivity, UserDictActivity::class.java))
            }
        }
        root.addView(userDictEntryBtn)
        refreshUserDictEntry()

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
        root.addView(label("模型下载地址："))
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
        if (::pairedContainer.isInitialized) refreshPairedList()
        if (::userDictEntryBtn.isInitialized) refreshUserDictEntry()
    }

    override fun onDestroy() {
        try { ClipSyncManager.removeListener(clipSyncListener) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------- 剪贴板历史（V69） ----------

    /** 搜索局域网内支持剪贴板的电脑（mDNS clip=1），列出未配对的可发起配对 */
    private fun discoverClipPcs(status: TextView, btn: Button) {
        btn.isEnabled = false
        btn.text = "搜索中…"
        status.text = "正在搜索局域网内支持剪贴板的电脑…"
        ClipSyncManager.init(this)
        val pairedIds = ClipSyncManager.getPaired().map { it.id }.toSet()
        ClipSyncManager.discover(10000) { pcs ->
            // 10 秒内用户若已退出设置页，不再弹窗
            if (isFinishing || isDestroyed) return@discover
            btn.isEnabled = true
            btn.text = "🔍 搜索可配对的电脑"
            val fresh = pcs.filter { it.deviceId !in pairedIds }
            if (fresh.isEmpty()) {
                status.text = "未发现可配对的电脑（电脑端需先开启剪贴板共享并发布 mDNS）"
                return@discover
            }
            val names = fresh.map { "${it.name}（${it.host}）" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("选择要配对的电脑")
                .setItems(names) { _, which -> pairClipPc(fresh[which], status) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 发起配对：等用户在电脑端弹窗点同意（7.2） */
    private fun pairClipPc(pc: ClipSyncManager.DiscoveredPc, status: TextView) {
        val progress = AlertDialog.Builder(this)
            .setTitle("等待电脑确认")
            .setMessage("请在电脑「${pc.name}」的配对请求弹窗中点击同意…")
            .setNegativeButton("取消", null)
            .show()
        ClipSyncManager.pair(pc) { ok, msg ->
            try { progress.dismiss() } catch (_: Exception) {}
            if (isFinishing || isDestroyed) return@pair
            status.text = msg
            toast(msg)
            refreshPairedList()
        }
    }

    /** 重建已配对电脑列表 */
    private fun refreshPairedList() {
        pairedContainer.removeAllViews()
        val paired = ClipSyncManager.getPaired()
        if (paired.isEmpty()) {
            pairedContainer.addView(label("（无）"))
            return
        }
        paired.forEach { dev ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val nameTv = TextView(this).apply {
                text = "${dev.name}（${dev.host}）"
                textSize = 14f
                setTextColor(Color.DKGRAY)
            }
            row.addView(
                nameTv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            val unpairBtn = Button(this).apply {
                text = "取消配对"
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("取消配对？")
                        .setMessage("不再与「${dev.name}」共享剪贴板。")
                        .setPositiveButton("取消配对") { _, _ ->
                            ClipSyncManager.unpair(dev.id)
                            toast("已取消配对")
                            refreshPairedList()
                        }
                        .setNegativeButton("保留", null)
                        .show()
                }
            }
            row.addView(unpairBtn)
            pairedContainer.addView(row)
        }
    }

    // ---------- 引擎选择 ----------

    private fun saveEngine() {
        val v = if (radioLan.isChecked) Prefs.ENGINE_LAN_ONLY else Prefs.ENGINE_ONDEVICE_FIRST
        Prefs.setEngine(this, v)
        toast("转写引擎：${if (v == Prefs.ENGINE_LAN_ONLY) "仅局域网" else "端侧优先"}")
    }

    // ---------- 模型状态 ----------

    private fun refreshModelStatus() {
        // 先尝试从备份恢复（卸载重装后自动恢复，无需重新下载；V61 起走 MediaStore，无需存储权限）
        if (!SherpaAsrBackend.isModelReady(this)) {
            SherpaAsrBackend.restoreFromBackup(this)
        }
        // 恢复按钮的原始点击事件
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
        when (ModelDownloader.networkType(this)) {
            ModelDownloader.NetworkType.NONE -> {
                toast("无网络连接，无法下载")
                return
            }
            ModelDownloader.NetworkType.MOBILE -> {
                // 手机流量：弹窗请用户确认，不禁止下载
                AlertDialog.Builder(this)
                    .setTitle("使用手机流量下载？")
                    .setMessage("当前正在使用手机流量\n模型约 230MB，是否继续下载？")
                    .setPositiveButton("继续下载") { _, _ -> beginDownload() }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
            ModelDownloader.NetworkType.WIFI -> { /* 直接下载 */ }
        }
        beginDownload()
    }

    /** 实际开始下载的流程（WiFi 直接进，手机流量经用户确认后进） */
    private fun beginDownload() {
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

    // ---------- 个人词典入口（V73） ----------

    /** 刷新入口行条数 */
    private fun refreshUserDictEntry() {
        com.icegps.voiceime.ime.pinyin.UserDict.init(this)
        userDictEntryBtn.text = "个人词典（${com.icegps.voiceime.ime.pinyin.UserDict.count()} 条）>"
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
