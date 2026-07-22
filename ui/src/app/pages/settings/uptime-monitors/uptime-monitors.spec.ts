import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../../mocks/node';
import { Feedback } from '../../../core/feedback';
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

/** hlm-select renders an ARIA combobox; open it by its label, then pick an option. */
async function pickOption(
  user: ReturnType<typeof userEvent.setup>,
  comboboxName: string,
  optionName: string,
): Promise<void> {
  await user.click(await screen.findByRole('combobox', { name: comboboxName }));
  await user.click(await screen.findByRole('option', { name: optionName }));
}

let feedback: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

function renderMonitors() {
  feedback = { success: vi.fn(), error: vi.fn() };
  return render(UptimeMonitorsSettings, {
    providers: [provideHttpClient(), { provide: Feedback, useValue: feedback }],
  });
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

    await pickOption(user, 'Project', 'shop-frontend');

    // Environment turns into a select once the project's environments load.
    await user.click(await screen.findByRole('combobox', { name: 'Environment' }));
    expect(await screen.findByRole('option', { name: 'prod' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'dev' })).toBeInTheDocument();
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

    await pickOption(user, 'Project', 'shop-frontend');
    await pickOption(user, 'Environment', 'prod');
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
      interval_seconds: 60,
      timeout_seconds: 10,
    });
    expect(feedback.success).toHaveBeenCalledWith('Monitor created.');
  });

  it('keeps the submit disabled until project, environment, and url are valid', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
      http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json([])),
      http.get(`${BASE}/projects/:id/environments`, () => HttpResponse.json(['prod'])),
    );
    await renderMonitors();
    const user = userEvent.setup();

    const submit = screen.getByRole('button', { name: 'Create monitor' });
    expect(submit).toBeDisabled();

    await pickOption(user, 'Project', 'shop-frontend');
    await pickOption(user, 'Environment', 'prod');
    expect(submit).toBeDisabled(); // url still missing

    await user.type(screen.getByLabelText('URL'), 'https://shop.example.com/health');
    expect(submit).toBeEnabled();
  });

  it('shows an inline error for an invalid url', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
      http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json([])),
    );
    await renderMonitors();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('URL'), 'not-a-url');
    await user.tab();

    expect(await screen.findByText('Enter a valid http(s) URL.')).toBeInTheDocument();
  });

  it('shows an inline error for an out-of-range timeout', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
      http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json([])),
    );
    await renderMonitors();
    const user = userEvent.setup();

    const timeout = screen.getByLabelText('Timeout (s)');
    await user.clear(timeout);
    await user.type(timeout, '99');
    await user.tab();

    expect(
      await screen.findByText('Timeout must be between 1 and 30 seconds.'),
    ).toBeInTheDocument();
  });

  it('surfaces an error when saving fails', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
      http.get(`${BASE}/uptime/monitors`, () => HttpResponse.json([])),
      http.get(`${BASE}/projects/:id/environments`, () => HttpResponse.json(['prod'])),
      http.post(`${BASE}/uptime/monitors`, () => new HttpResponse(null, { status: 400 })),
    );
    await renderMonitors();
    const user = userEvent.setup();

    await pickOption(user, 'Project', 'shop-frontend');
    await pickOption(user, 'Environment', 'prod');
    await user.type(screen.getByLabelText('URL'), 'https://shop.example.com/health');
    await user.click(screen.getByRole('button', { name: 'Create monitor' }));

    await waitFor(() =>
      expect(feedback.error).toHaveBeenCalledWith('Could not save monitor — check the URL.'),
    );
  });
});
