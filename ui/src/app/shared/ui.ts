/** Compact relative time: "3m ago", "2h ago", "5d ago". */
export function timeAgo(iso: string): string {
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}

export function levelClass(level: string | null | undefined): string {
  switch (level) {
    case 'fatal':
      return 'bg-red-600 text-white';
    case 'error':
      return 'bg-red-500/20 text-red-300 border border-red-500/40';
    case 'warn':
    case 'warning':
      return 'bg-amber-500/20 text-amber-300 border border-amber-500/40';
    case 'info':
      return 'bg-sky-500/20 text-sky-300 border border-sky-500/40';
    case 'trace':
    case 'debug':
      return 'bg-slate-600/30 text-slate-300 border border-slate-600';
    default:
      return 'bg-slate-600/30 text-slate-300 border border-slate-600';
  }
}
