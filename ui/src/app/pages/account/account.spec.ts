import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { App } from '../../app';
import { routes } from '../../app.routes';
import { Feedback } from '../../core/feedback';
import { SessionUser } from '../../core/models';
import { Session } from '../../core/session';
import { AccountPage } from './account';

const BASE = '*/api/internal';

let feedback: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

/** Session stand-in: the page reads the signed-in user, the guards resolve it. */
function fakeSession(role: SessionUser['role']): Session {
  const user = signal<SessionUser | null>({ email: 'me@example.com', role });
  return {
    user,
    isAdmin: () => role === 'admin',
    ensureLoaded: async () => user(),
    logout: async () => undefined,
  } as unknown as Session;
}

function renderAccount(role: SessionUser['role'] = 'member') {
  feedback = { success: vi.fn(), error: vi.fn() };
  return render(AccountPage, {
    providers: [
      provideHttpClient(),
      { provide: Feedback, useValue: feedback },
      { provide: Session, useValue: fakeSession(role) },
    ],
  });
}

async function fillForm(
  user: ReturnType<typeof userEvent.setup>,
  passwords: { current?: string; next?: string; confirm?: string } = {},
) {
  const { current = 'old-password', next = 'new-password', confirm = next } = passwords;
  await user.type(screen.getByLabelText('Current password'), current);
  await user.type(screen.getByLabelText('New password'), next);
  await user.type(screen.getByLabelText('Confirm new password'), confirm);
}

describe('AccountPage change password', () => {
  it('sends the current and new password and reports success', async () => {
    let body: { current_password: string; new_password: string } | null = null;
    server.use(
      http.post(`${BASE}/auth/password`, async ({ request }) => {
        body = (await request.json()) as typeof body;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await renderAccount();
    const user = userEvent.setup();

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() => expect(feedback.success).toHaveBeenCalledWith('Password changed.'));
    expect(body).toEqual({ current_password: 'old-password', new_password: 'new-password' });
  });

  it('clears the fields after a successful change', async () => {
    server.use(http.post(`${BASE}/auth/password`, () => new HttpResponse(null, { status: 204 })));
    await renderAccount();
    const user = userEvent.setup();

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() => expect(feedback.success).toHaveBeenCalled());
    expect(screen.getByLabelText('Current password')).toHaveValue('');
    expect(screen.getByLabelText('New password')).toHaveValue('');
    expect(screen.getByLabelText('Confirm new password')).toHaveValue('');
  });

  it('keeps submit disabled until every field is filled', async () => {
    await renderAccount();
    const user = userEvent.setup();

    const submit = screen.getByRole('button', { name: 'Change password' });
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText('Current password'), 'old-password');
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText('New password'), 'new-password');
    expect(submit).toBeDisabled(); // confirmation still empty

    await user.type(screen.getByLabelText('Confirm new password'), 'new-password');
    expect(submit).toBeEnabled();
  });

  it('blocks submit and explains when the confirmation does not match', async () => {
    let called = false;
    server.use(
      http.post(`${BASE}/auth/password`, () => {
        called = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await renderAccount();
    const user = userEvent.setup();

    await fillForm(user, { next: 'new-password', confirm: 'new-passward' });
    await user.tab(); // errors surface once the field is touched

    expect(await screen.findByText('Passwords do not match.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Change password' })).toBeDisabled();
    expect(called).toBe(false);
  });

  it('shows an inline error for a too-short new password', async () => {
    await renderAccount();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('New password'), 'short');
    await user.tab();

    expect(await screen.findByText('Password must be at least 8 characters.')).toBeInTheDocument();
  });

  it('surfaces a rejected current password through Feedback, not inline', async () => {
    server.use(
      http.post(`${BASE}/auth/password`, () =>
        HttpResponse.json({ detail: 'current password is incorrect' }, { status: 401 }),
      ),
    );
    await renderAccount();
    const user = userEvent.setup();

    await fillForm(user, { current: 'wrong-password' });
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() =>
      expect(feedback.error).toHaveBeenCalledWith('Current password is incorrect.'),
    );
    expect(feedback.success).not.toHaveBeenCalled();
    expect(screen.queryByText('Current password is incorrect.')).not.toBeInTheDocument();
  });

  it('reports any other server failure generically', async () => {
    server.use(http.post(`${BASE}/auth/password`, () => new HttpResponse(null, { status: 500 })));
    await renderAccount();
    const user = userEvent.setup();

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() => expect(feedback.error).toHaveBeenCalledWith('Could not change password.'));
  });
});

describe('AccountPage reachability', () => {
  it('renders for a Member — the page is authenticated, not Admin-gated', async () => {
    await renderAccount('member');

    expect(screen.getByRole('button', { name: 'Change password' })).toBeInTheDocument();
  });

  it('lets a Member navigate to /account through the real route table', async () => {
    // The whole /settings tree is Admin-only; this asserts /account is not, by
    // driving the actual routes rather than trusting the config by inspection.
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([])),
      http.get(`${BASE}/projects/environments`, () => HttpResponse.json([])),
    );
    const { fixture } = await render(App, {
      providers: [
        provideHttpClient(),
        provideRouter(routes),
        { provide: Session, useValue: fakeSession('member') },
        { provide: Feedback, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    });
    await fixture.debugElement.injector.get(Router).navigateByUrl('/account');

    expect(await screen.findByRole('button', { name: 'Change password' })).toBeInTheDocument();
  });
});
