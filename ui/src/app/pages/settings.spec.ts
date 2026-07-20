import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import {
  ApiToken,
  AppUser,
  NotificationChannel,
  Project,
  ProjectKey,
  SessionUser,
  UptimeMonitor,
} from '../core/models';
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

const CHANNEL: NotificationChannel = {
  id: 4,
  name: 'Team alerts',
  type: 'teams',
  url: 'https://example.webhook.office.com/webhookb2/abc',
  enabled: true,
  triggers: ['new_issue', 'incident_started'],
  project_filter: [],
  environment_filter: [],
  created_at: '2026-01-01T00:00:00Z',
  last_status: null,
  last_delivery_at: null,
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
    http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([CHANNEL])),
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
      expect(screen.getByRole('button', { name: 'notifications' })).toBeInTheDocument();
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
      expect(screen.queryByRole('button', { name: 'notifications' })).not.toBeInTheDocument();
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
        http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([])),
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
        http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([])),
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

  describe('notifications tab', () => {
    it('lists existing channels with their scope', async () => {
      seedHandlers();
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));

      expect(await screen.findByText('Team alerts')).toBeInTheDocument();
      const table = within(screen.getByRole('table'));
      expect(table.getByText('Teams')).toBeInTheDocument();
      // Empty filters render as "all" in the UI copy.
      expect(table.getByText(/All projects · All environments/)).toBeInTheDocument();
    });

    it('shows the empty state when there are no channels', async () => {
      seedHandlers();
      server.use(http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([])));
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));

      expect(await screen.findByText(/No notification channels yet/)).toBeInTheDocument();
    });

    it('creates a channel with the expected payload, defaulting filters to all', async () => {
      let created: Record<string, unknown> | null = null;
      const channels: NotificationChannel[] = [];
      seedHandlers();
      server.use(
        http.get(`${BASE}/notifications/channels`, () => HttpResponse.json(channels)),
        http.post(`${BASE}/notifications/channels`, async ({ request }) => {
          created = (await request.json()) as Record<string, unknown>;
          const next: NotificationChannel = {
            ...CHANNEL,
            id: 9,
            name: 'Ops',
            type: 'generic_json',
          };
          channels.push(next);
          return HttpResponse.json(next);
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));
      await user.type(await screen.findByLabelText('Name'), 'Ops');
      await user.selectOptions(nativeSelect('channelType'), 'generic_json');
      await user.type(screen.getByLabelText('Webhook URL'), 'https://hooks.example.com/x');
      await user.click(screen.getByLabelText('New issue'));
      await user.type(screen.getByLabelText('Environments'), 'prod');
      await user.click(screen.getByRole('button', { name: /create channel/i }));

      await waitFor(() => expect(screen.getByText('Ops')).toBeInTheDocument());
      expect(created).toEqual({
        name: 'Ops',
        type: 'generic_json',
        url: 'https://hooks.example.com/x',
        enabled: true,
        triggers: ['new_issue'],
        project_filter: [],
        environment_filter: ['prod'],
      });
    });

    it('scopes a channel to a chosen project', async () => {
      let created: Record<string, unknown> | null = null;
      seedHandlers();
      server.use(
        http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([])),
        http.post(`${BASE}/notifications/channels`, async ({ request }) => {
          created = (await request.json()) as Record<string, unknown>;
          return HttpResponse.json({ ...CHANNEL, id: 9 });
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));
      await user.type(await screen.findByLabelText('Name'), 'Shop only');
      await user.type(screen.getByLabelText('Webhook URL'), 'https://hooks.example.com/x');
      await user.click(screen.getByLabelText('Incident started'));
      // The project checkbox is labelled with the project name.
      await user.click(screen.getByLabelText('shop-frontend'));
      await user.click(screen.getByRole('button', { name: /create channel/i }));

      await waitFor(() =>
        expect(created).toEqual({
          name: 'Shop only',
          type: 'teams',
          url: 'https://hooks.example.com/x',
          enabled: true,
          triggers: ['incident_started'],
          project_filter: [1],
          environment_filter: [],
        }),
      );
    });

    it('toggles a channel enabled/disabled without losing its config', async () => {
      let patched: Record<string, unknown> | null = null;
      seedHandlers();
      server.use(
        http.patch(`${BASE}/notifications/channels/:id`, async ({ request }) => {
          patched = (await request.json()) as Record<string, unknown>;
          return HttpResponse.json({ ...CHANNEL, enabled: false });
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));
      await user.click(await screen.findByRole('button', { name: /disable/i }));

      await waitFor(() =>
        expect(patched).toEqual({
          name: CHANNEL.name,
          type: CHANNEL.type,
          url: CHANNEL.url,
          enabled: false,
          triggers: CHANNEL.triggers,
          project_filter: CHANNEL.project_filter,
          environment_filter: CHANNEL.environment_filter,
        }),
      );
    });

    it('requires confirmation before deleting a channel', async () => {
      let deleteCalls = 0;
      const channels = [CHANNEL];
      seedHandlers();
      server.use(
        http.get(`${BASE}/notifications/channels`, () => HttpResponse.json(channels)),
        http.delete(`${BASE}/notifications/channels/:id`, () => {
          deleteCalls += 1;
          channels.length = 0;
          return new HttpResponse(null, { status: 204 });
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));
      await user.click(await screen.findByRole('button', { name: /^delete$/i }));

      // First click only arms the confirmation — nothing deleted yet.
      expect(screen.getByText(/Delete this channel\?/)).toBeInTheDocument();
      expect(deleteCalls).toBe(0);
      expect(screen.getByText('Team alerts')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /confirm/i }));

      expect(await screen.findByText(/No notification channels yet/)).toBeInTheDocument();
      expect(deleteCalls).toBe(1);
    });

    it('keeps the edit form in sync when toggling the channel being edited', async () => {
      const patches: Record<string, unknown>[] = [];
      let channel: NotificationChannel = { ...CHANNEL };
      seedHandlers();
      server.use(
        http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([channel])),
        http.patch(`${BASE}/notifications/channels/:id`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>;
          patches.push(body);
          channel = { ...channel, ...body } as NotificationChannel;
          return HttpResponse.json(channel);
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));
      // Open the channel in the edit form, then disable it from its row.
      await user.click(await screen.findByRole('button', { name: /^edit$/i }));
      await user.click(screen.getByRole('button', { name: /disable/i }));
      await waitFor(() => expect(patches).toHaveLength(1));
      expect(patches[0]['enabled']).toBe(false);

      // Saving the form must not revert the toggle back to enabled.
      await user.click(screen.getByRole('button', { name: /save channel/i }));
      await waitFor(() => expect(patches).toHaveLength(2));
      expect(patches[1]['enabled']).toBe(false);
    });

    it('preserves project ids scoped to a project missing from the loaded list', async () => {
      let patched: Record<string, unknown> | null = null;
      // 999 is not among the loaded projects (only PROJECT id 1) — e.g. deleted.
      const scoped: NotificationChannel = {
        ...CHANNEL,
        id: 5,
        project_filter: [1, 999],
        environment_filter: ['prod'],
      };
      seedHandlers();
      server.use(
        http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([scoped])),
        http.patch(`${BASE}/notifications/channels/:id`, async ({ request }) => {
          patched = (await request.json()) as Record<string, unknown>;
          return HttpResponse.json(scoped);
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));
      await user.click(await screen.findByRole('button', { name: /^edit$/i }));
      await user.click(screen.getByRole('button', { name: /save channel/i }));

      await waitFor(() => expect(patched).not.toBeNull());
      // The stale id survives instead of collapsing the scope to "all projects".
      expect(patched!['project_filter']).toEqual([1, 999]);
    });

    it('shows the last delivery outcome and time per channel', async () => {
      const delivered: NotificationChannel = {
        ...CHANNEL,
        last_status: 'sent',
        last_delivery_at: '2026-07-20T10:00:00Z',
      };
      seedHandlers();
      server.use(http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([delivered])));
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));

      const table = within(await screen.findByRole('table'));
      expect(table.getByText('sent')).toBeInTheDocument();
    });

    it('shows "Never" for a channel with no deliveries', async () => {
      seedHandlers();
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));

      const table = within(await screen.findByRole('table'));
      expect(table.getByText('Never')).toBeInTheDocument();
    });

    it('test-sends a channel and reports a delivered outcome inline', async () => {
      let testCalls = 0;
      seedHandlers();
      const generic: NotificationChannel = { ...CHANNEL, type: 'generic_json' };
      server.use(
        http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([generic])),
        http.post(`${BASE}/notifications/channels/:id/test`, () => {
          testCalls += 1;
          return HttpResponse.json({ status: 'sent', error_detail: null });
        }),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));
      await user.click(await screen.findByRole('button', { name: /^test$/i }));

      expect(await screen.findByText('Test delivered')).toBeInTheDocument();
      expect(testCalls).toBe(1);
    });

    it('reports a failed test-send inline', async () => {
      seedHandlers();
      const generic: NotificationChannel = { ...CHANNEL, type: 'generic_json' };
      server.use(
        http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([generic])),
        http.post(`${BASE}/notifications/channels/:id/test`, () =>
          HttpResponse.json({ status: 'failed', error_detail: 'HTTP 500' }),
        ),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));
      await user.click(await screen.findByRole('button', { name: /^test$/i }));

      expect(await screen.findByText('Test failed')).toBeInTheDocument();
    });

    it('disables the Test button for a disabled channel', async () => {
      const disabled: NotificationChannel = { ...CHANNEL, enabled: false };
      seedHandlers();
      server.use(http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([disabled])));
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));

      expect(await screen.findByRole('button', { name: /^test$/i })).toBeDisabled();
    });

    it('expands a channel to show its recent delivery history', async () => {
      seedHandlers();
      server.use(
        http.get(`${BASE}/notifications/channels/:id/history`, () =>
          HttpResponse.json([
            {
              id: 20,
              trigger_type: 'test',
              status: 'sent',
              summary: 'test: Team alerts',
              error_detail: null,
              created_at: '2026-07-20T10:00:00Z',
              updated_at: '2026-07-20T10:00:00Z',
            },
            {
              id: 19,
              trigger_type: 'new_issue',
              status: 'failed',
              summary: 'new_issue: boom',
              error_detail: 'HTTP 500',
              created_at: '2026-07-19T10:00:00Z',
              updated_at: '2026-07-19T10:00:00Z',
            },
          ]),
        ),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));
      await user.click(await screen.findByRole('button', { name: /^history$/i }));

      expect(await screen.findByText('Recent deliveries')).toBeInTheDocument();
      // Both rows rendered: the failed row's error detail and both statuses show.
      expect(screen.getByText('HTTP 500')).toBeInTheDocument();
      expect(screen.getByText('sent')).toBeInTheDocument();
      expect(screen.getByText('failed')).toBeInTheDocument();
    });

    it('shows an empty history state when a channel has no deliveries', async () => {
      seedHandlers();
      server.use(
        http.get(`${BASE}/notifications/channels/:id/history`, () => HttpResponse.json([])),
      );
      await renderSettings('admin');
      const user = userEvent.setup();

      await user.click(screen.getByRole('button', { name: 'notifications' }));
      await user.click(await screen.findByRole('button', { name: /^history$/i }));

      expect(await screen.findByText('No deliveries yet.')).toBeInTheDocument();
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
        http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([])),
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
