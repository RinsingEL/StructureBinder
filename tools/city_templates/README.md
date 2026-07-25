# Trek fixed-template importer

`import_trek_fixed_templates.py` converts only the 20 reviewed single-root Trek B0.6 structures in
`trek_fixed_manifest.json`. Configured structure JSON and template pools are offline source indexes;
the imported runtime identity is always `geomantia:city/trek/...`.

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

After the server has reloaded the generated NBT, pass `--query-url` to query
`city_query_template_metadata` and write the formal catalog. A catalog is never emitted from offline
hashes or unconfirmed entrance data.
