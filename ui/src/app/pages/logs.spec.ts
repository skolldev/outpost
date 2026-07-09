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
});
