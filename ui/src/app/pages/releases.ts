import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';

import { Api } from '../core/api';
import { GlobalFilters } from '../core/filters';
import { Release, ReleaseArtifact } from '../core/models';
import { timeAgo } from '../shared/ui';

/**
 * Releases (§9 page 5): versions per project with received artifact bundles —
 * primarily a "why isn't my stack trace symbolicated" debugging aid.
 */
@Component({
  selector: 'app-releases',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (filters.project() === undefined) {
      <p class="py-12 text-center text-sm text-slate-500">
        Select a project in the header to see its releases.
      </p>
    } @else {
      <h1 class="mb-4 text-lg font-semibold text-white">Releases</h1>
      <div class="overflow-hidden rounded-lg border border-slate-800">
        <table class="w-full text-sm">
          <thead class="border-b border-slate-800 bg-slate-900 text-left text-xs text-slate-500">
            <tr>
              <th class="px-4 py-2 font-medium">Version</th>
              <th class="px-4 py-2 font-medium">First seen</th>
              <th class="px-4 py-2 text-right font-medium">Bundles</th>
              <th class="px-4 py-2 text-right font-medium">Artifacts</th>
              <th class="px-4 py-2 text-right font-medium">Issues</th>
            </tr>
          </thead>
          <tbody>
            @for (release of releases(); track release.id) {
              <tr
                (click)="toggle(release)"
                class="cursor-pointer border-b border-slate-800/60 last:border-0 hover:bg-slate-900/60"
              >
                <td class="px-4 py-2 font-mono text-xs text-slate-200">
                  <span class="mr-1 inline-block w-3 text-slate-500">{{
                    expanded() === release.version ? '▾' : '▸'
                  }}</span
                  >{{ release.version }}
                </td>
                <td
                  class="px-4 py-2 text-xs text-slate-400"
                  [title]="release.created_at | date: 'medium'"
                >
                  {{ timeAgo(release.created_at) }}
                </td>
                <td class="px-4 py-2 text-right text-slate-300">{{ release.bundle_count }}</td>
                <td class="px-4 py-2 text-right">
                  <span
                    [class]="release.artifact_count > 0 ? 'text-emerald-400' : 'text-slate-500'"
                    >{{ release.artifact_count }}</span
                  >
                </td>
                <td class="px-4 py-2 text-right text-slate-300">{{ release.issue_count }}</td>
              </tr>
              @if (expanded() === release.version) {
                <tr class="border-b border-slate-800/60 last:border-0">
                  <td colspan="5" class="bg-slate-950/60 px-4 py-3">
                    @if (artifacts().length > 0) {
                      <table class="w-full font-mono text-xs">
                        <thead class="text-left text-slate-500">
                          <tr>
                            <th class="pb-1 pr-4 font-medium">File</th>
                            <th class="pb-1 pr-4 font-medium">Type</th>
                            <th class="pb-1 pr-4 font-medium">Debug ID</th>
                            <th class="pb-1 text-right font-medium">Stored size</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (artifact of artifacts(); track artifact.id) {
                            <tr>
                              <td class="pr-4 text-slate-300">{{ artifact.file_path }}</td>
                              <td class="pr-4 text-slate-400">{{ artifact.artifact_type }}</td>
                              <td class="pr-4 text-slate-400">{{ artifact.debug_id }}</td>
                              <td class="text-right text-slate-400">
                                {{ size(artifact.size_bytes) }}
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    } @else {
                      <p class="text-xs text-slate-500">
                        No artifact bundles uploaded for this release — run
                        <code class="rounded bg-slate-900 px-1"
                          >sentry-cli sourcemaps inject && sentry-cli sourcemaps upload</code
                        >
                        in CI (SENTRY_URL = this Outpost, auth token from Settings → tokens).
                      </p>
                    }
                  </td>
                </tr>
              }
            } @empty {
              <tr>
                <td colspan="5" class="px-4 py-8 text-center text-sm text-slate-500">
                  No releases yet — they are auto-created from the SDK <code>release</code> field or
                  a source-map upload.
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
})
export class ReleasesPage {
  private readonly api = inject(Api);
  readonly filters = inject(GlobalFilters);

  readonly releases = signal<Release[]>([]);
  readonly artifacts = signal<ReleaseArtifact[]>([]);
  readonly expanded = signal<string | null>(null);

  readonly timeAgo = timeAgo;

  constructor() {
    effect(() => {
      const project = this.filters.project();
      this.expanded.set(null);
      if (project == null) {
        this.releases.set([]);
        return;
      }
      void firstValueFrom(this.api.releases(project)).then((releases) =>
        this.releases.set(releases),
      );
    });
  }

  async toggle(release: Release): Promise<void> {
    if (this.expanded() === release.version) {
      this.expanded.set(null);
      return;
    }
    const project = this.filters.project();
    if (project == null) return;
    this.artifacts.set([]);
    this.expanded.set(release.version);
    this.artifacts.set(await firstValueFrom(this.api.releaseArtifacts(release.version, project)));
  }

  size(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
}
