import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { Capability, CapabilityHelp, CapabilityType } from '../core/models';

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

          @if (help(); as h) {
            <div class="help-box">
              <button type="button" class="help-head" (click)="showHelp.set(!showHelp())">
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01"/></svg>
                <span>How to create a {{ h.title }}</span>
                <span class="spacer"></span>
                <span class="chev" [class.open]="showHelp()">▾</span>
              </button>
              @if (showHelp()) {
                <div class="help-body">
                  <p class="help-summary">{{ h.summary }}</p>
                  <p class="help-how">{{ h.howTo }}</p>
                  @if (h.fields.length) {
                    <div class="help-fields">
                      @for (f of h.fields; track f.key) {
                        <div class="help-field">
                          <code>{{ f.key }}</code>
                          <span class="req" [class.on]="f.required">{{ f.required ? 'required' : 'optional' }}</span>
                          <span class="fd">{{ f.description }}</span>
                        </div>
                      }
                    </div>
                  }
                  <div class="row help-ex-head">
                    <strong>Example spec</strong>
                    <span class="spacer"></span>
                    <button type="button" class="btn btn-ghost btn-sm" (click)="useExample()">Use example</button>
                  </div>
                  <pre class="code help-code">{{ exampleText() }}</pre>
                  @if (h.tips.length) {
                    <ul class="help-tips">
                      @for (t of h.tips; track t) { <li>{{ t }}</li> }
                    </ul>
                  }
                </div>
              }
            </div>
          }

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
          } @else if (form.type === 'function') {
            <label class="field" style="margin-top:14px"><span>Language</span>
              <select class="select" [(ngModel)]="form.language">
                <option value="javascript">JavaScript</option>
                <option value="typescript">TypeScript</option>
              </select></label>
            <label class="field" style="margin-top:14px">
              <span>Code — define <code class="mono">function handler(args)</code> returning a JSON value</span>
              <textarea class="textarea mono" rows="12" [(ngModel)]="codeText"
                        placeholder="function handler(args) { return { ok: true }; }"></textarea>
            </label>
            @if (!codeText.trim()) { <small class="err">Function code is required.</small> }
            <label class="field" style="margin-top:14px"><span>Input schema (JSON)</span>
              <textarea class="textarea" rows="7" [(ngModel)]="schemaText" [class.invalid]="!schemaValid()"></textarea></label>
            @if (!schemaValid()) { <small class="err">Invalid JSON.</small> }
          } @else {
            <label class="field" style="margin-top:14px">
              <span>Spec (JSON) — {{ form.type === 'skill' ? 'instructions / resources' : 'definition' }}</span>
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
    .help-box { margin-top: 14px; border: 1px solid var(--border); border-radius: 12px; background: var(--grad-soft); overflow: hidden; }
    .help-head { display: flex; align-items: center; gap: 9px; width: 100%; text-align: left; padding: 11px 14px; color: var(--text); font-weight: 600; font-size: 13.5px; background: transparent; }
    .help-head svg { color: var(--violet); flex: none; }
    .chev { transition: transform 0.18s ease; color: var(--text-dim); }
    .chev.open { transform: rotate(180deg); }
    .help-body { padding: 4px 16px 16px; border-top: 1px solid var(--border); }
    .help-summary { font-size: 13px; color: var(--text); margin-top: 12px; }
    .help-how { font-size: 12.5px; color: var(--text-dim); margin-top: 8px; line-height: 1.55; }
    .help-fields { display: flex; flex-direction: column; gap: 6px; margin-top: 12px; }
    .help-field { display: grid; grid-template-columns: minmax(90px, auto) auto 1fr; gap: 10px; align-items: baseline; font-size: 12.5px; }
    .help-field code { color: var(--teal); font-family: var(--mono); font-size: 12px; }
    .help-field .req { font-size: 10.5px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em; color: var(--text-dim); }
    .help-field .req.on { color: var(--pink); }
    .help-field .fd { color: var(--text-dim); }
    .help-ex-head { margin-top: 16px; align-items: center; }
    .help-ex-head strong { font-size: 12.5px; }
    .help-code { max-height: 200px; }
    .help-tips { margin: 14px 0 0; padding-left: 18px; display: flex; flex-direction: column; gap: 5px; }
    .help-tips li { font-size: 12.5px; color: var(--text-dim); line-height: 1.5; }
    @media (max-width: 980px) { .layout { grid-template-columns: 1fr; } .editor { position: static; } .g2 { grid-template-columns: 1fr; } }
  `],
})
export class CapabilitiesComponent implements OnInit {
  private api = inject(ApiService);
  types: CapabilityType[] = ['skill', 'tool', 'function', 'mcp'];

  items = signal<Capability[]>([]);
  helps = signal<CapabilityHelp[]>([]);
  showHelp = signal(true);
  filter = signal<string>('');
  editing = signal<Capability | null>(null);
  creating = signal(false);
  saving = signal(false);

  form: { type: CapabilityType; name: string; description: string; kind: string; baseUrl: string; authHeader: string; language: string } =
    { type: 'skill', name: '', description: '', kind: 'builtin', baseUrl: '', authHeader: '', language: 'javascript' };
  specText = '{}';
  operationsText = '[]';
  codeText = '';
  schemaText = '{}';
  testing = signal(false);
  testResult = signal('');
  testOperation = '';
  testArgsText = '{}';

  specValid = computed(() => { try { JSON.parse(this.specText); return true; } catch { return false; } });
  opsValid = computed(() => { try { return Array.isArray(JSON.parse(this.operationsText)); } catch { return false; } });
  schemaValid = computed(() => { try { JSON.parse(this.schemaText); return true; } catch { return false; } });
  visible = computed(() => {
    const f = this.filter();
    return f ? this.items().filter((c) => c.type === f) : this.items();
  });

  ngOnInit(): void {
    this.load();
    this.api.capabilityHelp().subscribe((h) => this.helps.set(h));
  }

  load(): void { this.api.listCapabilities().subscribe((c) => this.items.set(c)); }
  count = (t: string) => this.items().filter((c) => c.type === t).length;
  mcpKind = (c: Capability) => (c.spec?.['kind'] as string) || 'external';

  // Method (not computed) so it tracks form.type, which ngModel mutates outside signals.
  help(): CapabilityHelp | null {
    return this.helps().find((h) => h.type === this.form.type) ?? null;
  }
  exampleText(): string {
    const h = this.help();
    return h ? JSON.stringify(h.example, null, 2) : '';
  }

  /** Populate the spec editor (and MCP fields) from the type's example. */
  useExample(): void {
    const ex = this.help()?.example;
    if (!ex) return;
    if (this.form.type === 'mcp') {
      const kind = (ex['kind'] as string) || 'builtin';
      this.form.kind = kind;
      if (kind === 'builtin') {
        this.form.baseUrl = (ex['base_url'] as string) ?? '';
        this.form.authHeader = (ex['auth_header'] as string) ?? '';
        this.operationsText = JSON.stringify(ex['operations'] ?? [], null, 2);
      }
    } else if (this.form.type === 'function') {
      this.form.language = (ex['language'] as string) || 'javascript';
      this.codeText = (ex['code'] as string) ?? '';
      this.schemaText = JSON.stringify(ex['input_schema'] ?? {}, null, 2);
    }
    this.specText = JSON.stringify(ex, null, 2);
  }

  canSave(): boolean {
    if (!this.form.name.trim()) return false;
    if (this.form.type === 'mcp' && this.form.kind === 'builtin') return this.opsValid();
    if (this.form.type === 'function') return !!this.codeText.trim() && this.schemaValid();
    return this.specValid();
  }

  startNew(): void {
    this.editing.set(null); this.creating.set(true);
    this.form = { type: 'skill', name: '', description: '', kind: 'builtin', baseUrl: '', authHeader: '', language: 'javascript' };
    this.specText = '{}';
    this.codeText = 'function handler(args) {\n  return { ok: true };\n}';
    this.schemaText = '{\n  "type": "object",\n  "properties": {},\n  "required": []\n}';
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
      language: (spec['language'] as string) || 'javascript',
    };
    this.specText = JSON.stringify(spec, null, 2);
    this.operationsText = JSON.stringify(spec['operations'] ?? [], null, 2);
    this.codeText = (spec['code'] as string) ?? '';
    this.schemaText = JSON.stringify(spec['input_schema'] ?? {}, null, 2);
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
    if (this.form.type === 'function') {
      return {
        language: this.form.language,
        code: this.codeText,
        input_schema: JSON.parse(this.schemaText),
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
