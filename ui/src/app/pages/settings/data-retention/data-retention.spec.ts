import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../../mocks/node';
import { Feedback } from '../../../core/feedback';
import { DataRetentionSettings } from './data-retention';

const BASE = '*/api/internal';

function nativeSelect(wrapperId: string): HTMLSelectElement {
  const select = document.querySelector<HTMLSelectElement>(`#${wrapperId} select`);
  if (!select) throw new Error(`no native select rendered in #${wrapperId}`);
  return select;
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
    expect(nativeSelect('retentionDays')).toHaveValue('60');
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

    await waitFor(() => expect(nativeSelect('retentionDays')).toHaveValue('90'));
    await user.click(screen.getByLabelText('Automatically delete old data'));
    await user.selectOptions(nativeSelect('retentionDays'), '30');
    await user.click(screen.getByRole('button', { name: 'Save data retention' }));

    await waitFor(() => expect(saved).toEqual({ enabled: true, retention_days: 30 }));
    await waitFor(() =>
      expect(feedback.success).toHaveBeenCalledWith(
        'Data retention settings saved. Changes take effect at the next 02:00 UTC run.',
      ),
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
