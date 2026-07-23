# code_process_viewer

`code_process_viewer` 是实现仓库内独立的 Jigsaw 求解器调试回放工具。

当前首版能力：

- 读取已经生成的 `jigsaw_solver_debug/<runId>` 调试目录
- 导入 `trace.json`、预览图和 legend
- 写入 SQLite 会话库
- 导出规范化 session JSON 缓存
- 提供浏览器可访问的只读回放页面
- 支持保存步骤审查结论：`通过 / 存疑 / 驳回 / 待补证据`

## 目录说明

- `cmd/code_process_viewer/`
  - 程序入口
- `internal/importer/jigsawdebug/`
  - 调试产物导入器
- `internal/session/`
  - 会话模型与源码映射
- `internal/storage/`
  - SQLite 存储与缓存导出
- `internal/httpapi/`
  - HTTP API 与静态资源服务
- `web/`
  - 单页只读工作台资源
- `runtime/`
  - 默认运行期目录

## 启动方式

```powershell
Set-Location "E:\mc_dev\StructureBinder-rebuild\StructureBinder\code_process_viewer"

& "C:\Program Files\Go\bin\go.exe" run .\cmd\code_process_viewer `
  --repo-root "E:\mc_dev\StructureBinder-rebuild\StructureBinder" `
  --docs-repo-root "E:\mc_dev\StructureBinder-rebuild\designer_territoryMod" `
  --artifact-root "E:\path\to\jigsaw_solver_debug\20260407_123456_789"
```

默认端口为 `6657`。

如果启动时不传 `--artifact-root`，服务也会正常起来，此时可以打开页面后手工导入调试目录。

## 运行期目录

- `runtime/db/code_process_viewer.db`
  - SQLite 会话库
- `runtime/cache/<debug_run_id>.json`
  - 规范化 session JSON 缓存
- `runtime/cache/<debug_run_id>/assets/`
  - 步骤预览图副本

## 首版接口

- `GET /api/sessions`
- `GET /api/sessions/{id}`
- `GET /api/sessions/{id}/graph`
- `GET /api/nodes/{id}`
- `GET /api/nodes/{id}/tables`
- `POST /api/nodes/{id}/review`
- `GET /api/sessions/{id}/report`
- `POST /api/import/jigsaw-debug`

## 页面布局

- 左侧：步骤流程 / 步骤列表
- 中间：当前步骤预览图
- 右侧：步骤目标、动作摘要、实现说明、结果摘要、源码入口
- 下方：`evidence` / `legend` 表格
- 右下：检查点与审查结论保存

## 开发验证

```powershell
& "C:\Program Files\Go\bin\go.exe" test .\...
```

## 首版边界

- 只读已有调试产物，不触发 `city_jigsaw_solve`
- 不做实时刷新
- 不做多人协作
- 不引入独立前端框架
