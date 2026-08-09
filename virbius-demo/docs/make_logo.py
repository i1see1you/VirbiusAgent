# -*- coding: utf-8 -*-
"""把 JPG logo 处理为透明背景 PNG：移除白底、剔除右下角水印、保留中央 logo 主体。"""
import numpy as np
from PIL import Image
from collections import deque

SRC = r"C:\Users\jiagu\Desktop\微信图片_20260807145255_38_27.jpg"
DST = r"d:\workspace\vbagent\virbius-demo\static\images\logo.png"

im = Image.open(SRC).convert("RGB")
rgb = np.asarray(im).astype(np.int16)
H, W, _ = rgb.shape

# 判定“接近白”的背景像素
near_white = np.all(rgb > 232, axis=2)  # (H, W) bool

# ---- 泛洪填充：从四边背景像素出发，标记与边框相连的背景区域 ----
bg = np.zeros((H, W), dtype=bool)
q = deque()
for x in range(W):
    if near_white[0, x] and not bg[0, x]:
        bg[0, x] = True; q.append((0, x))
    if near_white[H - 1, x] and not bg[H - 1, x]:
        bg[H - 1, x] = True; q.append((H - 1, x))
for y in range(H):
    if near_white[y, 0] and not bg[y, 0]:
        bg[y, 0] = True; q.append((y, 0))
    if near_white[y, W - 1] and not bg[y, W - 1]:
        bg[y, W - 1] = True; q.append((y, W - 1))

while q:
    y, x = q.popleft()
    for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
        if 0 <= ny < H and 0 <= nx < W and near_white[ny, nx] and not bg[ny, nx]:
            bg[ny, nx] = True
            q.append((ny, nx))

foreground = ~bg  # 待评估为 logo/水印的非背景像素

# ---- 连通域标记：保留最大连通块（logo），剔除小面积噪音（如水印）----
label = np.zeros((H, W), dtype=np.int32)
cur = 0
sizes = {}
# 用 BFS 逐块标记
for sy in range(H):
    for sx in range(W):
        if foreground[sy, sx] and label[sy, sx] == 0:
            cur += 1
            cnt = 0
            q = deque([(sy, sx)])
            label[sy, sx] = cur
            while q:
                y, x = q.popleft()
                cnt += 1
                for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                    if 0 <= ny < H and 0 <= nx < W and foreground[ny, nx] and label[ny, nx] == 0:
                        label[ny, nx] = cur
                        q.append((ny, nx))
            sizes[cur] = cnt

big = max(sizes, key=sizes.get)
print("components:", len(sizes), "largest px:", sizes[big])
keep = label == big

# ---- 组装 RGBA ----
alpha = np.where(keep, 255, 0).astype(np.uint8)
out = np.dstack([rgb.astype(np.uint8), alpha])
img = Image.fromarray(out, "RGBA")

# 裁剪透明边距
img = img.crop(img.getbbox())

import os
os.makedirs(os.path.dirname(DST), exist_ok=True)
img.save(DST)
print("saved", DST, img.size)