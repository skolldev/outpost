import { provideHttpClient } from '@angular/common/http';
import { Component } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { Feedback } from '../../core/feedback';
import { SessionUser } from '../../core/models';
import { LoginPage } from './login';

const BASE = '*/api/internal';

const USER: SessionUser = { email: 'me@example.com', role: 'admin' };

/** Stub landing route so the post-login navigation resolves. */
@Component({ template: 'issues' })
class IssuesStub {}

let feedback: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

async function renderLogin() {
  feedback = { success: vi.fn(), error: vi.fn() };
  return render(LoginPage, {
    providers: [
      provideHttpClient(),
      provideRouter([{ path: 'issues', component: IssuesStub }]),
      { provide: Feedback, useValue: feedback },
    ],
  });
}

describe('LoginPage', () => {
  it('logs in and navigates on valid credentials', async () => {
    let body: { email: string; password: string } | null = null;
    server.use(
      http.post(`${BASE}/auth/login`, async ({ request }) => {
        body = (await request.json()) as typeof body;
        return HttpResponse.json(USER);
      }),
    );
    await renderLogin();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Email'), 'me@example.com');
    await user.type(screen.getByLabelText('Password'), 'hunter2');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(body).toEqual({ email: 'me@example.com', password: 'hunter2' }));
  });

  it('reports an auth failure through the Feedback toast, not inline', async () => {
    server.use(http.post(`${BASE}/auth/login`, () => new HttpResponse(null, { status: 401 })));
    await renderLogin();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Email'), 'me@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(feedback.error).toHaveBeenCalledWith('Invalid email or password.'));
    expect(screen.queryByText('Invalid email or password.')).not.toBeInTheDocument();
  });

  it('disables the submit button until the form is valid', async () => {
    await renderLogin();
    const user = userEvent.setup();

    expect(screen.getByRole('button', { name: /sign in/i })).toBeDisabled();

    await user.type(screen.getByLabelText('Email'), 'me@example.com');
    await user.type(screen.getByLabelText('Password'), 'hunter2');

    expect(screen.getByRole('button', { name: /sign in/i })).toBeEnabled();
  });

  it('shows an inline error for an invalid email', async () => {
    await renderLogin();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Email'), 'not-an-email');
    await user.tab();

    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument();
  });
});
