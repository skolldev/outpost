import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { HlmBadge } from '@spartan-ng/helm/badge';

interface LevelStyle {
  bg: string;
  fg: string;
  border: string;
}

/**
 * Semantic colored pill for log/error levels (fatal/error/warn/info/debug…),
 */
@Component({
  selector: 'app-level-badge',
  imports: [HlmBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      hlmBadge
      variant="outline"
      class="font-semibold uppercase"
      [style.background-color]="style().bg"
      [style.color]="style().fg"
      [style.border-color]="style().border"
    >
      {{ level() }}
    </span>
  `,
})
export class LevelBadge {
  readonly level = input<string | null | undefined>();

  private static readonly STYLES: Record<string, LevelStyle> = {
    fatal: {
      bg: 'var(--level-fatal-bg)',
      fg: 'var(--level-fatal)',
      border: 'var(--level-fatal-bg)',
    },
    error: {
      bg: 'var(--level-error-bg)',
      fg: 'var(--level-error)',
      border: 'color-mix(in oklch, var(--level-error) 30%, transparent)',
    },
    warn: {
      bg: 'var(--level-warn-bg)',
      fg: 'var(--level-warn)',
      border: 'color-mix(in oklch, var(--level-warn) 30%, transparent)',
    },
    info: {
      bg: 'var(--level-info-bg)',
      fg: 'var(--level-info)',
      border: 'color-mix(in oklch, var(--level-info) 30%, transparent)',
    },
    muted: {
      bg: 'var(--level-muted-bg)',
      fg: 'var(--level-muted)',
      border: 'var(--border)',
    },
  };

  readonly style = computed<LevelStyle>(() => {
    switch (this.level()) {
      case 'fatal':
        return LevelBadge.STYLES['fatal'];
      case 'error':
        return LevelBadge.STYLES['error'];
      case 'warn':
      case 'warning':
        return LevelBadge.STYLES['warn'];
      case 'info':
        return LevelBadge.STYLES['info'];
      default:
        return LevelBadge.STYLES['muted'];
    }
  });
}
