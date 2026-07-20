import { provideHttpClient } from '@angular/common/http';
import { Component } from '@angular/core';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { SessionUser } from '../../core/models';
import { LoginPage } from './login';

const BASE = '*/api/internal';

const USER: SessionUser = { email: 'me@example.com', role: 'admin' };

/** Stub landing route so the post-login navigation resolves. */
@Component({ template: 'issues' })
class IssuesStub {}

async function renderLogin() {
  return render(LoginPage, {
    providers: [provideHttpClient(), provideRouter([{ path: 'issues', component: IssuesStub }])],
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

  it('surfaces an error on invalid credentials', async () => {
    server.use(http.post(`${BASE}/auth/login`, () => new HttpResponse(null, { status: 401 })));
    await renderLogin();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Email'), 'me@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText(/Invalid email or password/)).toBeInTheDocument();
  });
});
