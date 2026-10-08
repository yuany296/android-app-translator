import glob, os, re, statistics, sys, json

ROOT = r"C:\homework\AI_work\android-app-translator\logs"
CASES = {
    "parallel": ["bench-parallel-artifacts", "bench-parallel2-artifacts"],
    "serial":   ["bench-serial-artifacts", "bench-serial2-artifacts"],
}

total_re = re.compile(r"paddle_total_ms=(\d+) rec_ms=(\d+) rec_threads=(\d+) .*?primary=(\d+) total_quads=(\d+)")
page_re  = re.compile(r"BENCH (bench_page\d)\.png run#(\d+) ms=(\d+) blocks=(\d+)")
block_re = re.compile(r"B (bench_page\d\.png)#(\d+) \[(.*)\] conf=([\d.]+)")


def read_lines(paths):
    out = []
    for p in paths:
        for f in glob.glob(os.path.join(p, "**", "logcat-*.txt"), recursive=True):
            with open(f, "r", encoding="utf-8", errors="replace") as fh:
                out.extend(fh.read().splitlines())
    return out


data = {}
for case, dirs in CASES.items():
    paths = [os.path.join(ROOT, d) for d in dirs]
    lines = read_lines(paths)
    pages = {}
    for ln in lines:
        m = page_re.search(ln)
        if m:
            page, run, ms, blocks = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4))
            pages.setdefault(page, {"ms": [], "blocks": set(), "texts": {}})
            pages[page]["ms"].append(ms)
            pages[page]["blocks"].add(blocks)
        m = block_re.search(ln)
        if m:
            page, run, text, conf = m.group(1).replace(".png", ""), int(m.group(2)), m.group(3), m.group(4)
            pages.setdefault(page, {"ms": [], "blocks": set(), "texts": {}})
            pages[page]["texts"].setdefault(run, []).append((text, conf))
    data[case] = pages

print("=" * 78)
print("PaddleOCR 整页耗时：并行 vs 串行（模拟器 Pixel_8，4 核，x86_64）")
print("每页每组 10 次采样（2 轮 × 5 次）")
print("=" * 78)
print(f"{'页面':<16}{'串行 中位':>12}{'并行 中位':>12}{'提速':>10}{'串行 min':>11}{'并行 min':>11}")
print("-" * 78)

summary = {}
for page in ["bench_page1", "bench_page2", "bench_page3"]:
    s = data["serial"].get(page, {}).get("ms", [])
    p = data["parallel"].get(page, {}).get("ms", [])
    if not s or not p:
        print(f"{page:<16}{'-- 缺数据 --':>34}")
        continue
    ms_s, ms_p = statistics.median(s), statistics.median(p)
    speedup = ms_s / ms_p
    summary[page] = {"serial_median": ms_s, "parallel_median": ms_p, "speedup": round(speedup, 2),
                     "serial_min": min(s), "parallel_min": min(p),
                     "serial_all": sorted(s), "parallel_all": sorted(p)}
    print(f"{page:<16}{ms_s:>10.0f}ms{ms_p:>10.0f}ms{speedup:>9.2f}x{min(s):>9}ms{min(p):>9}ms")

print("-" * 78)
all_s = [v for p in ["bench_page1", "bench_page2", "bench_page3"] for v in data["serial"].get(p, {}).get("ms", [])]
all_p = [v for p in ["bench_page1", "bench_page2", "bench_page3"] for v in data["parallel"].get(p, {}).get("ms", [])]
if all_s and all_p:
    print(f"{'全部 30 次采样':<16}{statistics.median(all_s):>10.0f}ms{statistics.median(all_p):>10.0f}ms"
          f"{statistics.median(all_s)/statistics.median(all_p):>9.2f}x")
    print(f"{'全部 平均':<16}{statistics.mean(all_s):>10.0f}ms{statistics.mean(all_p):>10.0f}ms"
          f"{statistics.mean(all_s)/statistics.mean(all_p):>9.2f}x")

print()
print("=" * 78)
print("识别结果是否一致")
print("=" * 78)
for page in ["bench_page1", "bench_page2", "bench_page3"]:
    ts = data["serial"].get(page, {}).get("texts", {})
    tp = data["parallel"].get(page, {}).get("texts", {})
    if not ts or not tp:
        print(f"{page}: 缺数据")
        continue
    text_runs_s = {tuple(t for t, _ in v) for v in ts.values()}
    text_runs_p = {tuple(t for t, _ in v) for v in tp.values()}
    same_text = text_runs_s == text_runs_p
    print(f"{page}: 文字序列 串行{len(text_runs_s)}种 / 并行{len(text_runs_p)}种 -> "
          f"{'完全一致' if same_text else '**文字存在差异**'}"
          f"；组内可复现={len(text_runs_s) == 1 and len(text_runs_p) == 1}")
    if not same_text:
        a, b = sorted(text_runs_s)[0], sorted(text_runs_p)[0]
        for x in a:
            if x not in b:
                print(f"   仅串行有: [{x}]")
        for x in b:
            if x not in a:
                print(f"   仅并行有: [{x}]")
    # 置信度数值差异（图优化导致的浮点末位差异，属预期）
    key_s = sorted(text_runs_s)[0]
    key_p = sorted(text_runs_p)[0]
    conf_s = [c for _, c in sorted(ts[min(ts)], key=lambda x: x[0])]
    conf_p = [c for _, c in sorted(tp[min(tp)], key=lambda x: x[0])]
    if len(conf_s) == len(conf_p):
        deltas = [abs(float(a) - float(b)) for a, b in zip(conf_s, conf_p)]
        if deltas:
            print(f"   置信度最大差异={max(deltas):.7f} 平均={statistics.mean(deltas):.7f}（浮点末位差异）")

print()
print("=" * 78)
print("rec 分阶段耗时（paddle_total_ms / rec_ms，取每页末次）")
print("=" * 78)
for case in ["serial", "parallel"]:
    for page in ["bench_page1", "bench_page2", "bench_page3"]:
        pass
for case, dirs in CASES.items():
    paths = [os.path.join(ROOT, d) for d in dirs]
    lines = read_lines(paths)
    recs = [l for l in lines if "paddle_total_ms" in l]
    loose = re.compile(r"paddle_total_ms=(\d+).*?total_quads=(\d+)")
    if recs:
        m = loose.search(recs[-1])
        if m:
            print(f"{case:<10} 末次: total={m.group(1)}ms quads={m.group(2)}  ({recs[0].split('PaddleOcr: ')[-1][:110]})")

print("\n采样数核对：")
for case in CASES:
    for page in ["bench_page1", "bench_page2", "bench_page3"]:
        n = len(data[case].get(page, {}).get("ms", []))
        print(f"  {case:<10}{page}  n={n}")

with open(os.path.join(ROOT, "bench-summary.json"), "w", encoding="utf-8") as fh:
    json.dump(summary, fh, ensure_ascii=False, indent=2)
print("\n[saved] logs/bench-summary.json")
