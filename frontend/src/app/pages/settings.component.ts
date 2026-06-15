import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { Provider, ProviderRequest } from '../core/models';

@Component({
  selector: 'rg-settings',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="page-head row fade-up">
      <div>
        <h1>Settings</h1>
        <p>Configure your custom <strong>vLLM</strong> provider — base URL, model, context size and sampling.
           The enabled provider powers live flow execution.</p>
      </div>
      <span class="spacer"></span>
      <button class="btn btn-primary" (click)="startNew()">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>
        Add provider
      </button>
    </div>

    <div class="layout fade-up">
      <div class="list">
        @for (p of providers(); track p.id) {
          <button class="prov" [class.on]="editing()?.id === p.id" (click)="edit(p)">
            <span class="step-icon" [class.live]="p.enabled"></span>
            <span class="prov-main">
              <strong>{{ p.name }}</strong>
              <small class="faint mono">{{ p.model }} · ctx {{ p.contextSize | number }}</small>
            </span>
            <span class="badge" [class.completed]="p.enabled" [class.draft]="!p.enabled">
              <span class="dot"></span>{{ p.enabled ? 'enabled' : 'disabled' }}
            </span>
          </button>
        }
        @if (providers().length === 0) { <div class="empty">No providers yet.</div> }
      </div>

      <div class="card editor">
        @if (editing() || creating()) {
          <div class="row">
            <h3>{{ creating() ? 'New vLLM provider' : 'Edit provider' }}</h3>
            <span class="spacer"></span>
            <span class="badge tool">vLLM · OpenAI-compatible</span>
          </div>

          <div class="grid g2" style="margin-top:16px">
            <label class="field"><span>Name</span>
              <input class="input" [(ngModel)]="form.name" placeholder="e.g. Local vLLM" /></label>
            <label class="field"><span>Model</span>
              <input class="input mono" [(ngModel)]="form.model" placeholder="e.g. meta-llama/Llama-3.1-8B-Instruct" /></label>
          </div>
          <label class="field" style="margin-top:14px"><span>Base URL (OpenAI-compatible, ends with /v1)</span>
            <input class="input mono" [(ngModel)]="form.baseUrl" placeholder="http://host.docker.internal:8000/v1" /></label>
          <label class="field" style="margin-top:14px"><span>API key (optional — leave blank to keep current)</span>
            <input class="input mono" type="password" [(ngModel)]="form.apiKey" placeholder="••••••" /></label>

          <div class="grid g3" style="margin-top:14px">
            <label class="field"><span>Context size</span>
              <input class="input" type="number" [(ngModel)]="form.contextSize" /></label>
            <label class="field"><span>Max tokens</span>
              <input class="input" type="number" [(ngModel)]="form.maxTokens" /></label>
            <label class="field"><span>Temperature</span>
              <input class="input" type="number" step="0.05" min="0" max="2" [(ngModel)]="form.temperature" /></label>
          </div>

          <label class="toggle" style="margin-top:16px">
            <input type="checkbox" [(ngModel)]="form.enabled" />
            <span>Enabled — use this provider for live runs (only one is used at a time)</span>
          </label>

          @if (testMsg()) {
            <div class="test-result" [class.ok]="testOk()" [class.err]="!testOk()">{{ testMsg() }}</div>
          }

          <div class="row" style="margin-top:18px">
            @if (!creating()) { <button class="btn btn-danger" (click)="remove()">Delete</button> }
            <button class="btn btn-ghost" [disabled]="creating() || testing()" (click)="test()">
              @if (testing()) { <span class="spinner"></span> } Test connection
            </button>
            <span class="spacer"></span>
            <button class="btn btn-ghost" (click)="cancel()">Cancel</button>
            <button class="btn btn-primary" [disabled]="!valid() || saving()" (click)="submit()">
              @if (saving()) { <span class="spinner"></span> } Save
            </button>
          </div>
          @if (creating()) { <small class="faint" style="display:block;margin-top:10px">Save the provider before testing the connection.</small> }
        } @else {
          <div class="placeholder">
            <svg viewBox="0 0 24 24" width="46" height="46" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" style="opacity:.4"><rect x="3" y="4" width="18" height="6" rx="2"/><rect x="3" y="14" width="18" height="6" rx="2"/><path d="M7 7h.01M7 17h.01"/></svg>
            <p class="muted">Select a provider to configure, or add a new vLLM endpoint.</p>
          </div>
        }
      </div>
    </div>

    <div class="card section deploy fade-up" style="margin-top:20px">
      <h3>Deployment</h3>
      <div class="deploy-grid">
        <div><span class="faint">Architecture</span><strong>Single Docker image</strong></div>
        <div><span class="faint">Backend</span><strong>Spring Boot · Java 21</strong></div>
        <div><span class="faint">Frontend</span><strong>Angular 18 (served static)</strong></div>
        <div><span class="faint">Database</span><strong>PostgreSQL 16</strong></div>
        <div><span class="faint">LLM</span><strong>Custom vLLM (OpenAI API)</strong></div>
        <div><span class="faint">MCP</span><strong>External + builtin (REST)</strong></div>
      </div>
    </div>
  `,
  styles: [`
    .layout { display: grid; grid-template-columns: 1fr 1.3fr; gap: 20px; align-items: start; }
    .list { display: flex; flex-direction: column; gap: 8px; }
    .prov { display: flex; align-items: center; gap: 13px; text-align: left; width: 100%; padding: 14px 15px; border-radius: 13px; color: var(--text); background: var(--surface); border: 1px solid var(--border); transition: all 0.15s ease; }
    .prov:hover { border-color: var(--border-strong); }
    .prov.on { border-color: var(--violet); box-shadow: var(--shadow-glow); }
    .step-icon { width: 11px; height: 11px; flex: none; border-radius: 50%; background: var(--text-faint); }
    .step-icon.live { background: var(--teal); box-shadow: 0 0 10px var(--teal); }
    .prov-main { display: flex; flex-direction: column; gap: 3px; flex: 1; }
    .prov-main strong { font-size: 14.5px; }
    .editor { padding: 24px; position: sticky; top: 24px; }
    .editor h3 { font-size: 17px; }
    .g2 { grid-template-columns: 1fr 1fr; }
    .g3 { grid-template-columns: 1fr 1fr 1fr; }
    .toggle { display: flex; align-items: center; gap: 10px; font-size: 13.5px; color: var(--text-dim); cursor: pointer; }
    .toggle input { width: 18px; height: 18px; accent-color: var(--violet); }
    .test-result { margin-top: 16px; padding: 11px 14px; border-radius: 10px; font-size: 13px; }
    .test-result.ok { background: rgba(35,213,171,0.1); border: 1px solid rgba(35,213,171,0.4); color: var(--teal); }
    .test-result.err { background: rgba(255,93,108,0.1); border: 1px solid rgba(255,93,108,0.4); color: var(--red); }
    .placeholder { display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 60px 20px; text-align: center; }
    .section { padding: 24px; } .section h3 { font-size: 16px; }
    .deploy-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-top: 14px; }
    .deploy-grid > div { display: flex; flex-direction: column; gap: 4px; padding: 14px; border-radius: 12px; background: rgba(8,10,20,0.4); border: 1px solid var(--border); }
    .deploy-grid .faint { font-size: 12px; }
    @media (max-width: 980px) { .layout { grid-template-columns: 1fr; } .editor { position: static; } .g3 { grid-template-columns: 1fr; } .deploy-grid { grid-template-columns: 1fr 1fr; } }
  `],
})
export class SettingsComponent implements OnInit {
  private api = inject(ApiService);

  providers = signal<Provider[]>([]);
  editing = signal<Provider | null>(null);
  creating = signal(false);
  saving = signal(false);
  testing = signal(false);
  testMsg = signal('');
  testOk = signal(false);

  form: ProviderRequest = this.blank();

  ngOnInit(): void { this.load(); }

  load(): void { this.api.listProviders().subscribe((p) => this.providers.set(p)); }

  blank(): ProviderRequest {
    return { name: '', type: 'vllm', baseUrl: 'http://host.docker.internal:8000/v1', apiKey: '',
      model: '', contextSize: 8192, maxTokens: 1024, temperature: 0.2, enabled: false };
  }

  valid(): boolean { return !!this.form.name.trim() && !!this.form.baseUrl.trim() && !!this.form.model.trim(); }

  startNew(): void { this.editing.set(null); this.creating.set(true); this.testMsg.set(''); this.form = this.blank(); }

  edit(p: Provider): void {
    this.creating.set(false); this.editing.set(p); this.testMsg.set('');
    this.form = { name: p.name, type: p.type, baseUrl: p.baseUrl, apiKey: '', model: p.model,
      contextSize: p.contextSize, maxTokens: p.maxTokens, temperature: p.temperature, enabled: p.enabled };
  }

  cancel(): void { this.editing.set(null); this.creating.set(false); this.testMsg.set(''); }

  submit(): void {
    if (!this.valid()) return;
    this.saving.set(true);
    const done = () => { this.saving.set(false); this.cancel(); this.load(); };
    if (this.creating()) {
      this.api.createProvider(this.form).subscribe({ next: done, error: () => this.saving.set(false) });
    } else {
      this.api.updateProvider(this.editing()!.id, this.form).subscribe({ next: done, error: () => this.saving.set(false) });
    }
  }

  test(): void {
    const p = this.editing();
    if (!p) return;
    this.testing.set(true); this.testMsg.set('');
    this.api.testProvider(p.id).subscribe({
      next: (r) => { this.testing.set(false); this.testOk.set(r.ok); this.testMsg.set(r.message); },
      error: (e) => { this.testing.set(false); this.testOk.set(false); this.testMsg.set(e?.error?.message || 'Connection failed'); },
    });
  }

  remove(): void {
    const p = this.editing();
    if (!p || !confirm(`Delete provider "${p.name}"?`)) return;
    this.api.deleteProvider(p.id).subscribe(() => { this.cancel(); this.load(); });
  }
}
