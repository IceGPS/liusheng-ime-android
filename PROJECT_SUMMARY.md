# 留声输入法（Liusheng）Android 项目总结文档

> 版本：V70（2026-10-20）
> 仓库：https://github.com/IceGPS/liusheng-ime-android
> 目标读者：未来接手维护/升级的开发者

---

## 一、项目简介

留声输入法是一款**极简离线语音输入法**，核心理念：**离线可用、数据主权**。

- 用户按住 🎤 说话，松开后语音在**本机**转成文字，直接上屏
- 每天的语音和转写自动存档（录音 + JSONL），通过 Syncthing 同步到 PC，长期积累个人语料
- 不依赖任何云服务，不上传任何数据

### 与最初设想的差异

| 最初设想 | 实际做出来的 |
|---|---|
| Fork CapsWriter，加 Android 端 | 全新 Kotlin 项目，只复用协议 |
| 悬浮球 + 无障碍服务上屏 | 极简 IME（InputMethodService），系统原生上屏通道 |
| MNN + Qwen3-ASR-0.6B 端侧 | sherpa-onnx + SenseVoice-Small（MNN 路线已放弃） |
| 完整键盘 | 四套键盘：极简语音 / 拼音（全拼+小鹤双拼）/ 符号 / 数字；底行统一为 [ABC/语音][符][123][🎤][。][⌫][⏎]（V67 起），候选栏右端 [📋][中/英][>]（V70 起） |

---

## 二、技术架构

### 2.1 总体架构

```
┌──────────────────────────────────────────────────────┐
│  VoiceImeService (InputMethodService)                 │
│  极简语音键盘（V67 起：可收起符号/数字区已退役）        │
│  PinyinKeyboardView + PinyinInputController（拼音键盘） │
│  SymbolKeyboardView（符号/数字键盘，V63 起完整实现）    │
│  底行四套统一：[ABC/语音][符][123][🎤][。][⌫][⏎]       │
└──────────┬──────────────────┬────────────────────────┘
           │                  │
    ┌──────▼──────┐    ┌─────▼────────┐
    │  Recorder   │    │ AsrBackend   │ ← 策略接口
    │  录音(PCM)  │    │  ├─ SherpaAsrBackend（端侧默认）
    └──────┬──────┘    │  └─ LanAsrBackend（局域网 PC）
           │          └──────────────┘
    ┌──────▼──────────────────────────────────┐
    │  TranscriptLogger                        │
    │  录音存 AAC/M4A + JSONL 转写日志          │
    │  → /sdcard/VoiceIME/（Syncthing 同步）    │
    └─────────────────────────────────────────┘
    ┌──────▼──────────────────────────────────┐
    │  ClipStore + ClipSyncManager（V69 起）    │
    │  本机剪贴板历史（SQLite）+ 局域网共享      │
    │  （mDNS 发现 + 配对 + AES-GCM，手机↔PC）  │
    └─────────────────────────────────────────┘
```

### 2.2 模块说明

