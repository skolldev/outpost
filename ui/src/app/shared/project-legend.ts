import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { ProjectsStore } from '../core/projects';
import { projectColor } from './ui';

/** Color legend mapping project dots to project names, shared by the trace views. */
@Component({
  selector: 'app-project-legend',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex flex-wrap items-center gap-4 text-xs text-muted-foreground' },
  template: `
    @for (item of items(); track item.id) {
      <span class="flex items-center gap-1.5">
        <span
          class="inline-block size-2.5 rounded-full"
          [style.background-color]="item.color"
        ></span>
        {{ item.name }}
      </span>
    }
  `,
})
export class ProjectLegend {
  private readonly store = inject(ProjectsStore);

  readonly projectIds = input.required<number[]>();

  readonly items = computed(() =>
    this.projectIds().map((id) => ({ id, name: this.store.name(id), color: projectColor(id) })),
  );
}
