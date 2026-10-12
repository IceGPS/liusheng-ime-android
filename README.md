# Voice-IME Android

## 一、开发目的

1. 解决个人数据隐私问题，放弃使用商业公司提供的在线输入法。
2. 解决在线语音输入法无法离线使用的问题。
3. 实现个人语音和文字数据的搜集整理。

## 二、主要功能

1. 基于本地模型的语音输入法，离线也可以使用，当前版本使用 QWEN ASR 0.6B 和 1.7B 本地模型。
2. 把每天通过本输入法输入的录音和文字记录起来，成为个人的历史素材库。
3. 每天自动把输入的内容汇总在一起，形成当天的输入记录文档。
4. 通过本地大模型对每天的输入内容进行整理和总结。
5. 支持 Windows、Linux、Android 等系统，基于 Syncthing 技术实现不同设备间的数据同步。
6. 极简的单行输入面板，在手机上使用的时候尽可能把屏幕面积保留给编辑区。

---

这是 voice-ime 项目（https://github.com/IceGPS/voice-ime）的 Android 端。
Windows 版已完成并通过验收。

## 当前形态（V8）：极简输入法

不再是悬浮球 + 无障碍方案，而是一个真正的系统输入法（InputMethodService）：

- **🎤 按住说话**：松开后转写，走系统 `commitText` 上屏——光标位置天然正确，不覆盖、不乱跳
- **⌫ 退格**：点按删一个字符，长按连续删除（`deleteSurroundingText`）
- **🌐 切换**：一键切回上一个输入法（搜狗/Gboard）

只需要两个权限：**麦克风** + **在系统设置里启用输入法**。
不再需要悬浮窗、无障碍、电池白名单、自启动。

## 转写后端

- [x] 可插拔接口：`service/asr/AsrBackend`（`transcribe(pcm16k): AsrResult(text, latencyMs)`）
- [x] 端侧后端 `MnnAsrBackend`：MNN Omni 引擎跑 Qwen3-ASR-0.6B INT8，
      纯 CPU，greedy 采样（temp=0, top_k=1）
- [x] 局域网后端 `LanAsrBackend`：WebSocket 发往 PC 服务端（CapsWriter 协议，备用/降级）
- [x] 模型下载流程：首次启动弹窗提示 → 仅 WiFi 下载（~826MB）→
      进度条 → SHA256 校验 → 标记就绪；下载地址在设置页可配置
- [x] 设置页：转写引擎选择（端侧优先 / 仅局域网）、模型状态
      （未下载/下载中x%/已就绪）、手动重新下载
- [x] 每次转写耗时打 logcat（tag=`VoiceIME`，INFO 级），方便回传延迟数据
- [ ] 端侧 NDK 构建（GitHub Actions 云端构建中）
- [ ] 1.7B 模型（端侧 0.6B 验证通过后再定）

## 安装（APK 侧载）

1. 从 `dist/` 拿最新 `voice-ime-android-v*.apk` 传到手机
2. 点击安装（允许"安装未知应用"）
3. 打开 App，按引导完成两步：麦克风权限 → 启用输入法
4. 在设置页填写 PC 服务端 IP（如 `192.168.1.100`，局域网模式用）
5. 在任意输入框调出键盘时，点键盘切换图标换到 Voice-IME

前置条件（局域网模式）：
- PC 上的 CapsWriter 服务端运行中（6016 端口）
- 手机与 PC 在同一 WiFi 下
- 端侧模式：需下载模型（约 826MB），下载后断网也能用

## 模型下载说明

- 模型文件（`llm.mnn` + `audio.mnn` + `llm_config.json`，INT8 共约 826MB）
  **绝不打包进 APK**，只走 App 内下载流程进入私有目录
  `<filesDir>/models/qwen3-asr-0.6b-int8/`。
- 下载地址默认占位 `https://github.com/IceGPS/voice-ime-android/releases`
  （`util/Prefs.kt` 的 `DEFAULT_MODEL_URL`，设置页可改）。
  **实际 hosting 确定后**，需保证 `<baseUrl>/SHA256SUMS` 与
  `<baseUrl>/<filename>` 可直接下载（SHA256SUMS 为 coreutils 格式，
  每行 `<sha256><空格><文件名>`）。

## 已知限制

- 端侧 JNI 库（`libvoiceime_mnn.so`）需要 NDK + MNN 源码编译，
  目前走 GitHub Actions 云端构建；`dist/` 的本地构建版是纯 Kotlin，
  端侧后端在运行时检测到缺库会自动降级到局域网。
- 密码框/银行类 App 的系统限制：输入法本身不受影响（这是无障碍方案的问题，IME 方案无此问题）。

## 构建

需要 JDK 17 + Android SDK（API 34）+ kotlinc：

```bash
export JAVA_HOME=<jdk17> ANDROID_HOME=~/android-sdk KOTLINC_HOME=<kotlinc>
tools/build-apk-manual.sh dist/voice-ime-android-vX.apk
# 自动完成：aapt2 编译资源 → kotlinc 编译 → d8 dex → 对齐 → apksigner 签名
```

## 目录结构

```
app/src/main/java/com/icegps/voiceime/
├── MainActivity.kt            # 主界面：状态总览 + 入口
├── ime/
│   └── VoiceImeService.kt     # 极简输入法：🎤语音 / ⌫退格 / 🌐切换
├── service/
│   ├── Recorder.kt            # AudioRecord 封装
│   ├── ServerClient.kt        # WebSocket 客户端（CapsWriter 协议，OkHttp）
│   ├── ModelDownloader.kt     # 端侧模型下载（WiFi/进度/SHA256）
│   └── asr/
│       ├── AsrBackend.kt      # 转写后端可插拔接口
│       ├── MnnAsrBackend.kt   # 端侧：MNN Omni 跑 Qwen3-ASR-0.6B INT8
│       └── LanAsrBackend.kt   # 备用：局域网 WebSocket
├── ui/
│   ├── OnboardingActivity.kt  # 引导页（麦克风 + 启用输入法）
│   └── SettingsActivity.kt    # 设置页（引擎选择/模型下载/服务端）
├── util/
│   ├── Protocol.kt            # 协议数据类
│   └── Prefs.kt               # 配置存储
└── cpp/
    ├── mnn_asr_bridge.cpp     # JNI 桥（Kotlin ↔ MNN Omni，需 NDK 编译）
    └── CMakeLists.txt
```

## 延迟数据回传

每次转写（端侧/局域网）都会打一条 logcat：

```
I/VoiceIME: [mnn] transcribe latency=2130ms textLen=18 on-device qwen3-asr-0.6b-int8
I/VoiceIME: [lan] transcribe latency=890ms textLen=18 host=192.168.1.100:6016
```

真机测试时抓 `adb logcat -s VoiceIME` 回传即可。
