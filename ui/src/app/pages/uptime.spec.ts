import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import { UptimeMonitorOverview } from '../core/models';
import { UptimePage } from './uptime';

const BASE = '*/api/internal';

/** UTC date string N days before today — matches the component's stripe math. */
function utcDaysAgo(days: number): string {
  const now = new Date();
  const todayUtc = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  return new Date(todayUtc - days * 86_400_000).toISOString().slice(0, 10);
}

function monitor(overrides: Partial<UptimeMonitorOverview> = {}): UptimeMonitorOverview {
  return {
    id: 1,
    project_id: 1,
    project_slug: 'shop-frontend',
    environment: 'prod',
    url: 'https://shop.example.com/health',
    interval_seconds: 60,
    status: 'up',
    open_incident: null,
    days: [
      { date: utcDaysAgo(1), total: 1440, failures: 0, uptime_pct: 100, avg_latency_ms: 87 },
      { date: utcDaysAgo(0), total: 720, failures: 180, uptime_pct: 75, avg_latency_ms: 120 },
    ],
    ...overrides,
  };
}

function seedOverview(monitors: UptimeMonitorOverview[]) {
  server.use(http.get(`${BASE}/uptime/overview`, () => HttpResponse.json({ monitors })));
}

async function renderUptime() {
  return render(UptimePage, { providers: [provideHttpClient()] });
}

describe('UptimePage', () => {
  it('shows an empty state when no monitors exist', async () => {
    seedOverview([]);
    await renderUptime();

    expect(await screen.findByText(/No uptime monitors configured yet/)).toBeInTheDocument();
  });

  it('renders one row per monitor with 90 stripes', async () => {
    seedOverview([monitor(), monitor({ id: 2, url: 'https://api.example.com/health' })]);
    await renderUptime();

    expect(await screen.findByText('https://shop.example.com/health')).toBeInTheDocument();
    expect(screen.getByText('https://api.example.com/health')).toBeInTheDocument();
    const stripes = screen.getAllByRole('button', { name: /^Uptime \d{4}-\d{2}-\d{2}$/ });
    expect(stripes).toHaveLength(180);
  });

  it('shows an open incident card', async () => {
    seedOverview([
      monitor({
        status: 'down',
        open_incident: {
          id: 9,
          monitor_id: 1,
          opened_at: '2026-07-19T05:00:00Z',
          last_error: 'HTTP 503',
        },
      }),
    ]);
    await renderUptime();

    expect(await screen.findByText('Down')).toBeInTheDocument();
    expect(screen.getByText('HTTP 503')).toBeInTheDocument();
    expect(screen.getByText(/since/)).toBeInTheDocument();
  });

  it('opens a popover with day details on stripe click', async () => {
    seedOverview([monitor()]);
    await renderUptime();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: `Uptime ${utcDaysAgo(0)}` }));

    await waitFor(() => expect(screen.getByText('75% uptime')).toBeInTheDocument());
    expect(screen.getByText('180 failed / 720 checks')).toBeInTheDocument();
    expect(screen.getByText('avg 120 ms')).toBeInTheDocument();
  });

  it('shows "No checks" for days without data', async () => {
    seedOverview([monitor()]);
    await renderUptime();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: `Uptime ${utcDaysAgo(45)}` }));

    await waitFor(() => expect(screen.getByText('No checks')).toBeInTheDocument());
  });
});
