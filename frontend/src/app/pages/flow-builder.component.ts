import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { Capability, CapabilityType, Flow } from '../core/models';

@Component({
  selector: 'rg-flow-builder',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    @if (flow(); as f) {
      <div class="page-head row fade-up">
        <div>
          <a routerLink="/flows" class="back">← Flows</a>
          <h1>{{ f.name }}</h1>
          <code class="mono endpoint">/api/flows/{{ f.slug }}/run</code>
        </div>
        <span class="spacer"></span>
        <div class="row">
          <a routerLink="/playground" [queryParams]="{ flow: f.slug }" class="btn btn-ghost">Open in Playground</a>
          @if (f.status !== 'published') {
            <button class="btn" (click)="publish()">Publish</button>
          }
          <button class="btn btn-primary" [disabled]="saving()" (click)="save()">
            @if (saving()) { <span class="spinner"></span> } Save
          </button>
        </div>
      </div>

      @if (toast()) { <div class="toast fade-up">{{ toast() }}</div> }

      <div class="builder fade-up">
        <div class="col">
          <div class="card section">
            <h3>Configuration</h3>
            <div class="grid g2">
              <label class="field"><span>Name</span>
                <input class="input" [(ngModel)]="name" /></label>
              <label class="field"><span>Status</span>
                <select class="select" [(ngModel)]="status">
                  <option value="draft">draft</option>
                  <option value="published">published</option>
                  <option value="archived">archived</option>
                </select></label>
            </div>
            <label class="field" style="margin-top:14px"><span>Description</span>
              <input class="input" [(ngModel)]="description" /></label>
            <label class="field" style="margin-top:14px">
              <span>Config (JSON) — models, guardrails, IO schema, and
                <code class="mono">subagents.agents[]</code> (skill-defined: name / skill / when / capabilities)</span>
              <textarea class="textarea" rows="14" [(ngModel)]="configText"
                        [class.invalid]="!configValid()"></textarea>
            </label>
            @if (!configValid()) { <small class="err">Invalid JSON — fix before saving.</small> }
          </div>
        </div>

        <div class="col">
          <div class="card section">
            <div class="row">
              <h3>Attached capabilities</h3>
              <span class="spacer"></span>
              <span class="faint">{{ selectedIds().size }} selected</span>
            </div>
            <p class="muted small">Capabilities live in the registry and are referenced by flows. Toggle to attach.</p>

            @for (group of grouped(); track group.type) {
              <div class="cap-group">
                <div class="cap-group-head"><span class="badge {{ group.type }}">{{ group.type }}</span></div>
                @for (c of group.items; track c.id) {
                  <button class="cap-row" [class.on]="selectedIds().has(c.id)" (click)="toggle(c.id)">
                    <span class="check">
                      @if (selectedIds().has(c.id)) {
                        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
                      }
                    </span>
                    <span class="cap-main">
                      <strong>{{ c.name }}</strong>
                      <small class="faint">{{ c.description || c.slug }}</small>
                    </span>
                  </button>
                }
              </div>
            }
            @if (capabilities().length === 0) {
              <div class="empty">No capabilities. Create some in the Registry.</div>
            }
          </div>

          <button class="btn btn-danger" style="margin-top:16px" (click)="remove()">Delete flow</button>
        </div>
      </div>
    } @else {
      <div class="empty fade-up">Loading flow…</div>
    }
  `,
  styles: [`
    .back { color: var(--text-dim); font-size: 13px; font-weight: 600; }
    .endpoint { display: inline-block; margin-top: 8px; font-size: 12.5px; color: var(--teal); background: rgba(35,213,171,0.08); padding: 5px 10px; border-radius: 8px; }
    .builder { display: grid; grid-template-columns: 1.15fr 1fr; gap: 20px; align-items: start; }
    .col { display: flex; flex-direction: column; }
    .section { padding: 22px; }
    .section h3 { font-size: 16px; margin-bottom: 16px; }
    .g2 { grid-template-columns: 1fr 1fr; }
    .small { font-size: 13px; margin: -6px 0 14px; }
    .textarea.invalid { border-color: var(--red); }
    .err { color: var(--red); font-size: 12.5px; }
    .cap-group { margin-top: 10px; }
    .cap-group-head { margin: 12px 0 8px; }
    .cap-row {
      display: flex; align-items: center; gap: 12px; width: 100%; text-align: left;
      padding: 11px 13px; margin-bottom: 6px; border-radius: 12px;
      background: rgba(8,10,20,0.4); border: 1px solid var(--border); color: var(--text);
      transition: all 0.15s ease;
    }
    .cap-row:hover { border-color: var(--border-strong); }
    .cap-row.on { border-color: var(--violet); background: var(--grad-soft); }
    .check {
      width: 22px; height: 22px; flex: none; border-radius: 7px; display: grid; place-items: center;
      border: 1px solid var(--border-strong); color: var(--teal);
    }
    .cap-row.on .check { background: var(--grad); color: #0a0b14; border: none; }
    .cap-main { display: flex; flex-direction: column; gap: 2px; overflow: hidden; }
    .cap-main strong { font-size: 14px; }
    .cap-main small { font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .toast { margin-bottom: 18px; padding: 12px 16px; border-radius: 12px; background: var(--grad-soft); border: 1px solid var(--border-strong); font-weight: 600; }
    @media (max-width: 980px) { .builder { grid-template-columns: 1fr; } .g2 { grid-template-columns: 1fr; } }
  `],
})
export class FlowBuilderComponent implements OnInit {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  flow = signal<Flow | null>(null);
  capabilities = signal<Capability[]>([]);
  selectedIds = signal<Set<string>>(new Set());
  saving = signal(false);
  toast = signal('');

  name = '';
  description = '';
  status = 'draft';
  configText = '{}';

  configValid = computed(() => { try { JSON.parse(this.configText); return true; } catch { return false; } });

  grouped = computed(() => {
    const order: CapabilityType[] = ['skill', 'tool', 'function', 'mcp'];
    return order
      .map((type) => ({ type, items: this.capabilities().filter((c) => c.type === type) }))
      .filter((g) => g.items.length > 0);
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.api.listCapabilities().subscribe((c) => this.capabilities.set(c));
    this.api.getFlow(id).subscribe((f) => {
      this.flow.set(f);
      this.name = f.name;
      this.description = f.description ?? '';
      this.status = f.status;
      this.configText = JSON.stringify(f.config ?? {}, null, 2);
      this.selectedIds.set(new Set(f.capabilities.map((c) => c.id)));
    });
  }

  toggle(id: string): void {
    const set = new Set(this.selectedIds());
    set.has(id) ? set.delete(id) : set.add(id);
    this.selectedIds.set(set);
  }

  save(): void {
    if (!this.configValid()) return;
    const f = this.flow()!;
    this.saving.set(true);
    this.api.updateFlow(f.id, {
      name: this.name,
      status: this.status,
      description: this.description,
      config: JSON.parse(this.configText),
      capabilityIds: [...this.selectedIds()],
    }).subscribe({
      next: (updated) => { this.flow.set(updated); this.saving.set(false); this.flash('Saved'); },
      error: () => this.saving.set(false),
    });
  }

  publish(): void {
    const f = this.flow()!;
    this.api.publishFlow(f.id).subscribe((updated) => {
      this.flow.set(updated); this.status = updated.status; this.flash('Published v' + updated.version);
    });
  }

  remove(): void {
    const f = this.flow()!;
    if (!confirm(`Delete flow "${f.name}"? This cannot be undone.`)) return;
    this.api.deleteFlow(f.id).subscribe(() => this.router.navigate(['/flows']));
  }

  private flash(msg: string): void {
    this.toast.set(msg);
    setTimeout(() => this.toast.set(''), 2500);
  }
}
