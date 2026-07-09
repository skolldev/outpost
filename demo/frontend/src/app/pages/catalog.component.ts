import { Component, inject, signal } from '@angular/core';
import * as Sentry from '@sentry/angular';
import { Product, ShopApiService } from '../core/shop-api.service';

@Component({
  selector: 'app-catalog',
  template: `
    <h2>Catalog</h2>
    @if (error()) {
      <p class="error">Could not load products: {{ error() }}</p>
    }
    <div class="grid">
      @for (p of products(); track p.id) {
        <div class="card">
          <h3>{{ p.name }}</h3>
          <p class="sku">{{ p.sku }}</p>
          <p class="price">{{ (p.priceCents / 100).toFixed(2) }} €</p>
          <p class="stock">{{ p.stock }} in stock</p>
        </div>
      }
    </div>
  `,
})
export class CatalogComponent {
  private readonly api = inject(ShopApiService);
  readonly products = signal<Product[]>([]);
  readonly error = signal<string | null>(null);

  constructor() {
    // Fetch on load: an http.client span inside the pageload/navigation transaction.
    this.api.products().subscribe({
      next: (products) => {
        this.products.set(products);
        Sentry.logger.info(Sentry.logger.fmt`Catalog loaded with ${String(products.length)} products`);
      },
      error: (err) => this.error.set(err.message ?? 'unknown'),
    });
  }
}
