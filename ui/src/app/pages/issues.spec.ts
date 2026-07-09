import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import { GlobalFilters } from '../core/filters';
import { Issue, IssuePage } from '../core/models';
import { IssuesPage } from './issues';

const BASE = '*/api/internal';

const ISSUE: Issue = {
  id: 1,
  project_id: 1,
  title: 'TypeError: cannot read x',
  culprit: 'cart.service.ts',
  level: 'error',
  status: 'unresolved',
  first_seen: '2026-06-01T00:00:00Z',
  last_seen: '2026-07-01T00:00:00Z',
  event_count: 42,
  users_affected: 3,
  environments: ['prod'],
  sparkline: [1, 2, 3],
};

function page(issues: Issue[], next: string | null = null): IssuePage {
  return { issues, next_cursor: next };
}

/**
 * Minimal GlobalFilters stand-in: the page only reads project()/environments()/from().
 * Faking it keeps the test off the URL-backed real implementation.
 */
function fakeFilters(): GlobalFilters {
  return {
    project: signal<number | undefined>(undefined),
    environments: signal<string[]>([]),
    from: signal<string | undefined>(undefined),
  } as unknown as GlobalFilters;
}

async function renderIssues() {
  return render(IssuesPage, {
    providers: [
      provideHttpClient(),
      provideRouter([]),
      { provide: GlobalFilters, useValue: fakeFilters() },
    ],
  });
}

describe('IssuesPage', () => {
  it('lists issues loaded on init', async () => {
    server.use(http.get(`${BASE}/issues`, () => HttpResponse.json(page([ISSUE]))));
    await renderIssues();

    expect(await screen.findByText('TypeError: cannot read x')).toBeInTheDocument();
    expect(screen.getByText('cart.service.ts')).toBeInTheDocument();
  });

  it('shows the empty state when no issues match', async () => {
    server.use(http.get(`${BASE}/issues`, () => HttpResponse.json(page([]))));
    await renderIssues();

    expect(await screen.findByText(/No issues match the current filters/)).toBeInTheDocument();
  });

  it('reloads with a status filter when a status tab is clicked', async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get(`${BASE}/issues`, ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('status'));
        return HttpResponse.json(page([ISSUE]));
      }),
    );
    await renderIssues();
    const user = userEvent.setup();
    await screen.findByText('TypeError: cannot read x');

    await user.click(screen.getByRole('button', { name: 'Resolved' }));

    await waitFor(() => expect(seen).toContain('resolved'));
  });

  it('appends the next page when Load more is clicked', async () => {
    const next: Issue = { ...ISSUE, id: 2, title: 'RangeError: out of bounds' };
    let call = 0;
    server.use(
      http.get(`${BASE}/issues`, () => {
        call += 1;
        return HttpResponse.json(call === 1 ? page([ISSUE], 'cursor-2') : page([next]));
      }),
    );
    await renderIssues();
    const user = userEvent.setup();
    await screen.findByText('TypeError: cannot read x');

    await user.click(screen.getByRole('button', { name: /load more/i }));

    expect(await screen.findByText('RangeError: out of bounds')).toBeInTheDocument();
    expect(screen.getByText('TypeError: cannot read x')).toBeInTheDocument();
  });
});
