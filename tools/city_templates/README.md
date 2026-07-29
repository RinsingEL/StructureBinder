# Trek fixed-template importer

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
  --profile-dir "run/config/structureTemplate/terrasense/geomantia_trek_fixed_b0_6_v1"
```

The profile package contains `StructureProfile.jsonl`, a complete frozen vocabulary snapshot, and
`TerraSenseStructureProfileSource.official.json`. Semantic approval and catalog readiness are
separate gates. All 20 entrances are reviewed against fixed NBT plus TerraSense four-view screenshots,
and each confirmation carries an exact NBT evidence block. Runtime metadata is still required before
formal `template_catalog.json` output.

After the server has reloaded the generated NBT, pass `--query-url` to query
`city_query_template_metadata` and write the formal catalog. A catalog is never emitted from offline
hashes or unconfirmed entrance data.
