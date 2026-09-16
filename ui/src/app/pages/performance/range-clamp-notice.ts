import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Notice shown when the server narrowed the global range to the 30-day cap
 * (ADR-0015). Shared component rather than duplicated markup, so the leaderboard and
 * detail view never disagree about the cap's wording.
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
