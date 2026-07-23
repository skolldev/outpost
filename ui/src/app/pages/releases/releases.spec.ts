import { provideHttpClient } from '@angular/common/http';
import { render, screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { Project, Release, ReleaseArtifact } from '../../core/models';
import { ReleasesPage } from './releases';

const BASE = '*/api/internal';

const PROJECT: Project = {
  id: 1,
  slug: 'shop',
  name: 'shop',
  platform: 'javascript',
  created_at: '2026-01-01T00:00:00Z',
};

const RELEASE: Release = {
  id: 1,
  version: 'shop@1.0.0',
  created_at: '2026-07-01T00:00:00Z',
  bundle_count: 2,
  artifact_count: 3,
  issue_count: 1,
};

const ARTIFACT: ReleaseArtifact = {
  id: 1,
  debug_id: 'abc-123',
  artifact_type: 'source_map',
  file_path: 'dist/main.js.map',
  size_bytes: 2048,
  bundle_checksum: 'sha-1',
  uploaded_at: '2026-07-01T00:00:00Z',
};

async function renderReleases() {
  server.use(http.get(`${BASE}/projects`, () => HttpResponse.json([PROJECT])));
  return render(ReleasesPage, {
    providers: [provideHttpClient()],
  });
}

/** Pick the sole project from the page-local selector. */
async function selectProject(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(screen.getByRole('combobox'));
  await user.click(await screen.findByRole('option', { name: 'shop' }));
}

describe('ReleasesPage', () => {
  it('prompts to pick a project when none is selected', async () => {
    await renderReleases();

    expect(screen.getByText(/Select a project to see its releases/)).toBeInTheDocument();
  });

  it('lists releases for the selected project', async () => {
    server.use(http.get(`${BASE}/releases`, () => HttpResponse.json([RELEASE])));
    await renderReleases();
    const user = userEvent.setup();

    await selectProject(user);

    expect(await screen.findByText('shop@1.0.0')).toBeInTheDocument();
  });

  it('shows the empty state when the project has no releases', async () => {
    server.use(http.get(`${BASE}/releases`, () => HttpResponse.json([])));
    await renderReleases();
    const user = userEvent.setup();

    await selectProject(user);

    expect(await screen.findByText(/No releases yet/)).toBeInTheDocument();
  });

  it('expands a release to reveal its artifact bundles', async () => {
    server.use(
      http.get(`${BASE}/releases`, () => HttpResponse.json([RELEASE])),
      http.get(`${BASE}/releases/:version/artifacts`, () => HttpResponse.json([ARTIFACT])),
    );
    await renderReleases();
    const user = userEvent.setup();

    await selectProject(user);
    await user.click(await screen.findByText('shop@1.0.0'));

    expect(await screen.findByText('dist/main.js.map')).toBeInTheDocument();
    expect(screen.getByText('abc-123')).toBeInTheDocument();
  });

  it('notes when an expanded release has no artifact bundles', async () => {
    server.use(
      http.get(`${BASE}/releases`, () => HttpResponse.json([{ ...RELEASE, artifact_count: 0 }])),
      http.get(`${BASE}/releases/:version/artifacts`, () => HttpResponse.json([])),
    );
    await renderReleases();
    const user = userEvent.setup();

    await selectProject(user);
    await user.click(await screen.findByText('shop@1.0.0'));

    expect(await screen.findByText(/No artifact bundles uploaded/)).toBeInTheDocument();
  });
});
