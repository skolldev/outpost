import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { ApiTokensSettings } from './api-tokens';
import { server } from '../../../../mocks/node';
import { Feedback } from '../../../core/feedback';
import { Session } from '../../../core/session';
import { ApiToken } from '../../../core/models';

const BASE = '*/api/internal';

const INSTALLATION_TOKEN: ApiToken = {
  id: 7,
  name: 'ci-shop',
  scopes: ['artifacts:write'],
  created_at: '2026-01-01T00:00:00Z',
  owner_user_id: null,
  owner_email: null,
};

const PERSONAL_TOKEN: ApiToken = {
  id: 8,
  name: 'my-agent',
  scopes: ['telemetry:read'],
  created_at: '2026-01-02T00:00:00Z',
  owner_user_id: 3,
  owner_email: 'dev@example.com',
};

let feedback: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

function renderTokens(role: 'admin' | 'member' = 'admin') {
  feedback = { success: vi.fn(), error: vi.fn() };
  return render(ApiTokensSettings, {
    providers: [
      provideHttpClient(),
      { provide: Feedback, useValue: feedback },
      { provide: Session, useValue: { isAdmin: () => role === 'admin' } },
    ],
  });
}

/** hlm-select renders an ARIA combobox; open it by its label, then pick an option. */
async function pickOption(
  user: ReturnType<typeof userEvent.setup>,
  comboboxName: string,
  optionName: string,
): Promise<void> {
  await user.click(await screen.findByRole('combobox', { name: comboboxName }));
  await user.click(await screen.findByRole('option', { name: optionName }));
}

/** Captures the create request body so ownership and scopes can be asserted. */
function captureCreate(created: Partial<ApiToken> = {}): { body?: Record<string, unknown> } {
  const captured: { body?: Record<string, unknown> } = {};
  server.use(
    http.post(`${BASE}/tokens`, async ({ request }) => {
      captured.body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({
        ...PERSONAL_TOKEN,
        name: captured.body['name'],
        scopes: captured.body['scopes'],
        token: 'secret-xyz',
        ...created,
      });
    }),
  );
  return captured;
}

describe('ApiTokensSettings', () => {
  it('distinguishes personal tokens from installation tokens in the list', async () => {
    server.use(
      http.get(`${BASE}/tokens`, () => HttpResponse.json([INSTALLATION_TOKEN, PERSONAL_TOKEN])),
    );
    await renderTokens();

    const table = within(await screen.findByRole('table'));
    const personalRow = (await table.findByText('my-agent')).closest('tr')!;
    expect(within(personalRow).getByText('Personal')).toBeInTheDocument();
    expect(within(personalRow).getByText('dev@example.com')).toBeInTheDocument();

    const installationRow = table.getByText('ci-shop').closest('tr')!;
    expect(within(installationRow).getByText('Installation')).toBeInTheDocument();
  });

  it('creates a personal telemetry:read token and reveals the secret once', async () => {
    const tokens: ApiToken[] = [];
    server.use(http.get(`${BASE}/tokens`, () => HttpResponse.json(tokens)));
    const captured = captureCreate({
      name: 'ci-new',
      mcp_url: 'https://outpost.example.test/o/mcp',
    });
    await renderTokens();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'ci-new');
    await user.click(screen.getByRole('button', { name: 'Create token' }));

    expect(await screen.findByText('secret-xyz')).toBeInTheDocument();
    expect(captured.body).toEqual({
      name: 'ci-new',
      scopes: ['telemetry:read'],
      personal: true,
    });
  });

  it('renders a paste-ready MCP client configuration built from the returned URL', async () => {
    server.use(http.get(`${BASE}/tokens`, () => HttpResponse.json([])));
    captureCreate({ mcp_url: 'https://outpost.example.test/o/mcp' });
    await renderTokens();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'agent');
    await user.click(screen.getByRole('button', { name: 'Create token' }));

    const config = await screen.findByText(/mcpServers/, { selector: 'pre' });
    expect(config).toHaveTextContent('https://outpost.example.test/o/mcp');
    expect(config).toHaveTextContent('Bearer secret-xyz');
  });

  it('lets an admin mint an installation token carrying artifacts:write', async () => {
    server.use(http.get(`${BASE}/tokens`, () => HttpResponse.json([])));
    const captured = captureCreate({ owner_user_id: null, owner_email: null });
    await renderTokens();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'ci');
    await user.click(screen.getByLabelText(/telemetry:read/));
    await user.click(screen.getByLabelText(/artifacts:write/));
    await pickOption(user, 'Owner', 'Installation');
    await user.click(screen.getByRole('button', { name: 'Create token' }));

    await waitFor(() =>
      expect(captured.body).toEqual({
        name: 'ci',
        scopes: ['artifacts:write'],
        personal: false,
      }),
    );
    // The CLI snippet belongs to artifacts:write; the MCP config does not appear.
    expect(await screen.findByText(/sentry-cli/, { selector: 'pre' })).toBeInTheDocument();
    expect(screen.queryByText(/mcpServers/, { selector: 'pre' })).not.toBeInTheDocument();
  });

  it('offers a member only the scope they may grant, and no ownership choice', async () => {
    server.use(http.get(`${BASE}/tokens`, () => HttpResponse.json([])));
    await renderTokens('member');

    expect(await screen.findByLabelText(/telemetry:read/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/artifacts:write/)).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Owner' })).not.toBeInTheDocument();
  });

  it('disables the create button until a name is entered', async () => {
    server.use(http.get(`${BASE}/tokens`, () => HttpResponse.json([])));
    await renderTokens();
    const user = userEvent.setup();

    expect(screen.getByRole('button', { name: 'Create token' })).toBeDisabled();

    await user.type(screen.getByLabelText('Name'), 'ci-new');

    expect(screen.getByRole('button', { name: 'Create token' })).toBeEnabled();
  });

  it('disables the create button when every scope is cleared', async () => {
    server.use(http.get(`${BASE}/tokens`, () => HttpResponse.json([])));
    await renderTokens();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Name'), 'ci-new');
    await user.click(screen.getByLabelText(/telemetry:read/));

    expect(screen.getByRole('button', { name: 'Create token' })).toBeDisabled();
    expect(await screen.findByText('Select at least one scope.')).toBeInTheDocument();
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
