import { Injectable, computed } from '@angular/core';
import { httpResource } from '@angular/common/http';

import { API_BASE } from './api-base';
import { Project } from './models';

/**
 * Shared project list, fetched once and reused by the shell's project selector
 * and anywhere project ids need to be resolved to display names.
 */
@Injectable({ providedIn: 'root' })
export class ProjectsStore {
  private readonly resource = httpResource<Project[]>(() => `${API_BASE}/projects`, {
    defaultValue: [],
  });

  readonly projects = this.resource.value;

  private readonly nameById = computed(
    () => new Map(this.projects().map((project) => [project.id, project.name])),
  );

  /** Display name for a project id; falls back while loading or for unknown ids. */
  name(id: number): string {
    return this.nameById().get(id) ?? `project ${id}`;
  }
}
