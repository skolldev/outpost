import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import { http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import { GlobalFilters } from '../core/filters';
import { Project, SessionUser } from '../core/models';
import { Session } from '../core/session';
import { Shell } from './shell';

const BASE = '*/api/internal';

const PROJECT: Project = {
  id: 1,
  slug: 'shop-frontend',
  name: 'shop-frontend',
  platform: 'javascript-angular',
  created_at: '2026-01-01T00:00:00Z',
};

function fakeSession(): Session {
  const user = signal<SessionUser | null>({ email: 'me@example.com', role: 'admin' });
  return { user, isAdmin: () => true, logout: () => Promise.resolve() } as unknown as Session;
}

/** GlobalFilters stand-in: the shell only reads the signals and calls the setters. */
function fakeFilters(project: number | undefined): GlobalFilters {
  return {
    project: signal<number | undefined>(project),
    environments: signal<string[]>([]),
    range: signal<string>('14d'),
    from: signal<string | undefined>(undefined),
    setProject: vi.fn(),
    setEnvironments: vi.fn(),
    setRange: vi.fn(),
  } as unknown as GlobalFilters;
}

async function renderShell(project: number | undefined) {
  server.use(
    http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
    http.get(`${BASE}/projects/:id/environments`, () => HttpResponse.json(['prod'])),
  );
  return render(Shell, {
    providers: [
      provideHttpClient(),
      provideRouter([]),
      { provide: Session, useValue: fakeSession() },
      { provide: GlobalFilters, useValue: fakeFilters(project) },
    ],
  });
}

describe('Shell project selector', () => {
  it('shows the selected project even when the list loads after the value is bound', async () => {
    // Regression for #58: on reload the `project` query param is present before
    // the async project list arrives, so the selector must display the selected
    // project's name once the list resolves — not silently fall back to "All
    // projects". The model-driven select renders only the *selected* label; the
    // options stay in a closed portal, so "All projects" is absent from the DOM.
    await renderShell(1);

    await waitFor(() => expect(screen.getByText('shop-frontend')).toBeInTheDocument());
    expect(screen.queryByText('All projects')).not.toBeInTheDocument();
  });

  it('shows "All projects" when no project is selected', async () => {
    await renderShell(undefined);

    await waitFor(() => expect(screen.getByText('All projects')).toBeInTheDocument());
    expect(screen.queryByText('shop-frontend')).not.toBeInTheDocument();
  });
});
