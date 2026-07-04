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
  templateUrl: './releases.html',
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
