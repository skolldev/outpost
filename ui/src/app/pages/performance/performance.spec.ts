import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { GlobalFilters } from '../../core/filters';
import { Project, Release, TransactionGroup, TransactionGroupPage } from '../../core/models';
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

const SECOND_PROJECT: Project = {
  id: 2,
  slug: 'shop-frontend',
  name: 'shop-frontend',
  platform: 'javascript',
  created_at: '2026-06-01T00:00:00Z',
};

const RELEASES: Release[] = [
  {
    id: 1,
    version: 'shop@2.0.0',
    created_at: '2026-08-01T00:00:00Z',
    bundle_count: 0,
    artifact_count: 0,
    issue_count: 0,
  },
  {
    id: 2,
    version: 'shop@1.0.0',
    created_at: '2026-07-01T00:00:00Z',
    bundle_count: 0,
    artifact_count: 0,
    issue_count: 0,
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

function fakeFilters(from?: string, project: number[] = []): GlobalFilters {
  return {
    project: signal<number[]>(project),
    environments: signal<string[]>([]),
    from: signal<string | undefined>(from),
  } as unknown as GlobalFilters;
}

async function renderPerformance(
  filters: GlobalFilters = fakeFilters(),
  projects: Project[] = PROJECTS,
  // Sort and Release are read from the URL, so a shared link is set up by arriving at one.
  queryParams: Record<string, string> = {},
) {
  // The constructor also loads projects for the legend, and the Releases of the one
  // Project the Release filter resolves to; without handlers those fetches reject and
  // fail assertions that have nothing to do with them.
  server.use(http.get(`${BASE}/projects`, () => HttpResponse.json(projects)));
  server.use(http.get(`${BASE}/releases`, () => HttpResponse.json(RELEASES)));
  // Joined by hand rather than through URLSearchParams: a version like `shop@2.0.0` is
  // legal unencoded in a query string, and percent-encoding it here arrives as a
  // literal `%40` in the param the page reads back.
  const query = Object.entries(queryParams)
    .map(([key, value]) => `${key}=${value}`)
    .join('&');
  return render(PerformancePage, {
    providers: [
      provideHttpClient(),
      provideRouter([]),
      { provide: GlobalFilters, useValue: filters },
    ],
    routes: [{ path: '', children: [] }],
    initialRoute: query ? `/?${query}` : '/',
  });
}

/** Records the value of one query param on every leaderboard request the page issues. */
function recordParam(param: string, body: TransactionGroupPage): (string | null)[] {
  const seen: (string | null)[] = [];
  server.use(
    http.get(`${BASE}/transaction-groups`, ({ request }) => {
      seen.push(new URL(request.url).searchParams.get(param));
      return HttpResponse.json(body);
    }),
  );
  return seen;
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

  it('ranks by total time until asked for another ranking', async () => {
    const seen = recordParam('sort', page([CHECKOUT]));
    await renderPerformance();
    const user = userEvent.setup();
    await screen.findByText('GET /api/checkout/{id}');
    expect(seen).toEqual(['total_ms']);

    await user.selectOptions(screen.getByLabelText('Sort Transaction Groups'), 'p95');

    await waitFor(() => expect(seen).toContain('p95'));
  });

  /**
   * Every ranking the control offers is one the server accepts. The values are its
   * whitelist — it rejects anything else outright rather than falling back to the
   * default — so an option added to the page without its counterpart on the server
   * would produce a 400 rather than a differently sorted list.
   */
  it('asks for every ranking it offers', async () => {
    const seen = recordParam('sort', page([CHECKOUT]));
    await renderPerformance();
    const user = userEvent.setup();
    await screen.findByText('GET /api/checkout/{id}');
    const sort = screen.getByLabelText('Sort Transaction Groups');

    for (const value of ['p95', 'p50', 'count', 'total_ms']) {
      await user.selectOptions(sort, value);
      await waitFor(() => expect(seen).toContain(value));
    }
  });

  it('debounces the search box into the request', async () => {
    const seen = recordParam('query', page([CHECKOUT]));
    await renderPerformance();
    const user = userEvent.setup();
    await screen.findByText('GET /api/checkout/{id}');

    await user.type(screen.getByPlaceholderText(/search by transaction name/i), 'cart');

    await waitFor(() => expect(seen).toContain('cart'));
    // Debounced: the intermediate keystrokes must not each fire a request.
    expect(seen.filter((q) => q && q !== 'cart' && 'cart'.startsWith(q))).toHaveLength(0);
  });

  it('narrows to a Release when one is chosen', async () => {
    const seen = recordParam('release', page([CHECKOUT]));
    await renderPerformance();
    const user = userEvent.setup();
    await screen.findByText('GET /api/checkout/{id}');
    expect(seen).toEqual([null]);

    // The options come from the Project's own Releases, a second request behind the list.
    await screen.findByRole('option', { name: 'shop@2.0.0' });
    await user.selectOptions(screen.getByLabelText('Filter by Release'), 'shop@2.0.0');

    await waitFor(() => expect(seen).toContain('shop@2.0.0'));
  });

  /**
   * A version belongs to one Project, so there is no honest list to offer across
   * several. The control stays on screen and says why, rather than disappearing and
   * leaving the user to wonder where the Release filter went.
   */
  it('cannot list Releases while the view spans more than one Project', async () => {
    server.use(http.get(`${BASE}/transaction-groups`, () => HttpResponse.json(page([CHECKOUT]))));
    await renderPerformance(fakeFilters(undefined, [1, 2]), [...PROJECTS, SECOND_PROJECT]);

    await screen.findByText('GET /api/checkout/{id}');
    expect(screen.getByLabelText('Filter by Release')).toBeDisabled();
    expect(screen.getByText(/Select a single Project to filter by Release/)).toBeInTheDocument();
  });

  /**
   * A link shared from another Project's view carries a Release this Project may not
   * have. It filters the request either way — the server is what applies it — so the
   * control has to show it, or it is a filter narrowing the list that the user can
   * neither see nor switch off.
   */
  it('shows a Release it cannot list, so a shared link can still be cleared', async () => {
    const seen = recordParam('release', page([CHECKOUT]));
    await renderPerformance(fakeFilters(undefined, [1, 2]), [...PROJECTS, SECOND_PROJECT], {
      release: 'shop@9.9.9',
    });
    const user = userEvent.setup();
    await screen.findByText('GET /api/checkout/{id}');

    expect(seen).toContain('shop@9.9.9');
    const select = screen.getByLabelText('Filter by Release');
    expect(select).toHaveValue('shop@9.9.9');
    expect(select).toBeEnabled();

    await user.selectOptions(select, '');

    await waitFor(() => expect(seen).toContain(null));
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
