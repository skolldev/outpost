/** Compact relative time: "3m ago", "2h ago", "5d ago". */
export function timeAgo(iso: string): string {
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}

/** Human duration for span/trace timings: "820µs", "12ms", "1.4s". */
export function formatDuration(ms: number): string {
  if (ms < 1) return `${Math.round(ms * 1000)}µs`;
  if (ms < 1000) return `${ms < 10 ? ms.toFixed(1) : Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)}s`;
}

/**
 * A duration that is a *sum* rather than one measurement, where `formatDuration`
 * would print "312.4s". Both Performance surfaces show the same total for the same
 * Transaction Group, so they format it the same way from here.
 */
export function formatTotalDuration(ms: number): string {
  return ms >= 60_000 ? `${(ms / 60_000).toFixed(1)}min` : formatDuration(ms);
}

const PROJECT_COLORS = [
  'var(--project-1)',
  'var(--project-2)',
  'var(--project-3)',
  'var(--project-4)',
  'var(--project-5)',
  'var(--project-6)',
  'var(--project-7)',
  'var(--project-8)',
] as const;

/** Deterministic semantic project color, resolved separately for each theme. */
export function projectColor(projectId: number): string {
  return PROJECT_COLORS[Math.abs(projectId) % PROJECT_COLORS.length];
}
