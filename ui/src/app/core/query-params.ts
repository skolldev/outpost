// Query-param builders shared by the declarative httpResource list pages and the
// imperative Api service — one source of truth for how filters map to the wire.
// Array values become repeated params (environment=a&environment=b); empty /
// undefined filters are omitted so the resource request stays stable.

import {
  IssueFilters,
  LogFilters,
  TraceFilters,
  TransactionGroupDetailFilters,
  TransactionGroupFilters,
} from './models';

/** Value shape accepted by both HttpParams and httpResource's `params` field. */
export type QueryParams = Record<
  string,
  string | number | boolean | readonly string[] | readonly number[]
>;

export function issueParams(filters: IssueFilters & { cursor?: string }): QueryParams {
  const params: QueryParams = {};
  if (filters.project?.length) params['project'] = filters.project;
  if (filters.environment?.length) params['environment'] = filters.environment;
  if (filters.status) params['status'] = filters.status;
  if (filters.query) params['query'] = filters.query;
  if (filters.sort) params['sort'] = filters.sort;
  if (filters.from) params['from'] = filters.from;
  if (filters.cursor) params['cursor'] = filters.cursor;
  return params;
}

export function logParams(filters: LogFilters & { cursor?: string }): QueryParams {
  const params: QueryParams = {};
  if (filters.project?.length) params['project'] = filters.project;
  if (filters.environment?.length) params['environment'] = filters.environment;
  if (filters.level?.length) params['level'] = filters.level;
  if (filters.traceId) params['trace_id'] = filters.traceId;
  if (filters.query) params['query'] = filters.query;
  if (filters.from) params['from'] = filters.from;
  if (filters.to) params['to'] = filters.to;
  if (filters.cursor) params['cursor'] = filters.cursor;
  return params;
}

export function traceParams(filters: TraceFilters & { cursor?: string }): QueryParams {
  const params: QueryParams = {};
  if (filters.project?.length) params['project'] = filters.project;
  if (filters.environment?.length) params['environment'] = filters.environment;
  if (filters.release) params['release'] = filters.release;
  if (filters.query) params['query'] = filters.query;
  if (filters.minDuration != null) params['min_duration'] = filters.minDuration;
  if (filters.maxDuration != null) params['max_duration'] = filters.maxDuration;
  if (filters.hasErrors) params['has_errors'] = 'true';
  if (filters.from) params['from'] = filters.from;
  if (filters.to) params['to'] = filters.to;
  if (filters.cursor) params['cursor'] = filters.cursor;
  return params;
}

/**
 * No `cursor`: the leaderboard is an aggregate, which has no key to seek on, so
 * it returns a top-N and nothing else (ADR-0015). No `to` either — the global
 * range filter's upper edge is always now, and the server resolves it and echoes
 * the window it used.
 */
export function transactionGroupParams(filters: TransactionGroupFilters): QueryParams {
  const params: QueryParams = {};
  if (filters.project?.length) params['project'] = filters.project;
  if (filters.environment?.length) params['environment'] = filters.environment;
  if (filters.release) params['release'] = filters.release;
  if (filters.query) params['query'] = filters.query;
  if (filters.sort) params['sort'] = filters.sort;
  if (filters.from) params['from'] = filters.from;
  return params;
}

/**
 * The detail view's key and filters. `project` and `name` are always sent — they are
 * the identity, not a narrowing — while **`op` is omitted when the group's op is
 * null**, which is how the server is told to resolve the null-op group rather than
 * matching an op of `""`.
 */
export function transactionGroupDetailParams(filters: TransactionGroupDetailFilters): QueryParams {
  const params: QueryParams = { project: filters.project, name: filters.name };
  if (filters.op) params['op'] = filters.op;
  if (filters.environment?.length) params['environment'] = filters.environment;
  if (filters.release) params['release'] = filters.release;
  if (filters.from) params['from'] = filters.from;
  return params;
}
