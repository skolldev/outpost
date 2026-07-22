import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../../mocks/node';
import { Feedback } from '../../../core/feedback';
import { NotificationChannel, NotificationHistoryEntry } from '../../../core/models';
import { NotificationChannelsSettings } from './notification-channels';

const BASE = '*/api/internal';

/** hlm-select renders an ARIA combobox; open it by its label, then pick an option. */
async function pickOption(
  user: ReturnType<typeof userEvent.setup>,
  comboboxName: string,
  optionName: string,
): Promise<void> {
  await user.click(await screen.findByRole('combobox', { name: comboboxName }));
  await user.click(await screen.findByRole('option', { name: optionName }));
}

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

const HISTORY: NotificationHistoryEntry = {
  id: 20,
  trigger_type: 'new_issue',
  status: 'sent',
  summary: 'New issue in shop-frontend',
  error_detail: null,
  created_at: '2026-01-02T00:00:00Z',
  updated_at: '2026-01-02T00:00:00Z',
};

let feedback: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

function renderChannels() {
  feedback = { success: vi.fn(), error: vi.fn() };
  return render(NotificationChannelsSettings, {
    providers: [provideHttpClient(), { provide: Feedback, useValue: feedback }],
  });
}

describe('NotificationChannelsSettings', () => {
  it('lists existing channels', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([])),
      http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([CHANNEL])),
    );
    await renderChannels();

    expect(await screen.findByText('Team alerts')).toBeInTheDocument();
  });

  it('creates a channel and reloads the list', async () => {
    let created: { name: string; triggers: string[] } | null = null;
    const channels: NotificationChannel[] = [];
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([])),
      http.get(`${BASE}/notifications/channels`, () => HttpResponse.json(channels)),
      http.post(`${BASE}/notifications/channels`, async ({ request }) => {
        created = (await request.json()) as typeof created;
        channels.push(CHANNEL);
        return HttpResponse.json(CHANNEL);
      }),
    );
    await renderChannels();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'Team alerts');
    await user.type(screen.getByLabelText('Webhook URL'), 'https://example.webhook.office.com/x');
    await user.click(screen.getByLabelText('New issue'));
    await user.click(screen.getByRole('button', { name: 'Create channel' }));

    await waitFor(() =>
      expect(within(screen.getByRole('table')).getByText('Team alerts')).toBeInTheDocument(),
    );
    expect(created).toMatchObject({ name: 'Team alerts', triggers: ['new_issue'] });
    expect(feedback.success).toHaveBeenCalledWith('Channel created.');
  });

  it('creates a channel with the type chosen from the select', async () => {
    let created: { type: string } | null = null;
    const channels: NotificationChannel[] = [];
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([])),
      http.get(`${BASE}/notifications/channels`, () => HttpResponse.json(channels)),
      http.post(`${BASE}/notifications/channels`, async ({ request }) => {
        created = (await request.json()) as typeof created;
        channels.push(CHANNEL);
        return HttpResponse.json(CHANNEL);
      }),
    );
    await renderChannels();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'JSON hook');
    await user.type(screen.getByLabelText('Webhook URL'), 'https://example.com/hook');
    await pickOption(user, 'Type', 'Generic JSON');
    await user.click(screen.getByLabelText('Incident started'));
    await user.click(screen.getByRole('button', { name: 'Create channel' }));

    await waitFor(() => expect(created).toMatchObject({ type: 'generic_json' }));
  });

  it('keeps the submit disabled until name, url, and a trigger are valid', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([])),
      http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([])),
    );
    await renderChannels();
    const user = userEvent.setup();

    const submit = screen.getByRole('button', { name: 'Create channel' });
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText('Name'), 'Team alerts');
    await user.type(screen.getByLabelText('Webhook URL'), 'https://example.webhook.office.com/x');
    expect(submit).toBeDisabled(); // no trigger selected yet

    await user.click(screen.getByLabelText('New issue'));
    expect(submit).toBeEnabled();
  });

  it('shows an inline error for an invalid url', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([])),
      http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([])),
    );
    await renderChannels();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Webhook URL'), 'not-a-url');
    await user.tab();

    expect(await screen.findByText('Enter a valid http(s) URL.')).toBeInTheDocument();
  });

  it('surfaces an error when saving fails', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([])),
      http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([])),
      http.post(`${BASE}/notifications/channels`, () => new HttpResponse(null, { status: 400 })),
    );
    await renderChannels();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'Team alerts');
    await user.type(screen.getByLabelText('Webhook URL'), 'https://example.webhook.office.com/x');
    await user.click(screen.getByLabelText('New issue'));
    await user.click(screen.getByRole('button', { name: 'Create channel' }));

    await waitFor(() =>
      expect(feedback.error).toHaveBeenCalledWith(
        'Could not save notification channel — check the URL and pick a trigger.',
      ),
    );
  });

  it('edits a channel and round-trips the non-visible enabled flag', async () => {
    const disabled: NotificationChannel = { ...CHANNEL, enabled: false };
    let updated: { name: string; enabled: boolean; triggers: string[] } | null = null;
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([])),
      http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([disabled])),
      http.patch(`${BASE}/notifications/channels/:id`, async ({ request }) => {
        updated = (await request.json()) as typeof updated;
        return HttpResponse.json(disabled);
      }),
    );
    await renderChannels();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    const name = screen.getByLabelText('Name');
    await user.clear(name);
    await user.type(name, 'Renamed alerts');
    await user.click(screen.getByRole('button', { name: 'Save channel' }));

    await waitFor(() => expect(updated).not.toBeNull());
    expect(updated).toMatchObject({
      name: 'Renamed alerts',
      enabled: false,
      triggers: ['new_issue', 'incident_started'],
    });
  });

  it('expands a channel to load its delivery history', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([])),
      http.get(`${BASE}/notifications/channels`, () => HttpResponse.json([CHANNEL])),
      http.get(`${BASE}/notifications/channels/:id/history`, () => HttpResponse.json([HISTORY])),
    );
    await renderChannels();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'History' }));

    expect(await screen.findByText('Recent deliveries')).toBeInTheDocument();
    // History rows show the delivery status (channel last_status is "Never").
    expect(screen.getByText('sent')).toBeInTheDocument();
  });
});
