# Trek fixed-template importer

## Construction pack discovery catalog

`asset_catalogs/construction_pack/README.md` is a name-based discovery index, separate from the
approved runtime catalog. It retains source series, style/function/theme evidence and original paths.
It does not inspect NBT or authorize world placement.

```powershell
python tools/city_templates/index_construction_assets.py --source '<g构建包 directory>' --output asset_catalogs/construction_pack
python tools/city_templates/query_construction_assets.py --style 中世纪 --function 行政 --kind 建筑与城市配套
python tools/city_templates/query_construction_assets.py --style 中世纪 --series 铜顶
```

Browse `styles/` and `functions/` for human-readable lists, or query `assets.jsonl` to give an AI only
the relevant candidates. `series.json` preserves original pack groupings; equal style tags do not
assert visual compatibility. Rebuilding the catalog derives tags solely from names, not manual approvals.

Read `COVERAGE.md` / `coverage.json` before treating a style as a city palette. The coverage audit
separates explicit building functions from decoration and unknown-purpose houses, at both style
and original-series level. These are review axes, not new runtime admission rules. The query CLI
includes style coverage and matched-series coverage alongside candidates. Rebuild coverage alone:

```powershell
python tools/city_templates/audit_construction_coverage.py
```

## Deployable City content pack

`build_city_content_pack.py` takes an already reviewed `city_template_catalog` plus a source world's
`generated` directory and writes a versioned payload beside the managed planning bundle. It copies
only unique `templateRef` values present in the active catalog; unrelated converted templates are
not included.

```powershell
python tools/city_templates/build_city_content_pack.py `
  --catalog "run/config/structureTemplate/terrasense/<bundle>/template_catalog.json" `
  --generated-root "run/saves/<reviewed-world>/generated" `
  --output-dir "run/config/structureTemplate/terrasense/<bundle>" `
  --pack-id "my_city_content_v1"
```

The output is `city_template_content_pack.json` plus `city_template_content_pack/`. On world start,
Geomantia validates full catalog coverage and source file hashes, atomically installs missing or
drifted files under that world's `generated/<namespace>/structures/`, and writes an install-state
record at the world root. Existing unrelated generated structures are never deleted.

## Trek v3 catalog curation

`curate_trek_v3_catalog.py` synchronizes an already reviewed Trek v3 standalone batch into the
active City catalogs. `StructureProfile.jsonl` remains the semantic source of truth. The tool grants
all four Minecraft rotations to `trek_v3_sanitized_v1`, replaces the collapsed `trek_v3` template
style/building semantic, adds concrete AI style profiles, and derives non-empty fill pools from
reviewed planning roles, styles, and function terms.

Preview and then write an active configuration directory:

```powershell
python tools/city_templates/curate_trek_v3_catalog.py `
  --config-dir "run/config/structureTemplate/terrasense/<catalog>"

python tools/city_templates/curate_trek_v3_catalog.py `
  --config-dir "run/config/structureTemplate/terrasense/<catalog>" --write
```

The tool does not infer waterfront, underground, or multi-piece eligibility. It only operates on
the 46 already approved `trek_v3_sanitized_v1` surface templates and preserves other variants.

## Generic standalone Jigsaw sanitizer

`jigsaw_template_sanitizer.py` accepts loose `.nbt` files collected from any mod. It does not run
template pools or decide whether a building is visually complete. Instead, it creates a review gate
before an explicitly approved standalone template can enter the fixed-template City path.

Audit a file or directory and create a disabled manifest draft:

```powershell
python tools/city_templates/jigsaw_template_sanitizer.py audit `
  --source "C:\path\to\collected_templates" `
  --report "run/jigsaw-import/audit.json" `
  --manifest-draft "run/jigsaw-import/manifest.json" `
  --target-prefix "city/imported"
```

The audit records source hashes, exact template sizes, every Jigsaw position, pool, target,
orientation, `final_state`, and horizontal road-entrance candidates. Candidates are review aids;
they are not automatically accepted as formal `roadEntrances[]`.

Review the generated manifest and set `standaloneConfirmed=true` only for complete buildings. Then
preflight or write a datapack-shaped output directory:

```powershell
python tools/city_templates/jigsaw_template_sanitizer.py sanitize `
  --source-root "C:\path\to\collected_templates" `
  --manifest "run/jigsaw-import/manifest.json" `
  --output-root "run/jigsaw-import/output" `
  --report "run/jigsaw-import/sanitize-report.json" `
  --dry-run

python tools/city_templates/jigsaw_template_sanitizer.py sanitize `
  --source-root "C:\path\to\collected_templates" `
  --manifest "run/jigsaw-import/manifest.json" `
  --output-root "run/jigsaw-import/output" `
  --report "run/jigsaw-import/sanitize-report.json"
```

The sanitizer requires an unchanged source SHA-256, an unchanged Jigsaw count, explicit standalone
approval, and `connectorPolicy=replace_all_with_final_state`. Every Jigsaw is replaced by its own
parsed `final_state`; malformed states fail the whole batch. Output is accepted only when no Jigsaw
remains. Existing targets require `--overwrite`. Entities are reported but not altered by this
generic tool; the active City worldgen placer ignores template entities.

The output layout is `data/<namespace>/structures/<path>.nbt`. Reload it as a datapack before using
`city_query_template_metadata`, then review the reported entrance candidates and add confirmed
entries to `CityTemplateCatalog`.

## Trek B0.6 reviewed batch

`import_trek_fixed_templates.py` converts only the 20 reviewed single-root Trek B0.6 structures in
`trek_fixed_manifest.json`. Configured structure JSON and template pools are offline source indexes;
the imported runtime identity is always `geomantia:city/trek/...`.

The same manifest is also the version-controlled source of the 20 fixed templates' canonical
TerraSense terms. Profile export uses the same fixed-template contract as Stubbs:
`single / structure_template_nbt / city_template_nbt / fixed_footprint`. It does not reuse the old
`trek:...` Jigsaw assembly profiles.

Dry run:

```powershell
python tools/city_templates/import_trek_fixed_templates.py `
  --save-dir "run/saves/新的世界 (5)" --dry-run
```

