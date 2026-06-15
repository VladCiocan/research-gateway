import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard · Research Gateway',
    loadComponent: () => import('./pages/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'flows',
    title: 'Flows · Research Gateway',
    loadComponent: () => import('./pages/flows.component').then((m) => m.FlowsComponent),
  },
  {
    path: 'flows/:id',
    title: 'Flow Builder · Research Gateway',
    loadComponent: () => import('./pages/flow-builder.component').then((m) => m.FlowBuilderComponent),
  },
  {
    path: 'capabilities',
    title: 'Capabilities · Research Gateway',
    loadComponent: () => import('./pages/capabilities.component').then((m) => m.CapabilitiesComponent),
  },
  {
    path: 'playground',
    title: 'Playground · Research Gateway',
    loadComponent: () => import('./pages/playground.component').then((m) => m.PlaygroundComponent),
  },
  {
    path: 'runs',
    title: 'Runs · Research Gateway',
    loadComponent: () => import('./pages/runs.component').then((m) => m.RunsComponent),
  },
  {
    path: 'settings',
    title: 'Settings · Research Gateway',
    loadComponent: () => import('./pages/settings.component').then((m) => m.SettingsComponent),
  },
  { path: '**', redirectTo: 'dashboard' },
];