| 模块 | 文件 | 职责 |
|---|---|---|
| 键盘服务 | `ime/VoiceImeService.kt` | InputMethodService；极简语音键盘 UI；录音手势；转写调度；文本上屏 |
| 拼音键盘 | `ime/pinyin/PinyinKeyboardView.kt`、`PinyinInputController.kt` | 手绘 QWERTY；候选栏/浮层；中/英切换；剪贴板面板入口 |
| 拼音引擎 | `ime/pinyin/PinyinEngine.kt` 等 | 纯 Kotlin 拼音引擎 + 雾凇词库（88 万词条）；小鹤双拼 |
| 符号键盘 | `ime/pinyin/SymbolKeyboardView.kt`、`SymbolWidth.kt` | 符号 2 页 / 数字键盘；全角半角区分；点选停留连打 |
| 剪贴板 | `service/clip/ClipStore.kt`、`ClipSyncManager.kt`、`ClipCrypto.kt` | SQLite 历史；mDNS 发现；配对；AES-GCM；手机↔PC 同步 |
| 录音 | `service/Recorder.kt` | AudioRecord 采集 16kHz PCM；支持 VAD 自动停止 |
| 端侧识别 | `service/asr/SherpaAsrBackend.kt` | sherpa-onnx + SenseVoice-Small INT8；离线转写 |
| 局域网识别 | `service/asr/LanAsrBackend.kt` | WebSocket 连 PC 端（CapsWriter 协议，6016端口） |
| 识别接口 | `service/asr/AsrBackend.kt` | 策略接口，统一端侧/局域网调用 |
| 存档 | `service/TranscriptLogger.kt` | PCM→AAC 编码；写 M4A 文件；append JSONL |
| 模型下载 | `service/ModelDownloader.kt` | 从 Release/HuggingFace 下载模型；SHA256 校验；支持备份恢复 |
| 服务发现 | `service/MdnsDiscovery.kt` | mDNS 发现局域网 PC 服务端（`_voiceime._tcp.`） |
| 设置页 | `ui/SettingsActivity.kt` | 引擎切换；服务器配置；VAD 开关；模型管理 |
| 引导页 | `ui/OnboardingActivity.kt` | 首次启动引导：麦克风权限→启用输入法→下载模型 |
| 主页 | `MainActivity.kt` | 状态总览（引擎/模型/服务器） |
| 偏好 | `util/Prefs.kt` | SharedPreferences 封装；所有用户设置持久化 |

### 2.3 关键技术选型

| 技术点 | 选型 | 说明 |
|---|---|---|
| 输入法框架 | InputMethodService | 系统原生 IME，`commitText()` 上屏，光标/覆盖问题天然不存在 |
| 端侧 ASR 引擎 | sherpa-onnx 1.13.8 | 预编译 AAR，无需手写 JNI |
| 端侧模型 | SenseVoice-Small INT8 | ~230MB，中文识别快而准；IME 进程内存可承受 |
| 录音格式 | AAC/M4A | MediaCodec 系统编码；Android/iOS 通用；约为 WAV 的 1/10 |
| 日志格式 | JSONL | 每行一个 JSON，追加高效，坏一行不影响其他 |
| 局域网协议 | WebSocket（CapsWriter 协议） | 复用 Windows 端现有服务端 |
| 服务发现 | mDNS（`_voiceime._tcp.`） | Android NSD；Windows 端用 zeroconf 发布 |
| 构建 | GitHub Actions | 云端构建，约 1 分钟；固定签名 keystore |

### 2.4 数据流

**语音输入流程**：
```
用户按住 🎤 → Recorder 采集 PCM → 松开 → AsrBackend.transcribe()
  → SherpaAsrBackend（端侧）或 LanAsrBackend（局域网）
  → textDocumentProxy.commitText() 上屏
  → TranscriptLogger.log() 存档（M4A + JSONL）
```

**存档目录结构**（`/sdcard/VoiceIME/`）：
```
recordings/2026/10/(20261013-153022)今天天气不错.m4a
logs/2026/10/2026-10-13.jsonl
```

JSONL 每行：
```json
{"ts":"2026-10-13T15:30:22","audio":"recordings/2026/10/(20261013-153022)今天天气不错.m4a","text":"识别结果","engine":"ondevice","dur_ms":3200}
```

---

## 三、工作流程（开发→交付）

### 3.1 分工

- **Mica**：全部开发工作（写代码、构建、自测、推送、交付 APK）
- **Hongjie**：真机验收（在他自己的小米 MIX FOLD 4 上测试）

### 3.2 迭代流程

1. Hongjie 提需求/反馈（通常带截图）
2. Mica 改代码 → 本地提交 → push 到 GitHub（`master:main`）
3. GitHub Actions 自动构建（约 5–10 分钟；注意按 workflow 名 + head_sha 过滤，勿取到 pages run）
4. Mica 下载 artifact → `apksigner verify` 验签 → 核对 versionCode → 发 GitHub Release（附 APK + AAB）
5. 告诉 Hongjie：版本号 + 提交 + CI run + 准确字节数（他自己去 GitHub Releases 下载，装前核对字节数）
6. Hongjie 真机测试 → 反馈 → 下一轮

