import { Component, inject, signal } from '@angular/core';
import * as Sentry from '@sentry/angular';
import { ShopApiService } from '../core/shop-api.service';

interface Scenario {
  label: string;
  hint: string;
  run: () => void;
}

@Component({
  selector: 'app-scenario-panel',
  template: `
    <aside class="panel">
      <h2>Scenarios</h2>
      <p class="subtitle">Each button sends real SDK telemetry to Outpost.</p>
      @for (s of scenarios; track s.label) {
        <button (click)="trigger(s)" [title]="s.hint">{{ s.label }}</button>
      }
      @if (status(); as msg) {
        <p class="status">{{ msg }}</p>
      }
    </aside>
  `,
})
export class ScenarioPanelComponent {
  private readonly api = inject(ShopApiService);
  readonly status = signal<string | null>(null);

  readonly scenarios: Scenario[] = [
    {
      label: '💥 Unhandled frontend error',
      hint: 'TypeError caught by Sentry.createErrorHandler → Issues (symbolicated after sourcemap upload)',
      run: () => {
        const cart = undefined as unknown as { total: number };
        // Throws "Cannot read properties of undefined" through Angular's ErrorHandler.
        this.status.set(`total: ${cart.total}`);
      },
    },
    {
      label: '🧾 Handled error + attachment',
      hint: 'captureException with tags and a cart.json attachment → Issues detail',
      run: () => {
        Sentry.withScope((scope) => {
          scope.setTag('area', 'cart');
          scope.setTag('checkout.step', 'coupon');
          scope.addAttachment({
            filename: 'cart.json',
            contentType: 'application/json',
            data: JSON.stringify({ items: [{ sku: 'SKU-1003', quantity: 1 }], coupon: 'SUMMER-EXPIRED' }),
          });
          Sentry.captureException(new Error('Coupon validation failed: SUMMER-EXPIRED'));
        });
        this.done('Captured handled error with attachment');
      },
    },
    {
      label: '🧬 Custom fingerprint ×3',
      hint: 'Three different messages grouped into one issue via setFingerprint',
      run: () => {
        for (const coupon of ['SPRING24', 'VIP-GOLD', 'WELCOME10']) {
          Sentry.withScope((scope) => {
            scope.setFingerprint(['demo', 'coupon-invalid']);
            Sentry.captureException(new Error(`Invalid coupon rejected: ${coupon}`));
          });
        }
        this.done('Sent 3 events sharing fingerprint [demo, coupon-invalid]');
      },
    },
    {
      label: '🔌 Failed fetch',
      hint: 'fetch to a dead port → captured TypeError + errored http.client span',
      run: () => {
        fetch('http://localhost:9999/unreachable').catch((err) => {
          Sentry.captureException(err);
          this.done('Captured failed fetch');
        });
      },
    },
    {
      label: '🔥 Backend 500 (/api/boom)',
      hint: 'Propagates the trace; backend error lands pinned to the same trace',
      run: () => this.api.boom().subscribe({ error: () => this.done('Backend threw — check shop-backend issues') }),
    },
    {
      label: '🐢 Slow request (2 s)',
      hint: 'Backend cache.rebuild span + db span → Traces duration filter',
      run: () => this.api.slowProducts(2000).subscribe(() => this.done('Slow request finished')),
    },
    {
      label: '🐇 N+1 queries',
      hint: 'One backend transaction with 15+ sequential db.query spans',
      run: () => this.api.nPlusOne().subscribe(() => this.done('N+1 report finished')),
    },
    {
      label: '🧾 Backend handled + attachment',
      hint: 'Backend captureException with cart-snapshot.json + custom fingerprint',
      run: () => this.api.handled().subscribe(() => this.done('Backend captured handled error')),
    },
    {
      label: '🪵 Frontend log burst (100)',
      hint: 'Sentry.logger + console integration → Logs live tail',
      run: () => {
        for (let i = 0; i < 100; i++) {
          const orderId = String(1000 + Math.floor(Math.random() * 9000));
          switch (i % 4) {
            case 0:
              Sentry.logger.debug(Sentry.logger.fmt`recomputed recommendations for order ${orderId}`);
              break;
            case 1:
              Sentry.logger.info(Sentry.logger.fmt`rendered product tile ${orderId}`);
              break;
            case 2:
              Sentry.logger.warn(Sentry.logger.fmt`slow image load for order ${orderId}`);
              break;
            default:
              console.error(`checkout widget recovered from render glitch (order ${orderId})`);
          }
        }
        this.done('Emitted 100 frontend logs');
      },
    },
    {
      label: '🪵 Backend log burst (200)',
      hint: 'SLF4J burst inside the request span → trace-correlated logs',
      run: () => this.api.logBurst(200).subscribe(() => this.done('Backend emitted 200 logs')),
    },
    {
      label: '🌊 Backpressure (5000 logs)',
      hint: 'Oversized burst to exercise 429 rate limiting + client reports',
      run: () => this.api.logBurst(5000).subscribe(() => this.done('Burst sent — watch for 429s')),
    },
  ];

  trigger(s: Scenario): void {
    Sentry.addBreadcrumb({ category: 'demo.trigger', message: s.label, level: 'info' });
    this.status.set(null);
    s.run();
  }

  private done(msg: string): void {
    this.status.set(msg);
  }
}
