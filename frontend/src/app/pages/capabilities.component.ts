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
              <strong>{{ c.name }}
                @if (c.type === 'mcp') { <em class="kind">{{ mcpKind(c) }}</em> }
              </strong>
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
          <label class="field" style="margin-top:14px"><span>Description (model-visible)</span>
            <input class="input" [(ngModel)]="form.description" placeholder="Short description" /></label>

          @if (form.type === 'mcp') {
            <label class="field" style="margin-top:14px"><span>MCP kind</span>
              <select class="select" [(ngModel)]="form.kind">
                <option value="builtin">builtin — REST integration configured here</option>
                <option value="external">external — connect to an MCP server</option>
              </select></label>

            @if (form.type === 'mcp' && form.kind === 'builtin') {
              <div class="grid g2" style="margin-top:14px">
                <label class="field"><span>Base URL</span>
                  <input class="input mono" [(ngModel)]="form.baseUrl" placeholder="https://api.example.com/v1" /></label>
                <label class="field"><span>Auth header (optional, "Name: Value")</span>
                  <input class="input mono" [(ngModel)]="form.authHeader" placeholder="Authorization: Bearer ..." /></label>
              </div>
              <label class="field" style="margin-top:14px">
                <span>Operations (JSON) — each becomes a callable tool</span>
                <textarea class="textarea" rows="10" [(ngModel)]="operationsText" [class.invalid]="!opsValid()"></textarea>
              </label>
              @if (!opsValid()) { <small class="err">Invalid JSON array.</small> }
            } @else {
              <label class="field" style="margin-top:14px"><span>Spec (JSON)</span>
                <textarea class="textarea" rows="8" [(ngModel)]="specText" [class.invalid]="!specValid()"></textarea></label>
            }
          } @else {
            <label class="field" style="margin-top:14px">
              <span>Spec (JSON) — {{ form.type === 'skill' ? 'instructions / resources' : form.type === 'function' ? 'input schema (mapped to a backend implementation by slug)' : 'input schema' }}</span>
              <textarea class="textarea" rows="9" [(ngModel)]="specText" [class.invalid]="!specValid()"></textarea>
            </label>
            @if (!specValid()) { <small class="err">Invalid JSON.</small> }
          }

          @if (!creating() && testable()) {
            <div class="test-box">
              <div class="row">
                <strong>Test live</strong>
                <span class="spacer"></span>
                <button class="btn btn-ghost btn-sm" [disabled]="testing()" (click)="runTest()">
                  @if (testing()) { <span class="spinner"></span> } Run test
                </button>
              </div>
              @if (form.type === 'mcp') {
                <label class="field" style="margin-top:10px"><span>Operation</span>
                  <input class="input mono" [(ngModel)]="testOperation" placeholder="operation name" /></label>
              }
              <label class="field" style="margin-top:10px"><span>Arguments (JSON)</span>
                <textarea class="textarea" rows="4" [(ngModel)]="testArgsText"></textarea></label>
              @if (testResult()) { <pre class="code">{{ testResult() }}</pre> }
            </div>
          }

          <div class="row" style="margin-top:18px">
            @if (!creating()) { <button class="btn btn-danger" (click)="remove()">Delete</button> }
            <span class="spacer"></span>
            <button class="btn btn-ghost" (click)="cancel()">Cancel</button>
            <button class="btn btn-primary" [disabled]="!canSave() || saving()" (click)="submit()">
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
    .chip { display: inline-flex; align-items: center; gap: 7px; padding: 7px 13px; border-radius: 999px; font-size: 13px; font-weight: 600; background: var(--surface); border: 1px solid var(--border); color: var(--text-dim); }
    .chip.on { color: var(--text); border-color: var(--violet); background: var(--grad-soft); }
    .layout { display: grid; grid-template-columns: 1fr 1.1fr; gap: 20px; align-items: start; }
    .list { display: flex; flex-direction: column; gap: 8px; }
    .cap { display: flex; align-items: center; gap: 12px; text-align: left; width: 100%; padding: 13px 15px; border-radius: 13px; color: var(--text); background: var(--surface); border: 1px solid var(--border); transition: all 0.15s ease; }
    .cap:hover { border-color: var(--border-strong); transform: translateX(2px); }
    .cap.on { border-color: var(--violet); box-shadow: var(--shadow-glow); }
    .cap-main { display: flex; flex-direction: column; gap: 2px; flex: 1; overflow: hidden; }
    .cap-main strong { font-size: 14.5px; }
    .kind { font-style: normal; font-size: 11px; color: var(--pink); margin-left: 6px; font-weight: 600; }
    .cap-main small { font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .editor { padding: 24px; position: sticky; top: 24px; }
    .editor h3 { font-size: 17px; }
    .g2 { grid-template-columns: 1fr 1fr; }
    .textarea.invalid { border-color: var(--red); }
    .err { color: var(--red); font-size: 12.5px; }
    .placeholder { display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 60px 20px; text-align: center; }
    .test-box { margin-top: 18px; padding: 16px; border-radius: 12px; background: rgba(8,10,20,0.4); border: 1px solid var(--border); }
    .test-box strong { font-size: 13.5px; }
    .code { margin-top: 10px; background: rgba(8,10,20,0.7); border: 1px solid var(--border); border-radius: 10px; padding: 12px; font-family: var(--mono); font-size: 12px; line-height: 1.5; max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
    @media (max-width: 980px) { .layout { grid-template-columns: 1fr; } .editor { position: static; } .g2 { grid-template-columns: 1fr; } }
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

  form: { type: CapabilityType; name: string; description: string; kind: string; baseUrl: string; authHeader: string } =
    { type: 'skill', name: '', description: '', kind: 'builtin', baseUrl: '', authHeader: '' };
  specText = '{}';
  operationsText = '[]';
  testing = signal(false);
  testResult = signal('');
  testOperation = '';
  testArgsText = '{}';

  specValid = computed(() => { try { JSON.parse(this.specText); return true; } catch { return false; } });
  opsValid = computed(() => { try { return Array.isArray(JSON.parse(this.operationsText)); } catch { return false; } });
  visible = computed(() => {
    const f = this.filter();
    return f ? this.items().filter((c) => c.type === f) : this.items();
  });

  ngOnInit(): void { this.load(); }

  load(): void { this.api.listCapabilities().subscribe((c) => this.items.set(c)); }
  count = (t: string) => this.items().filter((c) => c.type === t).length;
  mcpKind = (c: Capability) => (c.spec?.['kind'] as string) || 'external';

  canSave(): boolean {
    if (!this.form.name.trim()) return false;
    if (this.form.type === 'mcp' && this.form.kind === 'builtin') return this.opsValid();
    return this.specValid();
  }

  startNew(): void {
    this.editing.set(null); this.creating.set(true);
    this.form = { type: 'skill', name: '', description: '', kind: 'builtin', baseUrl: '', authHeader: '' };
    this.specText = '{}';
    this.operationsText = '[\n  {\n    "name": "example",\n    "method": "GET",\n    "path": "/resource/{id}",\n    "description": "Fetch a resource",\n    "params": { "id": "string (path)" }\n  }\n]';
  }

  edit(c: Capability): void {
    this.creating.set(false); this.editing.set(c);
    const spec = (c.spec ?? {}) as Record<string, unknown>;
    this.form = {
      type: c.type, name: c.name, description: c.description ?? '',
      kind: (spec['kind'] as string) || (c.type === 'mcp' ? 'external' : 'builtin'),
      baseUrl: (spec['base_url'] as string) ?? '',
      authHeader: (spec['auth_header'] as string) ?? '',
    };
    this.specText = JSON.stringify(spec, null, 2);
    this.operationsText = JSON.stringify(spec['operations'] ?? [], null, 2);
    this.testResult.set(''); this.testArgsText = '{}';
    const ops = spec['operations'] as Array<{ name?: string }> | undefined;
    this.testOperation = Array.isArray(ops) && ops.length ? (ops[0].name ?? '') : '';
  }

  cancel(): void { this.editing.set(null); this.creating.set(false); }

  private buildSpec(): Record<string, unknown> {
    if (this.form.type === 'mcp' && this.form.kind === 'builtin') {
      return {
        kind: 'builtin',
        base_url: this.form.baseUrl,
        auth_header: this.form.authHeader,
        operations: JSON.parse(this.operationsText),
      };
    }
    const base = JSON.parse(this.specText);
    if (this.form.type === 'mcp') base.kind = this.form.kind;
    return base;
  }

  submit(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    const body = {
      type: this.form.type,
      name: this.form.name.trim(),
      description: this.form.description.trim(),
      spec: this.buildSpec(),
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

  testable(): boolean {
    return this.form.type === 'function' || (this.form.type === 'mcp' && this.form.kind === 'builtin');
  }

  runTest(): void {
    const c = this.editing();
    if (!c) return;
    let args: unknown;
    try { args = JSON.parse(this.testArgsText || '{}'); }
    catch { this.testResult.set('Invalid JSON arguments'); return; }
    const body: Record<string, unknown> = this.form.type === 'mcp'
      ? { operation: this.testOperation, args } : { args };
    this.testing.set(true); this.testResult.set('');
    this.api.testCapability(c.id, body).subscribe({
      next: (r) => { this.testing.set(false); this.testResult.set(JSON.stringify(r, null, 2)); },
      error: (e) => { this.testing.set(false); this.testResult.set(JSON.stringify(e?.error ?? { message: 'failed' }, null, 2)); },
    });
  }
}
