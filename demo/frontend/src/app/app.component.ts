import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { DemoUserService, PERSONAS, Persona } from './core/demo-user.service';
import { ScenarioPanelComponent } from './pages/scenario-panel.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ScenarioPanelComponent],
  template: `
    <header class="topbar">
      <h1>🛰️ Outpost Demo Shop</h1>
      <nav>
        <a routerLink="/catalog" routerLinkActive="active">Catalog</a>
        <a routerLink="/checkout" routerLinkActive="active">Checkout</a>
      </nav>
      <div class="user-picker">
        @if (users.user(); as current) {
          <span class="user">👤 {{ current }}</span>
          <button (click)="users.signOut()">Sign out</button>
        } @else {
          <span>Sign in:</span>
          @for (p of personas; track p) {
            <button (click)="users.signIn(p)">{{ p }}</button>
          }
        }
      </div>
    </header>
    <div class="layout">
      <main>
        <router-outlet />
      </main>
      <app-scenario-panel />
    </div>
  `,
})
export class AppComponent {
  readonly users = inject(DemoUserService);
  readonly personas: readonly Persona[] = PERSONAS;
}
