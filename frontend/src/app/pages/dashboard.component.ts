import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { Capability, Flow, Run } from '../core/models';

@Component({
  selector: 'rg-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, DatePipe],
  template: `
    <div class="page-head fade-up">
      <h1>Welcome to <span class="gradient-text">Research Gateway</span></h1>
      <p>Compose agentic research flows from reusable skills, tools, functions and MCP servers — then
         test and observe every run, all from a single configurable surface.</p>
    </div>

    <div class="grid stats fade-up">
      <div class="card stat">
        <span class="stat-label">Flows</span>
        <span class="stat-value">{{ flows().length }}</span>
        <span class="stat-sub">{{ publishedCount() }} published</span>
        <div class="stat-glow" style="--c: var(--violet)"></div>
      </div>
      <div class="card stat">
        <span class="stat-label">Capabilities</span>
        <span class="stat-value">{{ capabilities().length }}</span>
        <span class="stat-sub">{{ countType('skill') }} skills · {{ countType('tool') }} tools · {{ countType('mcp') }} mcp</span>
        <div class="stat-glow" style="--c: var(--teal)"></div>
      </div>
      <div class="card stat">
        <span class="stat-label">Total runs</span>
        <span class="stat-value">{{ stats()?.totalRuns ?? 0 }}</span>
        <span class="stat-sub">{{ stats()?.completed ?? 0 }} completed · {{ stats()?.failed ?? 0 }} failed</span>
        <div class="stat-glow" style="--c: var(--pink)"></div>
      </div>
      <div class="card stat">
        <span class="stat-label">Spend (runs shown)</span>
        <span class="stat-value">\${{ totalCost().toFixed(4) }}</span>
        <span class="stat-sub">{{ totalTokens() | number }} tokens</span>
        <div class="stat-glow" style="--c: var(--amber)"></div>
      </div>
    </div>

    <div class="grid two fade-up" style="margin-top: 22px">
      <div class="card panel">
        <div class="panel-head">
          <h3>Active flows</h3>
          <a routerLink="/flows" class="btn btn-ghost btn-sm">Manage</a>
        </div>
        @if (flows().length === 0) {
          <div class="empty">No flows yet.</div>
        } @else {
          @for (f of flows().slice(0, 5); track f.id) {
            <a [routerLink]="['/flows', f.id]" class="list-row">
              <div class="lr-main">
                <strong>{{ f.name }}</strong>
                <code class="faint">/flows/{{ f.slug }}/run</code>
              </div>
              <div class="row">
                <span class="badge {{ f.status }}"><span class="dot"></span>{{ f.status }}</span>
                <span class="faint mono">v{{ f.version }}</span>
              </div>
            </a>
          }
        }
      </div>

      <div class="card panel">
        <div class="panel-head">
          <h3>Recent runs</h3>
          <a routerLink="/runs" class="btn btn-ghost btn-sm">View all</a>
        </div>
        @if (runs().length === 0) {
          <div class="empty">No runs yet — try the <a routerLink="/playground" class="gradient-text">Playground</a>.</div>
        } @else {
          @for (r of runs().slice(0, 6); track r.id) {
            <div class="list-row">
              <div class="lr-main">
                <strong>{{ r.flowSlug }}</strong>
                <span class="faint">{{ r.startedAt | date: 'MMM d, HH:mm' }}</span>
              </div>
              <div class="row">
                <span class="faint mono">{{ r.tokens | number }} tk</span>
                <span class="badge {{ r.status }}"><span class="dot"></span>{{ r.status }}</span>
              </div>
            </div>
          }
        }
      </div>
    </div>
  `,
  styles: [`
    .stats { grid-template-columns: repeat(4, 1fr); }
    .stat { position: relative; padding: 22px; overflow: hidden; }
    .stat-label { color: var(--text-dim); font-size: 13px; font-weight: 600; }
    .stat-value { display: block; font-size: 38px; font-weight: 800; margin: 6px 0 2px; letter-spacing: -0.03em; }
    .stat-sub { color: var(--text-faint); font-size: 12.5px; }
    .stat-glow {
      position: absolute; right: -40px; top: -40px; width: 130px; height: 130px; border-radius: 50%;
      background: radial-gradient(circle, var(--c), transparent 70%); opacity: 0.25; filter: blur(8px);
    }
    .two { grid-template-columns: 1fr 1fr; }
    .panel { padding: 8px 8px 14px; }
    .panel-head { display: flex; align-items: center; justify-content: space-between; padding: 16px 16px 10px; }
    .panel-head h3 { font-size: 16px; }
    .list-row {
      display: flex; align-items: center; justify-content: space-between;
      padding: 13px 16px; border-radius: 12px; transition: background 0.15s ease;
    }
    .list-row:hover { background: var(--surface-hover); }
    .lr-main { display: flex; flex-direction: column; gap: 3px; }
    .lr-main strong { font-size: 14.5px; }
    .lr-main code, .lr-main .faint { font-size: 12px; }
    @media (max-width: 980px) { .stats { grid-template-columns: 1fr 1fr; } .two { grid-template-columns: 1fr; } }
  `],
})
export class DashboardComponent implements OnInit {
  private api = inject(ApiService);

  flows = signal<Flow[]>([]);
  capabilities = signal<Capability[]>([]);
  runs = signal<Run[]>([]);
  stats = signal<{ totalRuns: number; completed: number; failed: number } | null>(null);

  ngOnInit(): void {
    this.api.listFlows().subscribe((f) => this.flows.set(f));
    this.api.listCapabilities().subscribe((c) => this.capabilities.set(c));
    this.api.listRuns().subscribe((r) => this.runs.set(r));
    this.api.runStats().subscribe((s) => this.stats.set(s));
  }

  publishedCount = () => this.flows().filter((f) => f.status === 'published').length;
  countType = (t: string) => this.capabilities().filter((c) => c.type === t).length;
  totalCost = () => this.runs().reduce((s, r) => s + (r.costUsd || 0), 0);
  totalTokens = () => this.runs().reduce((s, r) => s + (r.tokens || 0), 0);
}
