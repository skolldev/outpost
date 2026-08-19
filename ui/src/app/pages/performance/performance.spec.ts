import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor, within } from '@testing-library/angular';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { GlobalFilters } from '../../core/filters';
import { Project, TransactionGroup, TransactionGroupPage } from '../../core/models';
import { PerformancePage } from './performance';

const BASE = '*/api/internal';

const PROJECTS: Project[] = [
  {
    id: 1,
    slug: 'shop-backend',
    name: 'shop-backend',
    platform: 'java',
    created_at: '2026-06-01T00:00:00Z',
  },
];

const CHECKOUT: TransactionGroup = {
  project_id: 1,
  name: 'GET /api/checkout/{id}',
  op: 'http.server',
  count: 12401,
  total_ms: 522521,
  avg_ms: 42.1,
  max_ms: 4100,
  p50_ms: 42,
  p95_ms: 310,
  p99_ms: 1200,
};

const NO_OP: TransactionGroup = {
  project_id: 1,
  name: 'process outbox',
  op: null,
  count: 40,
  total_ms: 900,
  avg_ms: 22.5,
  max_ms: 60,
  p50_ms: 20,
  p95_ms: 55,
  p99_ms: 59,
};

/** The cardinality threshold the page warns above, restated so a change to it fails here. */
const HIGH_CARDINALITY_GROUPS = 5_000;

function page(
  groups: TransactionGroup[],
  overrides: Partial<TransactionGroupPage> = {},
): TransactionGroupPage {
  return {
    from: '2026-07-20T00:00:00Z',
    to: '2026-08-19T00:00:00Z',
    range_clamped: false,
    distinct_groups: groups.length,
    truncated: false,
    groups,
    ...overrides,
  };
}

function fakeFilters(from?: string): GlobalFilters {
  return {
    project: signal<number[]>([]),
    environments: signal<string[]>([]),
    from: signal<string | undefined>(from),
  } as unknown as GlobalFilters;
}

async function renderPerformance(filters: GlobalFilters = fakeFilters()) {
  // The constructor also loads projects for the legend; without a handler that
  // fetch rejects and fails assertions that have nothing to do with it.
  server.use(http.get(`${BASE}/projects`, () => HttpResponse.json(PROJECTS)));
  return render(PerformancePage, {
    providers: [
      provideHttpClient(),
      provideRouter([]),
      { provide: GlobalFilters, useValue: filters },
    ],
  });
}

