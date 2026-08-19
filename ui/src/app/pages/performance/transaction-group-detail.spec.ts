import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { GlobalFilters } from '../../core/filters';
import { Project, TransactionGroup, TransactionGroupDetail } from '../../core/models';
import { TransactionGroupDetailPage } from './transaction-group-detail';

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

const NO_OP: TransactionGroup = { ...CHECKOUT, name: 'process outbox', op: null };

function detail(
  group: TransactionGroup,
  overrides: Partial<TransactionGroupDetail> = {},
): TransactionGroupDetail {
  return {
    from: '2026-07-20T00:00:00Z',
    to: '2026-08-19T00:00:00Z',
    range_clamped: false,
    group,
    ...overrides,
  };
}

function fakeFilters(from?: string, environments: string[] = []): GlobalFilters {
  return {
    project: signal<number[]>([1]),
    environments: signal<string[]>(environments),
    from: signal<string | undefined>(from),
  } as unknown as GlobalFilters;
}

/** The link the leaderboard produces: the whole key, with `op` omitted when it is null. */
const KEY = { project: '1', name: 'GET /api/checkout/{id}', op: 'http.server' };

async function renderDetail(
  queryParams: Record<string, string> = KEY,
  filters: GlobalFilters = fakeFilters(),
) {
  server.use(http.get(`${BASE}/projects`, () => HttpResponse.json(PROJECTS)));
  // Joined by hand rather than through URLSearchParams: the render helper splits the
  // query string itself and hands the parts to the router unencoded, so a transaction
  // name percent-encoded here arrives as a literal `%2F` in the param the page reads.
  const query = Object.entries(queryParams)
    .map(([key, value]) => `${key}=${value}`)
    .join('&');
  return render(TransactionGroupDetailPage, {
    providers: [
      provideHttpClient(),
      provideRouter([]),
      { provide: GlobalFilters, useValue: filters },
    ],
    // `/traces` is a real route here because the deep links are half of what this page
    // does: without it a click would fail to navigate and the assertions would be about
    // the test's own routing rather than about the link.
    routes: [
      { path: '', children: [] },
      { path: 'traces', children: [] },
    ],
    initialRoute: `/?${query}`,
  });
}

/** Records one query param of every detail request the page issues. */
function recordParam(param: string, body: TransactionGroupDetail): (string | null)[] {
  const seen: (string | null)[] = [];
  server.use(
    http.get(`${BASE}/transaction-groups/detail`, ({ request }) => {
      seen.push(new URL(request.url).searchParams.get(param));
      return HttpResponse.json(body);
    }),
  );
  return seen;
}

/** The query params a deep link navigates to, read off the router after clicking it. */
async function clickAndReadParams(name: RegExp): Promise<Record<string, string>> {
  const user = userEvent.setup();
  await user.click(await screen.findByRole('link', { name }));
  const router = TestBed.inject(Router);
  await waitFor(() => expect(router.url).toContain('/traces'));
  return Object.fromEntries(new URL(router.url, 'http://localhost').searchParams);
}

