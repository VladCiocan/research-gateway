import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { Flow } from '../core/models';

@Component({
  selector: 'rg-flows',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="page-head row fade-up">
      <div>
        <h1>Flows</h1>
        <p>Each published flow exposes a dynamic endpoint <code class="mono">POST /api/flows/&#123;slug&#125;/run</code>.</p>
      </div>
      <span class="spacer"></span>
      <button class="btn btn-primary" (click)="showCreate.set(!showCreate())">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>
        New flow
      </button>
    </div>

    @if (showCreate()) {
      <div class="card create fade-up">
        <div class="grid create-grid">
          <label class="field"><span>Name</span>
            <input class="input" [(ngModel)]="newName" placeholder="e.g. Market Research" /></label>
          <label class="field"><span>Description</span>
            <input class="input" [(ngModel)]="newDesc" placeholder="What does this flow research?" /></label>
        </div>
        <div class="row" style="margin-top:14px">
          <span class="spacer"></span>
          <button class="btn btn-ghost" (click)="showCreate.set(false)">Cancel</button>
          <button class="btn btn-primary" [disabled]="!newName.trim() || saving()" (click)="create()">
            @if (saving()) { <span class="spinner"></span> } Create flow
          </button>
        </div>
      </div>
    }

    <div class="grid cards fade-up">
      @for (f of flows(); track f.id) {
        <a [routerLink]="['/flows', f.id]" class="card flow-card">
          <div class="row">
            <span class="badge {{ f.status }}"><span class="dot"></span>{{ f.status }}</span>
            <span class="spacer"></span>
            <span class="faint mono">v{{ f.version }}</span>
          </div>
          <h3>{{ f.name }}</h3>
          <p class="muted desc">{{ f.description || 'No description provided.' }}</p>
          <code class="endpoint mono">/api/flows/{{ f.slug }}/run</code>
          <div class="chips">
            @for (c of f.capabilities.slice(0, 4); track c.id) {
              <span class="badge {{ c.type }}">{{ c.name }}</span>
            }
            @if (f.capabilities.length > 4) { <span class="badge">+{{ f.capabilities.length - 4 }}</span> }
            @if (f.capabilities.length === 0) { <span class="faint">No capabilities attached</span> }
          </div>
        </a>
      }
      @if (flows().length === 0 && !showCreate()) {
        <div class="empty" style="grid-column: 1/-1">No flows yet. Create your first one.</div>
      }
    </div>
  `,
  styles: [`
    .create { padding: 20px; margin-bottom: 22px; }
    .create-grid { grid-template-columns: 1fr 1fr; }
    .cards { grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); }
    .flow-card { padding: 20px; display: flex; flex-direction: column; gap: 12px; transition: all 0.18s ease; }
    .flow-card:hover { transform: translateY(-3px); box-shadow: var(--shadow-glow); border-color: var(--border-strong); }
    .flow-card h3 { font-size: 18px; }
    .desc { font-size: 13.5px; min-height: 38px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .endpoint { font-size: 12px; color: var(--teal); background: rgba(35,213,171,0.08); padding: 6px 10px; border-radius: 8px; width: fit-content; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 2px; }
    @media (max-width: 720px) { .create-grid { grid-template-columns: 1fr; } }
  `],
})
export class FlowsComponent implements OnInit {
  private api = inject(ApiService);
  private router = inject(Router);

  flows = signal<Flow[]>([]);
  showCreate = signal(false);
  saving = signal(false);
  newName = '';
  newDesc = '';

  ngOnInit(): void { this.load(); }

  load(): void { this.api.listFlows().subscribe((f) => this.flows.set(f)); }

  create(): void {
    this.saving.set(true);
    this.api.createFlow({ name: this.newName.trim(), description: this.newDesc.trim() }).subscribe({
      next: (f) => { this.saving.set(false); this.router.navigate(['/flows', f.id]); },
      error: () => this.saving.set(false),
    });
  }
}
