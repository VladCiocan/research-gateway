import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

interface NavItem { path: string; label: string; icon: string; }

@Component({
  selector: 'rg-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell">
      <aside class="sidebar">
        <div class="brand">
          <div class="brand-mark">
            <svg viewBox="0 0 64 64" width="34" height="34">
              <defs>
                <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stop-color="#7c5cff"/><stop offset="1" stop-color="#23d5ab"/>
                </linearGradient>
              </defs>
              <circle cx="28" cy="28" r="13" fill="none" stroke="url(#bg)" stroke-width="5"/>
              <line x1="38" y1="38" x2="50" y2="50" stroke="url(#bg)" stroke-width="6" stroke-linecap="round"/>
            </svg>
          </div>
          <div class="brand-text">
            <strong>Research<span class="gradient-text">Gateway</span></strong>
            <small>agentic orchestration</small>
          </div>
        </div>

        <nav>
          @for (item of nav; track item.path) {
            <a [routerLink]="item.path" routerLinkActive="active" class="nav-item">
              <span class="ico" [innerHTML]="item.icon"></span>
              <span>{{ item.label }}</span>
            </a>
          }
        </nav>

        <div class="side-foot">
          <div class="status-pill">
            <span class="dot" style="color: var(--teal)"></span>
            Gateway online
          </div>
          <small class="faint">v0.1.0 · single-image build</small>
        </div>
      </aside>

      <main class="content">
        <router-outlet />
      </main>
    </div>
  `,
  styles: [`
    .shell { display: grid; grid-template-columns: 260px 1fr; min-height: 100vh; }
    .sidebar {
      position: sticky; top: 0; height: 100vh;
      display: flex; flex-direction: column;
      padding: 24px 18px;
      border-right: 1px solid var(--border);
      background: linear-gradient(180deg, rgba(13,15,26,0.7), rgba(7,8,17,0.5));
      backdrop-filter: blur(20px);
    }
    .brand { display: flex; align-items: center; gap: 12px; padding: 4px 8px 26px; }
    .brand-mark {
      display: grid; place-items: center; width: 46px; height: 46px;
      border-radius: 13px; background: var(--grad-soft); border: 1px solid var(--border);
    }
    .brand-text strong { display: block; font-size: 16px; letter-spacing: -0.02em; }
    .brand-text small { color: var(--text-faint); font-size: 11px; letter-spacing: 0.04em; text-transform: uppercase; }

    nav { display: flex; flex-direction: column; gap: 4px; flex: 1; }
    .nav-item {
      display: flex; align-items: center; gap: 12px;
      padding: 11px 13px; border-radius: 12px;
      color: var(--text-dim); font-weight: 600; font-size: 14px;
      transition: all 0.16s ease; position: relative;
    }
    .nav-item:hover { background: var(--surface-hover); color: var(--text); }
    .nav-item.active { color: var(--text); background: var(--grad-soft); }
    .nav-item.active::before {
      content: ''; position: absolute; left: -18px; top: 50%; transform: translateY(-50%);
      width: 4px; height: 22px; border-radius: 0 4px 4px 0; background: var(--grad);
    }
    .ico { display: inline-flex; width: 20px; height: 20px; }
    .ico ::ng-deep svg { width: 20px; height: 20px; stroke: currentColor; fill: none; stroke-width: 1.9; stroke-linecap: round; stroke-linejoin: round; }

    .side-foot { display: flex; flex-direction: column; gap: 10px; padding: 14px 8px 4px; }
    .status-pill {
      display: inline-flex; align-items: center; gap: 8px;
      font-size: 12.5px; font-weight: 600; color: var(--text-dim);
      padding: 7px 12px; border-radius: 999px; border: 1px solid var(--border);
      background: rgba(35,213,171,0.06); width: fit-content;
    }
    .content { padding: 38px 44px; max-width: 1320px; width: 100%; }

    @media (max-width: 860px) {
      .shell { grid-template-columns: 1fr; }
      .sidebar { position: relative; height: auto; flex-direction: row; align-items: center; flex-wrap: wrap; }
      nav { flex-direction: row; flex-wrap: wrap; }
      .nav-item.active::before { display: none; }
      .side-foot { display: none; }
      .content { padding: 24px 18px; }
    }
  `],
})
export class AppComponent {
  nav: NavItem[] = [
    { path: 'dashboard', label: 'Dashboard', icon: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="9"/><rect x="14" y="3" width="7" height="5"/><rect x="14" y="12" width="7" height="9"/><rect x="3" y="16" width="7" height="5"/></svg>' },
    { path: 'flows', label: 'Flows', icon: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="6" height="6" rx="1"/><rect x="15" y="15" width="6" height="6" rx="1"/><path d="M9 6h6a3 3 0 0 1 3 3v6"/></svg>' },
    { path: 'capabilities', label: 'Capabilities', icon: '<svg viewBox="0 0 24 24"><path d="M12 2 3 7v10l9 5 9-5V7z"/><path d="M3 7l9 5 9-5"/><path d="M12 12v10"/></svg>' },
    { path: 'playground', label: 'Playground', icon: '<svg viewBox="0 0 24 24"><polygon points="5 3 19 12 5 21 5 3"/></svg>' },
    { path: 'runs', label: 'Runs', icon: '<svg viewBox="0 0 24 24"><path d="M3 12a9 9 0 1 0 9-9"/><path d="M3 4v5h5"/><path d="M12 7v5l3 2"/></svg>' },
    { path: 'settings', label: 'Settings', icon: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 7 19.4a1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.6 1.6 0 0 0 2.6 14H2a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.6 7a1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.6 1.6 0 0 0 10 2.6V2a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7H22a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z"/></svg>' },
  ];
}
