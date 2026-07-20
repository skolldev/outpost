import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../../mocks/node';
import { Project, UptimeMonitor } from '../../../core/models';
import { UptimeMonitorsSettings } from './uptime-monitors';

const BASE = '*/api/internal';

const PROJECT: Project = {
  id: 1,
  slug: 'shop-frontend',
  name: 'shop-frontend',
  platform: 'javascript-angular',
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

/** hlm-native-select hides the real <select> behind a non-labellable wrapper id. */
function nativeSelect(wrapperId: string): HTMLSelectElement {
  const select = document.querySelector<HTMLSelectElement>(`#${wrapperId} select`);
  if (!select) throw new Error(`no native select rendered in #${wrapperId}`);
  return select;
}

function renderMonitors() {
  return render(UptimeMonitorsSettings, { providers: [provideHttpClient()] });
}

describe('UptimeMonitorsSettings', () => {
  it('lists existing monitors', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
      http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json([MONITOR])),
    );
    await renderMonitors();

    expect(await screen.findByText('https://shop.example.com/health')).toBeInTheDocument();
  });

  it('loads the environment list when a project is picked', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
      http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json([])),
      http.get(`${BASE}/projects/:id/environments`, () => HttpResponse.json(['prod', 'dev'])),
    );
    await renderMonitors();
    const user = userEvent.setup();

    await waitFor(() =>
      expect(within(nativeSelect('monitorProject')).getAllByRole('option')).toHaveLength(2),
    );
    await user.selectOptions(nativeSelect('monitorProject'), '1');

    await waitFor(() =>
      expect(
        within(nativeSelect('monitorEnv')).getByRole('option', { name: 'prod' }),
      ).toBeInTheDocument(),
    );
  });

  it('creates a monitor and reloads the list', async () => {
    let created: unknown = null;
    const monitors: UptimeMonitor[] = [];
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
      http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json(monitors)),
      http.get(`${BASE}/projects/:id/environments`, () => HttpResponse.json(['prod'])),
      http.post(`${BASE}/uptime/monitors`, async ({ request }) => {
        created = await request.json();
        monitors.push(MONITOR);
        return HttpResponse.json(MONITOR);
      }),
    );
    await renderMonitors();
    const user = userEvent.setup();

    await waitFor(() =>
      expect(within(nativeSelect('monitorProject')).getAllByRole('option')).toHaveLength(2),
    );
    await user.selectOptions(nativeSelect('monitorProject'), '1');
    await waitFor(() => expect(nativeSelect('monitorEnv')).toBeInTheDocument());
    await user.selectOptions(nativeSelect('monitorEnv'), 'prod');
    await user.type(screen.getByLabelText('URL'), 'https://shop.example.com/health');
    await user.click(screen.getByRole('button', { name: 'Create monitor' }));

    await waitFor(() =>
      expect(
        within(screen.getByRole('table')).getByText('https://shop.example.com/health'),
      ).toBeInTheDocument(),
    );
    expect(created).toMatchObject({
      project_id: 1,
      environment: 'prod',
      url: 'https://shop.example.com/health',
    });
  });
});
