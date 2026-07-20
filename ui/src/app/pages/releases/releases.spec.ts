import { provideHttpClient } from '@angular/common/http';
import { signal } from '@angular/core';
import { render, screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../../mocks/node';
import { GlobalFilters } from '../../core/filters';
import { Release, ReleaseArtifact } from '../../core/models';
import { ReleasesPage } from './releases';

const BASE = '*/api/internal';

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

/** GlobalFilters stand-in with a fixed project; releases only reads project(). */
function fakeFilters(project: number | undefined): GlobalFilters {
  return { project: signal<number | undefined>(project) } as unknown as GlobalFilters;
}

async function renderReleases(project: number | undefined) {
  return render(ReleasesPage, {
    providers: [provideHttpClient(), { provide: GlobalFilters, useValue: fakeFilters(project) }],
  });
}

describe('ReleasesPage', () => {
  it('prompts to pick a project when none is selected', async () => {
    await renderReleases(undefined);

    expect(screen.getByText(/Select a project in the header/)).toBeInTheDocument();
  });

  it('lists releases for the selected project', async () => {
    server.use(http.get(`${BASE}/releases`, () => HttpResponse.json([RELEASE])));
    await renderReleases(1);

    expect(await screen.findByText('shop@1.0.0')).toBeInTheDocument();
  });

  it('shows the empty state when the project has no releases', async () => {
    server.use(http.get(`${BASE}/releases`, () => HttpResponse.json([])));
    await renderReleases(1);

    expect(await screen.findByText(/No releases yet/)).toBeInTheDocument();
  });

  it('expands a release to reveal its artifact bundles', async () => {
    server.use(
      http.get(`${BASE}/releases`, () => HttpResponse.json([RELEASE])),
      http.get(`${BASE}/releases/:version/artifacts`, () => HttpResponse.json([ARTIFACT])),
    );
    await renderReleases(1);
    const user = userEvent.setup();

    await user.click(await screen.findByText('shop@1.0.0'));

    expect(await screen.findByText('dist/main.js.map')).toBeInTheDocument();
    expect(screen.getByText('abc-123')).toBeInTheDocument();
  });

  it('notes when an expanded release has no artifact bundles', async () => {
    server.use(
      http.get(`${BASE}/releases`, () => HttpResponse.json([{ ...RELEASE, artifact_count: 0 }])),
      http.get(`${BASE}/releases/:version/artifacts`, () => HttpResponse.json([])),
    );
    await renderReleases(1);
    const user = userEvent.setup();

    await user.click(await screen.findByText('shop@1.0.0'));

    expect(await screen.findByText(/No artifact bundles uploaded/)).toBeInTheDocument();
  });
});
