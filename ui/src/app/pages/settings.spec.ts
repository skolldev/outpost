import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import { ApiToken, AppUser, Project, ProjectKey, SessionUser, UptimeMonitor } from '../core/models';
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

const MONITOR: UptimeMonitor = {
  id: 3,
  project_id: 1,
  project_slug: 'shop-frontend',
  environment: 'prod',
  url: 'https://shop.example.com/health',
  interval_seconds: 60,
  timeout_seconds: 10,
  consecutive_failures: 0,
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
    http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json([MONITOR])),
    http.get(`${BASE}/projects/:id/environments`, () => HttpResponse.json(['prod', 'dev'])),
    http.get(`${BASE}/settings/data-retention`, () =>
      HttpResponse.json({ enabled: false, retention_days: 90 }),
    ),
  );
}

/**
 * hlm-native-select renders the real <select> inside a non-labellable wrapper
 * (the label's `for` points at the wrapper), so label-based queries can't
 * reach it — grab it via the wrapper id instead.
 */
function nativeSelect(wrapperId: string): HTMLSelectElement {
  const select = document.querySelector<HTMLSelectElement>(`#${wrapperId} select`);
  if (!select) throw new Error(`no native select rendered in #${wrapperId}`);
  return select;
}

async function renderSettings(role: 'admin' | 'member' = 'admin') {
  return render(SettingsPage, {
    providers: [provideHttpClient(), { provide: Session, useValue: fakeSession(role) }],
  });
}

