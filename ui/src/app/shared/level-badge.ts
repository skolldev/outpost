import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { hlm } from '@spartan-ng/helm/utils';
import { cva } from 'class-variance-authority';

/** Canonical level buckets; every raw level string is mapped onto one of these. */
type Level = 'fatal' | 'error' | 'warn' | 'info' | 'muted';

const LEVEL_ALIASES: Record<string, Level> = {
  fatal: 'fatal',
  critical: 'fatal',
  error: 'error',
  err: 'error',
  warn: 'warn',
  warning: 'warn',
  info: 'info',
  information: 'info',
};

const levelBadge = cva('font-semibold uppercase', {
  variants: {
    level: {
      fatal: 'bg-[var(--level-fatal-bg)] text-[var(--level-fatal)] border-[var(--level-fatal-bg)]',
      error:
        'bg-[var(--level-error-bg)] text-[var(--level-error)] border-[color-mix(in_oklch,var(--level-error)_30%,transparent)]',
      warn: 'bg-[var(--level-warn-bg)] text-[var(--level-warn)] border-[color-mix(in_oklch,var(--level-warn)_30%,transparent)]',
      info: 'bg-[var(--level-info-bg)] text-[var(--level-info)] border-[color-mix(in_oklch,var(--level-info)_30%,transparent)]',
      muted: 'bg-[var(--level-muted-bg)] text-[var(--level-muted)] border-border',
    },
  },
  defaultVariants: { level: 'muted' },
});

/**
 * Semantic colored pill for log/error levels (fatal/error/warn/info/debug…).
 */
@Component({
  selector: 'app-level-badge',
  imports: [HlmBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: ` <span hlmBadge variant="outline" [class]="classes()">{{ level() }}</span> `,
})
export class LevelBadge {
  readonly level = input<string | null | undefined>();

  private readonly resolved = computed<Level>(
    () => LEVEL_ALIASES[this.level()?.toLowerCase() ?? ''] ?? 'muted',
  );

  readonly classes = computed(() => hlm(levelBadge({ level: this.resolved() })));
}
