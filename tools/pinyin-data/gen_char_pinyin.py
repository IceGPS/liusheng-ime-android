#!/usr/bin/env python3
"""生成字→拼音小资产 app/src/main/assets/char_pinyin.tsv（语音纠错同音判定用）。

数据源：同目录 8105.dict.yaml（Rime 雾凇《通用规范汉字表》字表，格式为
"字\\t无调拼音\\t权重"，多音字多行）。源 yaml 按仓库惯例不进 git（本地文件），
本脚本与产物进 git；重新生成需先备好该 yaml（见 preprocess_dict.py 的来源说明）。

产物格式：每行 "字\\t读音1,读音2,..."（读音去重排序），按字码点排序。
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE / "8105.dict.yaml"
OUT = HERE.parent.parent / "app" / "src" / "main" / "assets" / "char_pinyin.tsv"


def main() -> int:
    if not SRC.exists():
        print(f"missing source: {SRC}", file=sys.stderr)
        return 1
    readings: dict[str, set[str]] = {}
    for line in SRC.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        ch, py = parts[0].strip(), parts[1].strip()
        if len(ch) == 1 and re.fullmatch(r"[a-z]+", py):
            readings.setdefault(ch, set()).add(py)
    lines = [
        f"{ch}\t{','.join(sorted(readings[ch]))}"
        for ch in sorted(readings)
    ]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    multi = sum(1 for v in readings.values() if len(v) > 1)
    print(f"chars={len(lines)} polyphonic={multi} -> {OUT} ({OUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
