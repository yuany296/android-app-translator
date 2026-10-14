#!/usr/bin/env python3
"""生成「椭圆白气泡 + 灰云背景 + 韩文」夹具（2026-10-14）。

复刻用户两张反馈的几何：
1. 椭圆泡收腰处译文戳出白边（ML Kit 文字超出气泡框）；
2. 纯白气泡的盖板被 inpaint 清理图弄脏（「纯色背景却用了清理图」）。

用法：python scripts/make_ellipse_bubble_fixture.py
输出：.verify/web/ellipse-bubble.png + .verify/web/ellipse-bubble.html
"""

import os
import random
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_PNG = os.path.join(ROOT, ".verify", "web", "ellipse-bubble.png")
OUT_HTML = os.path.join(ROOT, ".verify", "web", "ellipse-bubble.html")
KOREAN_FONT = r"C:\Windows\Fonts\malgun.ttf"

W, H = 600, 460
ELLIPSE = (60, 40, 540, 400)
LINES = [
    "지금 이 상황은 정말 최악이야",
    "이걸 끝내려면 죽거나",
    "돌아가거나 두 가지밖에 없어",
    "악질들한테 걸리기라도 하면",
]


def main():
    rnd = random.Random(20261014)
    img = Image.new("RGB", (W, H), (150, 156, 164))
    draw = ImageDraw.Draw(img)
    # 灰云背景：几团半透明椭圆（模拟用户在灰天空上的气泡）
    for _ in range(26):
        cx = rnd.randint(-40, W + 40)
        cy = rnd.randint(-30, H + 30)
        rx = rnd.randint(60, 190)
        ry = rnd.randint(40, 120)
        shade = rnd.randint(150, 205)
        draw.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=(shade, shade + 4, shade + 8))
    # 白气泡：椭圆 + 细描边（收腰明显，正是译文戳出去的位置）
    draw.ellipse(ELLIPSE, fill=(252, 252, 251), outline=(32, 34, 40), width=3)

    font = ImageFont.truetype(KOREAN_FONT, 34)
    line_height = 44
    total = line_height * len(LINES)
    top = (ELLIPSE[1] + ELLIPSE[3]) // 2 - total // 2
    for index, text in enumerate(LINES):
        box = draw.textbbox((0, 0), text, font=font)
        width = box[2] - box[0]
        x = (W - width) // 2
        y = top + index * line_height
        draw.text((x, y), text, font=font, fill=(24, 24, 28))
    img.save(OUT_PNG)

    html = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MT 椭圆白气泡夹具</title>
<style>
  html, body { margin: 0; padding: 0; background: #eef1f5; }
  header { height: 56px; background: #232a33; color: #fff; line-height: 56px; text-align: center; }
  #scroll-list { background: #fff; }
  #scroll-list .cut { display: block; width: 100%; margin: 0; }
</style>
</head>
<body>
<header>椭圆白气泡（气泡内文字 + 灰云背景）</header>
<div id="scroll-list">
  <img class="cut" id="cut0" src="ellipse-bubble.png" width="__W__" height="__H__" alt="ellipse bubble">
</div>
</body></html>
""".replace("__W__", str(W)).replace("__H__", str(H))
    with open(OUT_HTML, "w", encoding="utf-8") as handle:
        handle.write(html)
    print("wrote", OUT_PNG)
    print("wrote", OUT_HTML)


if __name__ == "__main__":
    main()
