import { LogRecord } from '../../core/models';

/**
 * One chip on the logs page: a field of a Log Record and the value it must equal.
 *
 * `trace_id` and `release` are columns and travel as their own query params; every
 * other key is an Attribute and travels as `attr=key=value`. The person filtering
 * has no reason to know which is which, so a chip does not say.
 */
export interface FieldFilter {
  key: string;
  value: string;
}

/**
 * The first-class fields a chip can name, each with how to read it off a record —
 * the one place that knows a filter key may be a column rather than an Attribute.
 */
const RECORD_FIELDS: Record<string, (record: LogRecord) => unknown> = {
  trace_id: (record) => record.trace_id,
  release: (record) => record.release,
};

/**
 * Attributes the SDK sets that restate a first-class field. Filtering on one would be
 * a second, subtly different spelling of a filter the page already has, so they are
 * not suggested — typing one still works.
 */
const SDK_ALIASES_OF_RECORD_FIELDS = new Set([
  'sentry.environment',
  'sentry.release',
  'sentry.trace.parent_span_id',
]);

const SDK_PREFIX = 'sentry.';

const MAX_VALUE_SUGGESTIONS = 50;

/** Whether `key` is a column of a Log Record, which travels as its own param rather than as `attr`. */
export function isRecordField(key: string): boolean {
  return Object.hasOwn(RECORD_FIELDS, key);
}

/**
 * Whether a filter can be built on `key`. `attr=key=value` is split on its first `=`,
 * so a key containing one would read back as a different key.
 */
export function isFilterableKey(key: string): boolean {
  return key.length > 0 && !key.includes('=');
}

/** Only a scalar can equal a filter's text (ADR-0018), so only scalars are offered or clickable. */
export function isFilterable(value: unknown): value is string | number | boolean {
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean';
}

/**
 * `attr` params as filters, one per key. A hand-typed URL can repeat a key or omit
 * the `=`; the last value wins and a bare key is dropped, since the page offers no
 * presence filter.
 */
export function parseAttr(raw: readonly string[]): FieldFilter[] {
  const byKey = new Map<string, string>();
  for (const entry of raw) {
    const eq = entry.indexOf('=');
    if (eq <= 0) continue;
    const key = entry.slice(0, eq);
    byKey.delete(key);
    byKey.set(key, entry.slice(eq + 1));
  }
  return [...byKey].map(([key, value]) => ({ key, value }));
}

/** The inverse of {@link parseAttr}: filters as `attr` params. */
export function formatAttr(filters: readonly FieldFilter[]): string[] {
  return filters.map(({ key, value }) => `${key}=${value}`);
}

/**
 * Keys worth offering, from the records already loaded: the first-class fields,
 * then the Project's own Attributes by how many records carry them, then the SDK's
 * `sentry.*` bookkeeping. Free, and blind to keys no loaded record carries.
 */
export function keySuggestions(records: readonly LogRecord[]): string[] {
  const counts = new Map<string, number>();
  for (const record of records) {
    for (const [key, value] of Object.entries(record.attributes)) {
      if (SDK_ALIASES_OF_RECORD_FIELDS.has(key) || !isFilterable(value)) continue;
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
  }
  const keys = byFrequency(counts);
  return [
    ...Object.keys(RECORD_FIELDS),
    ...keys.filter((key) => !key.startsWith(SDK_PREFIX)),
    ...keys.filter((key) => key.startsWith(SDK_PREFIX)),
  ];
}

/** Values the loaded records carry for `key`, most common first. */
export function valueSuggestions(records: readonly LogRecord[], key: string): string[] {
  if (!key) return [];
  const read = isRecordField(key)
    ? RECORD_FIELDS[key]
    : (record: LogRecord) => record.attributes[key];
  const counts = new Map<string, number>();
  for (const record of records) {
    const value = read(record);
    if (!isFilterable(value)) continue;
    const text = String(value);
    counts.set(text, (counts.get(text) ?? 0) + 1);
  }
  return byFrequency(counts).slice(0, MAX_VALUE_SUGGESTIONS);
}

function byFrequency(counts: Map<string, number>): string[] {
  return [...counts.keys()].sort((a, b) => counts.get(b)! - counts.get(a)! || a.localeCompare(b));
}
