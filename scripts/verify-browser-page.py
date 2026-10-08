#!/usr/bin/env python3
"""内嵌浏览器验证驱动（2026-09-30）。

把「装 APK → 打开内嵌浏览器 → 输入地址 → 等渲染 → 截图 / 抓诊断日志 / dump DOM」
这一套固化成一条命令，避免每次手敲 adb 时踩同一批坑：

- `adb shell` 里的 `/sdcard/...` 会被 MSYS Git Bash 转成本机路径 → 整条命令用双引号包住；
- `uiautomator dump` 拿不到 WebView 内部 DOM → 页面内 `mtdump.js` 走 console.log → logcat 的 `MTDUMP`；
- 地址栏必须敲**完整 http:// URL**：裸域名会被 BrowserUrlResolver 补 https:// → SSL 错误；
- 点击坐标每次现 dump 现取，不要复用上一轮的（Compose 会变）。

用法::

    python scripts/verify-browser-page.py --url http://127.0.0.1:8899/v-tilt.html \
        --out logs/tilt.png --dump logs/tilt.xml --logcat logs/tilt.logcat \
        --settle 25

`--install` 会先装 `app/build/outputs/apk/debug/app-debug.apk`。
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

# 诊断 tag：翻译链路的性能/几何日志都挂在 WebtoonImageTranslator 的 TranslationPerf 上。
# `EmbeddedBrowser` 是界面层的会话日志（scene_ok / seam_dispatch / seam_suppress）——
# 漏掉它的后果是「接缝到底有没有下发成功」在日志里完全看不到（2026-09-30 踩过）。
LOGCAT_TAGS = [
    "TranslationPerf:V",
    "MTDUMP:V",
    "WebtoonImageTranslator:V",
    "BrowserScene:V",
    "EmbeddedBrowser:V",
    "chromium:I",
    "AndroidRuntime:E",
]


class DeadlineExceeded(RuntimeError):
    """整轮验证超过硬超时。"""

    pass


# ---------------------------------------------------------------------------
# 硬超时（2026-09-30 加）
#
# 为什么必须加：这个脚本会「打开网页 → 点翻译 → 等 N 秒」，中间全靠轮询 UI。
# 一旦界面停在一个它不认识的弹窗上（例如系统 ANR），就会**一直转下去**、
# 并且一直占着模拟器 —— 实测把设备卡住过一次（用户反馈「刚刚好像卡住了」）。
#
# 现在有三重保护：
#   1. 全局 deadline：到点直接抛 DeadlineExceeded 并以退出码 3 结束；
#   2. 每次 dump_ui 前检查剩余时间，卡在等待循环里也会被叫停；
#   3. 单个 adb 子进程超时封顶（ADB_MAX_TIMEOUT）。
# ---------------------------------------------------------------------------
STARTED_AT = time.monotonic()
GLOBAL_TIMEOUT_S = 300.0
ADB_MAX_TIMEOUT = 60


def remaining_seconds():
    return GLOBAL_TIMEOUT_S - (time.monotonic() - STARTED_AT)


def check_deadline(what=""):
    left = remaining_seconds()
    if left <= 0:
        raise DeadlineExceeded(
            f"整轮验证超过 {GLOBAL_TIMEOUT_S:.0f}s 硬超时（停在：{what or '未标注'}）"
        )
    return left


def run(args, *, binary=False, timeout=180):
    proc = subprocess.run(
        args,
        capture_output=True,
        timeout=min(timeout, ADB_MAX_TIMEOUT),
        cwd=ROOT,
    )
    if binary:
        return proc.stdout
    return proc.stdout.decode("utf-8", "replace") + proc.stderr.decode("utf-8", "replace")


def adb(*args, binary=False, timeout=180):
    return run([ADB, "-s", SERIAL, *args], binary=binary, timeout=timeout)


def shell(cmd, *, binary=False, timeout=180):
    return adb("shell", cmd, binary=binary, timeout=timeout)


def dump_ui(timeout_ms=20000):
    """dump 当前界面 XML。整条命令必须整体加引号，否则 /sdcard 会被路径转换搞坏。"""
    check_deadline("dump_ui")
    out = shell('uiautomator dump --compressed /sdcard/wb_ui.xml && cat /sdcard/wb_ui.xml',
                timeout=timeout_ms // 1000 + 30)
    match = re.search(r"<hierarchy.*</hierarchy>", out, re.S)
    return match.group(0) if match else ""


def find_node(xml, *, text=None, contains=None, desc=None):
    """在 dump 里找节点，返回 (centerX, centerY, bounds) 或 None。"""
    for node in re.finditer(r"<node[^>]*/?>", xml):
        tag = node.group(0)
        attrs = dict(re.findall(r'([\w:-]+)="([^"]*)"', tag))
        if text is not None and attrs.get("text") != text:
            continue
        if contains is not None and contains not in attrs.get("text", ""):
            continue
        if desc is not None and desc not in attrs.get("content-desc", ""):
            continue
        bounds = attrs.get("bounds", "")
        coords = re.findall(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not coords:
            continue
        x0, y0, x1, y1 = (int(v) for v in coords[0])
        return (x0 + x1) // 2, (y0 + y1) // 2, (x0, y0, x1, y1)
    return None


def tap(x, y):
    shell(f"input tap {x} {y}")


def wait_for(xml_probe, *, tries=40, interval=3.0, what="目标"):
    """轮询 dump，直到 probe(xml) 为真。返回最后一次 xml。

    ANR 弹窗最多连点 3 次：冷启动时点一下能过去，但界面若**一直**弹 ANR，
    继续点就是死循环 —— 那说明设备本身不健康，应该报错退出而不是耗着。
    """
    last = ""
    anr_dismissed = 0
    for _ in range(tries):
        check_deadline(f"等待{what}")
        last = dump_ui()
        if last and xml_probe(last):
            return last
        anr = find_node(last, text="Wait") or find_node(last, text="等待")
        if anr:
            anr_dismissed += 1
            if anr_dismissed > 3:
                print(f"  ! ANR 弹窗连续出现 {anr_dismissed} 次，设备不健康，放弃等待{what}")
                return last
            print(f"  · 检测到 ANR 弹窗，点 Wait 继续等（第 {anr_dismissed} 次）")
            tap(anr[0], anr[1])
        time.sleep(interval)
    print(f"  ! 等待{what}超时")
    return last


def open_url(url, *, settle):
    shell(f"am force-stop {PKG}")
    time.sleep(1.5)
    shell(f"am start -n {ACTIVITY}")
    xml = wait_for(lambda x: "首页" in x or "启动服务" in x, tries=30, what="首页")
    if not xml:
        xml = dump_ui()

    # 底栏「浏览器」tab
    node = find_node(xml, text="浏览器") or find_node(xml, contains="浏览器")
    if node is None:
        print("! 没找到底栏「浏览器」tab，dump 里前若干 text：")
        print("  " + " | ".join(re.findall(r'text="([^"]+)"', xml)[:40]))
        return False
    tap(node[0], node[1])
    time.sleep(2.5)
    xml = dump_ui()

    # 地址栏
    field = (find_node(xml, contains="搜索或输入网址")
             or find_node(xml, contains="输入网址")
             or find_node(xml, contains="http"))
    if field is None:
        print("! 没找到地址栏，dump 里前若干 text：")
        print("  " + " | ".join(re.findall(r'text="([^"]+)"', xml)[:40]))
        return False
    tap(field[0], field[1])
    time.sleep(1.2)
    shell("input keycombination 113 29")   # Ctrl+A 全选
    time.sleep(0.4)
    shell(f'input text "{url}"')
    time.sleep(1.0)
    xml = dump_ui()
    if url.split("//")[-1] not in xml:
        print("! 地址栏里没看到目标 URL")
        print("  当前地址栏附近文本: " + " | ".join(
            t for t in re.findall(r'text="([^"]*)"', xml) if "127" in t or "http" in t))

    # ⚠️ 不要用 `keyevent 66` 提交：输入法弹着时回车会被 IME 吃掉（表现为「地址栏有 URL 但停在起始页」）。
    # 直接点地址栏右侧的提交箭头（content-desc="打开"）。
    submit = find_node(xml, desc="打开")
    if submit is not None:
        tap(submit[0], submit[1])
        print(f"  · 点「打开」提交")
    else:
        shell("input keyevent 111")   # ESC 收起输入法
        time.sleep(0.5)
        shell("keyevent 66")
        print("  · 没找到「打开」按钮，退回回车提交")
    print(f"  · 已提交 {url}，等 {settle}s 让页面加载 + 翻译完成")
    time.sleep(6.0)

    # 底栏还有一个「翻译」开关：不开的话页面只显示原文、一条 TranslationPerf 日志都不会有
    # （表现为「截图全韩文、日志 0 行」）。开了之后才走 扫描图片→OCR→翻译→覆写。
    xml = dump_ui()
    if "点「翻译」" in xml or find_node(xml, text="翻译") is not None:
        node = find_node(xml, text="翻译")
        if node is not None:
            toggle = find_node(xml, text="原文")
            # 「原文 / 翻译」是两个并列按钮；点「翻译」那一侧即开启整页自动翻译。
            tap(node[0], node[1])
            print("  · 点「翻译」开启整页自动翻译" + ("（旁边有「原文」按钮）" if toggle else ""))
        else:
            print("  ! 只看到提示文案，没找到「翻译」按钮")
    time.sleep(settle)
    return True


def main():
    # ⚠️ `global` 必须写在函数体最前面（Python 3.13+ 对「先读后 global」直接报 SyntaxError，
    # 本机 Python 3.14 实测：`SyntaxError: name 'GLOBAL_TIMEOUT_S' is used prior to global
    # declaration` —— 脚本因此完全跑不起来）。
    global GLOBAL_TIMEOUT_S
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--out", required=True, help="截图输出路径")
    parser.add_argument("--dump", help="uiautomator dump 输出路径")
    parser.add_argument("--logcat", help="logcat 输出路径")
    parser.add_argument("--settle", type=float, default=25.0)
    parser.add_argument("--install", action="store_true", help="先安装 debug APK")
    parser.add_argument("--timeout", type=float, default=GLOBAL_TIMEOUT_S,
                        help="整轮硬超时秒数（默认 %(default)s），到点强制退出，避免占着设备")
    args = parser.parse_args()
    GLOBAL_TIMEOUT_S = args.timeout

    if args.install:
        apk = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
        print(f"安装 {apk}")
        print(adb("install", "-r", "-d", apk, timeout=300))
    else:
        # 只清日志，不重装（重装会清掉 /sdcard 素材与已登录状态）。
        pass

    shell("logcat -c")
    if not open_url(args.url, settle=args.settle):
        sys.exit(2)

    if args.logcat:
        # `logcat -d` 才不会挂住；`-s` 后面每个 filterspec 必须是**独立 argv**（塞成一个带空格的
        # 字符串会被当成单个非法 filterspec，结果一行日志都抓不到）。
        text = run([ADB, "-s", SERIAL, "logcat", "-d", "-v", "time", "-s", *LOGCAT_TAGS],
                   timeout=120)
        with open(args.logcat, "w", encoding="utf-8") as handle:
            handle.write(text)
        print(f"  日志 -> {args.logcat} ({len(text.splitlines())} 行)")

    shot = adb("exec-out", "screencap", "-p", binary=True, timeout=120)
    with open(args.out, "wb") as handle:
        handle.write(shot)
    print(f"  截图 -> {args.out} ({len(shot)} bytes)")

    if args.dump:
        xml = dump_ui()
        with open(args.dump, "w", encoding="utf-8") as handle:
            handle.write(xml)
        print(f"  dump -> {args.dump} ({len(xml)} bytes)")


if __name__ == "__main__":
    try:
        main()
    except DeadlineExceeded as err:
        print(f"! {err}")
        print("  （硬超时保护：不继续占着设备。需要更久可加 --timeout 秒数）")
        sys.exit(3)
