# jniLibs/ — 预编译 MNN 动态库（不进 git，与官方 MnnLlmChat demo 一致）

本目录放 `arm64-v8a/libMNN.so`（必须包含 LLM/Omni 引擎，即用
`-DMNN_BUILD_LLM=true -DLLM_SUPPORT_AUDIO=true -DMNN_BUILD_AUDIO=true`
编出的版本）。

## 获取方式

```bash
# 1.  checkout MNN（版本见 ../../../../README.md "MNN native 构建"）
git clone https://github.com/alibaba/MNN.git
cd MNN

# 2. Android arm64 构建（需 NDK r27+）
./project/android/build_64.sh "-DMNN_BUILD_LLM=true -DLLM_SUPPORT_AUDIO=true \
  -DMNN_BUILD_AUDIO=true -DMNN_LOW_MEMORY=true -DMNN_CPU_WEIGHT_DEQUANT_GEMM=true \
  -DMNN_SUPPORT_TRANSFORMER_FUSE=true -DMNN_ARM82=true -DMNN_USE_LOGCAT=true \
  -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384' -DCMAKE_INSTALL_PREFIX=."

# 3. 拷贝到本目录（打包进 APK 时自动包含）
mkdir -p <voice-ime-android>/app/src/main/jniLibs/arm64-v8a
cp project/android/build_64/lib/libMNN.so \
   <voice-ime-android>/app/src/main/jniLibs/arm64-v8a/
```

## 说明

- `libMNN.so` 约几十 MB，是 MNN 推理引擎本体（含 LLM/Omni），不是模型文件。
- 模型文件（`llm.mnn`/`audio.mnn`，~826MB）**绝不**放这里，
  走 App 内的首次启动下载流程进私有目录。
- 没有本文件时 App 仍可编译安装，只是端侧转写不可用（自动降级局域网）。
