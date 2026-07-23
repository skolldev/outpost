import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { HlmSelectImports } from '@spartan-ng/helm/select';

import { Api } from '../../core/api';
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
  private readonly api = inject(Api);
  readonly projectsStore = inject(ProjectsStore);

  readonly project = signal<number | undefined>(undefined);

  readonly releases = signal<Release[]>([]);
  readonly artifacts = signal<ReleaseArtifact[]>([]);
  readonly expanded = signal<string | null>(null);

  readonly timeAgo = timeAgo;

  /** Resolves a project select value to its trigger label. */
  readonly projectLabel = (value: number): string => this.projectsStore.name(value);

  constructor() {
    effect(() => {
      const project = this.project();
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

  onProjectChange(value: number | null | undefined): void {
    this.project.set(value ?? undefined);
  }

  async toggle(release: Release): Promise<void> {
    if (this.expanded() === release.version) {
      this.expanded.set(null);
      return;
    }
    const project = this.project();
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
