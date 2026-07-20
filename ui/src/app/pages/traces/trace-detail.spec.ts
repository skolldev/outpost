import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { render, screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { Project, TraceDetail } from '../../core/models';
import { TraceDetailPage } from './trace-detail';

const BASE = '*/api/internal';

const PROJECTS: Project[] = [
  {
    id: 1,
    slug: 'shop-frontend',
    name: 'shop-frontend',
    platform: 'javascript',
    created_at: '2026-06-01T00:00:00Z',
  },
  {
    id: 2,
    slug: 'shop-backend',
    name: 'shop-backend',
    platform: 'java',
    created_at: '2026-06-01T00:00:00Z',
  },
];

// A cross-service trace: browser pageload (project 1) with a fetch span whose
// child is the backend request transaction (project 2), which has a JDBC span.
// An error is pinned to the backend transaction span, and one log rides along.
const TRACE: TraceDetail = {
  trace_id: 'trace-abc',
  transactions: [
    {
      id: 'txn-fe',
      project_id: 1,
      environment: 'prod',
      release: 'fe@1.0.0',
      trace_id: 'trace-abc',
      span_id: 'fe-root',
      parent_span_id: null,
      name: '/checkout',
      op: 'pageload',
      start_ts: '2026-07-01T00:00:00.000Z',
      end_ts: '2026-07-01T00:00:00.500Z',
      duration_ms: 500,
      status: 'ok',
      data: {},
    },
    {
      id: 'txn-be',
      project_id: 2,
      environment: 'prod',
      release: 'be@1.0.0',
      trace_id: 'trace-abc',
      span_id: 'be-root',
      parent_span_id: 'fe-fetch',
      name: 'GET /api/checkout',
      op: 'http.server',
      start_ts: '2026-07-01T00:00:00.100Z',
      end_ts: '2026-07-01T00:00:00.300Z',
      duration_ms: 200,
      status: 'ok',
      data: {},
    },
  ],
  spans: [
    {
      id: 'span-fetch',
      txn_id: 'txn-fe',
      project_id: 1,
      trace_id: 'trace-abc',
      span_id: 'fe-fetch',
      parent_span_id: 'fe-root',
      op: 'http.client',
      description: 'GET /api/checkout',
      start_ts: '2026-07-01T00:00:00.050Z',
      end_ts: '2026-07-01T00:00:00.320Z',
      duration_ms: 270,
      status: 'ok',
      data: {},
    },
    {
      id: 'span-db',
      txn_id: 'txn-be',
      project_id: 2,
      trace_id: 'trace-abc',
      span_id: 'be-db',
      parent_span_id: 'be-root',
      op: 'db.sql.query',
      description: 'SELECT * FROM orders',
      start_ts: '2026-07-01T00:00:00.120Z',
      end_ts: '2026-07-01T00:00:00.280Z',
      duration_ms: 160,
      status: 'ok',
      data: {},
    },
  ],
  errors: [
    {
      id: 'evt-1',
      project_id: 2,
      issue_id: 7,
      environment: 'prod',
      timestamp: '2026-07-01T00:00:00.250Z',
      span_id: 'be-root',
      level: 'error',
      message: 'order 4711 has no customer',
      exception_type: 'IllegalStateException',
    },
  ],
  logs: [
    {
      id: 'log-1',
      project_id: 2,
      environment: 'prod',
      timestamp: '2026-07-01T00:00:00.200Z',
      trace_id: 'trace-abc',
      span_id: 'be-root',
      level: 'info',
      severity_number: 9,
      body: 'handling checkout',
      attributes: {},
      release: 'be@1.0.0',
    },
  ],
};

async function renderTrace(trace: TraceDetail | 'notfound' = TRACE) {
  server.use(
    http.get(`${BASE}/traces/:id`, () =>
      trace === 'notfound' ? new HttpResponse(null, { status: 404 }) : HttpResponse.json(trace),
    ),
    http.get(`${BASE}/projects`, () => HttpResponse.json(PROJECTS)),
  );
  return render(TraceDetailPage, {
    providers: [provideHttpClient(), provideRouter([])],
    inputs: { traceId: 'trace-abc' },
  });
}

describe('TraceDetailPage', () => {
  it('renders every transaction and span in the waterfall', async () => {
    await renderTrace();

    expect(await screen.findByText('SELECT * FROM orders')).toBeInTheDocument();
    expect(screen.getAllByText('/checkout').length).toBeGreaterThan(0);
    // 'GET /api/checkout' names both the fetch span and the downstream backend txn.
    expect(screen.getAllByText('GET /api/checkout')).toHaveLength(2);
    // The 'txn' badge marks transaction rows — two services.
    expect(screen.getAllByText('txn')).toHaveLength(2);
  });

  it('shows a legend with the project names', async () => {
    await renderTrace();

    expect(await screen.findByText('shop-frontend')).toBeInTheDocument();
    expect(await screen.findByText('shop-backend')).toBeInTheDocument();
  });

  it('shows the error count for the trace', async () => {
    await renderTrace();

    expect(await screen.findByText('1 error')).toBeInTheDocument();
  });

  it('opens the span detail panel with a link to the pinned error issue', async () => {
    await renderTrace();
    const user = userEvent.setup();

    // 'GET /api/checkout' names both the fetch span and the backend txn; the
    // pinned error rides on the backend txn row, marked by its 'txn' badge.
    await screen.findByText('SELECT * FROM orders');
    const txnRow = screen
      .getAllByText('GET /api/checkout')
      .map((el) => el.closest<HTMLElement>('[role="button"]')!)
      .find((row) => within(row).queryByText('txn'));
    await user.click(txnRow!);

    // The side panel surfaces the pinned error linking to its issue.
    const issueLink = await screen.findByRole('link', { name: /IllegalStateException/ });
    expect(issueLink).toHaveAttribute('href', expect.stringContaining('/issues/7'));
    // The panel shows the project name, not the raw id.
    const aside = screen.getByRole('complementary');
    expect(within(aside).getByText('shop-backend')).toBeInTheDocument();
  });

  it('hides resource.* spans by default and reveals them via the toggle', async () => {
    const trace: TraceDetail = {
      ...TRACE,
      spans: [
        ...TRACE.spans,
        {
          ...TRACE.spans[0],
          id: 'span-res',
          span_id: 'fe-res',
          parent_span_id: 'fe-root',
          op: 'resource.script',
          description: '/assets/main.js',
          start_ts: '2026-07-01T00:00:00.010Z',
          end_ts: '2026-07-01T00:00:00.040Z',
          duration_ms: 30,
        },
      ],
    };
    await renderTrace(trace);
    const user = userEvent.setup();

    // Non-resource spans render; the resource span is filtered out by default.
    await screen.findByText('SELECT * FROM orders');
    expect(screen.queryByText('/assets/main.js')).not.toBeInTheDocument();
    expect(screen.getByText('Hide resource spans (1)')).toBeInTheDocument();

    await user.click(screen.getByRole('switch', { name: /resource/i }));

    expect(await screen.findByText('/assets/main.js')).toBeInTheDocument();
  });

  it('shows a not-found state when the trace is unknown', async () => {
    await renderTrace('notfound');

    expect(await screen.findByText('Trace not found.')).toBeInTheDocument();
  });

  it('places log markers on the timeline for a trace with no transactions or spans', async () => {
    // A trace can arrive as just an error plus its logs (no transaction). The
    // timeline window must still span those, or every marker collapses to the
    // 1970 fallback window and renders off the right edge.
    const trace: TraceDetail = {
      trace_id: 'trace-abc',
      transactions: [],
      spans: [],
      errors: [{ ...TRACE.errors[0], timestamp: '2026-07-07T16:05:00.000Z' }],
      logs: [
        { ...TRACE.logs[0], id: 'log-a', timestamp: '2026-07-07T16:04:05.544Z', body: 'first log' },
        { ...TRACE.logs[0], id: 'log-b', timestamp: '2026-07-07T16:05:48.745Z', body: 'last log' },
      ],
    };
    const view = await renderTrace(trace);

    await screen.findByText('Logs (2)');
    const markers = view.fixture.componentInstance.logMarkers();
    expect(markers).toHaveLength(2);
    // Spread across the window rather than all clamped to the far edge.
    expect(markers[0].leftPct).toBeCloseTo(0, 1);
    expect(markers[1].leftPct).toBeCloseTo(100, 1);
  });
});
