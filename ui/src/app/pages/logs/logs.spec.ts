import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { fireEvent, render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { GlobalFilters } from '../../core/filters';
import { LogPage, LogRecord, LogTimeline } from '../../core/models';
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

/** Minimal EventSource stand-in — MSW can't intercept SSE. Each instance registers itself so a test can push messages and assert on its URL. */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  closed = false;
  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }
  emit(record: LogRecord): void {
    this.onmessage?.({ data: JSON.stringify(record) } as MessageEvent<string>);
  }
  close(): void {
    this.closed = true;
  }
}

function fakeFilters(): GlobalFilters {
  return {
    project: signal<number[]>([]),
    environments: signal<string[]>([]),
    range: signal<string>('14d'),
    from: signal<string | undefined>(undefined),
  } as unknown as GlobalFilters;
}

/** An hour of one-minute buckets, so bucket N starts at N minutes past. */
const TIMELINE_FROM = '2026-07-01T00:00:00.000Z';

function timeline(): LogTimeline {
  return {
    from: TIMELINE_FROM,
    to: '2026-07-01T01:00:00.000Z',
    bucket_seconds: 60,
    buckets: [{ start: '2026-07-01T00:10:00.000Z', counts: { error: 2, info: 5 } }],
  };
}

/** The rendered chart, waited for since it's an independent request from the log list and may still be in flight. */
async function timelineSvg(container: Element): Promise<Element> {
  return waitFor(() => {
    const svg = container.querySelector('svg');
    if (!svg) throw new Error('no timeline rendered');
    return svg;
  });
}

/**
 * The chart is pointer-driven with no interactive roles (#141), and jsdom gives every
 * element a zero-sized box. This stubs the box and fires pointer events, asserting on
 * the resulting request rather than the SVG.
 */
async function brush(container: Element, clientX: number, toClientX = clientX): Promise<void> {
  const svg = await timelineSvg(container);
  svg.getBoundingClientRect = () => ({ left: 0, width: 600 }) as DOMRect;
  fireEvent.pointerDown(svg, { clientX });
  fireEvent.pointerMove(svg, { clientX: toClientX });
  fireEvent.pointerUp(svg, { clientX: toClientX });
}

