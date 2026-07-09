import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** 14-day issue activity sparkline. */
@Component({
  selector: 'app-sparkline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sparkline.html',
})
export class Sparkline {
  readonly data = input<number[]>([]);

  readonly width = 84;
  readonly height = 24;
  readonly barWidth = 4;

  readonly bars = computed(() => {
    const data = this.data();
    const max = Math.max(1, ...data);
    return data.map((value, i) => {
      const h = value > 0 ? Math.max(2, Math.round((value / max) * this.height)) : 2;
      return { x: i * 6, y: this.height - h, h, value };
    });
  });
}
