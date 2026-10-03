# Voice Session Suite v0.2.13

Canary-first ToolPkg release. 0.2.12 is quarantined and must not be used for physical qualification.

## Import invariant
All lifecycle hooks use the canonical upstream Operit registration form: local function references (for example `function: onCreate`), never `exports.*`.

## Qualification order
1. Import Coordinator canary first. It contains no DEX and isolates ToolPkg container/manifest/main-registration compatibility.
2. Only after Coordinator imports successfully, import Voice and Speech packages built from this exact commit.
3. Status/inspection operations must remain read-only. No synthetic pre-wake TTS lease is permitted.

## Release discipline
Artifacts are immutable per commit. Never overwrite a published version under the same filename or URL. SHA256 must be recorded after the build and before physical testing.
