import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'rg-settings',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="page-head fade-up">
      <h1>Settings</h1>
      <p>Providers, connectors and access. Secrets are referenced from a vault — never stored in flow config.</p>
    </div>

    <div class="grid two fade-up">
      <div class="card section">
        <h3>LLM providers</h3>
        <p class="muted small">Configure model routing — a strong model for planning &amp; synthesis, a fast one for extraction.</p>
        @for (p of providers; track p.name) {
          <div class="prov-row">
            <div class="prov-main">
              <strong>{{ p.name }}</strong>
              <small class="faint mono">{{ p.models }}</small>
            </div>
            <span class="badge" [class.completed]="p.connected" [class.draft]="!p.connected">
              <span class="dot"></span>{{ p.connected ? 'connected' : 'not configured' }}
            </span>
          </div>
        }
      </div>

      <div class="card section">
        <h3>MCP servers</h3>
        <p class="muted small">The gateway acts as an MCP client, exposing each server's tools to flows on least-privilege.</p>
        @for (m of mcps; track m.name) {
          <div class="prov-row">
            <div class="prov-main">
              <strong>{{ m.name }}</strong>
              <small class="faint mono">{{ m.transport }}</small>
            </div>
            <span class="badge {{ m.status }}"><span class="dot"></span>{{ m.status }}</span>
          </div>
        }
      </div>
    </div>

    <div class="card section fade-up" style="margin-top:20px">
      <h3>Consumer API keys</h3>
      <p class="muted small">Issue scoped keys with per-flow rate limits and token budgets for external consumers.</p>
      <div class="key-row">
        <code class="mono key">rg_live_••••••••••••••••••••••4f2a</code>
        <span class="badge completed"><span class="dot"></span>active</span>
        <span class="faint small">scopes: research:read · 30 rpm</span>
      </div>
    </div>

    <div class="card section deploy fade-up" style="margin-top:20px">
      <h3>Deployment</h3>
      <div class="deploy-grid">
        <div><span class="faint">Architecture</span><strong>Single Docker image</strong></div>
        <div><span class="faint">Backend</span><strong>Spring Boot · Java 21</strong></div>
        <div><span class="faint">Frontend</span><strong>Angular 18 (served static)</strong></div>
        <div><span class="faint">Database</span><strong>PostgreSQL 16</strong></div>
        <div><span class="faint">Orchestration</span><strong>Docker Compose</strong></div>
        <div><span class="faint">Migrations</span><strong>Flyway</strong></div>
      </div>
    </div>
  `,
  styles: [`
    .two { grid-template-columns: 1fr 1fr; }
    .section { padding: 24px; }
    .section h3 { font-size: 16px; }
    .small { font-size: 13px; margin: 6px 0 16px; }
    .prov-row { display: flex; align-items: center; justify-content: space-between; padding: 13px 0; border-top: 1px solid var(--border); }
    .prov-main { display: flex; flex-direction: column; gap: 3px; }
    .prov-main strong { font-size: 14.5px; }
    .key-row { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-top: 6px; }
    .key { background: rgba(8,10,20,0.6); border: 1px solid var(--border); padding: 10px 14px; border-radius: 10px; font-size: 13px; }
    .deploy-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-top: 10px; }
    .deploy-grid > div { display: flex; flex-direction: column; gap: 4px; padding: 14px; border-radius: 12px; background: rgba(8,10,20,0.4); border: 1px solid var(--border); }
    .deploy-grid .faint { font-size: 12px; }
    @media (max-width: 880px) { .two { grid-template-columns: 1fr; } .deploy-grid { grid-template-columns: 1fr 1fr; } }
  `],
})
export class SettingsComponent {
  providers = [
    { name: 'Anthropic', models: 'claude-opus-4-8 · claude-haiku-4-5', connected: false },
    { name: 'OpenAI', models: 'gpt-4o · gpt-4o-mini', connected: false },
    { name: 'Local (vLLM / Ollama)', models: 'self-hosted', connected: false },
  ];
  mcps = [
    { name: 'Internal Knowledge Base', transport: 'stdio', status: 'draft' },
    { name: 'CRM Connector', transport: 'http', status: 'draft' },
  ];
}
