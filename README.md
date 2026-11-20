# SketchPilot (fresh)

Autonomous, project-aware development environment for Sketchware Pro.

Package: `snd.sketchpilot` · Host: `pro.sketchware` · Integration: LSPosed · Control: MCP

## Modules

- `:core` — pure Kotlin engine: Sketchware codec, snapshot, validation, tools, IPC, MCP dispatch.
- `:integration` — thin, defensive LSPosed entry and native host entry point.
- `:app` — Material 3 companion app, secure configuration and loopback MCP server.

## Current slice

- Read-only project discovery and inspection over real Sketchware data files.
- One shared tool registry used by the UI and MCP.
- Native entry button injected into Sketchware's toolbar.
- Keystore-backed secret storage; MCP disabled until explicitly started.

## Build and test

All builds and tests run through GitHub Actions (`.github/workflows/verify.yml`).
Local builds are intentionally not performed.

Docs: `docs/sketchware-internals/`, `docs/COMPATIBILITY.md`, `docs/ROADMAP.md`.
