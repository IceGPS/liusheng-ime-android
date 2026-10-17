# Voice-IME Android

## 一、开发目的

1. 解决个人数据隐私问题，放弃使用商业公司提供的在线输入法。
2. 解决在线语音输入法无法离线使用的问题。
3. 实现个人语音和文字数据的搜集整理。

## 二、主要功能（V41）

1. **离线语音输入**：基于 sherpa-onnx + SenseVoice-Small 本地模型，按住说话、松开上屏，断网可用。
2. **拼音键盘**：QWERTY 全键盘，支持全拼和双拼两种方案，离线词库 88 万词条。
3. **个人语料库**：每次语音输入的录音（AAC/M4A）和转写自动存档，通过 Syncthing 同步到 PC，长期积累。
4. **极简模式**：默认只有一行按键，把屏幕面积最大限度留给编辑区；需要时一键切换全键盘。
5. **多平台数据互通**：与 Windows 版 Voice-IME 共用 Syncthing 同步方案。

---

这是 voice-ime 项目（https://github.com/IceGPS/voice-ime）的 Android 端。

## 三、V41 使用方法

### 安装

1. 从 GitHub Release 或 `dist/` 拿最新 `voice-ime-android-v*.apk` 传到手机
2. 点击安装（允许"安装未知应用"），**安装后核对文件大小**（见 Release 说明）
3. 打开 App，按引导完成：麦克风权限 → 启用输入法 → 切换到 Voice-IME
4. 首次使用拼音键盘时词库已内置，无需下载；语音模型（约 230MB）在设置页下载一次即可

### 极简键盘（默认）

主行：`⇧ | ⚙️ | 🌐 | ⌨️ | 🎤 | ⌫ | ⏎`

- **🎤**：按住中央说话，松开转写上屏（录音时按钮变红）；点按其他位置输入空格
- **⌨️**：切换到拼音全键盘
- **⇧**：展开/收起符号区和数字区（状态持久化）
  - 符号区（可左右滑动）：`， 。 ？ _ $ @ # ' " ; ! : 、 … — · ～`
  - 数字区（可左右滑动）：`1 2 3 4 5 6 7 8 9 0 + - × ÷ = % > < ( ) . ,`
  - 点符号/数字后不自动收起，可连续输入
- **⚙️**：设置（语音引擎、VAD、拼音方案、模型下载）
- **🌐**：切回上一个输入法

### 拼音键盘

点主行的 ⌨️ 进入。QWERTY 布局，键面有上标提示：

- **点按字母**：输入拼音，候选栏显示汉字候选，点选上屏
- **按住字母上滑**：输入上标的数字/符号（如 Q 上滑出 `1`），有预览框和震动反馈
- **空格键**：中央 🎤 按住说话（语音输入）；其他位置点按：有拼音时选首选上屏，无拼音时输入空格
- **空格键左侧 ⌨️**：切回极简键盘
- **候选栏**：可左右滑动浏览全部候选；点右端 `>` 弹出浮层（4 列网格，一次看 20 个），点选上屏、点外面关闭
- **中/英**：切换中文拼音输入和英文直接输入
- **符 / 123**：符号键盘和数字键盘（开发中，当前点按提示）

### 设置页

- **转写引擎**：端侧优先（默认）/ 仅局域网
- **VAD 模式**：点按开始、静音自动停止（默认关闭为按住说话）
- **拼音方案**：全拼（默认）/ 小鹤双拼
- **端侧模型**：SenseVoice-Small INT8，约 230MB，下载进度和 SHA256 校验

### 语音转写后端

- [x] 可插拔接口：`service/asr/AsrBackend`（`transcribe(pcm16k): AsrResult(text, latencyMs)`）
- [x] 端侧 `SherpaAsrBackend`：sherpa-onnx 跑 SenseVoice-Small INT8
- [x] 局域网 `LanAsrBackend`：WebSocket 发往 PC 服务端（CapsWriter 协议，备用）
- [x] mDNS 自动发现局域网服务端（`_voiceime._tcp.`）
- [x] 每次转写耗时打 logcat（tag=`VoiceIME`）

## 四、版本演进：V8 → V41

### V8 → V23：端侧 ASR 路线确立（2026-10-12 ~ 10-13）

V8 还是"计划用 MNN 跑 Qwen3-ASR"的阶段。V17–V22 在真机上反复闪退，先后怀疑过
`decodingMethod`、`provider`、sherpa 版本号，四个盲修全错。直到用 `adb logcat`
抓到 `F/sherpa-onnx` 的 FATAL 行，才定位根因：传了非空 AssetManager 却用绝对路径
加载模型。V23 一行修复（传 `null`），SenseVoice-Small 在小米 MIX FOLD 4 上跑通。

