# Trek fixed-template importer

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
