import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import { GlobalFilters } from '../core/filters';
import { EventDetail, EventPage, IssueDetail } from '../core/models';
import { IssueDetailPage } from './issue-detail';

const BASE = '*/api/internal';

const ISSUE: IssueDetail = {
  id: 1,
  project_id: 1,
  title: 'TypeError: cannot read x',
  culprit: 'cart.service.ts',
  level: 'error',
  status: 'unresolved',
  first_seen: '2026-06-01T00:00:00Z',
  last_seen: '2026-07-01T00:00:00Z',
  event_count: 42,
  env_stats: [{ environment: 'prod', event_count: 42, last_seen: '2026-07-01T00:00:00Z' }],
};

const EVENT: EventDetail = {
  id: 'evt-1',
  timestamp: '2026-07-01T00:00:00Z',
  environment: 'prod',
  release: 'shop@1.0.0',
  level: 'error',
  message: null,
  user_ident: null,
  trace_id: null,
  project_id: 1,
  issue_id: 1,
  exception_type: 'TypeError',
  symbolication_status: 'symbolicated',
  prev_event_id: null,
  next_event_id: null,
  data: {
    exception: {
      values: [
        {
          type: 'TypeError',
          value: 'cannot read x',
          stacktrace: {
            frames: [
              {
                filename: 'cart.service.ts',
                function: 'checkout',
                lineno: 12,
                in_app: true,
              },
            ],
          },
        },
      ],
    },
  },
};

function eventPage(): EventPage {
  return { events: [{ ...EVENT }], next_cursor: null };
}

function fakeFilters(): GlobalFilters {
  return {
    project: signal<number | undefined>(undefined),
    environments: signal<string[]>([]),
    from: signal<string | undefined>(undefined),
  } as unknown as GlobalFilters;
}

/** Default happy-path handlers; individual tests override with `server.use`. */
function seedHandlers(issue: IssueDetail = ISSUE, event: EventDetail = EVENT) {
  server.use(
    http.get(`${BASE}/issues/:id`, () => HttpResponse.json(issue)),
    http.get(`${BASE}/issues/:id/events`, () => HttpResponse.json(eventPage())),
    http.get(`${BASE}/events/:id`, () => HttpResponse.json(event)),
  );
}

async function renderDetail() {
  return render(IssueDetailPage, {
    providers: [
      provideHttpClient(),
      provideRouter([]),
      { provide: GlobalFilters, useValue: fakeFilters() },
    ],
    inputs: { id: '1' },
  });
}

describe('IssueDetailPage', () => {
  it('renders the issue header and its latest event stacktrace', async () => {
    seedHandlers();
    await renderDetail();

    expect(await screen.findByRole('heading', { name: /cannot read x/ })).toBeInTheDocument();
    expect(await screen.findByText('checkout')).toBeInTheDocument();
    // culprit + stack frame both render the filename; the function name is unique to the frame.
    expect(screen.getAllByText('cart.service.ts').length).toBeGreaterThan(0);
  });

  it('shows a symbolication warning when source maps are missing', async () => {
    seedHandlers(ISSUE, {
      ...EVENT,
      symbolication_status: 'missing_sourcemap',
      data: {
        ...EVENT.data,
        _outpost_symbolication: {
          status: 'missing_sourcemap',
          missing: [{ debug_id: 'abc-123', abs_path: '/dist/main.js' }],
        },
      },
    });
    await renderDetail();

    expect(await screen.findByText(/minified/i)).toBeInTheDocument();
    expect(screen.getByText(/abc-123/)).toBeInTheDocument();
  });

  it('toggles the issue status via the resolve button', async () => {
    let patched: { status: string } | null = null;
    seedHandlers();
    server.use(
      http.patch(`${BASE}/issues/:id`, async ({ request }) => {
        patched = (await request.json()) as typeof patched;
        return HttpResponse.json({ ...ISSUE, status: 'resolved' });
      }),
    );
    await renderDetail();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Resolve' }));

    await waitFor(() => expect(patched).toEqual({ status: 'resolved' }));
    expect(await screen.findByRole('button', { name: 'Unresolve' })).toBeInTheDocument();
  });
});
