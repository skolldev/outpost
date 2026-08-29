import { provideRouter } from '@angular/router';
import { render, screen } from '@testing-library/angular';

import { SettingsPage } from './settings';
import { Session } from '../../core/session';

function renderSettings(role: 'admin' | 'member') {
  return render(SettingsPage, {
    providers: [
      provideRouter([]),
      { provide: Session, useValue: { isAdmin: () => role === 'admin' } },
    ],
  });
}

describe('SettingsPage shell', () => {
  it('renders a tab link for each settings area', async () => {
    await renderSettings('admin');

    for (const label of [
      'Projects',
      'Uptime monitors',
      'Notification channels',
      'Data retention',
      'API tokens',
      'Users',
    ]) {
      expect(screen.getByRole('link', { name: label })).toBeInTheDocument();
    }
  });

  /** A Member manages no installation resources — only their own API tokens. */
  it('shows a member only the API tokens tab', async () => {
    await renderSettings('member');

    expect(screen.getByRole('link', { name: 'API tokens' })).toBeInTheDocument();
    for (const label of ['Projects', 'Uptime monitors', 'Data retention', 'Users']) {
      expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument();
    }
  });
});
