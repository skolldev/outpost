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
 * Deterministic oklch color for a project, used to color-code trace spans by
 * project (§9.4). The hue is derived from the project id via the golden-angle
 * so adjacent ids stay visually distinct; lightness/chroma are fixed so the
 * palette reads as one system in both themes (cf. the `--level-*` tokens).
 */
export function projectColor(projectId: number): string {
  const hue = (projectId * 137.508) % 360;
  return `oklch(0.62 0.17 ${hue.toFixed(1)})`;
}
