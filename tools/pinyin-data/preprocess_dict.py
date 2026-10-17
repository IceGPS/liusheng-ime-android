#!/usr/bin/env python3
"""
RIME 词库预处理：将 rime-ice 的 dict.yaml 转换为手机端紧凑二进制格式。

输入：base.dict.yaml, ext.dict.yaml（RIME 格式：词\t拼音\t词频）
输出：pinyin_dict.bin（二进制格式）

二进制格式：
- header: magic(4B) + version(4B) + entry_count(4B)
- 每个条目：pinyin_key_len(2B) + pinyin_key(utf8) + word_count(2B) +
             每个词：word_len(2B) + word(utf8) + freq(4B)
- 按词频过滤，只保留高频词以控制体积
"""
import struct
import sys

def parse_dict(path):
    """解析 RIME dict.yaml，返回 {pinyin_key: [(word, freq)]}"""
    entries = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#') or line.startswith('---'):
                continue
            if line.startswith('name:') or line.startswith('version:') or \
               line.startswith('sort:') or line.startswith('use_preset'):
                continue
            parts = line.split('\t')
            if len(parts) < 3:
                continue
            word, pinyin, freq_str = parts[0], parts[1], parts[2]
            try:
                freq = int(freq_str)
            except ValueError:
                continue
            # pinyin key：音节用空格分隔，转为无空格连接（便于前缀匹配）
            # 保留原始分词用于显示
            key = pinyin.replace(' ', '')
            if key not in entries:
                entries[key] = []
            entries[key].append((word, freq, pinyin))
    return entries

def main():
    import os
    data_dir = os.path.dirname(os.path.abspath(__file__))
    all_entries = {}

    for fname in ['base.dict.yaml', 'ext.dict.yaml', '8105.dict.yaml']:
        path = os.path.join(data_dir, fname)
        if not os.path.exists(path):
            print(f"跳过不存在的文件: {fname}")
            continue
        print(f"解析 {fname}...")
        d = parse_dict(path)
        for key, lst in d.items():
            if key not in all_entries:
                all_entries[key] = []
            all_entries[key].extend(lst)
        print(f"  累计 {len(all_entries)} 个拼音键")

    # 合并去重：同一拼音键+词，保留最大词频
    print("去重...")
    merged = {}
    for key, lst in all_entries.items():
        seen = {}
        for word, freq, pinyin in lst:
            if word not in seen or freq > seen[word][0]:
                seen[word] = (freq, pinyin)
        # 按词频降序
        sorted_words = sorted(seen.items(), key=lambda x: -x[1][0])
        merged[key] = [(w, f, p) for w, (f, p) in sorted_words]

    # 过滤：每个拼音键最多保留 20 个候选（控制体积）
    # 总词条数控制在合理范围
    MAX_PER_KEY = 20
    for key in merged:
        merged[key] = merged[key][:MAX_PER_KEY]

    # 写二进制
    out_path = os.path.join(data_dir, 'pinyin_dict.bin')
    total_words = 0
    with open(out_path, 'wb') as out:
        out.write(b'PYDI')  # magic
        out.write(struct.pack('<I', 1))  # version
        out.write(struct.pack('<I', len(merged)))  # entry count
        for key in sorted(merged.keys()):
            words = merged[key]
            key_b = key.encode('utf-8')
            out.write(struct.pack('<H', len(key_b)))
            out.write(key_b)
            out.write(struct.pack('<H', len(words)))
            for word, freq, pinyin in words:
                w_b = word.encode('utf-8')
                out.write(struct.pack('<H', len(w_b)))
                out.write(w_b)
                out.write(struct.pack('<I', freq))
                total_words += 1

    import os as _os
    size_mb = _os.path.getsize(out_path) / 1024 / 1024
    print(f"完成：{len(merged)} 个拼音键，{total_words} 个词条，{size_mb:.1f} MB")
    print(f"输出：{out_path}")

if __name__ == '__main__':
    main()
