import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import { ApiToken, AppUser, Project, ProjectKey, SessionUser } from '../core/models';
import { Session } from '../core/session';
import { SettingsPage } from './settings';

const BASE = '*/api/internal';

const PROJECT: Project = {
  id: 1,
  slug: 'shop-frontend',
  name: 'shop-frontend',
  platform: 'javascript-angular',
  created_at: '2026-01-01T00:00:00Z',
};

const KEY: ProjectKey = {
  id: 10,
  project_id: 1,
  public_key: 'pub-abc',
  is_active: true,
  created_at: '2026-01-01T00:00:00Z',
  dsn: 'https://pub-abc@outpost.example/1',
};

const USER: AppUser = {
  id: 5,
  email: 'member@example.com',
  role: 'member',
  created_at: '2026-01-01T00:00:00Z',
};

const TOKEN: ApiToken = {
  id: 7,
  name: 'ci-shop',
  scopes: ['artifacts:write'],
  created_at: '2026-01-01T00:00:00Z',
};

/**
 * Minimal Session stand-in: the component only ever reads `isAdmin()` (and the
 * template reads it too), so we skip the real cookie/HTTP-backed Session.
 */
function fakeSession(role: 'admin' | 'member'): Session {
  const user = signal<SessionUser | null>({ email: 'me@example.com', role });
  return { user, isAdmin: () => role === 'admin' } as unknown as Session;
}

/** Default happy-path handlers; individual tests override with `server.use`. */
function seedHandlers() {
  server.use(
    http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
    http.get(`${BASE}/projects/:id/keys`, () => HttpResponse.json([KEY])),
    http.get(`${BASE}/users`, () => HttpResponse.json([USER])),
    http.get(`${BASE}/tokens`, () => HttpResponse.json([TOKEN])),
  );
}

async function renderSettings(role: 'admin' | 'member' = 'admin') {
  return render(SettingsPage, {
    providers: [provideHttpClient(), { provide: Session, useValue: fakeSession(role) }],
  });
}

describe('SettingsPage', () => {
  describe('tabs & role gating', () => {
    it('shows all three tabs for an admin', async () => {
      seedHandlers();
      await renderSettings('admin');

      expect(screen.getByRole('button', { name: 'projects' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'tokens' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'users' })).toBeInTheDocument();
    });

    it('hides the tokens tab for a member', async () => {
      seedHandlers();
      await renderSettings('member');

      expect(screen.getByRole('button', { name: 'projects' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'users' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'tokens' })).not.toBeInTheDocument();
    });

    it('hides the create-project form from members', async () => {
      seedHandlers();
      await renderSettings('member');

      expect(screen.queryByRole('button', { name: /create project/i })).not.toBeInTheDocument();
    });
  });

  describe('projects tab', () => {
    it('lists projects loaded on init', async () => {
      seedHandlers();
      await renderSettings('admin');

      expect(await screen.findByText('shop-frontend')).toBeInTheDocument();
    });

    it('shows the empty state when there are no projects', async () => {
      server.use(
        http.get(`${BASE}/projects`, () => HttpResponse.json([])),
        http.get(`${BASE}/users`, () => HttpResponse.json([])),
        http.get(`${BASE}/tokens`, () => HttpResponse.json([])),
      );
      await renderSettings('admin');

      expect(await screen.findByText(/No projects yet/)).toBeInTheDocument();
    });

    it('creates a project and reloads the list', async () => {
      let created: { slug: string; name: string; platform: string | null } | null = null;
      const projects = [PROJECT];
      server.use(
        http.get(`${BASE}/projects`, () => HttpResponse.json(projects)),
        http.post(`${BASE}/projects`, async ({ request }) => {
          created = (await request.json()) as typeof created;
          const next: Project = { ...PROJECT, id: 2, slug: 'new-app', name: 'new-app' };
          projects.push(next);
          return HttpResponse.json(next);
        }),
        http.get(`${BASE}/projects/:id/keys`, () => HttpResponse.json([KEY])),
        http.get(`${BASE}/users`, () => HttpResponse.json([USER])),
        http.get(`${BASE}/tokens`, () => HttpResponse.json([TOKEN])),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.type(screen.getByLabelText('Slug'), 'new-app');
      await user.click(screen.getByRole('button', { name: /create project/i }));

      await waitFor(() => expect(screen.getByText('new-app')).toBeInTheDocument());
      expect(created).toEqual({ slug: 'new-app', name: 'new-app', platform: 'javascript-angular' });
    });

    it('surfaces an error when project creation fails', async () => {
      seedHandlers();
      server.use(http.post(`${BASE}/projects`, () => new HttpResponse(null, { status: 400 })));
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.type(screen.getByLabelText('Slug'), 'dup-slug');
      await user.click(screen.getByRole('button', { name: /create project/i }));

      expect(await screen.findByText(/Could not create project/)).toBeInTheDocument();
    });

    it('expands a project to reveal its DSN keys and SDK snippets', async () => {
      seedHandlers();
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(await screen.findByRole('button', { name: /shop-frontend/ }));

      expect(await screen.findByText('DSN keys')).toBeInTheDocument();
      expect(screen.getByText(KEY.dsn)).toBeInTheDocument();
      expect(screen.getByText('SDK setup')).toBeInTheDocument();
    });
  });

  describe('tokens tab', () => {
    it('lists existing tokens', async () => {
      seedHandlers();
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'tokens' }));

      expect(await screen.findByText('ci-shop')).toBeInTheDocument();
    });

    it('creates a token and reveals the one-time secret', async () => {
      seedHandlers();
      server.use(
        http.post(`${BASE}/tokens`, () =>
          HttpResponse.json({ ...TOKEN, id: 8, name: 'ci-new', token: 'secret-xyz' }),
        ),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'tokens' }));
      await user.type(await screen.findByLabelText('Name'), 'ci-new');
      await user.click(screen.getByRole('button', { name: /create token/i }));

      expect(await screen.findByText('secret-xyz')).toBeInTheDocument();
      expect(screen.getByText(/shown only once/)).toBeInTheDocument();
    });
  });

  describe('users tab', () => {
    it('lists users', async () => {
      seedHandlers();
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'users' }));

      expect(await screen.findByText('member@example.com')).toBeInTheDocument();
    });

    it('creates a user and reloads the list', async () => {
      const users = [USER];
      server.use(
        http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
        http.get(`${BASE}/tokens`, () => HttpResponse.json([TOKEN])),
        http.get(`${BASE}/users`, () => HttpResponse.json(users)),
        http.post(`${BASE}/users`, async ({ request }) => {
          const body = (await request.json()) as { email: string; role: string };
          const next: AppUser = { ...USER, id: 6, email: body.email, role: 'admin' };
          users.push(next);
          return HttpResponse.json(next);
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'users' }));
      await user.type(await screen.findByLabelText('Email'), 'new@example.com');
      await user.type(screen.getByLabelText('Password'), 'password123');
      await user.click(screen.getByRole('button', { name: /add user/i }));

      expect(await screen.findByText('new@example.com')).toBeInTheDocument();
    });
  });
});
