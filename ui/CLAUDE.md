# UI project instructions

Angular 22, standalone components, signals + `ChangeDetectionStrategy.OnPush`
everywhere. spartan/ui: Brain primitives from npm, Helm components vendored in
`libs/ui/*` and imported as `@spartan-ng/helm/<name>`.

## Idioms

- State: injectable singleton services holding signals (`core/session.ts`,
  `core/filters.ts`, `core/projects.ts`) — no store library. `GlobalFilters`
  keeps project/environment/range in URL query params; nav links use
  `queryParamsHandling="merge"` to preserve them.
- Data-entry forms use **Angular Signal Forms** (`@angular/forms/signals`): a
  typed model `signal`, `form(model, schema)` with **synchronous** validators,
  and `[formField]` bindings on the spartan controls (which implement
  `FormValueControl`/`FormCheckboxControl` — `hlmInput`, `hlm-checkbox`,
  `hlm-select`). Show client-side errors inline by looping the field's
  `errors()` into `hlm-field-error` inside an `hlmField`
  (`@spartan-ng/helm/field`), and disable submit while `f().invalid()`. Bind the
  `<form>` with `[formRoot]` and register the submit action via `form()`'s
  `submission` option — `FormRoot` runs it on submit and only when valid, so it
  calls `api.x()` and reloads the list on success (template: `pages/login`). The
  **server**
  outcome is reported through the `Feedback` seam (`core/feedback.ts`) —
  `feedback.error(...)`/`feedback.success(...)`, never an inline signal (ADR
  0007); validation is client-side, the toast is for what validation can't
  catch. Persistent page state, such as a failed initial load, may still use an
  inline signal (see `pages/settings/data-retention`). Filter/search boxes are
  **not** forms — they stay on `FormsModule` + `[(ngModel)]="signal"` (see
  `pages/issues`, `pages/logs`, `pages/traces`). See ADR 0008.
- API: one method per endpoint in `core/api.ts` returning `Observable`, plus
  `httpResource` for auto-refetching list pages. Every response type is an
  interface in `core/models.ts` with **snake_case** fields matching the JSON.
- Styling: Tailwind v4 with semantic tokens only (`bg-card`,
  `text-muted-foreground`, `--level-*` for status colors) — never raw colors.
  New tokens must be added to **both** `:root` and `:root.dark` in
  `src/styles.css`.
- No charting library: sparklines/stripes are hand-rolled SVG or %-positioned
  divs (`shared/sparkline.ts`, trace waterfall + `hlm-popover` hover pattern
  in `pages/trace-detail.html`, stripe row in `pages/uptime.html`).

## Wallaby and `Denied ID …?raw` errors

Wallaby serves files it saw change mid-session from `~/.wallaby/cache/**`
instead of their project paths. Vite's `server.fs.allow` check on `?raw` ids
(what `templateUrl` compiles to) rejects paths outside the allow list, so a
template edit used to wedge its specs with `Denied ID …/<file>.html?raw` until
Wallaby was restarted. **Fixed** by `vitest.wallaby.config.ts` (wired up via
`runnerConfig: true` in `angular.json`), which adds `~/.wallaby` to
`server.fs.allow` — template edits with any tool are now safe while Wallaby
runs.

If the error ever reappears, those two files are the first place to look; it is
never a code bug (`npx tsc --noEmit` and `ng build` will pass), and a Wallaby
restart clears it.

## Testing

Drive tests through Wallaby, not the CLI (`npx vitest` / `ng test` fail here
with "JIT compilation failed for BrowserXhr").

Specs are `*.spec.ts` colocated in `pages/`, using `@testing-library/angular`

- `user-event` + MSW (`src/mocks/node.ts`); provide `provideHttpClient()` and
  a fake `Session` via DI (template: `pages/settings.spec.ts`).

Gotchas:

- MSW handlers must cover **every request the component's constructor makes**
  (including admin-only loads) — a missing handler rejects the fetch and fails
  unrelated assertions.
- `hlm-native-select` puts the labeled `id` on a non-labellable wrapper, so
  `getByLabelText` cannot reach the inner `<select>` — query it via the
  wrapper instead (see the `nativeSelect()` helper in `pages/settings.spec.ts`).
- `getByText` on short labels (e.g. "1m") can collide with `<option>` text;
  scope table assertions with `within(screen.getByRole('table'))`.
- Run `npx prettier --check` on touched files; ESLint (angular-eslint) is
  enforced.
