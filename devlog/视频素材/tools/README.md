# 录制工具

开放日视频的录制和截图工具，Rin 在自己电脑上也能录。

- [record.js](record.js)：录视频。一帧一帧截图，直接交给 ffmpeg 合成，分几路同时录，最后拼起来。每次录出来都一模一样，不会掉帧。
- [still.js](still.js)：截几张定格图，几秒钟就好，适合改完版式先看一眼。

## 第一次使用（Windows）

1. 装 [Node.js](https://nodejs.org/)（LTS 版就行）。
2. 装 ffmpeg：在 PowerShell 里运行 `winget install Gyan.FFmpeg`，装完**重开一个终端**，输入 `ffmpeg -version` 能看到版本号就好了。
3. 在这个文件夹里装 Playwright 和它自带的浏览器：

   ```powershell
   cd E:\Mod_Dev\StructureBinder\devlog\视频素材\tools
   npm install
   npx playwright install chromium
   ```

字体从 Google Fonts 加载，录的时候电脑要能联网。

## 录视频

在 `tools` 文件夹里运行：

```powershell
node record.js ..\第三段\act3.html
```

录好的视频存在 `tools\输出\act3_录制.mp4`。终端里会显示进度和大概还剩几分钟。常用的选项：

| 想做什么 | 写法 |
|---|---|
| 先看个草稿（半分辨率、12 帧，快很多） | `node record.js ..\第三段\act3.html --preview` |
| 只录改过的一小段，比如第 120～180 秒 | `node record.js ..\第三段\act3.html --from 120 --to 180` |
| 指定输出文件 | `node record.js ..\第三段\act3.html D:\视频\第三段.mp4` |
| 改同时录的路数 | `--parts 8` |
| 文件太大，画质降一点 | `--crf 28`（默认 24，越小越清晰、文件越大） |

- **路数：** 默认按 CPU 估。9700X 是 8 核 16 线程，默认 6 路，留一点余量给电脑本身。录的时候电脑不做别的事，可以试试 `--parts 8`。
- **第二段：** `act2.html` 会自动另开一个页面播第二幕 PPT，按拍定格后贴到白板上，不用额外设置，只是比其他段慢一些。
- **中途停下：** 按 Ctrl+C。临时文件在系统临时目录的 `kaifangri-` 开头的文件夹里，可以删掉。

## 截图

```powershell
node still.js ..\第三段\act3.html 30 poly+2 compare
```

时间点可以写秒数（`30`），也可以写画面事件的名字，后面加秒数（`poly+2` 就是多边形出现后 2 秒）。画面事件的名字在 [../make_scripts.py](../make_scripts.py) 的 `MARKS` 里。图存在 `tools\截图\`。

## 不想装这些的时候

直接用浏览器打开各段的 html 就会播放，地址后面加 `?t=120` 从第 120 秒开始。配合 OBS 录屏也能出一版，只是电脑一卡可能掉帧，适合看节奏用的草稿。
