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
  // Added by the symbolicator when source maps were missing (§6.2).
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

export interface LogFilters {
  project?: number;
  environment?: string[];
  level?: string[];
  traceId?: string;
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

export interface ApiToken {
  id: number;
  name: string;
  scopes: string[];
  created_at: string;
  // Only present on the creation response — shown once.
  token?: string;
}

export interface AppUser {
  id: number;
  email: string;
  role: 'admin' | 'member';
  created_at: string;
}

export interface IssueFilters {
  project?: number;
  environment?: string[];
  status?: string;
  query?: string;
  sort?: string;
  from?: string;
  cursor?: string;
}

// --- Tracing (§9.4) ---

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
  project?: number;
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
