# 导出分类覆盖报告

Studio 与 Java 共用 `src/main/resources/geomantia/catalog/structure_functions.json`。`build_studio_test_bundle.py` 导出时保留作者原始用途、风格和独立的基建/景观标签，并生成 `classification_coverage.json`，在 provenance 与控制台摘要中显示分类覆盖情况。

- `unclassifiedCount`：没有匹配到任何标准功能，包括大类；不表示仅缺少小类。
- `unmappedFunctionTerms`：尚未识别的原始词。素材即使已有标准分类，也可能包含其他未识别词。
- `directFunctionIds`：直接命中的功能；`functionIds` 包含祖先分类。大类不会自动扩展为子类。
- 报告保留词表哈希。未知用途允许导出并明确报告，不自动推断或替换作者语义；未设置基建/景观标签也不自动补齐。

已有导出包可只做检查，无需重新导出 NBT：

```powershell
python tools/city_templates/classification_coverage.py <素材包>/StructureProfile.jsonl --output <报告路径>.json
```

建筑目录不包含全部公共空间模板。学院小型绿化另由 `CityDesignExamples` 的 `public_realm` 示例提供目录和池；不能只凭建筑素材数量判断全部可用素材。
