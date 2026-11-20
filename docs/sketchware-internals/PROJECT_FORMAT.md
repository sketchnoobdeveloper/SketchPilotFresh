# Project data format

Core payloads are AES-128-CBC/PKCS5Padding with key and IV `sketchwaresecure`
(corroborated by `mod/hey/studios/project/backup/BackupFactory.java`).

Observed on-disk layout:

```text
.sketchware/data/<sc_id>/project          encrypted metadata JSON
.sketchware/data/<sc_id>/file             encrypted @activity/@customview sections
.sketchware/data/<sc_id>/view             encrypted @<screen>.xml sections
.sketchware/data/<sc_id>/logic            encrypted @<Activity.java>_... sections
.sketchware/data/<sc_id>/library          encrypted named library sections
.sketchware/data/<sc_id>/resource         encrypted @images/@sounds/@fonts
.sketchware/data/<sc_id>/project_config   plaintext settings JSON
```

Section format: each `@name` line starts a section; subsequent lines are raw
records (usually one JSON object per line). The codec preserves unknown
sections and does not rewrite files during inspection.

Important invariants for future mutation work:

- Block IDs must be positive strings; `0` is rejected by the host validator.
- `nextBlock`, `subStack1`, `subStack2` are integer block references.
- Reporter blocks are embedded via `@<id>` in `parameters`.
- `onCreate` must not be duplicated into the events section.
- Version fields in `project` must remain JSON strings.
