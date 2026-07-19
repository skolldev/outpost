# Server conventions

Spring Boot 4, Java 25, servlet MVC. Plain JDBC — **no ORM, no Bean
Validation**; SQL is written inline with `JdbcClient`.

## Code style

- Tabs for indentation. Records over classes. Javadoc references the design
  spec by section (`§9.3`) where applicable.
- DTOs are `record`s declared inside the controller that uses them.

## Database

- Postgres + Flyway. New tables = next `V<N>__<name>.sql` in
  `src/main/resources/db/migration/`. Conventions: `bigint GENERATED ALWAYS AS
  IDENTITY PRIMARY KEY`, `timestamptz NOT NULL DEFAULT now()`, FK `ON DELETE
  CASCADE`, `CHECK` constraints for enum-like columns, explicit indexes.
- High-volume time-series tables (`event`, `log_record`, `txn`, `span`) are
  weekly range-partitioned; partitions are created at runtime by
  `db/PartitionManager` — a new partitioned table must be added to
  `PartitionManager.TABLES`. Partitioned tables are **not** FK-cascaded from
  `project` (see `ProjectController.delete`). Low-volume tables stay plain.

## Controllers / API

- Exemplar: `query/ProjectController.java`. Pattern: `@RestController` under
  `/api/internal/...`, constructor-injected `JdbcClient`, inline SQL, manual
  validation returning `ResponseEntity.badRequest().body(Map.of("detail", ...))`.
- Auth: `/api/internal/**` requires a session (free via `SecurityConfig`);
  admin-only mutations get `@PreAuthorize("hasRole('ADMIN')")` per method or
  class. Ingest (`/api/{projectId}/**`) self-authenticates by DSN key;
  `/api/0/**` uses bearer-token scopes.
- Environments and releases are auto-created on ingest (upserts in
  `EventStore`), not managed via admin endpoints.

## Background work

- **No `@EnableScheduling` / `@Scheduled` anywhere — by design.** Long-running
  or periodic work is a `SmartLifecycle` component owning its executors:
  `pipeline/IngestWorkers` (worker threads), `pipeline/LogTail` (heartbeat),
  `uptime/UptimeScheduler` (poll loop + virtual-thread fan-out). Follow that
  pattern; wrap periodic bodies in try/catch so a DB hiccup can't kill the loop.
- Single-instance deployment is assumed — no cross-replica coordination beyond
  the advisory lock in `PartitionManager`.

## Integration tests

- Exemplar: `query/TraceQueryIntegrationTest.java`. Pattern: `@SpringBootTest`
  RANDOM_PORT + `@Import(TestcontainersConfiguration.class)`, admin
  credentials via `outpost.admin.email/password` test properties, a `login()`
  helper capturing the session cookie, delete-all seeding in `@BeforeEach`.
- Gotchas learned the hard way:
  - `RestTemplate`'s default request factory cannot send PATCH — set
    `JdkClientHttpRequestFactory`.
  - Endpoints returning JSON arrays can't be read into `Map.class`.
  - Background schedulers run during tests: seed rows so they aren't "due"
    (e.g. future `next_check_at`) unless the test targets the scheduler.
  - For outbound-HTTP behavior, stand up `com.sun.net.httpserver.HttpServer`
    on an ephemeral port (see `uptime/UptimeIntegrationTest`).
