// API models — property names mirror the backend JSON (snake_case).

export interface SessionUser {
  email: string;
  role: 'admin' | 'member';
}

export interface Project {
  id: number;
  slug: string;
  name: string;
  platform: string | null;
  created_at: string;
}

export interface ProjectKey {
  id: number;
  project_id: number;
  public_key: string;
  is_active: boolean;
  created_at: string;
  dsn: string;
}

export interface Issue {
  id: number;
  project_id: number;
  title: string;
  culprit: string | null;
  level: string;
  status: 'unresolved' | 'resolved';
  first_seen: string;
  last_seen: string;
  event_count: number;
  sparkline?: number[];
  users_affected?: number;
  environments?: string[];
}

export interface IssueDetail extends Issue {
  env_stats: { environment: string; event_count: number; last_seen: string }[];
}

export interface IssuePage {
  issues: Issue[];
  next_cursor: string | null;
}

export interface EventSummary {
  id: string;
  timestamp: string;
  environment: string;
  release: string | null;
  level: string | null;
  message: string | null;
  user_ident: string | null;
  trace_id: string | null;
}

export interface EventPage {
  events: EventSummary[];
  next_cursor: string | null;
}

export interface EventDetail extends EventSummary {
  project_id: number;
  issue_id: number;
  exception_type: string | null;
  symbolication_status: string;
  prev_event_id: string | null;
  next_event_id: string | null;
  // Full processed Sentry event payload (exception, breadcrumbs, tags, contexts, request, ...).
  data: SentryEventData;
}

export interface SentryEventData {
  platform?: string;
  exception?: { values?: SentryException[] };
  breadcrumbs?: { values?: Breadcrumb[] } | Breadcrumb[];
  tags?: Record<string, string>;
  user?: Record<string, unknown>;
  contexts?: Record<string, Record<string, unknown>>;
  request?: { url?: string; method?: string; headers?: Record<string, string> };
  // Added by the symbolicator when source maps were missing.
  _outpost_symbolication?: {
    status: string;
    missing: { debug_id: string; abs_path: string }[];
  };
  [key: string]: unknown;
}

export interface SentryException {
  type?: string;
  value?: string;
  module?: string;
  stacktrace?: { frames?: StackFrame[] };
  // Pre-symbolication frames, kept by the symbolicator (like Sentry).
  raw_stacktrace?: { frames?: StackFrame[] };
}

export interface StackFrame {
  filename?: string;
  abs_path?: string;
  module?: string;
  function?: string;
  lineno?: number;
  colno?: number;
  in_app?: boolean;
  context_line?: string;
  pre_context?: string[];
  post_context?: string[];
}

export interface Breadcrumb {
  timestamp?: number | string;
  type?: string;
  category?: string;
  level?: string;
  message?: string;
  data?: Record<string, unknown>;
}

export interface LogRecord {
  id: string;
  project_id: number;
  environment: string;
  timestamp: string;
  trace_id: string | null;
  span_id: string | null;
  level: string;
  severity_number: number | null;
  body: string;
  // Flattened Sentry log attributes: {"sentry.release": "x@1.0.0", "cart.size": 3, ...}
  attributes: Record<string, unknown>;
  release: string | null;
}

export interface LogPage {
  logs: LogRecord[];
  next_cursor: string | null;
}

/**
 * One bucket of the log timeline. Levels with no records are omitted, and buckets
 * with no records are absent from the response entirely — the client places what it
 * gets by index off `from` and `bucket_seconds`.
 */
export interface LogTimelineBucket {
  start: string;
  counts: Record<string, number>;
}

/** The window the server resolved, the width it drew it at, and the non-empty buckets. */
export interface LogTimeline {
  from: string;
  to: string;
  bucket_seconds: number;
  buckets: LogTimelineBucket[];
}

export interface LogFilters {
  project?: number[];
  environment?: string[];
  level?: string[];
  traceId?: string;
  release?: string;
  /** Attribute equality filters as `key=value`, at most one per key (ADR-0018). */
  attr?: string[];
  query?: string;
  from?: string;
  to?: string;
  cursor?: string;
}

export interface Release {
  id: number;
  version: string;
  created_at: string;
  bundle_count: number;
  artifact_count: number;
  issue_count: number;
}

export interface ReleaseArtifact {
  id: number;
  debug_id: string;
  artifact_type: 'source_map' | 'minified_source';
  file_path: string;
  size_bytes: number;
  bundle_checksum: string;
  uploaded_at: string;
}

