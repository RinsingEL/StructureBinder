# Fish Audio API 便携 TTS 运行包（零外部依赖）

把压缩包解压后进入 `fish_tts_pack` 目录即可直接运行。脚本只用到 Python 3 标准库（无需 `pip install` 任何第三方包，也不用配置虚拟环境），Windows / macOS / Linux 通用。

> 注意：压缩包内**不包含你的 `FISH_API_KEY`**，请按下方第 2 步在目标电脑的终端里设置环境变量后再跑。

---

## 1. 目录里都有什么

- **5 位暂定合成底音（2026-11-17 用户确认，零真人克隆）**
  - `references/暂定_20261117/DeepSeek.wav`
  - `references/暂定_20261117/Claude.wav`（最初的 `claude_1.wav` 对应原版）
  - `references/暂定_20261117/Gemini.wav`
  - `references/暂定_20261117/Astra.wav`
  - `references/暂定_20261117/GLM.wav`（当前保留版，对应约 17.97 秒开场）
  - 当前选择与文件哈希见 `current_voice_set.json`；根目录的旧参考及各轮试音均保留。
- **脚本与试听页**
  - `test_fish_d4.py`：D4 五人 6 句快速试听脚本（已去掉 Claude 的 `[sigh]` 叹气标签，改用省略号自然停顿）
  - `run_fish_timeline.py`：全片 5 幕批量合成脚本（按整句合并字幕卡，自动跳过 `rin` 与片尾合唱 `all`，支持断点续跑、`--acts` 分幕跑、并输出对齐预览轨 `aligned_ai_preview.wav` 与 `manifest.json`）
  - `fish_common.py`：共享库（MessagePack 编码、WAV 读写、API 重试）
  - `index.html`：本地网页播放器（双击或 `python -m http.server 8765` 打开）
- **`alt_refs/` 备选底音**
  - `glm_Y1_s520.wav`（少女 Y1 seed 520）
  - `astra_A_s42.wav`、`glm_Y2_s108.wav`（Astra / GLM 未裁剪原始合成档）
- **`timelines/` 全片 5 幕时间轴 JSON**
  - `timeline_开场.json`、`timeline_第二段.json`、`timeline_第三段.json`、`timeline_第四段.json`、`timeline_第五段.json`
- **基线试听参考**
  - `fish_01_deepseek.wav` ~ `fish_06_glm_report.wav`、`fish_d4_reel.wav`（之前在 Mac 用 `s2.1-pro-free` 跑出的试听样例）

---

## 2. 设置 `FISH_API_KEY`（三选一，按系统来）

### Windows PowerShell
```powershell
$env:FISH_API_KEY="你的Fish_API_Key"
```
（想长期保存：`setx FISH_API_KEY "你的Fish_API_Key"`，然后重开终端生效）

### Windows CMD
```cmd
set FISH_API_KEY=你的Fish_API_Key
```

### macOS / Linux
```bash
export FISH_API_KEY="你的Fish_API_Key"
```

---

## 3. 怎么运行

### 先自检（不消耗额度 / 不连网）
```bash
python test_fish_d4.py --dry-run
python run_fish_timeline.py --dry-run
```
（macOS 若无 `python` 命令请改用 `python3`）

### A. 跑 D4 五人快速试听（调音色 / 改参考底音后验证用）
```bash
python test_fish_d4.py
# 如果要测正式付费模型：
python test_fish_d4.py --model s2.1-pro
```

### B. 跑全片 5 幕台词（支持断点续跑）
默认模型 `s2.1-pro-free`，生成结果保存在 `output/01_开场/` ～ `output/05_第五段/`：
```bash
# 推荐先跑小幕试水（例如开场 + 第五段，各 5 句）：
python run_fish_timeline.py --acts 开场 第五段

# 或者只测试前 3 句新台词：
python run_fish_timeline.py --limit 3

# 跑完整 5 幕（共 126 句 / 26,601 UTF-8 字节；已存在的 wav 会自动跳过）：
python run_fish_timeline.py

# 切换到正式版模型（约 $0.40 跑完全部 126 句）：
python run_fish_timeline.py --model s2.1-pro --force
```

---

## 4. 小提示
- 当前暂定版为“原版小克＋目前 GLM，约 84 秒”，完整快照在相邻的 `../暂定音色_20261117/`。两个生成脚本已改用本包内 `references/暂定_20261117/` 的五人副本，参考文本同步匹配。脚本仍会跳过已有 WAV，不会因更换参考音自动重生成；后续按暂定音色生成时可指定 `--out-dir output/暂定_20261117`，避免混入旧试音结果。
- 网页端快速预览 `index.html` 时，若部分浏览器限制本地 `file://` 加载同级音频，在当前目录跑一句：
  ```bash
  python -m http.server 8765
  ```
  然后浏览器打开 `http://127.0.0.1:8765` 即可。
- 新音色试验请另存文件，再调整 `fish_common.py` 中对应角色的参考路径与文本；当前暂定快照保留，不直接覆盖。正式变更需同步更新 `current_voice_set.json`，区分试音与已确认版本。
