import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import { GlobalFilters } from '../core/filters';
import { TracePage, TraceSummary } from '../core/models';
import { TracesPage } from './traces';

const BASE = '*/api/internal';

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
    project: signal<number | undefined>(undefined),
    environments: signal<string[]>([]),
    from: signal<string | undefined>(undefined),
  } as unknown as GlobalFilters;
}

async function renderTraces() {
  return render(TracesPage, {
    providers: [
      provideHttpClient(),
      provideRouter([]),
      { provide: GlobalFilters, useValue: fakeFilters() },
    ],
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

  it('debounces the search box into a query param', async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get(`${BASE}/traces`, ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('query'));
        return HttpResponse.json(page([TRACE]));
      }),
    );
    await renderTraces();
    const user = userEvent.setup();
    await screen.findByText('/checkout');

    await user.type(screen.getByPlaceholderText(/search by transaction name/i), 'boom');

    await waitFor(() => expect(seen).toContain('boom'));
    // Debounced: the intermediate keystrokes must not each fire a request.
    expect(seen.filter((q) => q && q !== 'boom' && 'boom'.startsWith(q))).toHaveLength(0);
  });

  it('appends the next page when Load more is clicked', async () => {
    const next: TraceSummary = { ...TRACE, id: 'txn-2', name: '/cart', trace_id: 'trace-def' };
    let call = 0;
    server.use(
      http.get(`${BASE}/traces`, () => {
        call += 1;
        return HttpResponse.json(call === 1 ? page([TRACE], 'cursor-2') : page([next]));
      }),
    );
    await renderTraces();
    const user = userEvent.setup();
    await screen.findByText('/checkout');

    await user.click(screen.getByRole('button', { name: /load more/i }));

    expect(await screen.findByText('/cart')).toBeInTheDocument();
    expect(screen.getByText('/checkout')).toBeInTheDocument();
  });

  it('resets to page one — dropping any accumulated pages — when the errors-only filter changes', async () => {
    const next: TraceSummary = { ...TRACE, id: 'txn-2', name: '/cart', trace_id: 'trace-def' };
    const fresh: TraceSummary = { ...TRACE, id: 'txn-3', name: '/refund', trace_id: 'trace-ghi' };
    let call = 0;
    server.use(
      http.get(`${BASE}/traces`, ({ request }) => {
        call += 1;
        const hasErrors = new URL(request.url).searchParams.get('has_errors');
        if (call === 1) return HttpResponse.json(page([TRACE], 'cursor-2'));
        if (hasErrors === 'true') return HttpResponse.json(page([fresh]));
        return HttpResponse.json(page([next]));
      }),
    );
    await renderTraces();
    const user = userEvent.setup();
    await screen.findByText('/checkout');

    await user.click(screen.getByRole('button', { name: /load more/i }));
    await screen.findByText('/cart');

    await user.click(screen.getByRole('switch', { name: /errors/i }));

    expect(await screen.findByText('/refund')).toBeInTheDocument();
    expect(screen.queryByText('/checkout')).not.toBeInTheDocument();
    expect(screen.queryByText('/cart')).not.toBeInTheDocument();
  });

  it('disables the Load more button while a page is loading', async () => {
    let resolveSecond: (() => void) | undefined;
    let call = 0;
    server.use(
      http.get(`${BASE}/traces`, async () => {
        call += 1;
        if (call === 1) return HttpResponse.json(page([TRACE], 'cursor-2'));
        await new Promise<void>((resolve) => (resolveSecond = resolve));
        return HttpResponse.json(page([{ ...TRACE, id: 'txn-2', name: '/cart' }]));
      }),
    );
    await renderTraces();
    const user = userEvent.setup();
    await screen.findByText('/checkout');

    const loadMoreButton = screen.getByRole('button', { name: /load more/i });
    await user.click(loadMoreButton);

    await waitFor(() => expect(loadMoreButton).toBeDisabled());

    resolveSecond?.();
    await waitFor(() => expect(loadMoreButton).not.toBeDisabled());
  });

  it('keeps the current rows visible while a reload is in flight', async () => {
    let resolveSecond: (() => void) | undefined;
    let call = 0;
    server.use(
      http.get(`${BASE}/traces`, async () => {
        call += 1;
        if (call >= 2) {
          await new Promise<void>((resolve) => (resolveSecond = resolve));
          return HttpResponse.json(page([{ ...TRACE, id: 'txn-2', name: '/cart' }]));
        }
        return HttpResponse.json(page([TRACE]));
      }),
    );
    await renderTraces();
    const user = userEvent.setup();
    await screen.findByText('/checkout');

    await user.click(screen.getByRole('switch', { name: /errors/i }));

    await waitFor(() => expect(screen.getByLabelText('Loading')).toBeInTheDocument());
    expect(screen.getByText('/checkout')).toBeInTheDocument();

    resolveSecond?.();
    expect(await screen.findByText('/cart')).toBeInTheDocument();
  });
});
