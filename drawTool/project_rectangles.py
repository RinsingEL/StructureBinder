from PIL import Image, ImageDraw, ImageFont
import json
import os

# ── 回退用的内置矩形数据（若 rectangles.json 读取失败时使用） ──
FALLBACK_RECTANGLES = [
    {"rid": "R01", "zone_id": 1, "structure_id": "royal_palace",        "name": "皇家宫殿",     "cx": 255, "cy": 155, "w": 18, "h": 16},
]

# ── 颜色定义 ──
ZONE_FILL = {1: (255, 215, 0, 90), 2: (0, 191, 255, 90), 3: (34, 139, 34, 90)}
ZONE_BORDER = {1: (255, 215, 0), 2: (0, 191, 255), 3: (34, 139, 34)}

def load_rectangles():
    json_path = "rectangles.json"
    if os.path.exists(json_path):
        try:
            with open(json_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            rects = data.get("rectangles", [])
            if rects:
                print(f"成功从 {json_path} 读取 {len(rects)} 个矩形")
                return rects
            else:
                print(f"{json_path} 中未找到 'rectangles' 数组，使用内置数据")
        except Exception as e:
            print(f"读取 {json_path} 失败：{e}，使用内置数据")
    else:
        print(f"未找到 {json_path}，使用内置数据")
    
    return FALLBACK_RECTANGLES

def main():
    base_image_path = "grid.png"
    if not os.path.exists(base_image_path):
        print(f"错误：未找到底图 {base_image_path}")
        return

    rectangles = load_rectangles()

    img = Image.open(base_image_path).convert("RGBA")
    draw = ImageDraw.Draw(img, "RGBA")

    try:
        font = ImageFont.truetype("arial.ttf", 14)          # 稍增大字体以提升清晰度
    except IOError:
        font = ImageFont.load_default()

    for r in rectangles:
        cx = r["cx"]
        cy = r["cy"]
        w = r["w"]
        h = r["h"]
        zone = r["zone_id"]

        left = cx - w // 2
        top = cy - h // 2
        right = left + w
        bottom = top + h

        fill = ZONE_FILL.get(zone, (200, 200, 200, 70))
        border = ZONE_BORDER.get(zone, (255, 255, 255))

        draw.rectangle((left, top, right, bottom), fill=fill, outline=border, width=3)

        name = r.get("name", r["structure_id"])
        label = f"{r['rid']} {name}"

        bbox = draw.textbbox((0, 0), label, font=font)
        tw = bbox[2] - bbox[0]
        th = bbox[3] - bbox[1]

        # ── 定义四个候选位置（优先级：上 → 右 → 下 → 左） ──
        candidates = []

        # 上方（尽量贴顶边）
        y_up = max(10, th // 2 + 5)                  # 至少留顶部边距
        candidates.append({
            'label_x': cx - tw // 2,
            'label_y': y_up,
            'line_end_x': cx,
            'line_end_y': y_up + th // 2 + 4,
            'priority': 1 if cy > img.height // 3 else 4   # 中下部矩形优先上方
        })

        # 右侧（尽量贴右边）
        x_right = min(img.width - tw - 10, img.width - 15)
        candidates.append({
            'label_x': x_right,
            'label_y': cy - th // 2,
            'line_end_x': x_right - 4,
            'line_end_y': cy,
            'priority': 2 if cx < img.width * 0.65 else 5
        })

        # 下方（尽量贴底边）
        y_down = min(img.height - th - 10, img.height - 15)
        candidates.append({
            'label_x': cx - tw // 2,
            'label_y': y_down,
            'line_end_x': cx,
            'line_end_y': y_down - 4,
            'priority': 3 if cy < img.height * 0.7 else 6
        })

        # 左侧（尽量贴左边）
        x_left = max(10, 15)
        candidates.append({
            'label_x': x_left,
            'label_y': cy - th // 2,
            'line_end_x': x_left + 4,
            'line_end_y': cy,
            'priority': 4
        })

        # 选择优先级最高且不重叠边界的候选（这里简单取优先级最低的数字）
        best = min(candidates, key=lambda c: c['priority'])

        label_x = best['label_x']
        label_y = best['label_y']
        line_end_x = best['line_end_x']
        line_end_y = best['line_end_y']

        # 绘制标注线（矩形中心 → 标签靠近侧）
        draw.line(
            [(cx, cy), (line_end_x, line_end_y)],
            fill=border, width=2
        )

        # 标签背景（半透明白色，提升对比）
        bg_padding = 6
        draw.rectangle(
            (label_x - bg_padding, label_y - bg_padding,
             label_x + tw + bg_padding * 2, label_y + th + bg_padding * 2),
            fill=(255, 255, 255, 160),
            outline=(0, 0, 0, 80), width=1
        )

        # 文字（黑色描边 + 白色主体）
        for dx in [-1, 0, 1]:
            for dy in [-1, 0, 1]:
                if dx != 0 or dy != 0:
                    draw.text((label_x + dx, label_y + dy), label, fill=(0, 0, 0, 255), font=font)

        draw.text((label_x, label_y), label, fill=(255, 255, 255, 255), font=font)

    output_path = "C2_with_placements_leader.png"
    img.save(output_path, "PNG")
    print(f"绘制完成（带外围标签 + 标注线）。输出文件：{output_path}")

if __name__ == "__main__":
    main()