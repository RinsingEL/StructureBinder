AI 的职责只有两件事：

1. **填参数**（在白名单+范围内）

2. **选择填充用的长方形尺寸**（可以多选并给权重/数量偏好）


我按这个思路把 `PLAZA_RING` 写成你可以直接放进文档/Schema 的模板。

* * *

# FillStyle: `PLAZA_RING`（参数模板 v1）

## 意图

以某个 anchor 为中心生成一个**留白广场（plaza void）**，并在其外侧生成 **一圈或多圈环带（ring bands）**，在环带里按规则投放“长方形地块（plots/bricks）”，供 C7 选择模板放置。

* 适用：市场核心、宗教核心、市政核心、地标前广场

* 结果特征：中心留白强、边缘建筑有节奏、可留开口连接未来道路（但 C6 不画路）


* * *

## AI 输入职责（仅两项）

### A) 参数设置（params）

AI 只能填本模板里列出的字段，且必须满足范围约束。

### B) 填充长方形尺寸选择（rect_sizes）

AI 选择一个或多个 `rect_size`（以 blocks 为单位），并可给：

* 权重（用于随机抽样）

* 或目标数量（用于配额式填充）

* 或二者（程序以数量优先）


* * *

## 数据结构（建议）

```jsonc
{
  "style": "PLAZA_RING",
  "params": {
    "plaza_shape": "CIRCLE",                 // 枚举
    "plaza_radius_blocks": [10, 14],         // [min,max]
    "plaza_padding_blocks": [2, 4],          // 广场边缘到第一圈建筑的缓冲带（留步道/绿化）

    "ring_count": 1,                         // 1..3
    "ring_depth_blocks": [[10, 14]],         // 每一圈环带厚度范围（数组长度=ring_count）
    "ring_gap_blocks": [[2, 4]],             // 每圈之间的间隔（数组长度=ring_count-1，可省略表示无间隔）

    "opening_count": 1,                      // 0..3 预留开口数量（将来道路/视线廊）
    "opening_width_blocks": [8, 12],         // 单个开口宽度
    "opening_angle_deg": [30, 60],           // 开口角度（圆形广场时）
    "opening_prefer_dirs": ["S", "SE"],      // 可选：偏好开口方向（与模块关系/地形相关）

    "min_spacing_blocks": [2, 4],            // plot 与 plot 的最小边距（非等距，用范围）
    "jitter": 0.35,                          // 0..0.8 位置抖动强度（越大越不整齐）
    "rotation_mode": "TANGENT",              // 枚举：TANGENT / RADIAL / FIXED
    "rotation_jitter_deg": [0, 12],          // 朝向随机扰动

    "respect_build_area_boundary": true,     // 超出 build_area 则裁掉/跳过
    "reserve_decor_ratio": 0.10              // 0..0.35：留给绿化/装饰/空地的比例（避免塞满）
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

* * *

## 字段说明与约束（程序校验要点）

### 1) 广场（plaza）

* `plaza_shape`

    * `CIRCLE`（推荐）｜`OVAL`（可选）｜`ROUNDED_RECT`（可选）

* `plaza_radius_blocks: [min,max]`

    * `min >= 6`

    * `max >= min`

* `plaza_padding_blocks: [min,max]`

    * 建议 `min >= 1`，否则第一圈会贴脸


> 程序实现：从 anchor 出发定义一个“不可放置区域（void）”，形状由 `plaza_shape` 决定。

### 2) 环带（rings）

* `ring_count: 1..3`

* `ring_depth_blocks`：长度必须等于 `ring_count`

* `ring_gap_blocks`：长度必须等于 `ring_count-1`（也可省略 => 视为全 0）


> 程序实现：环带是可放置区域；每一圈环带范围 = `plaza_void + padding + previous_rings + gaps` 的外侧厚度带。

### 3) 开口（openings）

* `opening_count: 0..3`

* `opening_width_blocks`、`opening_angle_deg`：用于从环带/广场边缘切一块“禁止放置扇区/走廊”

* `opening_prefer_dirs`：若提供，程序优先在这些方向放开口（否则均匀或随机）


> 这里是“为未来道路留口子”，但 C6 不生成道路。

### 4) 分布与朝向

* `min_spacing_blocks: [min,max]`：用范围做“距离带”，避免等距

* `jitter: 0..0.8`：对候选 plot 中心点施加噪声（但不得破坏合法性）

* `rotation_mode`：

    * `TANGENT`：贴着环绕方向（最像“围着广场开店”）

    * `RADIAL`：朝向广场中心（适合宗教/庄严）

    * `FIXED`：统一朝向（适合秩序感强的市政）

* `rotation_jitter_deg`：朝向扰动范围


### 5) 边界与留白

* `respect_build_area_boundary`：超界就裁/跳过（建议 true）

* `reserve_decor_ratio: 0..0.35`

    * 控制“不要塞满”，强制留下零碎空地给绿化/小径/装饰


* * *

## rect_sizes（填充长方形尺寸）规则

每个 `rect_size` 描述的是“可投放 plot 的尺寸族”：

* `w_blocks: [min,max]`

* `h_blocks: [min,max]`

* `weight`（可选）用于抽样

* `min_count/max_count`（可选）用于配额控制


**程序侧建议逻辑：**

1. 先按 `min_count` 为每个 size 放最低配额（放不下则记录失败原因）

2. 再在剩余空间里按 `weight` 抽样填充，直到：

    * 达到 `max_count` 或

    * 达到 `reserve_decor_ratio` 目标留白 或

    * 环带无更多合法位置


* * *

## 程序排列器（写死工具）契约：输入/输出

### 输入（arranger 输入）

* `anchor (x,z)`

* `build_area_id`（可放置 mask）

* `params`（本模板）

* `rect_sizes`（本模板）

* `seed`（确保可复现）


### 输出（给 C7 使用的 plot 列表）

输出是“候选地块列表”，不是模板实例：

```jsonc
{
  "plots": [
    {
      "plot_id": "p0",
      "ring_index": 0,
      "rect": { "x0": -1180, "z0": 552, "w": 12, "h": 10 },
      "rotation_deg": 90,
      "size_id": "M1",
      "tags": ["front_to_plaza"]
    }
  ],
  "voids": [
    { "kind": "plaza", "shape": "CIRCLE", "center": { "x": -1160, "z": 560 }, "radius": 12 },
    { "kind": "opening", "dir": "S", "width": 10 }
  ],
  "stats": {
    "placed_plots": 22,
    "failed_attempts": 71,
    "decor_reserved_ratio": 0.12
  }
}
```

* * *