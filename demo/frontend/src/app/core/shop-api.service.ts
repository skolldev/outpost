import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { RUNTIME_CONFIG } from './runtime-config';

export interface Product {
  id: number;
  sku: string;
  name: string;
  priceCents: number;
  stock: number;
}

export interface CheckoutResponse {
  orderId: number;
  totalCents: number;
  shipping: { zip: string; carrier: string; cents: number };
}

@Injectable({ providedIn: 'root' })
export class ShopApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(RUNTIME_CONFIG).apiBase;

  products(): Observable<Product[]> {
    return this.http.get<Product[]>(`${this.base}/api/products`);
  }

  slowProducts(ms: number): Observable<Product[]> {
    return this.http.get<Product[]>(`${this.base}/api/products/slow`, { params: { ms } });
  }

  nPlusOne(): Observable<unknown> {
    return this.http.get(`${this.base}/api/orders/nplusone`);
  }

  checkout(email: string, zip: string, items: { sku: string; quantity: number }[]): Observable<CheckoutResponse> {
    return this.http.post<CheckoutResponse>(`${this.base}/api/checkout`, { email, zip, items });
  }

  boom(): Observable<unknown> {
    return this.http.get(`${this.base}/api/boom`);
  }

  handled(): Observable<unknown> {
    return this.http.post(`${this.base}/api/handled`, {});
  }

  logBurst(n: number): Observable<unknown> {
    return this.http.post(`${this.base}/api/logburst`, {}, { params: { n } });
  }
}