**铁律**：只 `git add` 指定文件，绝不 `git add -A`（会误带 `app/libs/*.aar`、`dist/`、`icons/`、`tools/pinyin-data/`）。

### 3.3 版本号规则

- `versionCode` 每次递增（V23=23, V31=31）
- `versionName` = `0.<versionCode>.0-v<versionCode>`
- 提交信息格式：`v31: 一句话说明`

### 3.4 交付检查清单

- [ ] `apksigner verify` 通过
- [ ] versionCode 确实递增（versionName = `0.<code>.0-v<code>`）
- [ ] APK/AAB 已上传到 GitHub Release（v42 起每个版本都发 Release，不再用 CI artifacts 直链）
- [ ] 记录准确字节数（V65 损坏下载事故后，装前必须核对）
- [ ] targetSdk 35、AAB 随附（Play 上架要求，V59/V60 起）

---

## 四、尝试过的技术方案（踩坑记录）

### 4.1 上屏方案：无障碍服务 → IME

**尝试过**：悬浮球 + AccessibilityService 注入文本（V1-V4）

**遇到的问题**：
- 三星手机：文字进了输入法自己的候选栏，没上屏
- 小米手机：光标跳到开头，覆盖已有文字
- 两轮修复仍"不理想"

**最终方案**：改走 `InputMethodService`，用系统原生的 `commitText()` 上屏。光标位置、覆盖、候选栏问题天然不存在。Hongjie 评价"使用效果非常好"。

**教训**：不要跟系统对着干，用系统原生通道最可靠。

### 4.2 端侧 ASR：MNN + Qwen3-ASR → sherpa-onnx + SenseVoice

**尝试过**：MNN 3.6.1 + Qwen3-ASR-0.6B（自研 JNI 桥接，`mnn_asr_bridge.cpp`）

**遇到的问题**：
1. MNN Omni 引擎的 `<audio>` 标签解析在 CI 构建版本中不可用 → native 崩溃
2. 0.6B 模型 1.39GB，IME 进程（256-512MB 内存预算）必 OOM
3. 每次改 native 代码都要等 20+ 分钟 CI 编译

**最终方案**：sherpa-onnx 1.13.8（预编译 AAR）+ SenseVoice-Small INT8（~230MB）
- 有官方 Kotlin API，无需手写 JNI
- CI 从 20 分钟降到 1 分钟
- 识别速度快、中文准，Hongjie 实测满意

**教训**：优先用有预编译包的成熟方案，不要自己造轮子编译 native 库。

### 4.3 闪退定位：盲猜 → 先看 logcat

**血的教训**（V23 之前）：端侧模型加载闪退，先后盲猜了 4 个原因（tokens 路径、decodingMethod、provider、sherpa 版本），全错。

**根因**（Hongjie 抓的 adb logcat 明确指出）：
```
You are using an absolute path '.../tokens.txt',
but assetManager is NOT set to null.
```
代码用绝对路径加载模型，却传了 `appCtx.assets` 给 `OfflineRecognizer`。修复：传 `null`。

**铁律**：Android 真机 native 闪退，第一步永远是 `adb logcat`，不要盲猜。手机上的 Logcat Reader 看不到系统级崩溃堆栈，必须用电脑 adb。

### 4.4 录音格式：WAV → AAC/M4A

**最初**：WAV（实现简单，直接写 PCM + 文件头）

**问题**：文件大（约为 AAC 的 10 倍）；且项目既定规则是移动端统一 AAC

**最终**：Android MediaCodec 硬编码 AAC，存 M4A。零第三方依赖。iPhone（AVFoundation）同样原生支持 AAC，未来 iOS 版沿用。

**决策记录**（2026-10-13，Hongjie）：Android 和未来 iPhone 统一用 AAC/M4A，不用 WAV。

### 4.5 模型状态显示：MnnAsrBackend 残留