describe('TransactionGroupDetailPage', () => {
  it('shows the statistics of the Transaction Group named by the link', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups/detail`, () => HttpResponse.json(detail(CHECKOUT))),
    );
    await renderDetail();

    expect(await screen.findByRole('heading', { name: 'GET /api/checkout/{id}' })).toBeVisible();
    expect(screen.getByText('http.server')).toBeInTheDocument();
    expect(screen.getByText('12,401')).toBeInTheDocument();
    // p50 42ms, p95 310ms, p99 1.20s, avg 42.1ms, max 4.10s, total 8.7min.
    expect(screen.getByText('310ms')).toBeInTheDocument();
    expect(screen.getByText('1.20s')).toBeInTheDocument();
    expect(screen.getByText('4.10s')).toBeInTheDocument();
    expect(screen.getByText('8.7min')).toBeInTheDocument();
  });

  /**
   * (Project, name, op) is the whole key, so a link with no `op` asks for the group
   * whose op is null — not for "any op", which names a set of groups rather than one.
   */
  it('asks for the null-op group when the link carries no op', async () => {
    const seen = recordParam('op', detail(NO_OP));
    await renderDetail({ project: '1', name: 'process outbox' });

    await screen.findByRole('heading', { name: 'process outbox' });
    expect(seen).toEqual([null]);
    expect(screen.getByText('no op')).toBeInTheDocument();
  });

  it('carries the Project, Environment Name and time range into the request', async () => {
    const seen: URLSearchParams[] = [];
    server.use(
      http.get(`${BASE}/transaction-groups/detail`, ({ request }) => {
        seen.push(new URL(request.url).searchParams);
        return HttpResponse.json(detail(CHECKOUT));
      }),
    );
    await renderDetail(KEY, fakeFilters('2026-08-05T00:00:00Z', ['production']));

    await waitFor(() => expect(seen).not.toHaveLength(0));
    const params = seen[seen.length - 1];
    expect(params.get('project')).toBe('1');
    expect(params.get('name')).toBe('GET /api/checkout/{id}');
    expect(params.get('environment')).toBe('production');
    expect(params.get('from')).toBe('2026-08-05T00:00:00Z');
  });

  /** Inherited from the leaderboard's own Release filter, so both views agree. */
  it('carries a Release filter through from the list it was opened from', async () => {
    const seen = recordParam('release', detail(CHECKOUT));
    await renderDetail({ ...KEY, release: 'shop@2.0.0' });

    await waitFor(() => expect(seen).toContain('shop@2.0.0'));
  });

  it('says so when the server clamped the range to 30 days', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups/detail`, () =>
        HttpResponse.json(detail(CHECKOUT, { range_clamped: true })),
      ),
    );
    await renderDetail();

    expect(await screen.findByText(/Showing the last 30 days/i)).toBeInTheDocument();
  });

  /**
   * A key that names nothing in this window is the expected outcome of a shared link to
   * an endpoint that has since gone quiet — a state to explain, not a failure to report.
   */
  it('explains an empty window rather than reporting an error', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups/detail`, () =>
        HttpResponse.json({ detail: 'no Transactions' }, { status: 404 }),
      ),
    );
    await renderDetail();

    expect(
      await screen.findByText(/No Transactions in this Transaction Group/),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows a load error when the statistics cannot be computed', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups/detail`, () =>
        HttpResponse.json({ detail: 'failed' }, { status: 500 }),
      ),
    );
    await renderDetail();

    expect(await screen.findByRole('alert')).toHaveTextContent('Transaction Group unavailable');
  });

  it('says a link naming no Transaction Group cannot be opened', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups/detail`, () => HttpResponse.json(detail(CHECKOUT))),
    );
    await renderDetail({ op: 'http.server' });

    expect(await screen.findByText(/does not name a Transaction Group/)).toBeInTheDocument();
  });

  /**
   * The slow link is the tail the percentiles could only point at: everything at or
   * above p95, with no upper bound.
   */
  it('links to the Traces page filtered to this group’s slow Traces', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups/detail`, () => HttpResponse.json(detail(CHECKOUT))),
    );
    await renderDetail(KEY, fakeFilters(undefined, ['production']));
    await screen.findByRole('heading', { name: 'GET /api/checkout/{id}' });

    const params = await clickAndReadParams(/slow traces/i);

    expect(params['query']).toBe('GET /api/checkout/{id}');
    expect(params['min_duration']).toBe('310');
    expect(params['max_duration']).toBeUndefined();
  });

  /**
   * And the typical link is bounded on both sides: the median up to where the tail
   * begins. Without the upper bound "typical" would include every cache hit in the
   * window, which diffs against a slow request no more usefully than another slow one.
   */
  it('links to the Traces page filtered to this group’s typical Traces', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups/detail`, () => HttpResponse.json(detail(CHECKOUT))),
    );
    await renderDetail();
    await screen.findByRole('heading', { name: 'GET /api/checkout/{id}' });

    const params = await clickAndReadParams(/typical traces/i);

    expect(params['query']).toBe('GET /api/checkout/{id}');
    expect(params['min_duration']).toBe('42');
    expect(params['max_duration']).toBe('310');
  });

  /**
   * The Traces page has to open on the slice the user was just reading, and nothing
   * else: Project, Environment Name and range come with them, while `name` and `op`
   * identify a Transaction Group and mean nothing there — merged params outlive the
   * page that set them.
   */
  it('carries the current Project, environment and range through to the Traces page', async () => {
    server.use(
      http.get(`${BASE}/transaction-groups/detail`, () => HttpResponse.json(detail(CHECKOUT))),
    );
    await renderDetail({ ...KEY, environment: 'production', range: '7d' });
    await screen.findByRole('heading', { name: 'GET /api/checkout/{id}' });

    const params = await clickAndReadParams(/slow traces/i);

    expect(params['project']).toBe('1');
    expect(params['environment']).toBe('production');
    expect(params['range']).toBe('7d');
    expect(params['name']).toBeUndefined();
    expect(params['op']).toBeUndefined();
  });
});
