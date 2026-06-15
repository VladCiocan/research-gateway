import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../core/api.service';
import { Flow, Run, RunStep } from '../core/models';

@Component({
  selector: 'rg-playground',
  standalone: true,
  imports: [CommonModule, FormsModule, DecimalPipe],
  template: `
    <div class="page-head fade-up">
      <h1>Playground</h1>
      <p>Run any flow with real input and watch the orchestrator's trace — subagents, triage and synthesis — live.</p>
    </div>

    <div class="layout fade-up">
      <div class="card panel">
        <label class="field"><span>Flow</span>
          <select class="select" [(ngModel)]="selectedSlug">
            @for (f of flows(); track f.id) {
              <option [value]="f.slug">{{ f.name }} ({{ f.status }})</option>
            }
          </select></label>

        <label class="field" style="margin-top:16px"><span>Input (JSON)</span>
          <textarea class="textarea" rows="9" [(ngModel)]="inputText" [class.invalid]="!inputValid()"></textarea></label>
        @if (!inputValid()) { <small class="err">Invalid JSON.</small> }

        <button class="btn btn-primary run-btn" [disabled]="!canRun()" (click)="execute()">
          @if (running()) { <span class="spinner"></span> Running… }
          @else {
            <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
            Run flow
          }
        </button>

        @if (run(); as r) {
          <div class="metrics">
            <div class="metric"><span class="faint">Status</span><span class="badge {{ r.status }}"><span class="dot"></span>{{ r.status }}</span></div>
            <div class="metric"><span class="faint">Tokens</span><strong class="mono">{{ r.tokens | number }}</strong></div>
            <div class="metric"><span class="faint">Cost</span><strong class="mono">\${{ r.costUsd.toFixed(4) }}</strong></div>
            <div class="metric"><span class="faint">Steps</span><strong class="mono">{{ r.steps.length }}</strong></div>
          </div>
        }
      </div>

      <div class="card panel trace-panel">
        <h3>Live trace</h3>
        @if (!run() && !running()) {
          <div class="empty">Run a flow to see its agentic trace appear here.</div>
        }
        <div class="trace">
          @for (s of visibleSteps(); track s.id) {
            <div class="step fade-up" [attr.data-type]="s.type">
              <div class="step-icon {{ s.type }}"></div>
              <div class="step-body">
                <div class="row">
                  <strong>{{ s.title }}</strong>
                  <span class="spacer"></span>
                  <span class="badge">{{ s.type }}</span>
                  <span class="faint mono">{{ s.tokens | number }} tk</span>
                </div>
                @if (s.detail) { <p class="muted detail">{{ s.detail }}</p> }
                @if (hasSources(s)) {
                  <div class="sources">
                    @for (src of sources(s); track $index) {
                      <a class="src" [href]="src['url']" target="_blank" rel="noopener">
                        <span class="rel mono">{{ relevance(src) }}</span> {{ src['title'] }}
                      </a>
                    }
                  </div>
                }
              </div>
            </div>
          }
          @if (running() && visibleSteps().length < (run()?.steps?.length ?? 99)) {
            <div class="step pending"><div class="step-icon"></div><div class="step-body muted">Thinking…</div></div>
          }
        </div>

        @if (output()) {
          <div class="output fade-up">
            <h4>Synthesis</h4>
            <p class="summary">{{ output()!['summary'] }}</p>
            @if (outSources().length) {
              <h4 style="margin-top:14px">Sources</h4>
              <ol class="out-sources">
                @for (src of outSources(); track $index) {
                  <li><a [href]="src['url']" target="_blank" rel="noopener">{{ src['title'] }}</a></li>
                }
              </ol>
            }
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .layout { display: grid; grid-template-columns: 380px 1fr; gap: 20px; align-items: start; }
    .panel { padding: 22px; }
    .run-btn { width: 100%; justify-content: center; margin-top: 18px; padding: 13px; }
    .err { color: var(--red); font-size: 12.5px; }
    .metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 20px; }
    .metric { display: flex; flex-direction: column; gap: 5px; padding: 12px 14px; border-radius: 12px; background: rgba(8,10,20,0.4); border: 1px solid var(--border); }
    .metric .faint { font-size: 11.5px; }
    .metric strong { font-size: 17px; }
    .trace-panel h3 { font-size: 16px; margin-bottom: 16px; }
    .trace { display: flex; flex-direction: column; gap: 0; position: relative; }
    .step { display: flex; gap: 16px; padding-bottom: 22px; position: relative; }
    .step::before { content: ''; position: absolute; left: 11px; top: 24px; bottom: 0; width: 2px; background: var(--border); }
    .step:last-child::before { display: none; }
    .step-icon { width: 24px; height: 24px; flex: none; border-radius: 50%; border: 2px solid var(--border-strong); background: var(--surface-solid); z-index: 1; margin-top: 1px; }
    .step-icon.plan { border-color: var(--violet); background: var(--violet); }
    .step-icon.subagent { border-color: var(--teal); background: var(--teal); }
    .step-icon.synthesis { border-color: var(--pink); background: var(--pink); }
    .step-icon.guardrail { border-color: var(--amber); background: var(--amber); }
    .step-icon.tool_call { border-color: var(--blue); background: var(--blue); }
    .step-icon.skill { border-color: var(--violet-soft); background: var(--violet-soft); }
    .step-body { flex: 1; padding-top: 1px; }
    .step-body strong { font-size: 14.5px; }
    .detail { font-size: 13px; margin-top: 4px; }
    .sources { display: flex; flex-direction: column; gap: 5px; margin-top: 10px; }
    .src { display: flex; gap: 9px; align-items: center; font-size: 12.5px; color: var(--text-dim); padding: 6px 10px; border-radius: 8px; background: rgba(8,10,20,0.4); border: 1px solid var(--border); }
    .src:hover { color: var(--text); border-color: var(--border-strong); }
    .rel { color: var(--teal); font-size: 11.5px; }
    .pending .step-icon { animation: pulse 1s ease-in-out infinite; }
    @keyframes pulse { 0%,100% { opacity: .4; } 50% { opacity: 1; } }
    .output { margin-top: 14px; padding: 20px; border-radius: 14px; background: var(--grad-soft); border: 1px solid var(--border-strong); }
    .output h4 { font-size: 13px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-dim); }
    .summary { margin-top: 8px; line-height: 1.6; font-size: 14.5px; white-space: pre-wrap; }
    .out-sources { margin: 8px 0 0; padding-left: 20px; }
    .out-sources li { font-size: 13.5px; margin-bottom: 4px; color: var(--text-dim); }
    .out-sources a:hover { color: var(--text); }
    .textarea.invalid { border-color: var(--red); }
    @media (max-width: 980px) { .layout { grid-template-columns: 1fr; } }
  `],
})
export class PlaygroundComponent implements OnInit {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);

  flows = signal<Flow[]>([]);
  selectedSlug = '';
  inputText = '{\n  "query": "AI developer tools market"\n}';
  running = signal(false);
  run = signal<Run | null>(null);
  revealed = signal(0);

  inputValid = computed(() => { try { JSON.parse(this.inputText); return true; } catch { return false; } });
  canRun = () => !!this.selectedSlug && this.inputValid() && !this.running();
  visibleSteps = computed(() => (this.run()?.steps ?? []).slice(0, this.revealed()));
  output = computed(() => {
    const r = this.run();
    return r && r.status === 'completed' && this.revealed() >= r.steps.length ? r.output : null;
  });
  outSources = computed(() => (this.output()?.['sources'] as Record<string, unknown>[]) ?? []);

  ngOnInit(): void {
    this.api.listFlows().subscribe((f) => {
      this.flows.set(f);
      const q = this.route.snapshot.queryParamMap.get('flow');
      this.selectedSlug = q && f.some((x) => x.slug === q) ? q : (f[0]?.slug ?? '');
    });
  }

  execute(): void {
    if (!this.canRun()) return;
    this.running.set(true);
    this.run.set(null);
    this.revealed.set(0);
    this.api.runFlow(this.selectedSlug, JSON.parse(this.inputText)).subscribe({
      next: (r) => { this.run.set(r); this.reveal(r); },
      error: () => this.running.set(false),
    });
  }

  private reveal(r: Run): void {
    const total = r.steps.length;
    const tick = () => {
      const n = this.revealed() + 1;
      this.revealed.set(n);
      if (n < total) { setTimeout(tick, 420); }
      else { this.running.set(false); }
    };
    if (total > 0) { setTimeout(tick, 300); } else { this.running.set(false); }
  }

  hasSources = (s: RunStep) => Array.isArray(s.payload?.['sources']);
  sources = (s: RunStep) => (s.payload['sources'] as Record<string, unknown>[]) ?? [];
  relevance = (src: Record<string, unknown>) => {
    const r = src['relevance'];
    return typeof r === 'number' ? r.toFixed(2) : '';
  };
}