describe('PerformancePage', () => {
  it('lists a row per Transaction Group with its statistics', async () => {
    server.use(http.get(`${BASE}/transaction-groups`, () => HttpResponse.json(page([CHECKOUT]))));
    await renderPerformance();

    await screen.findByText('GET /api/checkout/{id}');
    // Scoped to the table: short cells like "42ms" collide with prose elsewhere.
    const table = within(screen.getByRole('table'));
    expect(table.getByText('shop-backend')).toBeInTheDocument();
    expect(table.getByText('http.server')).toBeInTheDocument();
    expect(table.getByText('12,401')).toBeInTheDocument();
    // p50 42ms, p95 310ms, p99 1.20s, avg 42.1ms, max 4.10s.
    expect(table.getByText('310ms')).toBeInTheDocument();
    expect(table.getByText('1.20s')).toBeInTheDocument();
    expect(table.getByText('4.10s')).toBeInTheDocument();
  });

  it('renders a group with no op rather than dropping it', async () => {
    server.use(http.get(`${BASE}/transaction-groups`, () => HttpResponse.json(page([NO_OP]))));
    await renderPerformance();

    await screen.findByText('process outbox');
    expect(within(screen.getByRole('table')).getByText('no op')).toBeInTheDocument();
  });

  it('shows the empty state when no Transaction Groups match', async () => {
    server.use(http.get(`${BASE}/transaction-groups`, () => HttpResponse.json(page([]))));
    await renderPerformance();

    expect(
      await screen.findByText(/No Transactions match the current filters/),
    ).toBeInTheDocument();
  });

  it('shows a load error instead of claiming that no Transactions match', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups`, () =>
        HttpResponse.json({ detail: 'failed' }, { status: 500 }),
      ),
    );
    await renderPerformance();

    expect(await screen.findByRole('alert')).toHaveTextContent('Performance unavailable');
    expect(screen.queryByText(/No Transactions match the current filters/)).not.toBeInTheDocument();
  });

  it('renders distinct groups whose tuple keys would collide when joined with spaces', async () => {
    const first = { ...CHECKOUT, name: 'alpha beta', op: 'gamma' };
    const second = { ...CHECKOUT, name: 'alpha', op: 'beta gamma' };
    server.use(
      http.get(`${BASE}/transaction-groups`, () => HttpResponse.json(page([first, second]))),
    );
    await renderPerformance();

    const table = within(screen.getByRole('table'));
    expect(await table.findByText('alpha beta')).toBeInTheDocument();
    expect(table.getByText('alpha')).toBeInTheDocument();
  });

  it('says so when the server clamped the range to 30 days', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups`, () =>
        HttpResponse.json(page([CHECKOUT], { range_clamped: true })),
      ),
    );
    await renderPerformance();

    // By text, not by role: the loading spinner is also a `status` region.
    expect(await screen.findByText(/Showing the last 30 days/i)).toBeInTheDocument();
  });

  it('raises no clamp notice when the window was answered as asked', async () => {
    server.use(http.get(`${BASE}/transaction-groups`, () => HttpResponse.json(page([CHECKOUT]))));
    await renderPerformance();

    await screen.findByText('GET /api/checkout/{id}');
    expect(screen.queryByText(/Showing the last 30 days/i)).not.toBeInTheDocument();
  });

  it('warns about name cardinality above the threshold, naming unparameterized URLs', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups`, () =>
        HttpResponse.json(page([CHECKOUT], { distinct_groups: HIGH_CARDINALITY_GROUPS + 1 })),
      ),
    );
    await renderPerformance();

    expect(await screen.findByText(/5,001 distinct Transaction Groups/)).toBeInTheDocument();
    // The count alone is not actionable; the cause and where to fix it are the point.
    expect(screen.getByText(/unparameterized URLs/)).toBeInTheDocument();
    expect(screen.getByText(/routing integration/)).toBeInTheDocument();
  });

  it('raises no cardinality warning for a normally instrumented Project', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups`, () =>
        HttpResponse.json(page([CHECKOUT], { distinct_groups: HIGH_CARDINALITY_GROUPS })),
      ),
    );
    await renderPerformance();

    await screen.findByText('GET /api/checkout/{id}');
    expect(screen.queryByText(/distinct Transaction Groups/)).not.toBeInTheDocument();
  });

  /**
   * The warning outlives the table. A Project emitting a Transaction Group per URL has
   * groups of one or two Transactions, which the sample floor excludes — so the list
   * comes back empty in exactly the case the explanation is needed.
   */
  it('still warns about cardinality when every group fell below the sample floor', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups`, () =>
        HttpResponse.json(page([], { distinct_groups: 412_903 })),
      ),
    );
    await renderPerformance();

    expect(await screen.findByText(/412,903 distinct Transaction Groups/)).toBeInTheDocument();
    expect(screen.getByText(/No Transactions match the current filters/)).toBeInTheDocument();
  });

  /**
   * The notice counts the rows it is standing next to rather than naming the server's
   * cap, which is not on the wire and would drift silently the day the cap moved. Two
   * groups here, so it says two — no real response pairs `truncated` with a short list.
   */
  it('says the list was cut, and how much of it is on screen', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups`, () =>
        HttpResponse.json(page([CHECKOUT, NO_OP], { truncated: true })),
      ),
    );
    await renderPerformance();

    expect(await screen.findByText(/Showing the top 2 Transaction Groups/)).toBeInTheDocument();
    expect(screen.getByText(/no next page/)).toBeInTheDocument();
  });

  it('raises no truncation notice when the whole list fits', async () => {
    server.use(http.get(`${BASE}/transaction-groups`, () => HttpResponse.json(page([CHECKOUT]))));
    await renderPerformance();

    await screen.findByText('GET /api/checkout/{id}');
    expect(screen.queryByText(/Showing the top/)).not.toBeInTheDocument();
  });

  it('carries the global time-range filter into the request', async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get(`${BASE}/transaction-groups`, ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('from'));
        return HttpResponse.json(page([CHECKOUT]));
      }),
    );
    await renderPerformance(fakeFilters('2026-08-05T00:00:00Z'));

    await waitFor(() => expect(seen).toContain('2026-08-05T00:00:00Z'));
  });
});