/** Records the time bounds of every log-list request, which is where a brush shows up. */
function captureLogWindows(): { from: string | null; to: string | null }[] {
  const windows: { from: string | null; to: string | null }[] = [];
  server.use(
    http.get(`${BASE}/logs`, ({ request }) => {
      const params = new URL(request.url).searchParams;
      windows.push({ from: params.get('from'), to: params.get('to') });
      return HttpResponse.json(page([LOG]));
    }),
  );
  return windows;
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
  const realEventSource = globalThis.EventSource;

  beforeEach(() => {
    FakeEventSource.instances = [];
    globalThis.EventSource = FakeEventSource as unknown as typeof EventSource;
    // The page loads the chart on init, so every spec needs this handler.
    server.use(http.get(`${BASE}/logs/timeline`, () => HttpResponse.json(timeline())));
  });

  afterEach(() => {
    globalThis.EventSource = realEventSource;
  });

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

  it('keeps already-fetched rows when entering live and prepends streamed records', async () => {
    server.use(http.get(`${BASE}/logs`, () => HttpResponse.json(page([LOG]))));
    await renderLogs();
    const user = userEvent.setup();
    await screen.findByText('checkout failed for user');

    await user.click(screen.getByRole('switch', { name: 'Live tail' }));
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));

    expect(screen.getByText('checkout failed for user')).toBeInTheDocument();

    FakeEventSource.instances[0].emit({ ...LOG, id: 'log-2', body: 'streamed while live' });
    expect(await screen.findByText('streamed while live')).toBeInTheDocument();
    expect(screen.getByText('checkout failed for user')).toBeInTheDocument();
  });

  it('clears the stale buffer and reconnects when a filter changes while live', async () => {
    server.use(http.get(`${BASE}/logs`, () => HttpResponse.json(page([]))));
    await renderLogs();
    const user = userEvent.setup();

    await user.click(screen.getByRole('switch', { name: 'Live tail' }));
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));

    FakeEventSource.instances[0].emit({ ...LOG, id: 'log-3', body: 'streamed under old filter' });
    expect(await screen.findByText('streamed under old filter')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'error' }));

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(2));
    expect(FakeEventSource.instances[0].closed).toBe(true);
    expect(FakeEventSource.instances[1].url).toContain('level=error');
    await waitFor(() =>
      expect(screen.queryByText('streamed under old filter')).not.toBeInTheDocument(),
    );
  });

  it('narrows the stream to the bucket that was clicked', async () => {
    const windows = captureLogWindows();
    const { container } = await renderLogs();
    await screen.findByText('checkout failed for user');

    // A sixth of the way across 60 one-minute buckets is bucket 10 — 00:10 to 00:11.
    await brush(container, 100);

    await waitFor(() =>
      expect(windows).toContainEqual({
        from: '2026-07-01T00:10:00.000Z',
        to: '2026-07-01T00:11:00.000Z',
      }),
    );
  });

  it('selects the whole dragged span, snapped to bucket edges', async () => {
    const windows = captureLogWindows();
    const { container } = await renderLogs();
    await screen.findByText('checkout failed for user');

    await brush(container, 100, 200); // buckets 10 through 20

    await waitFor(() =>
      expect(windows).toContainEqual({
        from: '2026-07-01T00:10:00.000Z',
        to: '2026-07-01T00:21:00.000Z',
      }),
    );
  });

  /** The chart spans the range so a selection can be seen in context (ADR 0011). */
  it('does not feed the selection back into the chart request', async () => {
    const chartRequests: { from: string | null; to: string | null }[] = [];
    server.use(
      http.get(`${BASE}/logs`, () => HttpResponse.json(page([LOG]))),
      http.get(`${BASE}/logs/timeline`, ({ request }) => {
        const params = new URL(request.url).searchParams;
        chartRequests.push({ from: params.get('from'), to: params.get('to') });
        return HttpResponse.json(timeline());
      }),
    );
    const { container } = await renderLogs();
    await screen.findByText('checkout failed for user');
    const before = chartRequests.length;

    await brush(container, 100);

    await waitFor(() => expect(screen.getByTitle('Clear the selected time window')).toBeVisible());
    // `before` must be nonzero, or the assertions below could pass vacuously.
    expect(before).toBeGreaterThan(0);
    expect(chartRequests).toHaveLength(before);
    expect(chartRequests.every((request) => request.to === null)).toBe(true);
  });

  /**
   * The visible half of ADR 0011: not re-requesting the chart also keeps it mounted.
   * A refetch would blank `bars()` and drop the `@if`, flickering the chart on every brush.
   */
  it('keeps the chart mounted when the selection changes', async () => {
    const windows = captureLogWindows();
    const { container } = await renderLogs();
    await screen.findByText('checkout failed for user');
    const svg = await timelineSvg(container);

    await brush(container, 100);

    // Waits on the list request the brush causes — a chart refetch would land in the same cycle.
    await waitFor(() => expect(windows.at(-1)?.to).toBe('2026-07-01T00:11:00.000Z'));
    expect(container.querySelector('svg')).toBe(svg);
  });

  it('drops the selection when live tail is switched on', async () => {
    server.use(http.get(`${BASE}/logs`, () => HttpResponse.json(page([LOG]))));
    const { container } = await renderLogs();
    const user = userEvent.setup();
    await screen.findByText('checkout failed for user');
    await brush(container, 100);
    await waitFor(() => expect(screen.getByTitle('Clear the selected time window')).toBeVisible());

    await user.click(screen.getByRole('switch', { name: 'Live tail' }));

    // A closed window and a live tail are contradictory, and the SSE endpoint takes no time bounds.
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
    expect(FakeEventSource.instances[0].url).not.toContain('to=');
    expect(screen.queryByTitle('Clear the selected time window')).not.toBeInTheDocument();
    expect(container.querySelector('svg')).toBeNull();
  });

  it('restores the unbrushed window when the selection is cleared', async () => {
    const windows = captureLogWindows();
    const { container } = await renderLogs();
    const user = userEvent.setup();
    await screen.findByText('checkout failed for user');
    await brush(container, 100);
    const chip = await screen.findByTitle('Clear the selected time window');

    await user.click(chip);

    await waitFor(() => expect(windows.at(-1)?.to).toBeNull());
    expect(screen.queryByTitle('Clear the selected time window')).not.toBeInTheDocument();
  });

  describe('attribute filters', () => {
    /** Records the attribute-shaped params of every log-list request. */
    function captureFilterParams(): {
      attr: string[];
      traceId: string | null;
      release: string | null;
    }[] {
      const seen: { attr: string[]; traceId: string | null; release: string | null }[] = [];
      server.use(
        http.get(`${BASE}/logs`, ({ request }) => {
          const params = new URL(request.url).searchParams;
          seen.push({
            attr: params.getAll('attr'),
            traceId: params.get('trace_id'),
            release: params.get('release'),
          });
          return HttpResponse.json(page([LOG]));
        }),
      );
      return seen;
    }

    async function addFilter(key: string, value: string): Promise<void> {
      const user = userEvent.setup();
      await user.click(screen.getByRole('button', { name: 'Add filter' }));
      await user.type(screen.getByRole('combobox', { name: 'Filter key' }), key);
      await user.type(screen.getByRole('combobox', { name: 'Filter value' }), `${value}{Enter}`);
    }

    it('reloads with an attribute filter built from a key and a value', async () => {
      const seen = captureFilterParams();
      await renderLogs();
      await screen.findByText('checkout failed for user');

      await addFilter('user.id', '42');

      await waitFor(() => expect(seen.at(-1)?.attr).toEqual(['user.id=42']));
      expect(screen.getByRole('button', { name: 'Remove user.id filter' })).toHaveTextContent(
        'user.id = 42',
      );
    });

    it('filters by an attribute clicked in an expanded row', async () => {
      const seen = captureFilterParams();
      await renderLogs();
      const user = userEvent.setup();
      await user.click(await screen.findByText('checkout failed for user'));

      await user.click(await screen.findByTitle('Filter logs by cart.size'));

      await waitFor(() => expect(seen.at(-1)?.attr).toEqual(['cart.size=3']));
      expect(screen.getByRole('button', { name: 'Remove cart.size filter' })).toBeInTheDocument();
    });

    it('sends trace_id and release as their own params, not as attributes', async () => {
      const seen = captureFilterParams();
      await renderLogs();
      const user = userEvent.setup();
      await user.click(await screen.findByText('checkout failed for user'));

      await user.click(await screen.findByTitle('Filter logs by release'));
      await addFilter('trace_id', 'trace-abc');

      await waitFor(() =>
        expect(seen.at(-1)).toEqual({ attr: [], traceId: 'trace-abc', release: 'shop@1.0.0' }),
      );
      expect(screen.getByRole('button', { name: 'Remove release filter' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Remove trace_id filter' })).toBeInTheDocument();
    });

    it('replaces the value when a second filter names the same key', async () => {
      const seen = captureFilterParams();
      await renderLogs();
      await screen.findByText('checkout failed for user');

      await addFilter('user.id', '42');
      await waitFor(() => expect(seen.at(-1)?.attr).toEqual(['user.id=42']));
      await addFilter('user.id', '43');

      await waitFor(() => expect(seen.at(-1)?.attr).toEqual(['user.id=43']));
      expect(screen.getAllByRole('button', { name: 'Remove user.id filter' })).toHaveLength(1);
    });

    it('keeps other filters when one chip is removed', async () => {
      const seen = captureFilterParams();
      await renderLogs();
      const user = userEvent.setup();
      await screen.findByText('checkout failed for user');
      await addFilter('user.id', '42');
      await addFilter('server.address', 'api-1');
      await waitFor(() => expect(seen.at(-1)?.attr).toHaveLength(2));

      await user.click(screen.getByRole('button', { name: 'Remove user.id filter' }));

      await waitFor(() => expect(seen.at(-1)?.attr).toEqual(['server.address=api-1']));
      expect(
        screen.queryByRole('button', { name: 'Remove user.id filter' }),
      ).not.toBeInTheDocument();
    });

    it('carries the filters into the live tail', async () => {
      captureFilterParams();
      await renderLogs();
      const user = userEvent.setup();
      await screen.findByText('checkout failed for user');
      await addFilter('user.id', '42');
      await screen.findByRole('button', { name: 'Remove user.id filter' });

      await user.click(screen.getByRole('switch', { name: 'Live tail' }));

      await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
      const params = new URL(FakeEventSource.instances[0].url, 'http://localhost').searchParams;
      expect(params.getAll('attr')).toEqual(['user.id=42']);
    });

    /**
     * Suggestions are read off the records on screen: first-class fields first, then the
     * Project's own keys by frequency, then SDK bookkeeping — never an attribute that
     * only restates a first-class field.
     */
    it('suggests keys and values from the loaded records', async () => {
      server.use(
        http.get(`${BASE}/logs`, () =>
          HttpResponse.json(
            page([
              {
                ...LOG,
                id: 'a',
                attributes: {
                  'user.id': 'u-1',
                  'server.address': 'api-1',
                  'sentry.sdk.name': 'sentry.javascript.angular',
                  'sentry.release': 'shop@1.0.0',
                },
              },
              {
                ...LOG,
                id: 'b',
                attributes: {
                  'server.address': 'api-2',
                  'sentry.environment': 'prod',
                  ctx: { nested: true },
                },
              },
            ]),
          ),
        ),
      );
      const { container } = await renderLogs();
      const user = userEvent.setup();
      await screen.findAllByText('checkout failed for user');
      const options = (list: string) =>
        [...container.querySelectorAll(`#${list} option`)].map(
          (option) => (option as HTMLOptionElement).value,
        );

      await user.click(screen.getByRole('button', { name: 'Add filter' }));
      expect(options('log-filter-keys')).toEqual([
        'trace_id',
        'release',
        'server.address',
        'user.id',
        'sentry.sdk.name',
      ]);

      await user.type(screen.getByRole('combobox', { name: 'Filter key' }), 'server.address');
      expect(options('log-filter-values')).toEqual(['api-1', 'api-2']);
    });

    it('says how values are matched when attribute filters leave nothing', async () => {
      server.use(http.get(`${BASE}/logs`, () => HttpResponse.json(page([]))));
      await renderLogs();
      await screen.findByText(/No log records match the current filters/);

      await addFilter('user.id', '42');

      expect(
        await screen.findByText('Attribute values are matched exactly and case-sensitively.'),
      ).toBeInTheDocument();
    });

    /** Equality is exact, so a value the page offered as clickable must reach the API as it was. */
    it('keeps the surrounding whitespace of a clicked value', async () => {
      const attrs: string[][] = [];
      server.use(
        http.get(`${BASE}/logs`, ({ request }) => {
          attrs.push(new URL(request.url).searchParams.getAll('attr'));
          return HttpResponse.json(page([{ ...LOG, attributes: { note: ' padded ' } }]));
        }),
      );
      await renderLogs();
      const user = userEvent.setup();
      await user.click(await screen.findByText('checkout failed for user'));

      await user.click(await screen.findByTitle('Filter logs by note'));

      await waitFor(() => expect(attrs.at(-1)).toEqual(['note= padded ']));
    });

    /** `attr=a=b=c` would read back as key `a`, so the chip would name a filter nobody built. */
    it('refuses a key containing "="', async () => {
      const seen = captureFilterParams();
      await renderLogs();
      await screen.findByText('checkout failed for user');

      await addFilter('a=b', 'c');

      expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled();
      expect(seen.every((request) => request.attr.length === 0)).toBe(true);
    });
  });
});