**思路变化**：从"猜参数"到"先看日志"。立下规矩：真机/native 闪退先抓 logcat，
FATAL 行会直接告诉你答案。

### V24 → V26：数据链路和连接（2026-10-13）

- V24：录音（AAC）+ JSONL 转写日志存到 `/sdcard/VoiceIME`，供 Syncthing 同步——
  "数据主权"从口号变成目录结构。
- V25：VAD 模式（点按开始、静音 1.5 秒自动停），默认关闭，设置页有开关。
- V26：mDNS 自动发现局域网 PC 服务端。认识到多服务器是鸿杰局域网的常态，
  不是边缘情况。

### V27 → V29：极简键盘的输入能力补完

- V27：⇧ 开关展开数字/标点区（默认关闭，状态持久化）。
- V28：数字标点区改两行置顶。
- V29：跟随系统深浅色，按键间隙缩小。鸿杰评价"非常漂亮，超出预期"。

**思路变化**：极简不等于残缺。语音是主力，但数字符号必须触手可及，
否则连输个验证码都要切输入法。

### V30 → V31：录音格式和命名

- V30：WAV 改 AAC/M4A（MediaCodec 系统编码），体积缩小一个数量级；
  定下规矩：移动端录音统一 AAC，永不用 WAV。
- V31：文件名从纯时间戳改成 `(时间戳)识别文本前15字.m4a`，
  对标 CapsWriter——在文件管理器里一眼能看到录音内容。

### V32 → V34：拼音键盘（2026-10-16）

转折点。鸿杰用了几天极简键盘后说："再改进一下就可以替代手机默认输入法，
但输入个别汉字（尤其生僻字）麻烦，语音不是强项，必须有键盘输入。"
他本人用小鹤双拼，但要求同时支持全拼让别人也能用。

- V32：QWERTY 手绘键盘 + 候选栏 + 语音/拼音模式切换。引擎方案上放弃了
  librime JNI（NDK + boost/leveldb 依赖重、CI 构建慢、难调试），改用
  **纯 Kotlin 引擎 + RIME 词库数据**：雾凇拼音 88 万词条预处理成 27MB
  二进制，迭代快、无 native 崩溃、APK 更小。`PinyinEngine` 接口预留，
  以后可换 librime 而不改 UI。
- V33：修 CI 漏打 assets 的 bug（`aapt2 link` 缺 `-A` 参数），词库随包发布。
- V34：设置页加拼音方案切换（全拼/小鹤双拼），模糊音默认关闭
  （鸿杰：z/zh 等六组声母对他"区分非常清楚"）。

### V35 → V37：候选词体验对标搜狗

- V35：候选栏横滑 + 点 `>` 弹浮层面板。对比搜狗"整屏替换"的做法，
  改成浮层盖在键盘上方——点选上屏、点外关闭，不用来回切换。
- V36：候选字 19sp → 22sp。实测发现字形和搜狗一样大，观感差异来自
  行高留白，加大字号的同时加了上下内边距。
- V37：极简键盘数字区 `0-9` 改 `1-0`，与拼音键盘上标顺序一致。

### V38 → V39：语音输入统一

- V38：拼音键盘空格键中央 🎤 改按住录音（红底反馈），和极简键盘行为一致。
  用户原话："空格键不管在哪个键盘上都可以保持一致的行为：做语音输入。"
- V39：修三个 bug——极简键盘录音红底提示、折叠屏展开时 mic 热区失效
  （热区缓存没跟上尺寸变化，改按住瞬间动态计算）、候选浮层抢焦点
  导致拼音直上屏（PopupWindow 改不可聚焦）。

### V40 → V41：极简键盘收尾

- V40：符号区/数字区改横滑扩充（符号 17 个、数字+数学符号 22 个）；
  新增了一行独立空格键。
- V41：用户纠正——"不应该新增一行，把原来的语音输入键改造一下就可以了"。
  删掉新增行，原"🎤 按住说话"键改成 🎤 居中：按住中央录音、点其他位置
  输入空格。界面做减法，回到 7 个键。

**思路变化**：听用户的。开发者觉得"加一行"顺手，用户觉得"改原来"
更干净——输入法的每一行高度都是寸土寸金。

## 五、致谢

### 中州韵（RIME）

拼音键盘的词库和双拼方案全部来自 RIME 社区：

- **词库数据**：rime-ice（雾凇拼音）的 `base.dict.yaml`（55 万行）、
  `ext.dict.yaml`（33 万行）、`8105.dict.yaml`，共 88 万词条。
  没有 RIME 社区十几年的词库积累，离线拼音输入法做不出来。
