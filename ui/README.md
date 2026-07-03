# Outpost UI

Angular frontend for Outpost. Built by the Angular CLI and packaged into the Spring Boot jar
as static resources (see the root `Dockerfile`); during development it runs standalone against
a local server on `:8080`.

Based on [angular-starter](https://github.com/skolldev/angular-starter): Tailwind, Vitest +
Testing Library + MSW, ESLint + Prettier + Knip.

## Scripts

```
pnpm start            # Dev server on localhost:4200, proxies /api to localhost:8080
pnpm build            # Production build → dist/outpost-ui
pnpm test             # Run tests via Vitest
pnpm lint             # ESLint check
pnpm prettier:check   # Check for formatting errors
pnpm prettier:fix     # Fix formatting errors
pnpm knip             # Find unused deps/exports
```
