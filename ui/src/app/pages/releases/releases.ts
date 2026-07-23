import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { HlmSelectImports } from '@spartan-ng/helm/select';

import { API_BASE } from '../../core/api-base';
import { ProjectsStore } from '../../core/projects';
import { Release, ReleaseArtifact } from '../../core/models';
import { timeAgo } from '../../shared/ui';

/**
 * Releases (§9 page 5): versions per project with received artifact bundles —
 * primarily a "why isn't my stack trace symbolicated" debugging aid.
 *
 * A Release and its Artifact Bundles belong to exactly one Project+version, so
 * the page owns a page-local single-Project selector rather than reading the
 * global header filter (#81) — this stays correct once the global filter goes
 * multi-select (#76).
 */
@Component({
  selector: 'app-releases',
  imports: [DatePipe, HlmSelectImports],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './releases.html',
})
export class ReleasesPage {
  readonly projectsStore = inject(ProjectsStore);

  readonly project = signal<number | undefined>(undefined);

  // Releases for the selected project; the fetch is skipped until one is chosen.
  private readonly releasesResource = httpResource<Release[]>(
    () => {
      const project = this.project();
      return project == null ? undefined : { url: `${API_BASE}/releases`, params: { project } };
    },
    { defaultValue: [] },
  );
  readonly releases = this.releasesResource.value;

  // The expanded release's version, or null when the list is collapsed.
  readonly expanded = signal<string | null>(null);

  // Artifact bundles for the expanded release; skips the fetch while collapsed.
  // Empty while loading so switching rows never shows the prior release's
  // bundles (httpResource retains its last value across a re-fetch).
  private readonly artifactsResource = httpResource<ReleaseArtifact[]>(
    () => {
      const version = this.expanded();
      const project = this.project();
      return version == null || project == null
        ? undefined
        : {
            url: `${API_BASE}/releases/${encodeURIComponent(version)}/artifacts`,
            params: { project },
          };
    },
    { defaultValue: [] },
  );
  readonly artifacts = computed(() =>
    this.artifactsResource.isLoading() ? [] : this.artifactsResource.value(),
  );

  readonly timeAgo = timeAgo;

  /** Resolves a project select value to its trigger label. */
  readonly projectLabel = (value: number): string => this.projectsStore.name(value);

  onProjectChange(value: number | null | undefined): void {
    this.project.set(value ?? undefined);
    this.expanded.set(null);
  }

  toggle(release: Release): void {
    this.expanded.update((version) => (version === release.version ? null : release.version));
  }

  size(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
}