/** The Scopes an API Token can carry; `artifacts:write` is Admin-only. */
export type TokenScope = 'telemetry:read' | 'artifacts:write';

export interface ApiToken {
  id: number;
  name: string;
  scopes: TokenScope[];
  created_at: string;
  // A Personal Token has an owner and dies with that account (ADR-0017); an
  // Installation Token has neither field set and outlives everybody.
  owner_user_id: number | null;
  owner_email: string | null;
  // Only present on the creation response — shown once.
  token?: string;
  // Also creation-only: the MCP Surface URL of this installation, which the
  // browser cannot derive (a reverse-proxy sub-path is invisible to origin).
  mcp_url?: string;
}

export interface AppUser {
  id: number;
  email: string;
  role: 'admin' | 'member';
  created_at: string;
}

export type RetentionDays = 30 | 60 | 90 | 180;

export interface DataRetentionSetting {
  enabled: boolean;
  retention_days: RetentionDays;
}

export interface IssueFilters {
  project?: number[];
  environment?: string[];
  status?: string;
  query?: string;
  sort?: string;
  from?: string;
  cursor?: string;
}

/** A row in the trace search list — one distributed trace, keyed by trace_id. */
export interface TraceSummary {
  id: string;
  project_id: number;
  environment: string;
  release: string | null;
  trace_id: string;
  name: string;
  op: string | null;
  start_ts: string;
  end_ts: string;
  duration_ms: number;
  status: string | null;
  span_count: number;
  error_count: number;
}

export interface TracePage {
  traces: TraceSummary[];
  next_cursor: string | null;
}

export interface TraceFilters {
  project?: number[];
  environment?: string[];
  release?: string;
  query?: string;
  minDuration?: number;
  maxDuration?: number;
  hasErrors?: boolean;
  from?: string;
  to?: string;
  cursor?: string;
}

/** A transaction (root span of one service's slice of the trace). */
export interface TraceTransaction {
  id: string;
  project_id: number;
  environment: string;
  release: string | null;
  trace_id: string;
  span_id: string;
  parent_span_id: string | null;
  name: string;
  op: string | null;
  start_ts: string;
  end_ts: string;
  duration_ms: number;
  status: string | null;
  data: Record<string, unknown>;
}

/** A child span within a transaction. */
export interface TraceSpan {
  id: string;
  txn_id: string;
  project_id: number;
  trace_id: string;
  span_id: string;
  parent_span_id: string | null;
  op: string | null;
  description: string | null;
  start_ts: string;
  end_ts: string;
  duration_ms: number;
  status: string | null;
  data: Record<string, unknown>;
}

/** An error event pinned to the trace by trace_id. */
export interface TraceError {
  id: string;
  project_id: number;
  issue_id: number;
  environment: string;
  timestamp: string;
  span_id: string | null;
  level: string | null;
  message: string | null;
  exception_type: string | null;
}

/** Full cross-project trace payload from GET /traces/{trace_id}. */
export interface TraceDetail {
  trace_id: string;
  transactions: TraceTransaction[];
  spans: TraceSpan[];
  errors: TraceError[];
  logs: LogRecord[];
}

/**
 * One Transaction Group on the Performance leaderboard — the recurring activity
 * that Transactions sharing a Project, name and op are instances of.
 * Figures describe only Transactions Outpost received, not requests served, and
 * are never extrapolated (ADR-0014); failures are reported by Issues, not here.
 */
export interface TransactionGroup {
  project_id: number;
  name: string;
  op: string | null;
  count: number;
  total_ms: number;
  avg_ms: number;
  max_ms: number;
  p50_ms: number;
  p95_ms: number;
  p99_ms: number;
}

/**
 * The leaderboard plus the window it was actually computed over. `range_clamped`
 * marks when the server narrowed the request to the 30-day cap (ADR-0015).
 * `distinct_groups` counts every group in the window, including ones not
 * returned — a Project with unparameterized URLs will show a huge count here
 * while `groups` stays short.
 */
export interface TransactionGroupPage {
  from: string;
  to: string;
  range_clamped: boolean;
  distinct_groups: number;
  truncated: boolean;
  groups: TransactionGroup[];
}

/** Server-side whitelist — anything else is rejected rather than coerced, so this stays a union rather than a bare string. */
export type TransactionGroupSort = 'total_ms' | 'p95' | 'p50' | 'count';

export interface TransactionGroupFilters {
  project?: number[];
  environment?: string[];
  release?: string;
  query?: string;
  sort?: TransactionGroupSort;
  from?: string;
}

