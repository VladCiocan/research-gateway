import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { ApiService } from '../core/api.service';
import { Run } from '../core/models';

@Component({
  selector: 'rg-runs',
  standalone: true,
  imports: [CommonModule, DatePipe, DecimalPipe],
  template: `
    <div class="page-head fade-up">
      <h1>Runs &amp; Observability</h1>
      <p>Full audit trail of every execution — drill into the trace, tokens and cost of each run.</p>
    </div>

    <div class="layout fade-up">
      <div class="list">
        @for (r of runs(); track r.id) {
          <button class="run-row" [class.on]="selected()?.id === r.id" (click)="open(r)">
            <span class="step-icon {{ r.status }}"></span>
            <span class="rr-main">
              <strong>{{ r.flowSlug }}</strong>
              <small class="faint">{{ r.startedAt | date: 'MMM d, y · HH:mm:ss' }}</small>
            </span>
            <span class="rr-meta">
              <span class="badge {{ r.status }}">{{ r.status }}</span>
              <small class="faint mono">{{ r.tokens | number }} tk · \${{ r.costUsd.toFixed(4) }}</small>
            </span>
          </button>
        }
        @if (runs().length === 0) { <div class="empty">No runs recorded yet.</div> }
      </div>

      <div class="card detail">
        @if (selected(); as r) {
          <div class="row">
            <div>
              <h3>{{ r.flowSlug }} <span class="faint mono">v{{ r.flowVersion }}</span></h3>
              <small class="faint">{{ r.startedAt | date: 'medium' }}</small>
            </div>
            <span class="spacer"></span>
            <span class="badge {{ r.status }}"><span class="dot"></span>{{ r.status }}</span>
          </div>

          <div class="kv">
            <div><span class="faint">Input</span><pre class="code">{{ pretty(r.input) }}</pre></div>
          </div>

          <h4>Trace ({{ r.steps.length }} steps)</h4>
          <div class="trace">
            @for (s of r.steps; track s.id) {
              <div class="step">
                <div class="step-icon sm {{ s.type }}"></div>
                <div class="step-body">
                  <div class="row"><strong>{{ s.title }}</strong><span class="spacer"></span>
                    <span class="badge">{{ s.type }}</span><span class="faint mono">{{ s.tokens | number }} tk</span></div>
                  @if (s.detail) { <p class="muted detail">{{ s.detail }}</p> }
                </div>
              </div>
            }
          </div>

          @if (r.output) {
            <h4>Output</h4>
            <pre class="code">{{ pretty(r.output) }}</pre>
          }
          @if (r.error) { <div class="error-box">{{ r.error }}</div> }
        } @else {
          <div class="placeholder">
            <svg viewBox="0 0 24 24" width="46" height="46" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" style="opacity:.4"><path d="M3 12a9 9 0 1 0 9-9"/><path d="M3 4v5h5"/><path d="M12 7v5l3 2"/></svg>
            <p class="muted">Select a run to inspect its full trace.</p>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .layout { display: grid; grid-template-columns: 1fr 1.2fr; gap: 20px; align-items: start; }
    .list { display: flex; flex-direction: column; gap: 8px; }
    .run-row { display: flex; align-items: center; gap: 13px; text-align: left; width: 100%; padding: 13px 15px; border-radius: 13px; color: var(--text); background: var(--surface); border: 1px solid var(--border); transition: all 0.15s ease; }
    .run-row:hover { border-color: var(--border-strong); }
    .run-row.on { border-color: var(--violet); box-shadow: var(--shadow-glow); }
    .rr-main { display: flex; flex-direction: column; gap: 2px; flex: 1; }
    .rr-main strong { font-size: 14px; }
    .rr-meta { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
    .step-icon { width: 12px; height: 12px; flex: none; border-radius: 50%; background: var(--text-faint); }
    .step-icon.completed { background: var(--teal); } .step-icon.failed { background: var(--red); }
    .step-icon.running, .step-icon.pending { background: var(--amber); }
    .detail-panel, .detail { padding: 24px; position: sticky; top: 24px; }
    .detail h3 { font-size: 17px; }
    .detail h4 { font-size: 12.5px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-dim); margin: 22px 0 10px; }
    .kv { margin-top: 18px; }
    .code { background: rgba(8,10,20,0.6); border: 1px solid var(--border); border-radius: 10px; padding: 13px; font-family: var(--mono); font-size: 12.5px; line-height: 1.5; overflow-x: auto; white-space: pre-wrap; word-break: break-word; }
    .trace .step { display: flex; gap: 13px; padding-bottom: 14px; position: relative; }
    .trace .step::before { content: ''; position: absolute; left: 5px; top: 14px; bottom: 0; width: 2px; background: var(--border); }
    .trace .step:last-child::before { display: none; }
    .step-icon.sm { width: 12px; height: 12px; margin-top: 3px; z-index: 1; }
    .step-icon.sm.plan { background: var(--violet); } .step-icon.sm.subagent { background: var(--teal); }
    .step-icon.sm.synthesis { background: var(--pink); } .step-icon.sm.guardrail { background: var(--amber); }
    .step-icon.sm.tool_call { background: var(--blue); } .step-icon.sm.skill { background: var(--violet-soft); }
    .step-body strong { font-size: 13.5px; }
    .detail2 { font-size: 12.5px; }
    .detail-box .detail { font-size: 12.5px; }
    .error-box { margin-top: 14px; padding: 13px; border-radius: 10px; background: rgba(255,93,108,0.1); border: 1px solid rgba(255,93,108,0.4); color: var(--red); font-size: 13px; }
    .placeholder { display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 60px 20px; text-align: center; }
    @media (max-width: 980px) { .layout { grid-template-columns: 1fr; } .detail { position: static; } }
  `],
})
export class RunsComponent implements OnInit {
  private api = inject(ApiService);

  runs = signal<Run[]>([]);
  selected = signal<Run | null>(null);

  ngOnInit(): void {
    this.api.listRuns().subscribe((r) => this.runs.set(r));
  }

  open(r: Run): void {
    this.api.getRun(r.id).subscribe((full) => this.selected.set(full));
  }

  pretty(o: unknown): string { return JSON.stringify(o ?? {}, null, 2); }
}
