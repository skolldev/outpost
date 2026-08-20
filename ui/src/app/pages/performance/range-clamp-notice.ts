import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * What the Performance surfaces say when the server narrowed the global range filter
 * to the 30-day cap (ADR-0015).
 *
 * A component rather than the same paragraph in two templates: the leaderboard and the
 * detail view are read in sequence over the same window, and the whole point of the
 * notice is that the figures never quietly disagree with the filter on screen. Two
 * copies of the wording would be two chances to explain the same cap differently.
 */
@Component({
  selector: 'app-range-clamp-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p
      role="status"
      class="mb-4 block rounded-lg border border-border bg-muted px-3 py-2 text-sm text-muted-foreground"
    >
      Showing the last 30 days. Performance statistics are capped at 30 days.
    </p>
  `,
})
export class RangeClampNotice {}