/**
 * One bucket of the duration trend. `p99_ms` is omitted here — over one bucket
 * it's computed from too few samples to be meaningful — and `count` says how
 * much a point is worth: a bucket of four Transactions has a p95 that's one of them.
 */
export interface TransactionGroupTrendPoint {
  start: string;
  count: number;
  p50_ms: number;
  p95_ms: number;
}

/**
 * The bucketed series behind the detail view's chart. `from` here is the window's
 * start floored onto the bucket grid, not the response's `from` — points are
 * placed by `(start - trend.from) / bucket_seconds`, and empty buckets are
 * omitted rather than zero since a bucket with no Transactions has no p50.
 */
export interface TransactionGroupTrend {
  from: string;
  bucket_seconds: number;
  points: TransactionGroupTrendPoint[];
}

/**
 * One Transaction Group's statistics for the detail view a leaderboard row opens
 * into — same figures, same window, so `range_clamped` here can't disagree with
 * what the row showed. The trend rides along rather than a separate endpoint,
 * since this view never paginates and so never refetches without the chart.
 */
export interface TransactionGroupDetail {
  from: string;
  to: string;
  range_clamped: boolean;
  group: TransactionGroup;
  trend: TransactionGroupTrend;
}

/** A missing group still echoes the effective window so a clamp is never silent. */
export interface TransactionGroupDetailNotFound {
  detail: string;
  from: string;
  to: string;
  range_clamped: boolean;
}

/**
 * The identity of one Transaction Group plus the filters its statistics are
 * computed under. `name` matches exactly (the leaderboard's `query` is how the
 * group is found, not identified); an absent `op` means `op` is null, since
 * (project, name, op) is the whole key.
 */
export interface TransactionGroupDetailFilters {
  project: number;
  name: string;
  op?: string | null;
  environment?: string[];
  release?: string;
  from?: string;
}

export interface UptimeMonitor {
  id: number;
  project_id: number;
  project_slug: string;
  environment: string;
  url: string;
  interval_seconds: number;
  timeout_seconds: number;
  consecutive_failures: number;
  created_at: string;
}

export interface UptimeTestResult {
  success: boolean;
  status_code: number | null;
  latency_ms: number;
  error: string | null;
}

export interface UptimeDay {
  date: string;
  total: number;
  failures: number;
  uptime_pct: number;
  avg_latency_ms: number | null;
}

export interface UptimeOpenIncident {
  id: number;
  monitor_id: number;
  opened_at: string;
  last_error: string | null;
}

export interface UptimeMonitorOverview {
  id: number;
  project_id: number;
  project_slug: string;
  environment: string;
  url: string;
  interval_seconds: number;
  status: 'up' | 'down' | 'unknown';
  open_incident: UptimeOpenIncident | null;
  days: UptimeDay[];
}

export interface UptimeOverview {
  monitors: UptimeMonitorOverview[];
}

export type NotificationChannelType = 'teams' | 'generic_json';
export type NotificationTrigger = 'new_issue' | 'incident_started' | 'incident_resolved';

export interface NotificationChannel {
  id: number;
  name: string;
  type: NotificationChannelType;
  url: string;
  enabled: boolean;
  triggers: NotificationTrigger[];
  // Empty project_filter = all Projects; empty environment_filter = all Environments.
  project_filter: number[];
  environment_filter: string[];
  created_at: string;
  // Last delivery outcome for this channel, or null before anything was sent.
  last_status: NotificationDeliveryStatus | null;
  last_delivery_at: string | null;
}

/** Delivery lifecycle of one Notification: pending → sent/failed (suppressed = rate-capped). */
export type NotificationDeliveryStatus = 'pending' | 'sent' | 'failed' | 'suppressed';

/** The trigger recorded on a history row — the channel triggers plus the test action. */
export type NotificationHistoryTrigger = NotificationTrigger | 'test';

export interface NotificationHistoryEntry {
  id: number;
  trigger_type: NotificationHistoryTrigger;
  status: NotificationDeliveryStatus;
  summary: string;
  error_detail: string | null;
  created_at: string;
  updated_at: string;
}

/** Inline outcome of an Admin test-send. */
export interface NotificationTestResult {
  status: 'sent' | 'failed';
  error_detail: string | null;
}

/** Create/update payload — the server-managed id and created_at are omitted. */
export interface NotificationChannelInput {
  name: string;
  type: NotificationChannelType;
  url: string;
  enabled: boolean;
  triggers: NotificationTrigger[];
  project_filter: number[];
  environment_filter: string[];
}
