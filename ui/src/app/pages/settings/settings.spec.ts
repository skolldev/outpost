import { provideRouter } from '@angular/router';
import { render, screen } from '@testing-library/angular';

import { SettingsPage } from './settings';

describe('SettingsPage shell', () => {
  it('renders a tab link for each settings area', async () => {
    await render(SettingsPage, { providers: [provideRouter([])] });

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
});
