import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { GlobalFilters } from '../../core/filters';
import { Project, TracePage, TraceSummary } from '../../core/models';
import { TracesPage } from './traces';

const BASE = '*/api/internal';

const PROJECTS: Project[] = [
  {
    id: 1,
    slug: 'shop-frontend',
    name: 'shop-frontend',
    platform: 'javascript',
    created_at: '2026-06-01T00:00:00Z',
  },
];

const TRACE: TraceSummary = {
  id: 'txn-1',
  project_id: 1,
  environment: 'prod',
  release: 'shop-frontend@1.0.0',
  trace_id: 'trace-abc',
  name: '/checkout',
  op: 'pageload',
  start_ts: '2026-07-01T00:00:00Z',
  end_ts: '2026-07-01T00:00:00.500Z',
  duration_ms: 500,
  status: 'ok',
  span_count: 4,
  error_count: 2,
};

function page(traces: TraceSummary[], next: string | null = null): TracePage {
  return { traces, next_cursor: next };
}

function fakeFilters(): GlobalFilters {
  return {
    project: signal<number[]>([]),
    environments: signal<string[]>([]),
    from: signal<string | undefined>(undefined),
  } as unknown as GlobalFilters;
}

async function renderTraces(queryParams: Record<string, string> = {}) {
  server.use(http.get(`${BASE}/projects`, () => HttpResponse.json(PROJECTS)));
  const query = Object.entries(queryParams)
    .map(([key, value]) => `${key}=${value}`)
    .join('&');
  return render(TracesPage, {
    providers: [
      provideHttpClient(),
      provideRouter([]),
      { provide: GlobalFilters, useValue: fakeFilters() },
    ],
    routes: [{ path: '', children: [] }],
    initialRoute: query ? `/?${query}` : '/',
  });
}

describe('TracesPage', () => {
  it('lists traces loaded on init', async () => {
    server.use(http.get(`${BASE}/traces`, () => HttpResponse.json(page([TRACE]))));
    await renderTraces();

    expect(await screen.findByText('/checkout')).toBeInTheDocument();
    // Error count badge is shown.
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('shows a legend with the project names', async () => {
    server.use(http.get(`${BASE}/traces`, () => HttpResponse.json(page([TRACE]))));
    await renderTraces();

    await screen.findByText('/checkout');
    expect(await screen.findByText('shop-frontend')).toBeInTheDocument();
  });

  it('shows the empty state when no traces match', async () => {
    server.use(http.get(`${BASE}/traces`, () => HttpResponse.json(page([]))));
    await renderTraces();

    expect(await screen.findByText(/No traces match the current filters/)).toBeInTheDocument();
  });

  it('reloads with has_errors when the toggle is switched on', async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get(`${BASE}/traces`, ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('has_errors'));
        return HttpResponse.json(page([TRACE]));
      }),
    );
    await renderTraces();
    const user = userEvent.setup();
    await screen.findByText('/checkout');

    await user.click(screen.getByRole('switch', { name: /errors/i }));

    await waitFor(() => expect(seen).toContain('true'));
  });

  /**
   * The duration band has no control on this page — it arrives from the Performance
   * detail view's links to a Transaction Group's slow and typical Traces (#162), and
   * `max_duration` is what makes "typical" mean anything: without an upper bound it
   * would include every cache hit in the window.
   */
  it('filters by a duration band carried in the URL', async () => {
    const seen: Record<string, string | null>[] = [];
    server.use(
      http.get(`${BASE}/traces`, ({ request }) => {
        const params = new URL(request.url).searchParams;
        seen.push({ min: params.get('min_duration'), max: params.get('max_duration') });
        return HttpResponse.json(page([TRACE]));
      }),
    );

    await renderTraces({ min_duration: '42', max_duration: '310' });

    await waitFor(() => expect(seen).toContainEqual({ min: '42', max: '310' }));
  });
});