Write templates and previews. Existing targets require `--overwrite`:

```powershell
python tools/city_templates/import_trek_fixed_templates.py `
  --save-dir "run/saves/新的世界 (5)" --overwrite
```

Export only the TerraSense semantic package; this does not write a world or emit a template catalog:

```powershell
python tools/city_templates/import_trek_fixed_templates.py `
  --profile-dir "run/config/structureTemplate/terrasense/geomantia_trek_fixed_b0_6"
```

The profile package contains `StructureProfile.jsonl`, a complete frozen vocabulary snapshot, and
`TerraSenseStructureProfileSource.official.json`. Semantic approval and catalog readiness are
separate gates. All 20 entrances are reviewed against fixed NBT plus TerraSense four-view screenshots,
and each confirmation carries an exact NBT evidence block. Runtime metadata is still required before
formal `template_catalog.json` output.

After the server has reloaded the generated NBT, pass `--query-url` to query
`city_query_template_metadata` and write the formal catalog. A catalog is never emitted from offline
hashes or unconfirmed entrance data.

## Studio 独立测试素材包

`build_studio_test_bundle.py` 导出沙漠、精灵、魔法学院的常规平地模型，原始 NBT 与作者文件不变。

作者可显式标记 `ground_plane: {"y": 2, "note": "外部地面上边界/玩家脚底高度"}`。
`y` 是结构局部整数坐标，必须满足 `0 <= y < size[1]`；`note` 必须为非空说明。
导出器使用 Studio 共用校验，拒绝存在但非法的标记（含布尔值），并将合法 `y` 原值写入
`template_catalog.json` 的 `templates[].groundPlaneY`、`codec_input.json` 与导出 provenance。
没有标记的旧资产继续导出并省略该字段，不从 `preview_context`、入口或其他几何猜测。
终端 summary 与 `studio_export_provenance.json` 的 `groundPlaneCoverage` 记录已标数量、未标数量和未标资产 ID，便于后续补齐。
该字段表达放置接地基准，不代表已通过实际世界接地验收。聚焦回归：
`python -m unittest discover -s tools/city_templates -p 'test_studio_ground_plane_export.py' -v`。
参数为 `--output <新目录> --baseline <既有目录> --classpath-file build/classpath/runClient_minecraftClasspath.txt --java <JDK17/java.exe>`。
基线仅提供算法、道路与景观规则，建筑及填充池全部换为 `studio:` 命名空间。
工具调用 `StudioTemplateMetadata.java` 使用 Minecraft 1.20.1 的实际模板编解码器计算运行时内容哈希，并核对调色板和方块数量。
这是离线测试导出，不是正式发布流程：语义 `approved` 只表示已核对作者标签的逐字段映射；不提升 Studio 原有图审状态。
道路入口按作者朝向投影到边界并检查净空，明确使用 `legacy_catalog`，不生成已图审入口 sidecar。
接地、道路高差、实际生成必须在游戏内验证；原始选址文字保存在 `asset_names.json` 和 provenance，目前运行时不读取它。

`package_studio_test.py --base-package <既有安装包展开目录> --bundle <已验证的 Studio 导出目录> --output <新安装包目录>`
组合当前 build/libs 的主包、可选附属包、配置及 NBT，输出 ZIP 和逐文件 SHA-256。
不携带 `mcp_server.json`，让新实例首次主菜单仍能选择端口；W 半径使用已确认的 12288。

## 聚落素材角色初筛（运行）

运行 `curate_planning_roles.py --bundle <配置目录> --report <报告路径>` 查看保守角色整理；加 `--apply` 写入角色、词表和填充池。完整组合采用 `--overrides asset_catalogs/planning_roles/overrides.json`，每项绑定模板内容哈希与核对证据。

角色按名字和功能判定，尺寸另行分组；大型住宅、别墅仍可重复填充，不因尺寸变大失去填充资格或自动成为核心。不确定用途保留明确选用，名字包含“花园”不等于已经通过自动绿化或完整组合审查。报告的 `visualReviewComplete=false` 表示没有冒充全量视觉验收。

`public_small_support` / `public_medium_support` 分别提供144/400格以内且高度不超过24的配套，并按原有风格分池；无后缀为中世纪，其余使用 `_desert`、`_japanese` 等后缀，避免随机混入不同风格。每种模板每组最多2份。原类别池保留ID，但只留下重复候选；空池不会自动补入主体。

公共绿化地表和独立结构清单分别由 `config/geomantia/city_public_greenery.json`、`city_public_greenery_structures.json` 配置；未设置独立结构清单时复用现有道路树。完整花园、喷泉庭院通过D4明确选材，不自动填入残余空隙。


尺寸展示名保留 originalDisplayName，格式为【占地档·占地宽×深·高H】原名，重复执行不叠加前缀。按用途、风格、占地和高度生成 pool:scaled_*；占地档 small≤144且边≤16、medium≤400且边≤24、large≤900且边≤36、其余extra_large；高度档low≤12、medium≤24、tall≤40、very_tall>40。配套池不包含超高小占地住宅。

迁移时使用原 --report 的受支持策略记录识别旧自动角色；保留记录外的显式修改，--overrides优先。请使用同一报告路径迭代；同时生成同名Markdown分组清单。打包/增量脚本会为新输出运行整理并生成 planning_role_audit.json；现有包升级使用原审计报告单独运行整理。
