#!/usr/bin/env python3
"""
联想词表（bigram）生成：从 rime-ice 词库挖掘相邻词共现。

输入：base.dict.yaml, ext.dict.yaml（短语语料）+ 8105.dict.yaml（补充单字词集）
方法：以词库全部词条为词集，对每个短语词条做前向最大匹配分词
      （匹配长度上限 = 词条长-1，避免整词自成一 token、挖不到对），
      统计相邻词对共现，权重 = 100*log10(1+词条词频)（压缩量级）。
      每词保留 top8 下一词；另合并一份手工高频虚词搭配兜底（强权重置前）。
输出：app/src/main/assets/assoc_dict.bin

二进制格式（与 AssociationEngine.kt 解析一致，大端）：
- magic(4B "ASOC") + version(4B) + entry_count(4B)
- 每条：prev_len(1B) + prev(utf8) + next_count(1B) +
         每个下一词：len(1B) + word(utf8)
"""
import math
import struct
import sys
from collections import defaultdict

BASE_DIR = "tools/pinyin-data"
OUT_PATH = "app/src/main/assets/assoc_dict.bin"

TOP_N = 8
MAX_TOKEN_LEN = 5
PAIR_MIN_SCORE = 300       # 单个词对的最低累计分（log 权重空间）
PREV_MIN_TOTAL = 600       # prev 保留的最低总分
MAX_PREVS = 90000          # prev 总量上限（控体积，按总分截断）

# 手工兜底：高频虚词/常用词搭配，分词挖掘容易漏或排序不佳的，强权重合并
FALLBACK = {
    "你": ["好", "是", "在", "们", "想", "说", "要", "的"],
    "我": ["们", "是", "想", "要", "去", "在", "说", "不"],
    "他": ["们", "是", "说", "在", "想"],
    "她": ["们", "是", "说", "在", "想"],
    "我们": ["一起", "去", "都", "要", "是"],
    "你们": ["都", "去", "是"],
    "的": ["时候", "人", "事", "是", "话"],
    "是": ["的", "不", "什么", "我", "你"],
    "不": ["是", "知道", "会", "要", "能", "用"],
    "在": ["这里", "那里", "家", "公司", "学校"],
    "想": ["去", "吃", "问", "知道"],
    "好": ["的", "了", "吗", "吃", "看"],
    "了": ["解", "一下", "吗"],
    "请": ["问", "进", "坐", "稍等"],
    "谢谢": ["你", "了"],
    "今天": ["天气", "下午", "晚上", "上午"],
    "明天": ["见", "上午", "下午", "晚上"],
    "吃": ["饭", "了", "什么", "点"],
    "去": ["公司", "学校", "北京", "哪"],
    "来": ["了", "一下", "这"],
    "看": ["看", "一下", "书", "见"],
    "说": ["什么", "话", "的"],
    "给": ["我", "你", "他", "打"],
    "到": ["了", "公司", "家"],
    "就": ["是", "去", "来"],
    "都": ["是", "去", "要"],
    "很": ["好", "多", "高兴"],
    "有": ["没有", "什么", "时间", "问题"],
    "没": ["有", "关系", "事"],
    "可以": ["吗", "去", "开始"],
    "需要": ["什么", "注意"],
    "现在": ["是", "去", "开始"],
    "这": ["个", "是", "里"],
    "那": ["个", "是", "里"],
    "什么": ["时候", "事", "问题", "东西"],
    "怎么": ["样", "办", "了"],
    "为什么": ["呢", "不"],
    "哪里": ["人", "去"],
    "问题": ["是", "不大"],
    "公司": ["的", "里", "领导"],
    "工作": ["人员", "中", "日"],
    "时间": ["段", "上"],
}
FALLBACK_BONUS = 10_000_000


def is_cjk(s: str) -> bool:
    return all("\u4e00" <= ch <= "\u9fff" for ch in s)


def parse_words(paths):
    """返回 {word: max_freq}，只收纯汉字词条"""
    words = {}
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or line.startswith("---"):
                    continue
                if line.startswith(("name:", "version:", "sort:", "use_preset")):
                    continue
                parts = line.split("\t")
                if len(parts) < 2:
                    continue
                word = parts[0].strip()
                if not word or not is_cjk(word):
                    continue
                freq = 0
                if len(parts) >= 3:
                    try:
                        freq = int(parts[2])
                    except ValueError:
                        freq = 0
                if word not in words or freq > words[word]:
                    words[word] = freq
    return words


