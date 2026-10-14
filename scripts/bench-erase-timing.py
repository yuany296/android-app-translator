#!/usr/bin/env python3
"""「精细擦字（清理图 / inpaint）」开 vs 关的耗时基准（2026-10-11）。

用户问题：「清理图模式（精细擦字开）和关闭 inpaint 有什么区别？检测一下用时。」

这个脚本把两件事固化成一条命令，避免每次手敲 adb 时踩同一批坑：

1. 同一个页面、同一批气泡，**交替**跑「精细擦字 开 / 关」若干轮（交替是为了让
   翻译缓存 / OCR 缓存的冷热对两边的平均影响互相抵消）；
2. 每轮清一次 logcat、跑一次翻译、抓 `TranslationPerf` 全量日志，落盘到
   `--outdir/roundN_<mode>.logcat`；
3. `--analyze` 解析这些日志，按 traceId 把每个阶段的**耗时增量**算出来并对照打印。

用法::

    # 1) 先起本地验证页 + adb reverse
    python -m http.server 8899 --bind 127.0.0.1 --directory .verify/web
    adb -s emulator-5554 reverse tcp:8899 tcp:8899

    # 2) 跑基准（2 轮 × 开/关）
    python scripts/bench-erase-timing.py --url http://127.0.0.1:8899/big.html \
        --outdir logs/erase-bench --rounds 2 --expect 1

    # 3) 分析
    python scripts/bench-erase-timing.py --analyze logs/erase-bench

坑（都是实测踩过的）：
- 地址栏必须敲**完整 http:// URL**，裸域名会被补 https:// → SSL 错误；
- 提交别用 `keyevent 66`（输入法弹着时会被 IME 吃掉），点右箭头 `content-desc="打开"`；
- 切「精细擦字」开关会 `stopSession` + 清掉已贴译文，必须重新点「翻译」；
- `logcat -s` 后面每个 filterspec 必须是独立 argv，塞成一个带空格的字符串会一行都抓不到。
"""

import argparse
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Users\yuanying\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.manga.translator.app"
ACTIVITY = f"{PKG}/.MainActivity"
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

LOGCAT_TAGS = [
    "TranslationPerf:V",
    "EmbeddedBrowser:V",
    "WebtoonImageTranslator:V",
    "CleanedImageProcessor:V",
    "TieredEraseProcessor:V",
    "AndroidRuntime:E",
]

# 一张图处理完的标志（`TranslationPerf` / `EmbeddedBrowser` 都会打）。
DONE_MARKERS = ("scene_ok", "image_ok", "image_no_text", "image_fail")

STARTED_AT = time.monotonic()
GLOBAL_TIMEOUT_S = 900.0
ADB_MAX_TIMEOUT = 60


def remaining_seconds():
    return GLOBAL_TIMEOUT_S - (time.monotonic() - STARTED_AT)


def check_deadline(what=""):
    left = remaining_seconds()
    if left <= 0:
        raise SystemExit(f"! 整轮超过 {GLOBAL_TIMEOUT_S:.0f}s 硬超时（停在：{what or '未标注'}）")
    return left


def run(args, *, binary=False, timeout=180):
    proc = subprocess.run(args, capture_output=True, timeout=min(timeout, ADB_MAX_TIMEOUT), cwd=ROOT)
    if binary:
        return proc.stdout
    return proc.stdout.decode("utf-8", "replace") + proc.stderr.decode("utf-8", "replace")


def adb(*args, binary=False, timeout=180):
    return run([ADB, "-s", SERIAL, *args], binary=binary, timeout=timeout)


def shell(cmd, *, binary=False, timeout=180):
    return adb("shell", cmd, binary=binary, timeout=timeout)


