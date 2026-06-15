import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { Capability, CapabilityType } from '../core/models';

@Component({
  selector: 'rg-capabilities',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="page-head row fade-up">
      <div>
        <h1>Capability Registry</h1>
        <p>Reusable building blocks — skills, tools, native functions and MCP servers — attachable to any flow.</p>
      </div>
      <span class="spacer"></span>
      <button class="btn btn-primary" (click)="startNew()">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>
        New capability
      </button>
    </div>

    <div class="filters fade-up">
      <button class="chip" [class.on]="filter() === ''" (click)="filter.set('')">All ({{ items().length }})</button>
      @for (t of types; track t) {
        <button class="chip" [class.on]="filter() === t" (click)="filter.set(t)">
          <span class="badge {{ t }}">{{ t }}</span> {{ count(t) }}
        </button>
      }
    </div>

    <div class="layout fade-up">
      <div class="list">
        @for (c of visible(); track c.id) {
          <button class="cap" [class.on]="editing()?.id === c.id" (click)="edit(c)">
            <span class="badge {{ c.type }}">{{ c.type }}</span>
            <span class="cap-main">
              <strong>{{ c.name }}</strong>
              <small class="faint">{{ c.description || c.slug }}</small>
            </span>
            <span class="faint mono">v{{ c.version }}</span>
          </button>
        }
        @if (visible().length === 0) { <div class="empty">No capabilities here yet.</div> }
      </div>

      <div class="card editor">
        @if (editing() || creating()) {
          <h3>{{ creating() ? 'New capability' : 'Edit capability' }}</h3>
          <div class="grid g2" style="margin-top:16px">
            <label class="field"><span>Type</span>
              <select class="select" [(ngModel)]="form.type">
                @for (t of types; track t) { <option [value]="t">{{ t }}</option> }
              </select></label>
            <label class="field"><span>Name</span>
              <input class="input" [(ngModel)]="form.name" placeholder="e.g. Web Search" /></label>
          </div>
          <label class="field" style="margin-top:14px"><span>Description</span>
            <input class="input" [(ngModel)]="form.description" placeholder="Short, model-visible description" /></label>
          <label class="field" style="margin-top:14px"><span>Spec (JSON) — input schema / config / resources</span>
            <textarea class="textarea" rows="10" [(ngModel)]="specText" [class.invalid]="!specValid()"></textarea></label>
          @if (!specValid()) { <small class="err">Invalid JSON.</small> }

          <div class="row" style="margin-top:18px">
            @if (!creating()) { <button class="btn btn-danger" (click)="remove()">Delete</button> }
            <span class="spacer"></span>
            <button class="btn btn-ghost" (click)="cancel()">Cancel</button>
            <button class="btn btn-primary" [disabled]="!form.name.trim() || !specValid() || saving()" (click)="submit()">
              @if (saving()) { <span class="spinner"></span> } Save
            </button>
          </div>
        } @else {
          <div class="placeholder">
            <svg viewBox="0 0 24 24" width="46" height="46" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" style="opacity:.4"><path d="M12 2 3 7v10l9 5 9-5V7z"/><path d="M3 7l9 5 9-5"/><path d="M12 12v10"/></svg>
            <p class="muted">Select a capability to edit, or create a new one.</p>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .filters { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 18px; }
    .chip {
      display: inline-flex; align-items: center; gap: 7px;
      padding: 7px 13px; border-radius: 999px; font-size: 13px; font-weight: 600;
      background: var(--surface); border: 1px solid var(--border); color: var(--text-dim);
    }
    .chip.on { color: var(--text); border-color: var(--violet); background: var(--grad-soft); }
    .layout { display: grid; grid-template-columns: 1fr 1.1fr; gap: 20px; align-items: start; }
    .list { display: flex; flex-direction: column; gap: 8px; }
    .cap {
      display: flex; align-items: center; gap: 12px; text-align: left; width: 100%;
      padding: 13px 15px; border-radius: 13px; color: var(--text);
      background: var(--surface); border: 1px solid var(--border); transition: all 0.15s ease;
    }
    .cap:hover { border-color: var(--border-strong); transform: translateX(2px); }
    .cap.on { border-color: var(--violet); box-shadow: var(--shadow-glow); }
    .cap-main { display: flex; flex-direction: column; gap: 2px; flex: 1; overflow: hidden; }
    .cap-main strong { font-size: 14.5px; }
    .cap-main small { font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .editor { padding: 24px; position: sticky; top: 24px; }
    .editor h3 { font-size: 17px; }
    .g2 { grid-template-columns: 1fr 1fr; }
    .textarea.invalid { border-color: var(--red); }
    .err { color: var(--red); font-size: 12.5px; }
    .placeholder { display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 60px 20px; text-align: center; }
    @media (max-width: 980px) { .layout { grid-template-columns: 1fr; } .editor { position: static; } }
  `],
})
export class CapabilitiesComponent implements OnInit {
  private api = inject(ApiService);
  types: CapabilityType[] = ['skill', 'tool', 'function', 'mcp'];

  items = signal<Capability[]>([]);
  filter = signal<string>('');
  editing = signal<Capability | null>(null);
  creating = signal(false);
  saving = signal(false);

  form: { type: CapabilityType; name: string; description: string } = { type: 'skill', name: '', description: '' };
  specText = '{}';

  specValid = computed(() => { try { JSON.parse(this.specText); return true; } catch { return false; } });
  visible = computed(() => {
    const f = this.filter();
    return f ? this.items().filter((c) => c.type === f) : this.items();
  });

  ngOnInit(): void { this.load(); }

  load(): void { this.api.listCapabilities().subscribe((c) => this.items.set(c)); }
  count = (t: string) => this.items().filter((c) => c.type === t).length;

  startNew(): void {
    this.editing.set(null);
    this.creating.set(true);
    this.form = { type: 'skill', name: '', description: '' };
    this.specText = '{}';
  }

  edit(c: Capability): void {
    this.creating.set(false);
    this.editing.set(c);
    this.form = { type: c.type, name: c.name, description: c.description ?? '' };
    this.specText = JSON.stringify(c.spec ?? {}, null, 2);
  }

  cancel(): void { this.editing.set(null); this.creating.set(false); }

  submit(): void {
    if (!this.specValid()) return;
    this.saving.set(true);
    const body = {
      type: this.form.type,
      name: this.form.name.trim(),
      description: this.form.description.trim(),
      spec: JSON.parse(this.specText),
    };
    const done = () => { this.saving.set(false); this.cancel(); this.load(); };
    if (this.creating()) {
      this.api.createCapability(body).subscribe({ next: done, error: () => this.saving.set(false) });
    } else {
      this.api.updateCapability(this.editing()!.id, body).subscribe({ next: done, error: () => this.saving.set(false) });
    }
  }

  remove(): void {
    const c = this.editing();
    if (!c || !confirm(`Delete capability "${c.name}"?`)) return;
    this.api.deleteCapability(c.id).subscribe(() => { this.cancel(); this.load(); });
  }
}