**问题**：从 MNN 切换到 sherpa 后，`SettingsActivity` 和 `MainActivity` 还在调用 `MnnAsrBackend.isModelReady()`，导致"模型明明存在却显示未下载"。

**教训**：切换底层引擎时，用 grep 全局搜旧类名，不要漏。已修复两处。

### 4.6 GitHub 推送：connector/SSH → HTTPS + PAT

**尝试过**：
- custom GitHub connector：一直报 `Failed to connect`
- SSH deploy key：云端沙盒代理 reset 22/443 端口，不可用

**最终**：Hongjie 提供的 classic PAT（`repo` + `workflow` 权限），`git push https://<token>@github.com/...` 可用。Token 保留用于后续迭代，收尾时提醒 revoke。

**注意**：不要在任何回复/日志/文档中显示 token 原文。

### 4.7 模型备份恢复：分区存储限制

**问题**：Android 11+ 分区存储，直接 File API 读写 `/sdcard/Download` 不可靠。

**解决**：申请 `MANAGE_EXTERNAL_STORAGE`（所有文件访问权限），设置页放授权按钮，授权返回后重试恢复。备份位置：`/sdcard/Download/voice-ime-models/sense-voice-small/`。

---

## 五、用户设置持久化清单

所有设置存 `SharedPreferences`（`util/Prefs.kt`），IME 重启后恢复：

| 设置项 | Key | 默认值 |
|---|---|---|
| 转写引擎 | `asr_engine` | 端侧优先 |
| 服务端 IP/端口 | `server_host` / `server_port` | 空 / 6016 |
| VAD 开关 | `vad_enabled` | false |
| VAD 静音时长 | `vad_silence_ms` | 1500ms |
| 键盘模式 | `kb_mode` | voice（语音/拼音持久化） |
| 拼音方案 | `pinyin_scheme` | 全拼/小鹤 |
| 模型地址 | `model_base_url` | HuggingFace 默认 |
| 模型已就绪 | `model_ready` | false |
| 剪贴板总开关 | `clip_enabled` | false（首次配对后自动开） |
| 本机设备 ID | `clip_device_id` | 首次生成后持久化 |
| 配对表 | `clip_paired_json` | 空 |

**原则**：键盘主界面的每个可交互元素，状态都必须持久化，用户下次打开键盘时恢复。

---

## 六、已知待办 / 未来方向

1. **mDNS 多服务器**：目前 Android 端自动选第一台；目标是多服务器列表、用户选择并记住、故障转移（V26 起部分实现）
2. **VAD 阈值调优**：当前固定 RMS 0.02，未经充分真机验证，可能需要自适应环境噪声
3. **Syncthing 配置指导**：待存储格式稳定后，指导 Hongjie 配置 `/sdcard/VoiceIME` 同步
4. **用户词典自学习**：拼音输入记住用户选词习惯，未做
5. **剪贴板 v2**：极简键盘入口、历史搜索、图片条目、离线队列、TLS 通道
6. **iOS / macOS / Linux 版**：待 Hongjie 决策平台顺序（iOS 键盘内录音已验证可行）
7. **Play 上架**：Mica 侧已就绪（targetSdk 35、AAB、隐私政策、商店素材）；待 Hongjie：开发者账号、数据安全表、分级问卷、真机截图、USPTO 1(b) 决策

---

## 七、关键联系人 & 资源

- **用户/测试**：Hongjie（小米 MIX FOLD 4，Snapdragon 8 Gen 3，16GB RAM）
- **Android 仓库**：https://github.com/IceGPS/liusheng-ime-android
- **Windows 仓库**：https://github.com/IceGPS/CapsWriter-Offline
- **模型**：sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17（INT8）
- **签名**：CI 用 GitHub secret `KEYSTORE_B64` 固定签名（pass:android）
- **APK 分发**：GitHub Release（`https://github.com/IceGPS/liusheng-ime-android/releases`），每版附 APK + AAB；交付信息含版本/提交/CI run/字节数/签名验证，不再发直链（Hongjie 自己去 GitHub 下）

---

*本文档随项目演进更新。每次大版本变更后同步修订。*
