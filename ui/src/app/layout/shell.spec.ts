import { provideHttpClient } from '@angular/common/http';
import { signal, WritableSignal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import { http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import { GlobalFilters } from '../core/filters';
import { Project, SessionUser } from '../core/models';
import { Session } from '../core/session';
import { Shell } from './shell';

const BASE = '*/api/internal';

const PROJECTS: Project[] = [
  {
    id: 1,
    slug: 'shop-frontend',
    name: 'shop-frontend',
    platform: 'javascript-angular',
    created_at: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    slug: 'shop-backend',
    name: 'shop-backend',
    platform: 'java',
    created_at: '2026-01-01T00:00:00Z',
  },
];

function fakeSession(): Session {
  const user = signal<SessionUser | null>({ email: 'me@example.com', role: 'admin' });
  return { user, isAdmin: () => true, logout: () => Promise.resolve() } as unknown as Session;
}

/** GlobalFilters stand-in: the shell reads the signals and calls the setters. */
function fakeFilters(project: number[], environments: string[] = []) {
  return {
    project: signal<number[]>(project),
    environments: signal<string[]>(environments),
    range: signal<string>('14d'),
    from: signal<string | undefined>(undefined),
    setProjects: vi.fn(),
    setEnvironments: vi.fn(),
    setRange: vi.fn(),
  };
}

/**
 * Renders the shell against a fake GlobalFilters and an environment-intersection
 * endpoint that echoes an intersection keyed on the in-scope `project` params, so
 * changing the project signal drives a realistic refetch.
 */
async function renderShell(
  filters: ReturnType<typeof fakeFilters>,
  intersectionFor: (projectIds: string[]) => string[] | 'error',
) {
  server.use(
    http.get(`${BASE}/projects`, () => HttpResponse.json(PROJECTS)),
    http.get(`${BASE}/projects/environments`, ({ request }) => {
      const ids = new URL(request.url).searchParams.getAll('project');
      const result = intersectionFor(ids);
      return result === 'error'
        ? new HttpResponse(null, { status: 500 })
        : HttpResponse.json(result);
    }),
  );
  return render(Shell, {
    providers: [
      provideHttpClient(),
      provideRouter([]),
      { provide: Session, useValue: fakeSession() },
      { provide: GlobalFilters, useValue: filters as unknown as GlobalFilters },
    ],
  });
}

describe('Shell project multi-select', () => {
  it('renders a chip per in-scope Project, resolved to its name', async () => {
    await renderShell(fakeFilters([1, 2]), () => []);

    await waitFor(() => expect(screen.getByText('shop-frontend')).toBeInTheDocument());
    expect(screen.getByText('shop-backend')).toBeInTheDocument();
  });

  it('shows the "All projects" placeholder when nothing is selected', async () => {
    await renderShell(fakeFilters([]), () => []);

    await waitFor(() => expect(screen.getByPlaceholderText('All projects')).toBeInTheDocument());
  });
});

describe('Shell environment-intersection bar', () => {
  it('shows the intersection of Environment Names for the in-scope Projects', async () => {
    // frontend {local,dev,qa} ∩ backend {dev,qa} = dev, qa (computed server-side).
    await renderShell(fakeFilters([1, 2]), () => ['dev', 'qa']);

    await waitFor(() => expect(screen.getByRole('button', { name: 'dev' })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'qa' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'local' })).not.toBeInTheDocument();
  });
});

describe('Shell environment pruning', () => {
  it('prunes the active environment filter to the names still in the new intersection', async () => {
    // Project 1 alone intersects to {local,dev,qa}; adding project 2 narrows it to
    // {dev,qa}, so an active {local,dev} filter should prune to {dev} — not clear.
    const filters = fakeFilters([1], ['local', 'dev']);
    await renderShell(filters, (ids) =>
      ids.includes('2') ? ['dev', 'qa'] : ['local', 'dev', 'qa'],
    );

    // Initial intersection keeps both names — no prune while the filter stays valid.
    await waitFor(() => expect(screen.getByRole('button', { name: 'dev' })).toBeInTheDocument());
    expect(filters.setEnvironments).not.toHaveBeenCalled();

    // Widen the selection; the bar refetches and 'local' drops out of the intersection.
    (filters.project as WritableSignal<number[]>).set([1, 2]);

    await waitFor(() => expect(filters.setEnvironments).toHaveBeenCalledWith(['dev']));
  });

  it('leaves the environment filter untouched when every active name survives', async () => {
    const filters = fakeFilters([1], ['dev']);
    await renderShell(filters, (ids) =>
      ids.includes('2') ? ['dev', 'qa'] : ['local', 'dev', 'qa'],
    );

    await waitFor(() => expect(screen.getByRole('button', { name: 'dev' })).toBeInTheDocument());
    (filters.project as WritableSignal<number[]>).set([1, 2]);

    // 'dev' is still in {dev,qa}; give the refetch room to land, then assert no prune.
    await waitFor(() => expect(screen.getByRole('button', { name: 'qa' })).toBeInTheDocument());
    expect(filters.setEnvironments).not.toHaveBeenCalled();
  });

  it('preserves a shared-URL environment filter on the first load, even outside the intersection', async () => {
    // A reloaded/shared URL carries its own environment filter; the first
    // intersection to settle reflects that URL, not a selection change, so it
    // must not prune — 'prod' survives even though it isn't in {dev,qa} (AC3).
    const filters = fakeFilters([1], ['prod']);
    await renderShell(filters, () => ['dev', 'qa']);

    await waitFor(() => expect(screen.getByRole('button', { name: 'dev' })).toBeInTheDocument());
    expect(filters.setEnvironments).not.toHaveBeenCalled();
  });

  it('does not prune when the intersection refetch fails', async () => {
    // On error the resource value falls back to its empty default; treating that
    // as an intersection would wrongly clear a valid filter, so a failed refetch
    // must leave the environment filter alone (AC5: prune, never clear).
    const filters = fakeFilters([1], ['local', 'dev']);
    await renderShell(filters, (ids) => (ids.includes('2') ? 'error' : ['local', 'dev', 'qa']));

    await waitFor(() => expect(screen.getByRole('button', { name: 'dev' })).toBeInTheDocument());
    (filters.project as WritableSignal<number[]>).set([1, 2]);

    // The errored refetch clears the bar (empty default) but must not prune.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'dev' })).not.toBeInTheDocument(),
    );
    expect(filters.setEnvironments).not.toHaveBeenCalled();
  });
});
