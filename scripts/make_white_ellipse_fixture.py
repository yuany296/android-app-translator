#!/usr/bin/env python3
"""生成「白底面板 + 高椭圆气泡」夹具（2026-10-14）。

复刻用户这张反馈图的几何：
- 画面基本全白（面板背景 = 白），气泡也是白色椭圆、细黑描边 —— 区域检测/内部测量都容易
  把「整页白底」当成容器；
- 上气泡很高，原文字只占上半部分（4 行韩文），译文比原文长；
- 下气泡同页（原图里它渲染正常，作为对照）。

用法：python scripts/make_white_ellipse_fixture.py
输出：.verify/web/white-ellipse.png + .verify/web/white-ellipse.html
"""

import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_PNG = os.path.join(ROOT, ".verify", "web", "white-ellipse.png")
OUT_HTML = os.path.join(ROOT, ".verify", "web", "white-ellipse.html")
KOREAN_FONT = r"C:\Windows\Fonts\malgun.ttf"

W, H = 620, 700
TOP_BUBBLE = (110, 65, 520, 400)
BOTTOM_BUBBLE = (40, 455, 450, 690)
TOP_LINES = [
    "그…라이트노벨 같은거",
    "입다보면 나오잖아??",
    "낭만있게 모험을 떠난다거나,",
    "히로인을 만난다거나 하는…",
]
BOTTOM_LINES = [
    "그런데 그 경호원들이",
    "붙어다니니까…",
    "아무런 사건도",
    "일어나지가 않았다고!!",
]


def draw_lines(draw, lines, font, line_height, center_x, top_y, fill=(20, 20, 24)):
    for index, text in enumerate(lines):
        box = draw.textbbox((0, 0), text, font=font)
        width = box[2] - box[0]
        draw.text((center_x - width // 2, top_y + index * line_height), text, font=font, fill=fill)


def main():
    img = Image.new("RGB", (W, H), (255, 255, 255))
    draw = ImageDraw.Draw(img)
    # 面板：白底 + 极浅的灰边（与用户截图里的白面板一致）
    draw.rectangle((2, 2, W - 3, H - 3), outline=(226, 228, 232), width=3)
    # 底部一块深红（原图下半有红色画面，用来检验「白背景假容器」是否被识破）
    draw.rectangle((0, H - 40, W, H), fill=(120, 30, 40))

    draw.ellipse(TOP_BUBBLE, fill=(255, 255, 255), outline=(28, 30, 36), width=3)
    draw.ellipse(BOTTOM_BUBBLE, fill=(255, 255, 255), outline=(28, 30, 36), width=3)

    top_font = ImageFont.truetype(KOREAN_FONT, 34)
    bottom_font = ImageFont.truetype(KOREAN_FONT, 34)
    draw_lines(draw, TOP_LINES, top_font, 44, (TOP_BUBBLE[0] + TOP_BUBBLE[2]) // 2, 130)
    draw_lines(draw, BOTTOM_LINES, bottom_font, 44, (BOTTOM_BUBBLE[0] + BOTTOM_BUBBLE[2]) // 2, 500)
    img.save(OUT_PNG)

    html = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MT 白底高椭圆夹具</title>
<style>
  html, body { margin: 0; padding: 0; background: #eef1f5; }
  header { height: 56px; background: #232a33; color: #fff; line-height: 56px; text-align: center; }
  #page { background: #fff; }
  #page img { display: block; width: 100%; margin: 0; }
</style>
</head>
<body>
<header>白底高椭圆（上泡原文只占上半，译文更长）</header>
<div id="page">
  <img src="white-ellipse.png" width="__W__" height="__H__" alt="white ellipse bubbles">
</div>
</body></html>
""".replace("__W__", str(W)).replace("__H__", str(H))
    with open(OUT_HTML, "w", encoding="utf-8") as handle:
        handle.write(html)
    print("wrote", OUT_PNG)
    print("wrote", OUT_HTML)


if __name__ == "__main__":
    main()