def tokenize(entry: str, wordset: set) -> list | None:
    """前向最大匹配；匹配上限 = min(MAX_TOKEN_LEN, len-1)，强制至少切两段"""
    n = len(entry)
    cap = min(MAX_TOKEN_LEN, n - 1)
    tokens = []
    i = 0
    while i < n:
        found = None
        for length in range(min(cap, n - i), 0, -1):
            cand = entry[i:i + length]
            if cand in wordset:
                found = cand
                break
        if found is None:
            return None
        tokens.append(found)
        i += len(found)
    return tokens if len(tokens) >= 2 else None


def main():
    words = parse_words([f"{BASE_DIR}/base.dict.yaml",
                         f"{BASE_DIR}/ext.dict.yaml",
                         f"{BASE_DIR}/8105.dict.yaml"])
    wordset = set(words.keys())
    print(f"词集：{len(wordset)} 词")

    pair_score = defaultdict(lambda: defaultdict(int))
    n_entries = 0
    n_tokenized = 0
    for path in (f"{BASE_DIR}/base.dict.yaml", f"{BASE_DIR}/ext.dict.yaml"):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or line.startswith("---"):
                    continue
                if line.startswith(("name:", "version:", "sort:", "use_preset")):
                    continue
                parts = line.split("\t")
                if len(parts) < 3:
                    continue
                entry = parts[0].strip()
                if len(entry) < 2 or not is_cjk(entry):
                    continue
                try:
                    freq = int(parts[2])
                except ValueError:
                    continue
                n_entries += 1
                tokens = tokenize(entry, wordset)
                if tokens is None:
                    continue
                n_tokenized += 1
                w = int(100 * math.log10(1 + freq))
                for a, b in zip(tokens, tokens[1:]):
                    # 长 token 多为机构名等专名片段（如 中国→知识产权），
                    # 作"下一词"价值低且挤占 top8，按长度打折
                    factor = 1.0 if len(b) <= 2 else (0.7 if len(b) == 3 else 0.4)
                    pair_score[a][b] += int(w * factor)
    print(f"短语词条 {n_entries}，成功分词 {n_tokenized}")

    # 手工兜底合并（强权重，保证排在前面）
    for prev, nexts in FALLBACK.items():
        for rank, nxt in enumerate(nexts):
            pair_score[prev][nxt] += FALLBACK_BONUS - rank * 1000

    # 过滤 + topN
    result = {}
    for prev, nexts in pair_score.items():
        kept = [(nxt, s) for nxt, s in nexts.items() if s >= PAIR_MIN_SCORE]
        if not kept:
            continue
        total = sum(s for _, s in kept)
        if total < PREV_MIN_TOTAL and prev not in FALLBACK:
            continue
        kept.sort(key=lambda x: (-x[1], -words.get(x[0], 0), x[0]))
        result[prev] = [nxt for nxt, _ in kept[:TOP_N]]

    # 总量截断（按 prev 总分降序）
    if len(result) > MAX_PREVS:
        totals = {p: sum(pair_score[p][n] for n in ns) for p, ns in result.items()}
        keep = set(sorted(result, key=lambda p: -totals[p])[:MAX_PREVS])
        result = {p: ns for p, ns in result.items() if p in keep}

    total_pairs = sum(len(ns) for ns in result.values())
    print(f"prev 词 {len(result)} 个，词对 {total_pairs} 对")

    # 写二进制
    buf = bytearray()
    buf += b"ASOC"
    buf += struct.pack(">II", 1, len(result))
    for prev in sorted(result):
        pb = prev.encode("utf-8")
        assert len(pb) <= 255
        buf += struct.pack("B", len(pb)) + pb
        nexts = result[prev]
        buf += struct.pack("B", len(nexts))
        for nxt in nexts:
            nb = nxt.encode("utf-8")
            assert len(nb) <= 255
            buf += struct.pack("B", len(nb)) + nb
    with open(OUT_PATH, "wb") as f:
        f.write(buf)
    print(f"写入 {OUT_PATH}：{len(buf)} 字节（{len(buf)/1024/1024:.2f} MB）")
    # 抽查
    for probe in ("你", "我", "中国", "公司", "可以"):
        if probe in result:
            print(f"  {probe} → {' '.join(result[probe])}")


if __name__ == "__main__":
    sys.exit(main())