describe('SettingsPage', () => {
  describe('tabs & role gating', () => {
    it('shows all admin tabs for an admin', async () => {
      seedHandlers();
      await renderSettings('admin');

      expect(screen.getByRole('button', { name: 'projects' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'uptime' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Data retention' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'tokens' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'users' })).toBeInTheDocument();
    });

    it('hides the admin-only tabs for a member', async () => {
      seedHandlers();
      await renderSettings('member');

      expect(screen.getByRole('button', { name: 'projects' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'users' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'tokens' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'uptime' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Data retention' })).not.toBeInTheDocument();
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
        http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json([])),
        http.get(`${BASE}/settings/data-retention`, () =>
          HttpResponse.json({ enabled: false, retention_days: 90 }),
        ),
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
        http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json([])),
        http.get(`${BASE}/settings/data-retention`, () =>
          HttpResponse.json({ enabled: false, retention_days: 90 }),
        ),
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

  describe('data retention tab', () => {
    it('loads and hydrates the saved setting for admins', async () => {
      seedHandlers();
      server.use(
        http.get(`${BASE}/settings/data-retention`, () =>
          HttpResponse.json({ enabled: true, retention_days: 60 }),
        ),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'Data retention' }));

      await waitFor(() =>
        expect(screen.getByLabelText('Automatically delete old data')).toBeChecked(),
      );
      expect(nativeSelect('retentionDays')).toHaveValue('60');
      expect(screen.getByText(/next daily cleanup run at 02:00 UTC/i)).toBeInTheDocument();
      expect(screen.getByText('Permanent deletion')).toBeInTheDocument();
    });

    it('defaults to unchecked and enables the duration only when checked', async () => {
      seedHandlers();
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'Data retention' }));
      const checkbox = screen.getByLabelText('Automatically delete old data');
      const duration = nativeSelect('retentionDays');
      await waitFor(() =>
        expect(screen.queryByLabelText('Loading data retention settings')).toBeNull(),
      );

      expect(checkbox).not.toBeChecked();
      expect(duration).toBeDisabled();
      expect(duration).toHaveValue('90');
      await user.click(checkbox);
      expect(duration).toBeEnabled();
    });

    it('shows a loading state while the setting request is pending', async () => {
      seedHandlers();
      server.use(
        http.get(`${BASE}/settings/data-retention`, async () => {
          await delay(100);
          return HttpResponse.json({ enabled: false, retention_days: 90 });
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'Data retention' }));

      expect(screen.getByLabelText('Loading data retention settings')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Save data retention' })).toBeDisabled();
      await waitFor(() =>
        expect(screen.queryByLabelText('Loading data retention settings')).not.toBeInTheDocument(),
      );
    });

    it.each([30, 60, 90] as const)('saves an exact %i-day payload', async (days) => {
      let saved: { enabled: boolean; retention_days: number } | null = null;
      seedHandlers();
      server.use(
        http.put(`${BASE}/settings/data-retention`, async ({ request }) => {
          saved = (await request.json()) as { enabled: boolean; retention_days: number };
          return HttpResponse.json(saved);
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'Data retention' }));
      await waitFor(() => expect(nativeSelect('retentionDays')).toHaveValue('90'));
      await user.click(screen.getByLabelText('Automatically delete old data'));
      await user.selectOptions(nativeSelect('retentionDays'), String(days));
      await user.click(screen.getByRole('button', { name: 'Save data retention' }));

      await waitFor(() => expect(saved).toEqual({ enabled: true, retention_days: days }));
      expect(await screen.findByText('Settings saved')).toBeInTheDocument();
      expect(screen.getByText(/next 02:00 UTC run/i)).toBeInTheDocument();
    });

    it('shows an error when saving fails', async () => {
      seedHandlers();
      server.use(
        http.put(`${BASE}/settings/data-retention`, () => new HttpResponse(null, { status: 500 })),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'Data retention' }));
      await waitFor(() =>
        expect(screen.queryByLabelText('Loading data retention settings')).toBeNull(),
      );
      await user.click(screen.getByRole('button', { name: 'Save data retention' }));

      expect(
        await screen.findByText('Could not save data retention settings.'),
      ).toBeInTheDocument();
    });

    it('does not load the admin setting for members', async () => {
      let requests = 0;
      seedHandlers();
      server.use(
        http.get(`${BASE}/settings/data-retention`, () => {
          requests += 1;
          return HttpResponse.json({ enabled: false, retention_days: 90 });
        }),
      );

      await renderSettings('member');
      await delay(10);

      expect(requests).toBe(0);
    });
  });

  describe('uptime tab', () => {
    it('lists existing monitors', async () => {
      seedHandlers();
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'uptime' }));

      expect(await screen.findByText('https://shop.example.com/health')).toBeInTheDocument();
      const table = within(screen.getByRole('table'));
      expect(table.getByText('1m')).toBeInTheDocument();
      expect(table.getByText('10s')).toBeInTheDocument();
    });

    it('creates a monitor with the expected payload', async () => {
      let created: Record<string, unknown> | null = null;
      const monitors: UptimeMonitor[] = [];
      seedHandlers();
      server.use(
        http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json(monitors)),
        http.post(`${BASE}/uptime/monitors`, async ({ request }) => {
          created = (await request.json()) as Record<string, unknown>;
          monitors.push(MONITOR);
          return HttpResponse.json(MONITOR);
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'uptime' }));
      await user.selectOptions(nativeSelect('monitorProject'), '1');
      // The environment field switches from free-text input to a select once
      // the project's environments have loaded.
      await waitFor(() => nativeSelect('monitorEnv'));
      await user.selectOptions(nativeSelect('monitorEnv'), 'prod');
      await user.type(screen.getByLabelText('URL'), 'https://shop.example.com/health');
      await user.selectOptions(nativeSelect('monitorInterval'), '300');
      await user.click(screen.getByRole('button', { name: /create monitor/i }));

      await waitFor(() =>
        expect(screen.getByText('https://shop.example.com/health')).toBeInTheDocument(),
      );
      expect(created).toEqual({
        project_id: 1,
        environment: 'prod',
        url: 'https://shop.example.com/health',
        interval_seconds: 300,
        timeout_seconds: 10,
      });
    });

    it('shows a successful test-connection result', async () => {
      seedHandlers();
      server.use(
        http.post(`${BASE}/uptime/monitors/test`, () =>
          HttpResponse.json({ success: true, status_code: 200, latency_ms: 42, error: null }),
        ),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'uptime' }));
      await user.type(await screen.findByLabelText('URL'), 'https://shop.example.com/health');
      await user.click(screen.getByRole('button', { name: /test connection/i }));

      expect(await screen.findByText(/Success — HTTP 200 · 42 ms/)).toBeInTheDocument();
    });

    it('shows a failed test-connection result', async () => {
      seedHandlers();
      server.use(
        http.post(`${BASE}/uptime/monitors/test`, () =>
          HttpResponse.json({
            success: false,
            status_code: null,
            latency_ms: 5000,
            error: 'java.net.http.HttpTimeoutException: request timed out',
          }),
        ),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'uptime' }));
      await user.type(await screen.findByLabelText('URL'), 'https://slow.example.com');
      await user.click(screen.getByRole('button', { name: /test connection/i }));

      expect(await screen.findByText(/Failed/)).toBeInTheDocument();
      expect(screen.getByText(/HttpTimeoutException/)).toBeInTheDocument();
    });

    it('deletes a monitor and reloads the list', async () => {
      const monitors = [MONITOR];
      seedHandlers();
      server.use(
        http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json(monitors)),
        http.delete(`${BASE}/uptime/monitors/:id`, () => {
          monitors.length = 0;
          return new HttpResponse(null, { status: 204 });
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'uptime' }));
      await user.click(await screen.findByRole('button', { name: /delete/i }));

      expect(await screen.findByText(/No uptime monitors yet/)).toBeInTheDocument();
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
        http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json([])),
        http.get(`${BASE}/settings/data-retention`, () =>
          HttpResponse.json({ enabled: false, retention_days: 90 }),
        ),
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
