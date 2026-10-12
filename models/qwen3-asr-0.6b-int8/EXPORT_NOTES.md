# Qwen3-ASR-0.6B MNN 导出记录（INT8）

> 日期：2026-10-12　|　用途：voice-ime Android V2 端侧转写（MNN 路线）
> 导出环境：Linux x86_64（CPU），Python 3.12 venv

## 1. 模型来源

- HuggingFace 官方：`Qwen/Qwen3-ASR-0.6B`（transformers 格式，单 `model.safetensors`）
- 实际大小：1,876,091,704 bytes（约 1.75 GiB；"1.2GB" 只是粗略估计）
- 校验：safetensors 可正常打开，共 612 个 tensor

## 2. 导出工具与版本

- 导出脚本：`alibaba/MNN` master 分支 `transformers/llm/export/llmexport.py`
- MNN commit：`9c1788748c4d8aba00f9b644f50f5c15576d7d80`（2026-10-09，已含 PR #4478 的 Qwen3-ASR 完整导出+运行时支持）
- 转换器：PyPI `MNN` 3.6.1 wheel 自带的 `mnnconvert`（pymnn fallback；未编译 C++ 版 MNNConvert binary）
- 关键依赖：`torch 2.14.1+cpu`、`transformers==4.57.6`（与 Qwen3-ASR 官方 repo pin 一致）、`qwen_asr`（`QwenLM/Qwen3-ASR` 官方推理包，需 `PYTHONPATH` 指向，另需 `pip install nagisa`）、`onnx/onnxruntime/onnxslim`

## 3. 导出命令（exact）

```bash
cd <MNN>/transformers/llm/export
PYTHONPATH=<Qwen3-ASR repo> python llmexport.py \
    --path <Qwen/Qwen3-ASR-0.6B 本地目录> \
    --dst_path ./Qwen3-ASR-0.6B-INT8 \
    --export mnn \
    --quant_bit 8 --embed_bit 16
```

要点（来自预研报告 + 社区实战 fengchengcn/mnn）：
- **没有传 `--transformer_fuse`**（store_true，默认 False；传了会把音频编码器错误融合）
- `--quant_bit 8`：LLM 部分 INT8 量化（含 tie_embeddings，`llm_config.json` 中 `quant_bit: 8`）
- `--embed_bit 16`：embedding 保持 bf16
- 音频编码器（`audio.mnn`）按官方默认走 **FP16**（`Audio.quant_bit` 硬编码 16，无 CLI 开关；与 PR #4478 验证过的配置一致，不建议擅自改 INT8）
- `--mnnconvert` 未传有效路径，自动 fallback 到 pymnn 的 `mnnconvert`

## 4. 产物清单

| 文件 | 大小 | 说明 |
|---|---|---|
| `llm.mnn` | 1.4 MB | LLM 图结构（INT8） |
| `llm.mnn.weight` | ~634 MB | LLM INT8 权重（external data） |
| `audio.mnn` | 3.0 MB | 音频编码器图结构（FP16） |
| `audio.mnn.weight` | ~373 MB | 音频编码器 FP16 权重 |
| `config.json` | — | 运行配置；**已改为 greedy 采样**（见下） |
| `llm_config.json` | — | 模型结构配置（`is_audio: true`, `audio_type: qwen3_asr`, `asr_language: Chinese`） |
| `tokenizer.txt` | 3.2 MB | MNN tokenizer |
| `export_args.json` | — | 导出参数存档 |
| `sha256sums.txt` | — | 上述文件 SHA256 |

合计约 1.0 GB。`llm.mnn.weight` / `audio.mnn.weight` 为必需（`--saveExternalData`），加载时需与 `.mnn` 同目录。

### config.json 采样说明
导出默认 `sampler_type: mixed, temperature: 0.8, top_k: 40`。ASR 必须用 greedy，
已改为 `"sampler_type": "greedy", "temperature": 0.0, "top_k": 1`（稀有 token 置信度仅 30–70%，非 greedy 会乱采样）。

## 5. 数值对齐验证

### 方法
- **Torch baseline**：同一台机器，用导出流水线自身的 `LlmExporter.response()`（greedy argmax）跑
  `Qwen/Qwen3-ASR-0.6B`，即与导出完全一致的音频前处理 + 模型结构。
- **MNN 待测**：从 MNN master（同 commit `9c17887`）源码编译 `llm_demo`
 （`MNN_BUILD_LLM=ON + MNN_BUILD_AUDIO=ON`，CPU），`llm_demo config.json <prompt.txt>`，
  prompt 为 `<audio>/path/to/16k.wav</audio>`，config.json 已设 greedy。
- 测试音频：两段中文 TTS（16kHz mono，长度补齐到整秒以满足编码器 `n_window*2` 对齐要求）。

### 结果（完全一致 ✅）

| 样本 | 时长 | Torch baseline | MNN（llm.mnn+audio.mnn） | 结论 |
|---|---|---|---|---|
| 1 | 6.0s | 语音输入法是个好主意，每天自动整理成文档，工作效率提高了很多。 | 语音输入法是个好主意，每天自动整理成文档，工作效率提高了很多。 | 逐字一致 |
| 2 | 5.0s | 明天早上八点开会，请大家准时参加，记得带上项目进度报告。 | 明天早上八点开会，请大家准时参加，记得带上项目进度报告。 | 逐字一致 |

**结论：数值对齐通过（完全一致）。** INT8 LLM + FP16 音频编码器的 MNN 导出与 PyTorch 原始模型转写结果逐字相同。

附：MNN 端实测耗时（x86_64 虚拟机 2 核 CPU，仅供参考，非手机数据）—
音频编码 1.16s / prefill 1.63s（55.7 tok/s）/ decode 0.83s（24.1 tok/s），6s 音频端到端 3.68s。

## 6. 已知事项 / 注意

1. PyPI 的 `mnn` 3.6.1 wheel **不含** Qwen3-ASR 运行时（无 `qwen3_asr` 符号），只能做 `mnnconvert`，
   不能跑推理验证；验证用的 `llm_demo` 必须从 master 源码编译。
2. 构建 `llm_demo` 时 `MNN_SKIPBUILD_GEOMETRY` 不能设 ON（LLM engine 依赖 Express），
   且稀疏 checkout 需包含 `source include transformers cmake schema 3rd_party tools/audio tools/cv tools/cpp express`。
3. 导出时内存：模型 2.04B 参数（bf16），峰值约 4–5 GB；本机曾因内存不足卡住，
   后内存释放（~4.5 GB 可用）后一次通过。
4. `transformers` 必须用 4.57.x（Qwen3-ASR 官方 repo pin；5.x 的 `check_model_inputs` API 不兼容，
   且 5.19 自带的 Qwen3ASR 建模与本 checkpoint 的 projector 布局不一致）。
5. 本目录文件较大（~1GB），已加入 `.gitignore`（`models/`），不进 git。
