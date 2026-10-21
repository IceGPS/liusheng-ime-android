#!/usr/bin/env python3
"""
词库覆盖审计（可复跑）。

审计 1（单字覆盖）：以三份源词库（base/ext/8105.dict.yaml）合并去重后的全部
单字条目为基准集——每个（字，读音）对逐一检查该字是否出现在 pinyin_dict.bin
对应拼音键的候选列表中。多音字的每个读音各算一个对。

审计 2（双拼可达性）：源词库用到的每个音节必须
  (a) 在 PinyinSyllables 合法音节集内；
  (b) 能被 ShuangpinMapper.buildReverseMap 映射到至少一个按键对。
映射表与合法音节集直接从 Kotlin 源码正则提取，buildReverseMap 逻辑用
Python 忠实复刻（含 V85 零声母补建、V86 字面对）。

用法：
  python3 audit_dict_coverage.py [--bin PATH] [--top N]
默认 bin 为同目录 pinyin_dict.bin；assets 内文件与之相同（md5 一致）。
退出码：缺失对为 0 且无不可达音节时为 0，否则为 1。
"""
import argparse
import os
import re
import struct
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
KT_DIR = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'java',
                      'com', 'icegps', 'voiceime', 'ime', 'pinyin')

sys.path.insert(0, HERE)
from preprocess_dict import parse_dict, SYLLABLE_ALIASES  # noqa: E402  与预处理完全同口径解析


# ---------------- 源词库基准集（与 preprocess_dict.main 相同的合并去重） ----------------

def load_source_merged():
    all_entries = {}
    for fname in ['base.dict.yaml', 'ext.dict.yaml', '8105.dict.yaml']:
        d = parse_dict(os.path.join(HERE, fname))
        for key, lst in d.items():
            all_entries.setdefault(key, []).extend(lst)
    merged = {}
    for key, lst in all_entries.items():
        seen = {}
        for word, freq, pinyin in lst:
            if word not in seen or freq > seen[word][0]:
                seen[word] = (freq, pinyin)
        sorted_words = sorted(seen.items(), key=lambda x: -x[1][0])
        merged[key] = [(w, f, p) for w, (f, p) in sorted_words]
    return merged


# ---------------- bin 读取（与 PinyinDictionary.kt 加载口径一致） ----------------

def load_bin(path):
    with open(path, 'rb') as f:
        data = f.read()
    assert data[:4] == b'PYDI', 'magic 错误'
    _ver, count = struct.unpack_from('<II', data, 4)
    off = 12
    entries = {}
    for _ in range(count):
        (klen,) = struct.unpack_from('<H', data, off); off += 2
        key = data[off:off + klen].decode('utf-8'); off += klen
        (wcount,) = struct.unpack_from('<H', data, off); off += 2
        words = []
        for _ in range(wcount):
            (wlen,) = struct.unpack_from('<H', data, off); off += 2
            word = data[off:off + wlen].decode('utf-8'); off += wlen
            off += 4  # freq
            words.append(word)
        entries[key] = words
    return entries


# ---------------- 审计 1：单字（字，读音）对覆盖 ----------------

def audit_coverage(merged, bin_entries):
    pairs = set()          # (字, 读音键)
    anomalies = []         # 单字却多音节拼音等源数据异常
    for key, words in merged.items():
        for word, _freq, pinyin in words:
            if len(word) == 1:
                if ' ' in pinyin.strip():
                    anomalies.append((word, pinyin))
                pairs.add((word, key))
    missing = []
    for ch, key in sorted(pairs):
        if ch not in bin_entries.get(key, []):
            missing.append((ch, key))
    return pairs, missing, anomalies


# ---------------- 审计 2：双拼可达性 ----------------

def extract_kotlin_maps():
    """从 Kotlin 源码提取 VALID 音节集 / FINAL_TO_KEY / INITIAL_TO_KEY。"""
    with open(os.path.join(KT_DIR, 'PinyinSyllables.kt'), encoding='utf-8') as f:
        src = f.read()
    valid_block = re.search(r'private val VALID: Set<String> = setOf\((.*?)\)', src, re.S).group(1)
    valid = set(re.findall(r'"([^"]+)"', valid_block))

    with open(os.path.join(KT_DIR, 'ShuangpinMapper.kt'), encoding='utf-8') as f:
        src = f.read()
    final_block = re.search(r'private val FINAL_TO_KEY: Map<String, Char> = mapOf\((.*?)\)\s*\n', src, re.S).group(1)
    final_to_key = dict(re.findall(r'"([^"]+)" to \'(.)\'', final_block))
    init_block = re.search(r'private val INITIAL_TO_KEY: Map<String, Char> = mapOf\((.*?)\)', src, re.S).group(1)
    initial_to_key = dict(re.findall(r'"([^"]+)" to \'(.)\'', init_block))
    return valid, final_to_key, initial_to_key