- **小鹤双拼映射**：采用 RIME `xiaohe.schema.yaml` 的社区标准
  （zh→v、ch→i、sh→u 等），保证和主流双拼输入法键位一致。
- 本项目只用了 RIME 的**数据**，引擎是独立实现的纯 Kotlin 代码，
  没有使用 librime。

### CapsWriter（HaujetZhao）

项目的起点，Windows 版直接基于 CapsWriter-Offline 改造：

- **局域网协议**：Android 端 `LanAsrBackend` 用的就是 CapsWriter 的
  WebSocket 协议，PC 服务端零改动即可对接。
- **录音命名**：`(时间戳)识别文本.m4a` 的文件名格式是对标 CapsWriter 学的。
- **JSONL 转写日志**：按天记录转写文本、时间戳、音频路径的思路来自 CapsWriter。
- **每日文档**：把一天的输入汇总成文档、再做 AI 总结的两层设计，
  是在 CapsWriter 日记功能基础上的延伸。

## 六、已知限制

- 密码框/银行类 App：按设计不在支持范围（本工具只做日常便捷输入）。
- `符` / `123` 键在拼音键盘上尚未实现（点按提示）。
- 用户词典自学习（按使用频率调候选排序）开发中。
- iOS / Linux / macOS 版按计划推进中。

## 七、构建

需要 JDK 17 + Android SDK（API 34）+ kotlinc，云端 CI 自动构建。
本地手动构建：

```bash
export JAVA_HOME=<jdk17> ANDROID_HOME=~/android-sdk KOTLINC_HOME=<kotlinc>
tools/build-apk-manual.sh dist/voice-ime-android-vX.apk
# 自动完成：aapt2 编译资源（含 assets 词库）→ kotlinc 编译 → d8 dex → 对齐 → apksigner 签名
```

注意：`aapt2 link` 必须带 `-A app/src/main/assets`，否则词库打不进包
（V33 踩过的坑）。

## 八、目录结构

```
app/src/main/
├── assets/
│   └── pinyin_dict.bin          # 拼音词库（27MB，雾凇拼音预处理）
├── java/com/icegps/voiceime/
│   ├── MainActivity.kt          # 主界面
│   ├── ime/
│   │   ├── VoiceImeService.kt   # 输入法服务：极简键盘 + 拼音键盘切换
│   │   └── pinyin/              # 拼音键盘模块
│   │       ├── PinyinKeyboardView.kt   # 手绘 QWERTY 键盘（含上滑手势）
│   │       ├── CandidateBarView.kt     # 候选栏 + 浮层展开面板
│   │       ├── PinyinInputController.kt# UI 事件 → InputConnection
│   │       ├── PinyinEngine.kt         # 引擎接口（可换 librime）
│   │       ├── KotlinPinyinEngine.kt   # 纯 Kotlin 实现
│   │       ├── PinyinDictionary.kt     # 二进制词典加载
│   │       ├── PinyinSyllables.kt      # 音节切分
│   │       ├── ShuangpinMapper.kt      # 小鹤双拼映射
│   │       └── EngineAdapter.kt        # 引擎 → 接口适配
│   ├── service/
│   │   ├── Recorder.kt          # AudioRecord 封装
│   │   ├── ServerClient.kt      # WebSocket 客户端（CapsWriter 协议）
│   │   ├── ModelDownloader.kt   # 模型下载
│   │   └── asr/
│   │       ├── AsrBackend.kt    # 转写后端接口
│   │       ├── SherpaAsrBackend.kt     # 端侧 SenseVoice
│   │       └── LanAsrBackend.kt        # 局域网 PC
│   ├── ui/
│   │   ├── OnboardingActivity.kt
│   │   └── SettingsActivity.kt  # 引擎/VAD/拼音方案/模型下载
│   └── util/
│       ├── Protocol.kt
│       └── Prefs.kt             # 配置持久化
└── res/                         # 资源（跟随深浅色）
tools/
├── build-apk-manual.sh          # 手动打包脚本（CI 同款流程）
└── pinyin-data/
    ├── preprocess_dict.py       # 词库预处理（RIME yaml → 二进制）
    └── *.dict.yaml              # RIME 原始词库（不进 git）
```

## 九、延迟数据回传

每次转写都会打一条 logcat：

```
I/VoiceIME: [sherpa] transcribe latency=1230ms textLen=18 on-device sensevoice-small-int8
I/VoiceIME: [lan] transcribe latency=890ms textLen=18 host=192.168.1.100:6016
```

真机测试时抓 `adb logcat -s VoiceIME` 回传即可。
