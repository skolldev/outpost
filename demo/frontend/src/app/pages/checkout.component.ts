import { Component, computed, inject, signal } from '@angular/core';
import * as Sentry from '@sentry/angular';
import { Product, ShopApiService } from '../core/shop-api.service';
import { DemoUserService } from '../core/demo-user.service';

interface CartLine {
  product: Product;
  quantity: number;
}

@Component({
  selector: 'app-checkout',
  template: `
    <h2>Checkout</h2>
    <div class="checkout-grid">
      <section>
        <h3>Add to cart</h3>
        @for (p of products(); track p.id) {
          <button class="add" (click)="add(p)">+ {{ p.name }}</button>
        }
      </section>
      <section>
        <h3>Cart</h3>
        @if (cart().length === 0) {
          <p>Cart is empty.</p>
        }
        @for (line of cart(); track line.product.id) {
          <p>{{ line.quantity }}× {{ line.product.name }}</p>
        }
        <p class="price">Total: {{ (totalCents() / 100).toFixed(2) }} €</p>
        <button class="primary" [disabled]="cart().length === 0 || busy()" (click)="checkout()">
          {{ busy() ? 'Placing order…' : 'Place order' }}
        </button>
        @if (confirmation(); as msg) {
          <p class="ok">{{ msg }}</p>
        }
        @if (error(); as msg) {
          <p class="error">{{ msg }}</p>
        }
      </section>
    </div>
  `,
})
export class CheckoutComponent {
  private readonly api = inject(ShopApiService);
  private readonly users = inject(DemoUserService);

  readonly products = signal<Product[]>([]);
  readonly cart = signal<CartLine[]>([]);
  readonly busy = signal(false);
  readonly confirmation = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly totalCents = computed(() =>
    this.cart().reduce((sum, l) => sum + l.product.priceCents * l.quantity, 0),
  );

  constructor() {
    this.api.products().subscribe((products) => this.products.set(products.slice(0, 6)));
  }

  add(product: Product): void {
    Sentry.addBreadcrumb({ category: 'cart', message: `Added ${product.sku} to cart` });
    this.cart.update((lines) => {
      const existing = lines.find((l) => l.product.id === product.id);
      return existing
        ? lines.map((l) => (l === existing ? { ...l, quantity: l.quantity + 1 } : l))
        : [...lines, { product, quantity: 1 }];
    });
  }

  checkout(): void {
    this.busy.set(true);
    this.confirmation.set(null);
    this.error.set(null);
    const email = `${this.users.user() ?? 'guest'}@example.com`;
    const items = this.cart().map((l) => ({ sku: l.product.sku, quantity: l.quantity }));
    // The distributed-trace showcase: this POST carries sentry-trace/baggage, so
    // the backend checkout + shipping-quote transactions join this browser trace.
    this.api.checkout(email, '10115', items).subscribe({
      next: (res) => {
        this.busy.set(false);
        this.cart.set([]);
        this.confirmation.set(
          `Order #${res.orderId} placed — shipping via ${res.shipping.carrier} for ${(res.shipping.cents / 100).toFixed(2)} €`,
        );
        Sentry.logger.info(Sentry.logger.fmt`Order ${String(res.orderId)} placed from checkout page`);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(err.message ?? 'checkout failed');
      },
    });
  }
}