def build_reverse_map_replica(valid, final_to_key, initial_to_key):
    """忠实复刻 ShuangpinMapper.buildReverseMap（含 V85 零声母补建、V86 字面对）。"""
    result = defaultdict(list)
    initials = ["b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
                "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y", "w", ""]
    finals = list(final_to_key.keys())
    for init in initials:
        for fin in finals:
            syllable = init + fin
            if syllable not in valid:
                continue
            init_key = initial_to_key.get(init)
            if init_key is None:
                if init == '':
                    continue  # "".firstOrNull() == null -> continue（零声母下方补建）
                init_key = init[0]
            fin_key = final_to_key[fin]
            pair = f"{init_key}{fin_key}"
            result[pair].append(syllable)
    extra_zero = {"ia", "ie", "iao", "ian", "iang", "in", "ing", "iong", "ong"}
    for fin in finals:
        if fin not in valid and fin not in extra_zero:
            continue
        fin_key = final_to_key[fin]
        pair = f"{fin[0]}{fin_key}"
        if fin not in result[pair]:
            result[pair].append(fin)
    for fin in ["an", "ai", "ao", "ou", "en", "ei", "er", "in"]:
        if fin not in result[fin]:
            result[fin].append(fin)
    return dict(result)


def audit_shuangpin(merged):
    valid, final_to_key, initial_to_key = extract_kotlin_maps()
    reverse = build_reverse_map_replica(valid, final_to_key, initial_to_key)
    reachable = set()
    for syls in reverse.values():
        reachable.update(syls)
    raw_used = set()
    for key, words in merged.items():
        for _w, _f, pinyin in words:
            raw_used.update(pinyin.split())
    # 审计口径与预处理一致：先经 SYLLABLE_ALIASES 拼写对齐再判合法/可达；
    # 被对齐改写的原始音节单独回报，不静默。
    aliased = {s: SYLLABLE_ALIASES[s] for s in sorted(raw_used) if s in SYLLABLE_ALIASES}
    used = {SYLLABLE_ALIASES.get(s, s) for s in raw_used}
    not_in_valid = sorted(s for s in used if s not in valid)
    unreachable = sorted(s for s in used if s in valid and s not in reachable)
    big_pairs = {p: s for p, s in reverse.items() if len(s) >= 4}
    return used, not_in_valid, unreachable, big_pairs, aliased


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--bin', default=os.path.join(HERE, 'pinyin_dict.bin'))
    ap.add_argument('--top', type=int, default=20)
    args = ap.parse_args()

    merged = load_source_merged()
    bin_entries = load_bin(args.bin)
    total_bin_words = sum(len(v) for v in bin_entries.values())
    print(f"源词库合并去重：{len(merged)} 个拼音键，"
          f"{sum(len(v) for v in merged.values())} 个条目")
    print(f"待审计 bin：{args.bin}")
    print(f"bin：{len(bin_entries)} 个拼音键，{total_bin_words} 个条目，"
          f"{os.path.getsize(args.bin)} 字节")
    print('=' * 70)

    pairs, missing, anomalies = audit_coverage(merged, bin_entries)
    print(f"【审计 1】单字（字，读音）对总数：{len(pairs)}")
    print(f"【审计 1】缺失对总数：{len(missing)}")
    if anomalies:
        print(f"【审计 1】源数据异常（单字多音节拼音）：{len(anomalies)} 例 {anomalies[:5]}")
    miss_by_key = Counter(k for _c, k in missing)
    print(f"【审计 1】有缺失的音节键数：{len(miss_by_key)}")
    print(f"【审计 1】缺失最严重的 {args.top} 个音节（键: 缺失单字数 / 该键源单字总数）：")
    key_single_total = Counter(k for _c, k in pairs)
    for key, n in miss_by_key.most_common(args.top):
        print(f"    {key}: {n} / {key_single_total[key]}")
    print(f"【审计 1】缺失字样例（前 40）：{' '.join(c for c, _k in missing[:40])}")
    if 'shi' in miss_by_key:
        shi_missing = [c for c, k in missing if k == 'shi']
        print(f"【审计 1】shi 键缺失 {len(shi_missing)} 字：{' '.join(shi_missing)}")
        print(f"【审计 1】'适' 在 shi 缺失名单中：{'适' in shi_missing}")
    print('=' * 70)

    used, not_in_valid, unreachable, big_pairs, aliased = audit_shuangpin(merged)
    print(f"【审计 2】经拼写对齐的原始音节：{aliased}")
    print(f"【审计 2】源词库用到的音节总数（对齐后）：{len(used)}")
    print(f"【审计 2】不在 PinyinSyllables 合法集内的音节：{len(not_in_valid)} {not_in_valid}")
    print(f"【审计 2】合法但双拼不可达的音节：{len(unreachable)} {unreachable}")
    print(f"【审计 2】按键对映射音节数 >=4 的对：{len(big_pairs)} {big_pairs}")
    print('=' * 70)

    sizes = sorted((len(v) for v in merged.values()), reverse=True)
    single_sizes = Counter()
    for key, words in merged.items():
        single_sizes[key] = sum(1 for w in words if len(w[0]) == 1)
    top_single = single_sizes.most_common(10)
    print(f"【参考】源数据单键最大条目数：{sizes[0]}；单字最多音节：{top_single}")
    ok = (len(missing) == 0 and not not_in_valid and not unreachable)
    print(f"结论：{'全部通过' if ok else '存在缺失/不可达，见上'}")
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
