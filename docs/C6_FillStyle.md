# C6 FillStyle 规范（按系统格式）

本文档用于统一定义 C6 阶段的 Fill Style 枚举案例。  
目标：AI 只填参数与尺寸族，程序执行排列，不让 AI 直接下坐标。

---

## 全局约定（所有 Fill Style 通用）

### AI 只负责两件事

1. 填参数（仅白名单字段，且必须通过范围校验）。
2. 选择 `rect_sizes`（可多选，可给 `weight`，可给 `min_count/max_count`）。

### 程序负责

1. 可建造合法性判定（边界、坡度、水体、保护点等）。
2. 排列执行、冲突回避、失败统计。
3. 输出 `plots`（候选地块），不输出模板实例。

### 通用输入契约

* `anchor`：`{x, z}`
* `build_area_id`
* `params`：当前 style 参数
* `rect_sizes`：尺寸族
* `seed`：可复现

### 通用输出契约

```jsonc
{
  "plots": [
    {
      "plot_id": "p0",
      "rect": { "x0": 0, "z0": 0, "w": 10, "h": 8 },
      "rotation_deg": 90,
      "size_id": "M1",
      "tags": ["front_to_plaza"]
    }
  ],
  "voids": [],
  "stats": {
    "placed_plots": 0,
    "failed_attempts": 0,
    "decor_reserved_ratio": 0.0
  }
}
```

---

## FillStyle: `PLAZA_RING`（参数模板 v1）

### 意图

以 anchor 为中心生成留白广场（plaza void），外侧生成一圈或多圈环带，在环带内投放长方形地块（plots/bricks）供 C7 筛选模板。

### 数据结构

```jsonc
{
  "style": "PLAZA_RING",
  "params": {
    "plaza_shape": "CIRCLE",
    "plaza_radius_blocks": [10, 14],
    "plaza_padding_blocks": [2, 4],

    "ring_count": 1,
    "ring_depth_blocks": [[10, 14]],
    "ring_gap_blocks": [[2, 4]],

    "opening_count": 1,
    "opening_width_blocks": [8, 12],
    "opening_angle_deg": [30, 60],
    "opening_prefer_dirs": ["S", "SE"],

    "min_spacing_blocks": [2, 4],
    "jitter": 0.35,
    "rotation_mode": "TANGENT",
    "rotation_jitter_deg": [0, 12],

    "respect_build_area_boundary": true,
    "reserve_decor_ratio": 0.10
  },
  "rect_sizes": [
    {
      "id": "S1",
      "w_blocks": [7, 9],
      "h_blocks": [7, 9],
      "weight": 0.55,
      "min_count": 6,
      "max_count": 18
    },
    {
      "id": "M1",
      "w_blocks": [10, 14],
      "h_blocks": [8, 12],
      "weight": 0.35,
      "min_count": 2,
      "max_count": 8
    },
    {
      "id": "L1",
      "w_blocks": [16, 22],
      "h_blocks": [12, 18],
      "weight": 0.10,
      "min_count": 0,
      "max_count": 2
    }
  ]
}
```

### 校验要点

* `plaza_shape`：`CIRCLE | OVAL | ROUNDED_RECT`
* `plaza_radius_blocks`：`min >= 6`，`max >= min`
* `ring_count`：`1..3`
* `ring_depth_blocks` 长度必须等于 `ring_count`
* `ring_gap_blocks` 长度必须等于 `ring_count-1`（可省略，默认 0）
* `opening_count`：`0..3`
* `jitter`：`0..0.8`
* `reserve_decor_ratio`：`0..0.35`
* `respect_build_area_boundary` 建议强制 `true`

### 排列建议（程序写死）

1. 先放 `min_count` 配额，放不下记录原因。
2. 再按 `weight` 抽样填充。
3. 当达到 `max_count` 或保留留白目标或无合法位置时停止。

### 输出附加字段建议

* `ring_index`
* `voids` 包含 `plaza` 与 `opening`
* `stats.failed_attempts`
* `stats.decor_reserved_ratio`

---

## FillStyle: `STREET_SPINE`（模板位）

### 意图

以 1~N 条主脊线组织地块，形成沿线展开的街巷型布局。

### 状态

待细化参数与校验规则（按 `PLAZA_RING` 同结构补齐）。

---

## FillStyle: `EDGE_FOLLOW`（模板位）

### 意图

沿可建造区边缘或地形边界贴边排布，强调轮廓感。

### 状态

待细化参数与校验规则（按 `PLAZA_RING` 同结构补齐）。

---

## FillStyle: `CLUSTER_POISSON`（模板位）

### 意图

基于 Poisson / 蓝噪声采样做簇状投放，强调自然散布。

### 状态

待细化参数与校验规则（按 `PLAZA_RING` 同结构补齐）。

---

## FillStyle: `GRID_RELAXED`（模板位）

### 意图

先规则网格后松弛扰动，保留秩序同时避免过于机械。

### 状态

待细化参数与校验规则（按 `PLAZA_RING` 同结构补齐）。

---

## FillStyle: `TERRACE_BANDS`（模板位）

### 意图

按等高/台地带状切分并排布，适合坡地城镇。

### 状态

待细化参数与校验规则（按 `PLAZA_RING` 同结构补齐）。

---

## FillStyle: `DECOR_BUFFER`（模板位）

### 意图

以留白与低密度装饰为主，服务边界缓冲和视觉过渡。

### 状态

待细化参数与校验规则（按 `PLAZA_RING` 同结构补齐）。

---

## 版本与落地建议

* 建议在每个 style 对象加 `style_version`。
* 程序侧统一维护 `reject_reason` 枚举，便于调试与回放。
* C6 输出只给 plot 候选，不提前绑定具体模板（模板选择在 C7）。
