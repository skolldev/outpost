import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../../mocks/node';
import { NotificationChannel, NotificationHistoryEntry } from '../../../core/models';
import { NotificationChannelsSettings } from './notification-channels';

const BASE = '*/api/internal';

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

function renderChannels() {
  return render(NotificationChannelsSettings, { providers: [provideHttpClient()] });
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
