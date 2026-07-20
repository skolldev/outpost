import { provideHttpClient } from '@angular/common/http';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../../mocks/node';
import { Feedback } from '../../../core/feedback';
import { Project, ProjectKey } from '../../../core/models';
import { ProjectsSettings } from './projects';

const BASE = '*/api/internal';

const PROJECT: Project = {
  id: 1,
  slug: 'shop-frontend',
  name: 'shop-frontend',
  platform: 'javascript-angular',
  created_at: '2026-01-01T00:00:00Z',
};

const KEY: ProjectKey = {
  id: 10,
  project_id: 1,
  public_key: 'pub-abc',
  is_active: true,
  created_at: '2026-01-01T00:00:00Z',
  dsn: 'https://pub-abc@outpost.example/1',
};

let feedback: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

function renderProjects() {
  feedback = { success: vi.fn(), error: vi.fn() };
  return render(ProjectsSettings, {
    providers: [provideHttpClient(), { provide: Feedback, useValue: feedback }],
  });
}

describe('ProjectsSettings', () => {
  it('lists projects from the shared store', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
      http.get(`${BASE}/projects/:id/keys`, () => HttpResponse.json([])),
    );
    await renderProjects();

    expect(await screen.findByText('shop-frontend')).toBeInTheDocument();
  });

  it('shows the empty state when there are no projects', async () => {
    server.use(http.get(`${BASE}/projects`, () => HttpResponse.json([])));
    await renderProjects();

    expect(await screen.findByText(/No projects yet/)).toBeInTheDocument();
  });

  it('creates a project and reloads the list', async () => {
    let created: { slug: string; name: string; platform: string | null } | null = null;
    const projects = [PROJECT];
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json(projects)),
      http.post(`${BASE}/projects`, async ({ request }) => {
        created = (await request.json()) as typeof created;
        const next: Project = { ...PROJECT, id: 2, slug: 'new-app', name: 'new-app' };
        projects.push(next);
        return HttpResponse.json(next);
      }),
      http.get(`${BASE}/projects/:id/keys`, () => HttpResponse.json([])),
    );
    await renderProjects();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Slug'), 'new-app');
    await user.click(screen.getByRole('button', { name: /create project/i }));

    await waitFor(() => expect(screen.getByText('new-app')).toBeInTheDocument());
    expect(created).toEqual({ slug: 'new-app', name: 'new-app', platform: 'javascript-angular' });
    expect(feedback.success).toHaveBeenCalledWith('Project created.');
  });

  it('surfaces an error when project creation fails', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
      http.post(`${BASE}/projects`, () => new HttpResponse(null, { status: 400 })),
      http.get(`${BASE}/projects/:id/keys`, () => HttpResponse.json([])),
    );
    await renderProjects();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Slug'), 'dup-slug');
    await user.click(screen.getByRole('button', { name: /create project/i }));

    await waitFor(() =>
      expect(feedback.error).toHaveBeenCalledWith('Could not create project — check the slug.'),
    );
  });

  it('expands a project to reveal its DSN keys and SDK snippets', async () => {
    server.use(
      http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])),
      http.get(`${BASE}/projects/:id/keys`, () => HttpResponse.json([KEY])),
    );
    await renderProjects();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /shop-frontend/ }));

    expect(await screen.findByText('DSN keys')).toBeInTheDocument();
    expect(screen.getByText(KEY.dsn)).toBeInTheDocument();
    expect(screen.getByText('SDK setup')).toBeInTheDocument();
  });
});
