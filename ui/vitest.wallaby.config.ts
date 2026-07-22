import { homedir } from 'node:os';
import { join } from 'node:path';
import { defineConfig } from 'vitest/config';

// Wallaby-only vitest overrides, picked up via `runnerConfig: true` in
// angular.json (Wallaby prefers this file; `ng test` looks for
// vitest-base.config.ts instead and ignores it).
//
// Wallaby serves mid-session file changes from ~/.wallaby/cache/** while
// unchanged files keep their real project paths. Vite's server.fs allow-list
// check on `?raw` ids (Angular templateUrl imports) rejects anything outside
// `server.fs.allow` with "Denied ID …html?raw", killing every spec that
// covers a template edited during the session. Setting `allow` replaces the
// default (workspace root only), so the repo root must be listed alongside
// the Wallaby cache.
export default defineConfig({
  server: {
    fs: {
      allow: [join(__dirname, '..'), join(homedir(), '.wallaby')],
    },
  },
});
