export type CapabilityType = 'skill' | 'tool' | 'function' | 'mcp';

export interface Capability {
  id: string;
  type: CapabilityType;
  name: string;
  slug: string;
  version: number;
  description?: string;
  spec: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface CapabilityRequest {
  type: CapabilityType;
  name: string;
  slug?: string;
  description?: string;
  spec?: Record<string, unknown>;
}

export interface Flow {
  id: string;
  slug: string;
  name: string;
  version: number;
  status: 'draft' | 'published' | 'archived';
  description?: string;
  config: Record<string, unknown>;
  capabilities: Capability[];
  createdAt: string;
  updatedAt: string;
}

export interface FlowRequest {
  name: string;
  slug?: string;
  status?: string;
  description?: string;
  config?: Record<string, unknown>;
  capabilityIds?: string[];
}

export interface CapabilityHelpField {
  key: string;
  required: boolean;
  description: string;
}

export interface CapabilityHelp {
  type: CapabilityType;
  title: string;
  summary: string;
  howTo: string;
  fields: CapabilityHelpField[];
  example: Record<string, unknown>;
  tips: string[];
}

export interface Provider {
  id: string;
  name: string;
  type: string;
  baseUrl: string;
  hasApiKey: boolean;
  model: string;
  contextSize: number;
  maxTokens: number;
  temperature: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ProviderRequest {
  name: string;
  type?: string;
  baseUrl: string;
  apiKey?: string;
  model: string;
  contextSize?: number;
  maxTokens?: number;
  temperature?: number;
  enabled?: boolean;
}

export interface RunStep {
  id: string;
  seq: number;
  type: 'plan' | 'subagent' | 'tool_call' | 'synthesis' | 'guardrail' | 'skill';
  title: string;
  detail?: string;
  payload: Record<string, unknown>;
  tokens: number;
  costUsd: number;
  createdAt: string;
}

export interface Run {
  id: string;
  flowId: string;
  flowSlug: string;
  flowVersion: number;
  status: 'pending' | 'running' | 'completed' | 'failed';
  input: Record<string, unknown>;
  output?: Record<string, unknown>;
  error?: string;
  costUsd: number;
  tokens: number;
  startedAt: string;
  endedAt?: string;
  steps: RunStep[];
}
