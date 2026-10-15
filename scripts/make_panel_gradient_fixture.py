#!/usr/bin/env python3
"""生成「渐变面板 + 两行短韩文」夹具（2026-10-15）。

复刻用户 Kakao 真机截图里的形态（Todolist §49「锯齿状的背景」）：
- 深红画面上一块**浅色渐变面板**（不是纯色：真机现场就是浅底渐变）；
- 面板上只有**两行较短的韩文**、水平居中 —— 面板远比文字宽（容器量测会在
  文字行上被字形卡住、在空白行一直走到面板边，逐行弦宽一宽一窄）；
- 周围是深红插画（盖板裁歪/裁出缺口时肉眼可见）。

用途：验证 `CoverOutlinePolygon` 的孤立窄行抑制（修复前后各跑一次对比）。

用法：python scripts/make_panel_gradient_fixture.py
输出：.verify/web/panel-gradient.png + .verify/web/panel-gradient.html
"""

import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_PNG = os.path.join(ROOT, ".verify", "web", "panel-gradient.png")
OUT_HTML = os.path.join(ROOT, ".verify", "web", "panel-gradient.html")
KOREAN_FONT = r"C:\Windows\Fonts\malgun.ttf"

W, H = 900, 1300
PANEL = (70, 270, 830, 660)          # 760 × 390 的浅色面板（宽 ≈ 字形宽的 1.85 倍）
LINES = ["토크쇼에서", "그 소리였어요"]
LINE_HEIGHT = 120
FIRST_LINE_TOP = 320


def main():
    img = Image.new("RGB", (W, H), (122, 31, 31))
    draw = ImageDraw.Draw(img)
    # 深红背景：横向渐变 + 几道竖向条纹（模拟插画，让「盖板裁出去」一眼可见）
    for x in range(W):
        t = x / W
        draw.line((x, 0, x, H), fill=(int(96 + 60 * t), int(22 + 18 * t), int(24 + 16 * t)))
    for x in range(60, W, 140):
        draw.rectangle((x, 0, x + 14, H), fill=(70, 16, 20))
    # 面板：浅色**纵向渐变**（顶部更亮，跨度控制在「纯色升级」容忍范围内：
    # 与底色距离 ≤ 24 —— 用户真机那块浅底渐变面板也是这么被升成 solid 的）
    for y in range(PANEL[1], PANEL[3]):
        t = (y - PANEL[1]) / max(1, PANEL[3] - PANEL[1])
        color = (int(236 - 30 * t), int(234 - 30 * t), int(236 - 26 * t))
        draw.line((PANEL[0], y, PANEL[2], y), fill=color)
    draw.rectangle(PANEL, outline=(120, 112, 120), width=3)

    font = ImageFont.truetype(KOREAN_FONT, 72)
    center_x = (PANEL[0] + PANEL[2]) // 2
    for index, text in enumerate(LINES):
        box = draw.textbbox((0, 0), text, font=font)
        width = box[2] - box[0]
        draw.text((center_x - width // 2, FIRST_LINE_TOP + index * LINE_HEIGHT), text,
                  font=font, fill=(28, 26, 32))
    img.save(OUT_PNG)

    html = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MT 渐变面板夹具</title>
<style>
  html, body { margin: 0; padding: 0; background: #16181c; }
  #page img { display: block; width: 100%; margin: 0; }
</style>
</head>
<body>
<div id="page">
  <img src="panel-gradient.png" width="__W__" height="__H__" alt="gradient panel bubble">
</div>
</body></html>
""".replace("__W__", str(W)).replace("__H__", str(H))
    with open(OUT_HTML, "w", encoding="utf-8") as handle:
        handle.write(html)
    print("wrote", OUT_PNG)
    print("wrote", OUT_HTML)


if __name__ == "__main__":
    main()
