import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { HlmCard } from '@spartan-ng/helm/card';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmPopoverImports } from '@spartan-ng/helm/popover';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmAlert, HlmAlertDescription, HlmAlertTitle } from '@spartan-ng/helm/alert';

import { Api } from '../core/api';
import { UptimeDay, UptimeMonitorOverview } from '../core/models';

const DAY_MS = 86_400_000;
const WINDOW_DAYS = 90;

interface Stripe {
  date: string;
  day: UptimeDay | null;
  color: string;
}

interface MonitorRow {
  monitor: UptimeMonitorOverview;
  stripes: Stripe[];
}

/**
 * Uptime status page: one row per monitor with 90 daily stripes (fixed UTC
 * window, independent of the global filters) plus open incidents on top.
 */
@Component({
  selector: 'app-uptime',
  imports: [
    DatePipe,
    HlmCard,
    HlmSpinner,
    HlmPopoverImports,
    HlmButton,
    HlmAlert,
    HlmAlertTitle,
    HlmAlertDescription,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './uptime.html',
})
export class UptimePage {
  private readonly api = inject(Api);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly monitors = signal<UptimeMonitorOverview[]>([]);

  readonly rows = computed<MonitorRow[]>(() =>
    this.monitors().map((monitor) => ({ monitor, stripes: this.buildStripes(monitor.days) })),
  );

  readonly incidents = computed(() =>
    this.monitors().filter((monitor) => monitor.open_incident !== null),
  );

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const overview = await firstValueFrom(this.api.uptimeOverview());
      this.monitors.set(overview.monitors);
    } catch {
      this.error.set('Could not load uptime data.');
    } finally {
      this.loading.set(false);
    }
  }

  statusColor(status: UptimeMonitorOverview['status']): string {
    if (status === 'up') return 'var(--level-success)';
    if (status === 'down') return 'var(--level-error)';
    return 'var(--level-muted)';
  }

  intervalLabel(seconds: number): string {
    return seconds < 60 ? `${seconds}s` : `${seconds / 60}m`;
  }

  private buildStripes(days: UptimeDay[]): Stripe[] {
    const byDate = new Map(days.map((day) => [day.date, day]));
    const now = new Date();
    const todayUtc = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
    const stripes: Stripe[] = [];
    for (let i = WINDOW_DAYS - 1; i >= 0; i--) {
      const date = new Date(todayUtc - i * DAY_MS).toISOString().slice(0, 10);
      const day = byDate.get(date) ?? null;
      stripes.push({ date, day, color: this.stripeColor(day) });
    }
    return stripes;
  }

  private stripeColor(day: UptimeDay | null): string {
    if (day === null) return 'var(--level-muted-bg)';
    if (day.failures === 0) return 'var(--level-success)';
    if (day.uptime_pct >= 98) return 'var(--level-warn)';
    return 'var(--level-error)';
  }
}
