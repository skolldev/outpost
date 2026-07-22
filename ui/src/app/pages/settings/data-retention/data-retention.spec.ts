import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../../mocks/node';
import { Feedback } from '../../../core/feedback';
import { DataRetentionSettings } from './data-retention';

const BASE = '*/api/internal';

/** hlm-select renders an ARIA combobox; open it, then pick an option by name. */
async function pickPeriod(
  user: ReturnType<typeof userEvent.setup>,
  optionName: string,
): Promise<void> {
  await user.click(await screen.findByRole('combobox'));
  await user.click(await screen.findByRole('option', { name: optionName }));
}

let feedback: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

function renderRetention() {
  feedback = { success: vi.fn(), error: vi.fn() };
  return render(DataRetentionSettings, {
    providers: [provideHttpClient(), { provide: Feedback, useValue: feedback }],
  });
}

describe('DataRetentionSettings', () => {
  it('hydrates the form from the saved setting', async () => {
    server.use(
      http.get(`${BASE}/settings/data-retention`, () =>
        HttpResponse.json({ enabled: true, retention_days: 60 }),
      ),
    );
    await renderRetention();

    await waitFor(() =>
      expect(screen.getByLabelText('Automatically delete old data')).toBeChecked(),
    );
    expect(screen.getByRole('combobox')).toHaveTextContent('60 days');
  });

  it('saves an exact payload and confirms success', async () => {
    let saved: { enabled: boolean; retention_days: number } | null = null;
    server.use(
      http.get(`${BASE}/settings/data-retention`, () =>
        HttpResponse.json({ enabled: false, retention_days: 90 }),
      ),
      http.put(`${BASE}/settings/data-retention`, async ({ request }) => {
        saved = (await request.json()) as typeof saved;
        return HttpResponse.json(saved);
      }),
    );
    await renderRetention();
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByRole('combobox')).toHaveTextContent('90 days'));
    await user.click(screen.getByLabelText('Automatically delete old data'));
    await pickPeriod(user, '30 days');
    await user.click(screen.getByRole('button', { name: 'Save data retention' }));

    await waitFor(() => expect(saved).toEqual({ enabled: true, retention_days: 30 }));
    await waitFor(() =>
      expect(feedback.success).toHaveBeenCalledWith(
        'Data retention settings saved. Changes take effect at the next 02:00 UTC run.',
      ),
    );
  });

  it('keeps the submit disabled until a retention period is selected', async () => {
    // A failed load leaves the required select empty, so the form is invalid.
    server.use(
      http.get(`${BASE}/settings/data-retention`, () => new HttpResponse(null, { status: 500 })),
    );
    await renderRetention();
    const user = userEvent.setup();

    const submit = await screen.findByRole('button', { name: 'Save data retention' });
    expect(submit).toBeDisabled();

    await pickPeriod(user, '90 days');
    expect(submit).toBeEnabled();
  });

  it('shows an inline error when the required period is left empty', async () => {
    server.use(
      http.get(`${BASE}/settings/data-retention`, () => new HttpResponse(null, { status: 500 })),
    );
    await renderRetention();
    const user = userEvent.setup();

    // Open and dismiss the select without choosing, marking it touched.
    await user.click(await screen.findByRole('combobox'));
    await user.keyboard('{Escape}');
    await user.tab();

    expect(await screen.findByText('Select a retention period.')).toBeInTheDocument();
  });

  it('surfaces an error toast when saving fails', async () => {
    server.use(
      http.get(`${BASE}/settings/data-retention`, () =>
        HttpResponse.json({ enabled: false, retention_days: 90 }),
      ),
      http.put(`${BASE}/settings/data-retention`, () => new HttpResponse(null, { status: 500 })),
    );
    await renderRetention();
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByRole('combobox')).toHaveTextContent('90 days'));
    await user.click(screen.getByRole('button', { name: 'Save data retention' }));

    await waitFor(() =>
      expect(feedback.error).toHaveBeenCalledWith('Could not save data retention settings.'),
    );
  });

  it('shows an alert when the setting cannot be loaded', async () => {
    server.use(
      http.get(`${BASE}/settings/data-retention`, () => new HttpResponse(null, { status: 500 })),
    );
    await renderRetention();

    expect(await screen.findByText('Could not load data retention settings.')).toBeInTheDocument();
  });
});