def dump_ui(timeout_ms=25000):
    """整条命令整体加引号：`/sdcard/...` 不加引号会被 MSYS 路径转换搞坏。"""
    check_deadline("dump_ui")
    out = shell('uiautomator dump --compressed /sdcard/eb_ui.xml && cat /sdcard/eb_ui.xml',
                timeout=timeout_ms // 1000 + 30)
    match = re.search(r"<hierarchy.*</hierarchy>", out, re.S)
    return match.group(0) if match else ""


def nodes(xml):
    for node in re.finditer(r"<node[^>]*/?>", xml):
        tag = node.group(0)
        attrs = dict(re.findall(r'([\w:-]+)="([^"]*)"', tag))
        coords = re.findall(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attrs.get("bounds", ""))
        if not coords:
            continue
        x0, y0, x1, y1 = (int(v) for v in coords[0])
        attrs["_center"] = ((x0 + x1) // 2, (y0 + y1) // 2)
        attrs["_bounds"] = (x0, y0, x1, y1)
        yield attrs


def find_node(xml, *, text=None, contains=None, desc=None):
    for attrs in nodes(xml):
        if text is not None and attrs.get("text") != text:
            continue
        if contains is not None and contains not in attrs.get("text", ""):
            continue
        if desc is not None and desc not in attrs.get("content-desc", ""):
            continue
        return attrs["_center"][0], attrs["_center"][1], attrs["_bounds"]
    return None


def tap(x, y):
    shell(f"input tap {x} {y}")


def logcat_since_clear():
    return run([ADB, "-s", SERIAL, "logcat", "-d", "-v", "time", "-s", *LOGCAT_TAGS], timeout=120)


def wait_for(xml_probe, *, tries=30, interval=2.5, what="目标"):
    last = ""
    for _ in range(tries):
        check_deadline(f"等待{what}")
        last = dump_ui()
        if last and xml_probe(last):
            return last
        anr = find_node(last, text="Wait") or find_node(last, text="等待")
        if anr:
            tap(anr[0], anr[1])
        time.sleep(interval)
    print(f"  ! 等待{what}超时")
    return last


def open_page(url, *, settle=8.0):
    """启动 App → 浏览器 tab → 地址栏输入 url → 提交 → 等页面加载。"""
    shell(f"am force-stop {PKG}")
    time.sleep(1.5)
    shell(f"am start -n {ACTIVITY}")
    xml = wait_for(lambda x: "首页" in x or "启动服务" in x, what="首页")
    if not xml:
        xml = dump_ui()

    node = find_node(xml, text="浏览器") or find_node(xml, contains="浏览器")
    if node is None:
        print("  ! 没找到底栏「浏览器」tab")
        return False
    tap(node[0], node[1])
    time.sleep(2.5)
    xml = dump_ui()

    # 地址栏：切 tab 的那一帧可能还没渲染完（Compose 过渡 + WebView 首帧）→ 重试几次再放弃。
    field = None
    for _ in range(4):
        xml = dump_ui()
        field = (find_node(xml, contains="搜索或输入网址")
                 or find_node(xml, contains="输入网址")
                 or find_node(xml, contains="http"))
        if field is not None:
            break
        print("  · 没抓到地址栏，2s 后重试 dump")
        time.sleep(2.0)
    if field is None:
        print("  ! 没找到地址栏")
        return False
    tap(field[0], field[1])
    time.sleep(1.2)
    shell("input keycombination 113 29")   # Ctrl+A
    time.sleep(0.4)
    shell(f'input text "{url}"')
    time.sleep(1.0)
    xml = dump_ui()
    submit = find_node(xml, desc="打开")
    if submit is not None:
        tap(submit[0], submit[1])
    else:
        shell("input keyevent 111")
        time.sleep(0.5)
        shell("keyevent 66")
    print(f"  · 已提交 {url}，等 {settle}s 加载")
    time.sleep(settle)
    return True


def find_switch_for(xml, title):
    """返回 (checked, (x, y))：标题那一行右侧的 Switch。"""
    title_node = find_node(xml, text=title) or find_node(xml, contains=title)
    if title_node is None:
        return None
    _, ty, (tx0, ty0, tx1, ty1) = title_node
    best = None
    for attrs in nodes(xml):
        if attrs.get("checkable") != "true":
            continue
        cx, cy = attrs["_center"]
        bx0, by0, bx1, by1 = attrs["_bounds"]
        if cy < ty0 - 40 or cy > ty1 + 40:
            continue
        if bx0 < tx1 - 20:
            continue
        score = abs(cy - ty)
        if best is None or score < best[0]:
            best = (score, attrs.get("checked") == "true", (cx, cy))
    if best is None:
        return None
    return best[1], best[2]


def set_inpaint_mode(enabled):
    """打开齿轮菜单，把「精细擦字」设成期望状态。返回实际状态。"""
    gear = None
    for _ in range(3):
        xml = dump_ui()
        gear = find_node(xml, desc="浏览器设置")
        if gear is not None:
            break
        # 页面刚重载 / 上一轮动画没停时 dump 可能抓不到顶栏，等一下再抓。
        print("  · 没抓到齿轮，1.5s 后重试 dump")
        time.sleep(1.5)
    if gear is None:
        print("  ! 没找到齿轮「浏览器设置」")
        return None
    tap(gear[0], gear[1])
    time.sleep(1.5)
    xml = dump_ui()
    found = find_switch_for(xml, "精细擦字")
    if found is None:
        print("  ! 菜单里没找到「精细擦字」开关，dump 里的 text：")
        print("    " + " | ".join(re.findall(r'text="([^"]+)"', xml)[:30]))
        shell("input keyevent 4")
        return None
    checked, (x, y) = found
    if checked != enabled:
        tap(x, y)
        time.sleep(2.0)
        if enabled:
            # 切到「开」时菜单会自动收起；切到「关」时同样。兜底按一次返回。
            xml = dump_ui()
            if find_switch_for(xml, "精细擦字") is not None:
                shell("input keyevent 4")
                time.sleep(1.0)
    else:
        shell("input keyevent 4")
        time.sleep(1.0)
    print(f"  · 精细擦字 -> {'开' if enabled else '关'}（点前 checked={checked}）")
    return enabled


def trigger_translate(*, expect, wait_timeout):
    """清 logcat → 点「翻译」→ 轮询到 expect 张图处理完 → 点「停止」。返回 logcat 文本。"""
    shell("logcat -c")
    time.sleep(0.5)
    xml = dump_ui()
    node = find_node(xml, text="翻译")
    if node is None:
        print("  ! 没找到「翻译」按钮，dump 里的 text：")
        print("    " + " | ".join(re.findall(r'text="([^"]+)"', xml)[:30]))
        return ""
    tap(node[0], node[1])
    print(f"  · 已点「翻译」，等 {expect} 张图处理完（上限 {wait_timeout:.0f}s）")
    started = time.monotonic()
    text = ""
    done = 0
    while time.monotonic() - started < wait_timeout:
        check_deadline("等待翻译完成")
        time.sleep(3.0)
        text = logcat_since_clear()
        done = sum(text.count(m) for m in DONE_MARKERS)
        if done >= expect:
            break
    time.sleep(2.0)
    text = logcat_since_clear()
    # 停会话：按钮此时应该是「停止」。
    xml = dump_ui()
    stop = find_node(xml, text="停止")
    if stop is not None:
        tap(stop[0], stop[1])
        time.sleep(1.5)
    print(f"  · 完成标记 {done}/{expect}，日志 {len(text.splitlines())} 行")
    return text


def do_bench(args):
    outdir = args.outdir if os.path.isabs(args.outdir) else os.path.join(ROOT, args.outdir)
    os.makedirs(outdir, exist_ok=True)
    order = [m.strip() for m in args.order.split(",") if m.strip()]
    if not order:
        raise SystemExit("! --order 为空")

    if not args.skip_open:
        if not open_page(args.url, settle=args.settle):
            raise SystemExit("! 页面没打开")

    for round_index in range(1, args.rounds + 1):
        for mode in order:
            enabled = mode == "on"
            print(f"\n== round{round_index} 精细擦字={mode} ==")
            if args.cold:
                # 内存缓存外才有意义：整页清理图按 (pageHash|include_solid) 常驻 LRU，
                # 进程活着时第二轮必然命中 → 显示的 0ms 是缓存，不是真实成本。
                print("  · --cold：重启 App + 重新打开页面，清掉内存缓存")
                if not open_page(args.url, settle=args.settle):
                    raise SystemExit("! 页面没打开")
            actual = set_inpaint_mode(enabled)
            if actual is None:
                raise SystemExit("! 开关没设成功")
            text = trigger_translate(expect=args.expect, wait_timeout=args.wait_timeout)
            path = os.path.join(outdir, f"round{round_index}_{mode}.logcat")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(text)
            print(f"  -> {path}")
            if args.shot:
                shot_path = os.path.join(outdir, f"round{round_index}_{mode}.png")
                with open(shot_path, "wb") as handle:
                    handle.write(adb("exec-out", "screencap", "-p", binary=True, timeout=120))
                print(f"  -> {shot_path}")

    print("\n跑完。分析：")
    print(f"  python scripts/bench-erase-timing.py --analyze {args.outdir}")


# ---------------------------------------------------------------------------
# 分析
# ---------------------------------------------------------------------------

# `logcat -v time` 打出来的是 `D/TranslationPerf( 9725): [id] tag: 12ms | extra`，
# 而 `-v threadtime` 是 `D TranslationPerf: [id] ...` —— 两种都要吃。
MARK_RE = re.compile(r"TranslationPerf[^:]{0,16}:\s*\[\s*(\d+)\]\s+([\w_]+):\s*(\d+)ms(?:\s*\|\s*(.*))?$")

# 关注的阶段（用「该 mark 相对上一个 mark 的增量」表示耗时）
STAGES = [
    ("ocr", "request_send", "response_ok"),
    ("translate", "response_ok", "translate_response"),
    ("cleaned_image", "erase_targets", "cleaned_image"),
    ("erase_page", "erase_mode", "erase_apply"),
    ("erase_quality", "erase_patch_count", "erase_quality"),
]


def parse_traces(text):
    traces = {}
    for line in text.splitlines():
        match = MARK_RE.search(line)
        if not match:
            continue
        trace_id, tag, ms, extra = match.group(1), match.group(2), int(match.group(3)), match.group(4) or ""
        traces.setdefault(trace_id, []).append((tag, ms, extra))
    return traces


def traces_to_rows(traces):
    rows = []
    for trace_id, marks in traces.items():
        by_tag = {tag: (ms, extra) for tag, ms, extra in marks}
        if "start" not in by_tag:
            continue
        policy = ""
        counts = ""
        for tag, ms, extra in marks:
            if tag == "erase_mode":
                policy = extra
            if tag == "erase_apply":
                counts = extra
        row = {
            "trace": trace_id,
            "policy": policy,
            "counts": counts,
            "src": by_tag.get("start", (0, ""))[1],
            "total": max(ms for _, ms, _ in marks),
            "cleaned_flag": "cleaned_image" in by_tag,
        }
        for label, start_tag, end_tag in STAGES:
            if start_tag in by_tag and end_tag in by_tag:
                row[label] = by_tag[end_tag][0] - by_tag[start_tag][0]
            else:
                row[label] = None
        rows.append(row)
    return rows


def summarize(rows, label):
    picked = [r for r in rows if r["cleaned_flag"]]
    if not picked:
        print(f"  {label}: 没有可用的 trace")
        return
    print(f"  {label}: traces={len(picked)}")


def analyze(outdir):
    base = outdir if os.path.isabs(outdir) else os.path.join(ROOT, outdir)
    files = sorted(f for f in os.listdir(base) if f.endswith(".logcat"))
    if not files:
        raise SystemExit(f"! {base} 下没有 .logcat")

    per_file = {}
    print("=" * 100)
    print(f"{'file':<26}{'trace':<10}{'mode':<8}{'page':<11}{'OCR':>7}{'translate':>10}"
          f"{'cleaned':>9}{'erase':>8}{'total':>8}")
    print("-" * 100)
    for name in files:
        with open(os.path.join(base, name), encoding="utf-8") as handle:
            text = handle.read()
        rows = traces_to_rows(parse_traces(text))
        per_file[name] = rows
        for row in rows:
            def fmt(key):
                value = row.get(key)
                return "-" if value is None else str(value)
            print(f"{name:<26}{row['trace']:<10}"
                  f"{('ON' if 'true' in row['policy'] else 'OFF' if 'false' in row['policy'] else '?'):<8}"
                  f"{str(row['src'])[:10]:<11}{fmt('ocr'):>7}{fmt('translate'):>10}"
                  f"{fmt('cleaned_image'):>9}{fmt('erase_page'):>8}{row['total']:>8}")
        for row in rows:
            print(f"      erase_apply: {row['counts']}")

    print("=" * 100)
    print("summary by mode (median / mean, ms)")
    buckets = {"ON": [], "OFF": []}
    for rows in per_file.values():
        for row in rows:
            if "true" in row["policy"]:
                buckets["ON"].append(row)
            elif "false" in row["policy"]:
                buckets["OFF"].append(row)

    def stat(values):
        values = sorted(v for v in values if v is not None)
        if not values:
            return "-"
        mid = values[len(values) // 2] if len(values) % 2 else (values[len(values) // 2 - 1] + values[len(values) // 2]) / 2
        return f"{mid:.0f} / {sum(values) / len(values):.0f}"

    for key in ("ON", "OFF"):
        rows = buckets[key]
        if not rows:
            continue
        print(f"  {key:<4} n={len(rows):<3} "
              f"OCR={stat([r['ocr'] for r in rows]):<14}"
              f"translate={stat([r['translate'] for r in rows]):<14}"
              f"cleaned={stat([r['cleaned_image'] for r in rows]):<14}"
              f"erase={stat([r['erase_page'] for r in rows]):<14}"
              f"total={stat([r['total'] for r in rows])}")
    print("=" * 100)


def main():
    global GLOBAL_TIMEOUT_S
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8899/big.html")
    parser.add_argument("--outdir", default="logs/erase-bench")
    parser.add_argument("--rounds", type=int, default=2)
    parser.add_argument("--order", default="on,off")
    parser.add_argument("--expect", type=int, default=1, help="每轮等几张图处理完")
    parser.add_argument("--settle", type=float, default=8.0)
    parser.add_argument("--wait-timeout", type=float, default=120.0)
    parser.add_argument("--skip-open", action="store_true", help="页面已打开，只跑翻译")
    parser.add_argument("--cold", action="store_true",
                        help="每轮开始前重启 App + 重开页面，清掉内存缓存（整页清理图/贴片缓存）")
    parser.add_argument("--shot", action="store_true", help="每轮结束截一张图（人工比对观感）")
    parser.add_argument("--analyze", help="只分析该目录下的 .logcat")
    parser.add_argument("--timeout", type=float, default=GLOBAL_TIMEOUT_S)
    args = parser.parse_args()
    GLOBAL_TIMEOUT_S = args.timeout

    if args.analyze:
        analyze(args.analyze)
        return
    do_bench(args)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
