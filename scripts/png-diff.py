#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""两张 PNG 的像素级差异定位（纯标准库，不需要 Pillow）。

用途
----
设计稿被误改后，拿「当前导出」跟「归档的上一次好版本」比，判断：
  - 到底哪几张被动过（md5 先筛一轮，几秒钟）
  - 动的是哪个区域、差多少（本脚本，给出差异行/包围盒/幅度）

为什么要用它
------------
Ardot 导出的是 colortype=3（调色板 PNG）。**调色板索引不同不代表像素不同**——
图像内容一变，编码器的调色板会重排，直接比字节会得到 100% 差异的假阳性。
本脚本把索引按各自的 PLTE 还原成真彩色再比，所以不会误报。

典型用法
--------
    # 1) 先按 md5 粗筛（快）
    python scripts/png-diff.py --md5 old.png new.png

    # 2) 对可疑的那张做像素比对
    python scripts/png-diff.py old.png new.png
    python scripts/png-diff.py old.png new.png --bbox          # 只出包围盒
    python scripts/png-diff.py old.png new.png --col 170 --scale 2   # 扫某一列的颜色变化

解读
----
- 包围盒集中在某个区域 + 该区域出现白↔蓝这类「边缘色对边缘色」的差异
  → 是整体 1~2px 的偏移（渲染/取整差异），**不是丢了元素**。设计上可忽略。
- 某个元素整块消失 → 会出现「元素色 vs 背景色」的大面积差异。

注意
----
脚本按 8bit、非隔行、colortype 0/2/3/4/6 解析；Ardot 当前的导出属于 colortype=3。
"""
from __future__ import annotations

import argparse
import hashlib
import struct
import sys
import zlib


def load_png(path: str):
    """返回 (width, height, 索引或通道字节, 调色板或 None)。"""
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"不是 PNG: {path}")
    i = 8
    idat = b""
    plte = b""
    w = h = bd = ct = inter = None
    while i < len(data):
        ln = struct.unpack(">I", data[i : i + 4])[0]
        typ = data[i + 4 : i + 8]
        chunk = data[i + 8 : i + 8 + ln]
        if typ == b"IHDR":
            w, h, bd, ct, _comp, _filt, inter = struct.unpack(">IIBBBBB", chunk)
            if bd != 8:
                raise ValueError(f"只支持 8bit，实际 {bd}bit")
            if inter != 0:
                raise ValueError("只支持非隔行 PNG")
        elif typ == b"PLTE":
            plte = chunk
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
        i += 12 + ln

    raw = zlib.decompress(idat)
    bpp = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    stride = w * bpp
    out = bytearray(h * stride)
    prev = bytearray(stride)
    pos = 0
    for y in range(h):
        f = raw[pos]
        pos += 1
        line = bytearray(raw[pos : pos + stride])
        pos += stride
        if f == 1:  # Sub
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x - bpp]) & 255
        elif f == 2:  # Up
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:  # Average
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:  # Paeth
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x - bpp] if x >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out[y * stride : (y + 1) * stride] = line
        prev = line

    pal = None
    if ct == 3:
        pal = [bytes(plte[k * 3 : k * 3 + 3]) for k in range(len(plte) // 3)]
    return w, h, bpp, out, pal


def rgb_row(buf, pal, y, w, bpp):
    row = buf[y * w * bpp : (y + 1) * w * bpp]
    if pal is None:
        return [bytes(row[x * bpp : x * bpp + 3]) for x in range(w)]
    return [pal[row[x]] for x in range(w)]


def md5_of(path: str) -> str:
    return hashlib.md5(open(path, "rb").read()).hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--scale", type=float, default=1.0,
                    help="导出倍率（Ardot 导出常用 2），用于把像素换算回设计稿 dp")
    ap.add_argument("--bbox", action="store_true", help="只输出包围盒与统计")
    ap.add_argument("--col", type=float, default=None,
                    help="扫描该列（设计稿 dp），列出颜色变化点")
    ap.add_argument("--threshold", type=int, default=8,
                    help="单通道差超过该值才算「可见差异」，默认 8")
    ap.add_argument("--md5", action="store_true", help="只比 md5（粗筛用）")
    args = ap.parse_args()

    if args.md5:
        ha, hb = md5_of(args.a), md5_of(args.b)
        print("A", ha)
        print("B", hb)
        print("IDENTICAL" if ha == hb else "DIFFERS")
        return 0

    wa, ha_, bppa, A, PA = load_png(args.a)
    wb, hb_, bppb, B, PB = load_png(args.b)
    if (wa, ha_, bppa) != (wb, hb_, bppb):
        print(f"尺寸/格式不同: A={wa}x{ha_} bpp{bppa}  B={wb}x{hb_} bpp{bppb}")
        return 2

    s = args.scale or 1.0

    if args.col is not None:
        x = int(round(args.col * s))
        for name, buf, pal in (("A " + args.a, A, PA), ("B " + args.b, B, PB)):
            print(f"--- {name}  x={args.col}dp ---")
            prev = None
            for y in range(ha_):
                c = rgb_row(buf, pal, y, wa, bppa)[x].hex()
                if c != prev:
                    print(f"   y={y / s:7.1f}dp  #{c}")
                    prev = c
        return 0

    x0 = y0 = 10 ** 9
    x1 = y1 = -1
    visible = 0
    maxdelta = 0
    bands: dict[int, int] = {}
    for y in range(ha_):
        ra = rgb_row(A, PA, y, wa, bppa)
        rb = rgb_row(B, PB, y, wa, bppa)
        if ra == rb:
            continue
        for x in range(wa):
            ca, cb = ra[x], rb[x]
            if ca == cb:
                continue
            d = max(abs(ca[k] - cb[k]) for k in range(3))
            if d <= args.threshold:
                continue
            visible += 1
            maxdelta = max(maxdelta, d)
            x0, x1 = min(x0, x), max(x1, x)
            y0, y1 = min(y0, y), max(y1, y)
            bands[int(y / s) // 25] = bands.get(int(y / s) // 25, 0) + 1

    total = wa * ha_
    print(f"图片 {wa}x{ha_}（scale={s}，即设计稿 {wa / s:.0f}x{ha_ / s:.0f} dp）")
    if visible == 0:
        print(f"像素完全一致（阈值 {args.threshold}）")
        return 0
    print(f"可见差异像素: {visible} / {total}  ({100 * visible / total:.2f}%)  最大单通道差 {maxdelta}")
    print(f"包围盒 dp: x {x0 / s:.0f} ~ {x1 / s:.0f}   y {y0 / s:.0f} ~ {y1 / s:.0f}")
    print("按 25dp 纵向分带的差异像素数：")
    for k in sorted(bands):
        print(f"   y {k * 25:4d} ~ {k * 25 + 25:4d} dp : {bands[k]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
