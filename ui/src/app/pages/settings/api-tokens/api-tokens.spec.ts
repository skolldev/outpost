import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { ApiTokensSettings } from './api-tokens';
import { server } from '../../../../mocks/node';
import { Feedback } from '../../../core/feedback';
import { ApiToken } from '../../../core/models';

const BASE = '*/api/internal';

const TOKEN: ApiToken = {
  id: 7,
  name: 'ci-shop',
  scopes: ['artifacts:write'],
  created_at: '2026-01-01T00:00:00Z',
};

let feedback: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

function renderTokens() {
  feedback = { success: vi.fn(), error: vi.fn() };
  return render(ApiTokensSettings, {
    providers: [provideHttpClient(), { provide: Feedback, useValue: feedback }],
  });
}

describe('ApiTokensSettings', () => {
  it('lists existing tokens', async () => {
    server.use(http.get(`${BASE}/tokens`, () => HttpResponse.json([TOKEN])));
    await renderTokens();

    expect(
      await within(await screen.findByRole('table')).findByText('ci-shop'),
    ).toBeInTheDocument();
  });

  it('creates a token and reveals the secret once', async () => {
    const tokens: ApiToken[] = [];
    server.use(
      http.get(`${BASE}/tokens`, () => HttpResponse.json(tokens)),
      http.post(`${BASE}/tokens`, async ({ request }) => {
        const body = (await request.json()) as { name: string };
        const created: ApiToken = { ...TOKEN, name: body.name, token: 'secret-xyz' };
        tokens.push({
          id: created.id,
          name: created.name,
          scopes: created.scopes,
          created_at: created.created_at,
        });
        return HttpResponse.json(created);
      }),
    );
    await renderTokens();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'ci-new');
    await user.click(screen.getByRole('button', { name: 'Create token' }));

    expect(await screen.findByText('secret-xyz')).toBeInTheDocument();
    await waitFor(() =>
      expect(within(screen.getByRole('table')).getByText('ci-new')).toBeInTheDocument(),
    );
  });

  it('disables the create button until a name is entered', async () => {
    server.use(http.get(`${BASE}/tokens`, () => HttpResponse.json([])));
    await renderTokens();
    const user = userEvent.setup();

    expect(screen.getByRole('button', { name: 'Create token' })).toBeDisabled();

    await user.type(screen.getByLabelText('Name'), 'ci-new');

    expect(screen.getByRole('button', { name: 'Create token' })).toBeEnabled();
  });

  it('shows an inline error when the name is left blank', async () => {
    server.use(http.get(`${BASE}/tokens`, () => HttpResponse.json([])));
    await renderTokens();
    const user = userEvent.setup();

    await user.click(screen.getByLabelText('Name'));
    await user.tab();

    expect(await screen.findByText('Token name is required.')).toBeInTheDocument();
  });

  it('reports a create failure through the Feedback toast', async () => {
    server.use(
      http.get(`${BASE}/tokens`, () => HttpResponse.json([])),
      http.post(`${BASE}/tokens`, () => new HttpResponse(null, { status: 500 })),
    );
    await renderTokens();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'ci-new');
    await user.click(screen.getByRole('button', { name: 'Create token' }));

    await waitFor(() => expect(feedback.error).toHaveBeenCalledWith('Could not create token.'));
  });
});
