import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Capability, CapabilityRequest, Flow, FlowRequest, Provider, ProviderRequest, Run,
} from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = '/api';

  // ---- Flows ----
  listFlows(): Observable<Flow[]> { return this.http.get<Flow[]>(`${this.base}/flows`); }
  getFlow(id: string): Observable<Flow> { return this.http.get<Flow>(`${this.base}/flows/${id}`); }
  createFlow(body: FlowRequest): Observable<Flow> { return this.http.post<Flow>(`${this.base}/flows`, body); }
  updateFlow(id: string, body: FlowRequest): Observable<Flow> { return this.http.put<Flow>(`${this.base}/flows/${id}`, body); }
  publishFlow(id: string): Observable<Flow> { return this.http.post<Flow>(`${this.base}/flows/${id}/publish`, {}); }
  deleteFlow(id: string): Observable<void> { return this.http.delete<void>(`${this.base}/flows/${id}`); }
  runFlow(slug: string, input: Record<string, unknown>): Observable<Run> {
    return this.http.post<Run>(`${this.base}/flows/${slug}/run`, input);
  }

  // ---- Capabilities ----
  listCapabilities(type?: string): Observable<Capability[]> {
    const q = type ? `?type=${type}` : '';
    return this.http.get<Capability[]>(`${this.base}/capabilities${q}`);
  }
  createCapability(body: CapabilityRequest): Observable<Capability> {
    return this.http.post<Capability>(`${this.base}/capabilities`, body);
  }
  updateCapability(id: string, body: CapabilityRequest): Observable<Capability> {
    return this.http.put<Capability>(`${this.base}/capabilities/${id}`, body);
  }
  deleteCapability(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/capabilities/${id}`);
  }
  testCapability(id: string, body: Record<string, unknown>): Observable<{ ok: boolean; result?: unknown; message?: string }> {
    return this.http.post<{ ok: boolean; result?: unknown; message?: string }>(`${this.base}/capabilities/${id}/test`, body);
  }

  // ---- Providers ----
  listProviders(): Observable<Provider[]> { return this.http.get<Provider[]>(`${this.base}/providers`); }
  createProvider(body: ProviderRequest): Observable<Provider> { return this.http.post<Provider>(`${this.base}/providers`, body); }
  updateProvider(id: string, body: ProviderRequest): Observable<Provider> { return this.http.put<Provider>(`${this.base}/providers/${id}`, body); }
  deleteProvider(id: string): Observable<void> { return this.http.delete<void>(`${this.base}/providers/${id}`); }
  testProvider(id: string): Observable<{ ok: boolean; message: string }> {
    return this.http.post<{ ok: boolean; message: string }>(`${this.base}/providers/${id}/test`, {});
  }

  // ---- Runs ----
  listRuns(): Observable<Run[]> { return this.http.get<Run[]>(`${this.base}/runs`); }
  getRun(id: string): Observable<Run> { return this.http.get<Run>(`${this.base}/runs/${id}`); }
  runStats(): Observable<{ totalRuns: number; completed: number; failed: number }> {
    return this.http.get<{ totalRuns: number; completed: number; failed: number }>(`${this.base}/runs/stats`);
  }
}
