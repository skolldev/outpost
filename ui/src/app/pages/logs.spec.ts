import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import { GlobalFilters } from '../core/filters';
import { LogPage, LogRecord } from '../core/models';
import { LogsPage } from './logs';

const BASE = '*/api/internal';

const LOG: LogRecord = {
  id: 'log-1',
  project_id: 1,
  environment: 'prod',
  timestamp: '2026-07-01T00:00:00Z',
  trace_id: 'trace-abc',
  span_id: 'span-1',
  level: 'error',
  severity_number: 17,
  body: 'checkout failed for user',
  attributes: { 'cart.size': 3 },
  release: 'shop@1.0.0',
};

function page(logs: LogRecord[], next: string | null = null): LogPage {
  return { logs, next_cursor: next };
}

function fakeFilters(): GlobalFilters {
  return {
    project: signal<number | undefined>(undefined),
    environments: signal<string[]>([]),
    from: signal<string | undefined>(undefined),
  } as unknown as GlobalFilters;
}

async function renderLogs() {
  return render(LogsPage, {
    providers: [
      provideHttpClient(),
      provideRouter([]),
      { provide: GlobalFilters, useValue: fakeFilters() },
    ],
  });
}

describe('LogsPage', () => {
  it('lists log records loaded on init', async () => {
    server.use(http.get(`${BASE}/logs`, () => HttpResponse.json(page([LOG]))));
    await renderLogs();

    expect(await screen.findByText('checkout failed for user')).toBeInTheDocument();
  });

  it('shows the empty state when no logs match', async () => {
    server.use(http.get(`${BASE}/logs`, () => HttpResponse.json(page([]))));
    await renderLogs();

    expect(await screen.findByText(/No log records match the current filters/)).toBeInTheDocument();
  });

  it('reloads with a level filter when a level chip is toggled', async () => {
    const seen: string[][] = [];
    server.use(
      http.get(`${BASE}/logs`, ({ request }) => {
        seen.push(new URL(request.url).searchParams.getAll('level'));
        return HttpResponse.json(page([LOG]));
      }),
    );
    await renderLogs();
    const user = userEvent.setup();
    await screen.findByText('checkout failed for user');

    await user.click(screen.getByRole('button', { name: 'error' }));

    await waitFor(() => expect(seen.some((levels) => levels.includes('error'))).toBe(true));
  });

  it('expands a row to reveal attributes and trace id', async () => {
    server.use(http.get(`${BASE}/logs`, () => HttpResponse.json(page([LOG]))));
    await renderLogs();
    const user = userEvent.setup();

    await user.click(await screen.findByText('checkout failed for user'));

    expect(await screen.findByText('cart.size')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'trace-abc' })).toBeInTheDocument();
  });

  it('debounces the search box into a query param', async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get(`${BASE}/logs`, ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('query'));
        return HttpResponse.json(page([LOG]));
      }),
    );
    await renderLogs();
    const user = userEvent.setup();
    await screen.findByText('checkout failed for user');

    await user.type(screen.getByPlaceholderText(/search log body/i), 'boom');

    await waitFor(() => expect(seen).toContain('boom'));
    // Debounced: the intermediate keystrokes must not each fire a request.
    expect(seen.filter((q) => q && q !== 'boom' && 'boom'.startsWith(q))).toHaveLength(0);
  });

  it('appends the next page when Load more is clicked', async () => {
    const next: LogRecord = { ...LOG, id: 'log-2', body: 'second page record' };
    let call = 0;
    server.use(
      http.get(`${BASE}/logs`, () => {
        call += 1;
        return HttpResponse.json(call === 1 ? page([LOG], 'cursor-2') : page([next]));
      }),
    );
    await renderLogs();
    const user = userEvent.setup();
    await screen.findByText('checkout failed for user');

    await user.click(screen.getByRole('button', { name: /load more/i }));

    expect(await screen.findByText('second page record')).toBeInTheDocument();
    expect(screen.getByText('checkout failed for user')).toBeInTheDocument();
  });

  it('resets to page one — dropping any accumulated pages — when a level filter changes', async () => {
    const next: LogRecord = { ...LOG, id: 'log-2', body: 'second page record' };
    const fresh: LogRecord = { ...LOG, id: 'log-3', body: 'fresh filtered record' };
    let call = 0;
    server.use(
      http.get(`${BASE}/logs`, ({ request }) => {
        call += 1;
        const levels = new URL(request.url).searchParams.getAll('level');
        if (call === 1) return HttpResponse.json(page([LOG], 'cursor-2'));
        if (levels.includes('error')) return HttpResponse.json(page([fresh]));
        return HttpResponse.json(page([next]));
      }),
    );
    await renderLogs();
    const user = userEvent.setup();
    await screen.findByText('checkout failed for user');

    await user.click(screen.getByRole('button', { name: /load more/i }));
    await screen.findByText('second page record');

    await user.click(screen.getByRole('button', { name: 'error' }));

    expect(await screen.findByText('fresh filtered record')).toBeInTheDocument();
    expect(screen.queryByText('checkout failed for user')).not.toBeInTheDocument();
    expect(screen.queryByText('second page record')).not.toBeInTheDocument();
  });

  it('disables the Load more button while a page is loading', async () => {
    let resolveSecond: (() => void) | undefined;
    let call = 0;
    server.use(
      http.get(`${BASE}/logs`, async () => {
        call += 1;
        if (call === 1) return HttpResponse.json(page([LOG], 'cursor-2'));
        await new Promise<void>((resolve) => (resolveSecond = resolve));
        return HttpResponse.json(page([{ ...LOG, id: 'log-2', body: 'second page record' }]));
      }),
    );
    await renderLogs();
    const user = userEvent.setup();
    await screen.findByText('checkout failed for user');

    const loadMoreButton = screen.getByRole('button', { name: /load more/i });
    await user.click(loadMoreButton);

    await waitFor(() => expect(loadMoreButton).toBeDisabled());

    resolveSecond?.();
    await waitFor(() => expect(loadMoreButton).not.toBeDisabled());
  });

  it('does not show the empty state while live tail is active with no records yet', async () => {
    server.use(http.get(`${BASE}/logs`, () => HttpResponse.json(page([]))));
    await renderLogs();
    const user = userEvent.setup();
    await screen.findByText(/No log records match the current filters/);

    await user.click(screen.getByRole('switch', { name: 'Live tail' }));

    expect(
      screen.queryByText(/No log records match the current filters/),
    ).not.toBeInTheDocument();
  });

  it('clears the buffered rows and resets pagination when leaving live mode', async () => {
    class FakeEventSource {
      onmessage: ((ev: MessageEvent) => void) | null = null;
      close(): void {
        /* no-op */
      }
      constructor(readonly url: string) {}
    }
    vi.stubGlobal('EventSource', FakeEventSource);

    const fresh: LogRecord = { ...LOG, id: 'log-4', body: 'fresh after live' };
    let call = 0;
    server.use(
      http.get(`${BASE}/logs`, () => {
        call += 1;
        return HttpResponse.json(call === 1 ? page([LOG], 'cursor-2') : page([fresh]));
      }),
    );
    await renderLogs();
    const user = userEvent.setup();
    await screen.findByText('checkout failed for user');

    // Accumulate a second page, then switch to live tail.
    await user.click(screen.getByRole('button', { name: /load more/i }));
    await screen.findByText('second page record');
    await user.click(screen.getByRole('switch', { name: 'Live tail' }));

    // Leave live mode: the buffered rows must be dropped and pagination reset,
    // so the next fetch's result — not a stale cursor — becomes the source of truth.
    await user.click(screen.getByRole('switch', { name: 'Live tail' }));

    expect(await screen.findByText('fresh after live')).toBeInTheDocument();
    expect(screen.queryByText('checkout failed for user')).not.toBeInTheDocument();
    expect(screen.queryByText('second page record')).not.toBeInTheDocument();

    vi.unstubAllGlobals();
  });
});
