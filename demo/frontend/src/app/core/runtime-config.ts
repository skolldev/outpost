import { InjectionToken } from '@angular/core';

export interface RuntimeConfig {
  sentryDsn: string;
  environment: string;
  release: string;
  apiBase: string;
}

export const RUNTIME_CONFIG = new InjectionToken<RuntimeConfig>('RUNTIME_CONFIG');
