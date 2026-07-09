# UI project instructions

## Editing files while Wallaby is running

Wallaby runs a long-lived Vite server against its own copy of the sources and
tracks module IDs (including `?raw` template imports) by inode. Overwriting an
existing file wholesale unlinks and recreates it (new inode), which desyncs
Wallaby's Vite graph and makes it deny the module — symptom: `Denied ID
…/<file>.html?raw`, zero tests collected. It does not self-heal; only a Wallaby
restart clears it.

**Rule:** modify existing files with in-place edits (the `Edit` tool), never
overwrite them wholesale (`Write`). `Write` is only for creating new files.
This applies to every file type, but `.html` templates are the usual victim.

If Wallaby does wedge with a "Denied ID" error: it is not a code bug — verify
with `npx tsc --noEmit -p tsconfig.app.json`, `-p tsconfig.spec.json`, and
`npx ng build --configuration development`, then restart Wallaby (the user must
do this) to run the specs.

## Testing

Drive tests through Wallaby, not the CLI (`npx vitest` / `ng test` fail here
with "JIT compilation failed for BrowserXhr").
