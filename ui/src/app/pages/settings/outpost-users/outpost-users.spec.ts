import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../../mocks/node';
import { Feedback } from '../../../core/feedback';
import { AppUser } from '../../../core/models';
import { OutpostUsersSettings } from './outpost-users';

const BASE = '*/api/internal';

const USER: AppUser = {
  id: 5,
  email: 'member@example.com',
  role: 'member',
  created_at: '2026-01-01T00:00:00Z',
};

let feedback: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

function renderUsers() {
  feedback = { success: vi.fn(), error: vi.fn() };
  return render(OutpostUsersSettings, {
    providers: [provideHttpClient(), { provide: Feedback, useValue: feedback }],
  });
}

describe('OutpostUsersSettings', () => {
  it('lists existing users', async () => {
    server.use(http.get(`${BASE}/users`, () => HttpResponse.json([USER])));
    await renderUsers();

    expect(await screen.findByText('member@example.com')).toBeInTheDocument();
  });

  async function selectRole(user: ReturnType<typeof userEvent.setup>, name: string) {
    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByRole('option', { name }));
  }

  it('creates a user and reloads the list', async () => {
    let created: { email: string; password: string; role: string } | null = null;
    const users = [USER];
    server.use(
      http.get(`${BASE}/users`, () => HttpResponse.json(users)),
      http.post(`${BASE}/users`, async ({ request }) => {
        created = (await request.json()) as typeof created;
        const next: AppUser = {
          id: 6,
          email: 'new@example.com',
          role: 'admin',
          created_at: '2026-02-01T00:00:00Z',
        };
        users.push(next);
        return HttpResponse.json(next);
      }),
    );
    await renderUsers();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Email'), 'new@example.com');
    await user.type(screen.getByLabelText('Password'), 'password123');
    await selectRole(user, 'admin');
    await user.click(screen.getByRole('button', { name: 'Add user' }));

    await waitFor(() =>
      expect(within(screen.getByRole('table')).getByText('new@example.com')).toBeInTheDocument(),
    );
    expect(created).toMatchObject({
      email: 'new@example.com',
      password: 'password123',
      role: 'admin',
    });
    expect(feedback.success).toHaveBeenCalledWith('User created.');
  });

  it('keeps the submit disabled until email, password, and role are all valid', async () => {
    server.use(http.get(`${BASE}/users`, () => HttpResponse.json([USER])));
    await renderUsers();
    const user = userEvent.setup();

    const submit = screen.getByRole('button', { name: 'Add user' });
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText('Email'), 'new@example.com');
    expect(submit).toBeDisabled(); // password + role still missing

    await user.type(screen.getByLabelText('Password'), 'password123');
    expect(submit).toBeDisabled(); // role still unselected

    await selectRole(user, 'member');
    expect(submit).toBeEnabled();
  });

  it('shows an inline error for an invalid email', async () => {
    server.use(http.get(`${BASE}/users`, () => HttpResponse.json([USER])));
    await renderUsers();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Email'), 'not-an-email');
    await user.tab();

    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument();
  });

  it('shows an inline error for a too-short password', async () => {
    server.use(http.get(`${BASE}/users`, () => HttpResponse.json([USER])));
    await renderUsers();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Password'), 'short');
    await user.tab();

    expect(await screen.findByText('Password must be at least 8 characters.')).toBeInTheDocument();
  });

  it('deletes a user behind a two-step confirm and reloads the list', async () => {
    let users = [USER];
    let deleted: string | null = null;
    server.use(
      http.get(`${BASE}/users`, () => HttpResponse.json(users)),
      http.delete(`${BASE}/users/:id`, ({ params }) => {
        deleted = params['id'] as string;
        users = [];
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await renderUsers();
    const user = userEvent.setup();
    const table = () => within(screen.getByRole('table'));

    await user.click(await table().findByRole('button', { name: 'Delete' }));
    // First click only arms the confirm; nothing is sent yet.
    expect(deleted).toBeNull();

    await user.click(table().getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(deleted).toBe('5'));
    await waitFor(() => expect(table().queryByText('member@example.com')).not.toBeInTheDocument());
    expect(feedback.success).toHaveBeenCalledWith('User deleted.');
  });

  it('cancels the delete confirm without sending a request', async () => {
    let deleted = false;
    server.use(
      http.get(`${BASE}/users`, () => HttpResponse.json([USER])),
      http.delete(`${BASE}/users/:id`, () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await renderUsers();
    const user = userEvent.setup();
    const table = () => within(screen.getByRole('table'));

    await user.click(await table().findByRole('button', { name: 'Delete' }));
    await user.click(table().getByRole('button', { name: 'Cancel' }));

    expect(deleted).toBe(false);
    expect(table().getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });

  it('reports the reason a delete was refused', async () => {
    server.use(
      http.get(`${BASE}/users`, () => HttpResponse.json([USER])),
      http.delete(`${BASE}/users/:id`, () =>
        HttpResponse.json({ detail: 'You cannot delete your own account.' }, { status: 409 }),
      ),
    );
    await renderUsers();
    const user = userEvent.setup();
    const table = () => within(screen.getByRole('table'));

    await user.click(await table().findByRole('button', { name: 'Delete' }));
    await user.click(table().getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(feedback.error).toHaveBeenCalledWith('You cannot delete your own account.'),
    );
    expect(within(screen.getByRole('table')).getByText('member@example.com')).toBeInTheDocument();
  });

  it('surfaces an error when user creation fails', async () => {
    server.use(
      http.get(`${BASE}/users`, () => HttpResponse.json([USER])),
      http.post(`${BASE}/users`, () => new HttpResponse(null, { status: 400 })),
    );
    await renderUsers();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Email'), 'new@example.com');
    await user.type(screen.getByLabelText('Password'), 'password123');
    await selectRole(user, 'admin');
    await user.click(screen.getByRole('button', { name: 'Add user' }));

    await waitFor(() => expect(feedback.error).toHaveBeenCalledWith('Could not create user.'));
  });
});
