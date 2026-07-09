import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { http, HttpResponse } from 'msw';

import { server } from '../../mocks/node';
import { IssuePage, LogPage, TracePage } from './models';
import { Api } from './api';

const BASE = '*/api/internal';

function setup(): Api {
  TestBed.configureTestingModule({ providers: [provideHttpClient()] });
  return TestBed.inject(Api);
}

describe('Api', () => {
  describe('issues()', () => {
    it('serializes filters — including repeated environment params — onto the query string', async () => {
      let url: URL | undefined;
      server.use(
        http.get(`${BASE}/issues`, ({ request }) => {
          url = new URL(request.url);
          return HttpResponse.json({ issues: [], next_cursor: null } satisfies IssuePage);
        }),
      );
      const api = setup();

      await firstValueFrom(
        api.issues({
          project: 1,
          environment: ['prod', 'staging'],
          status: 'unresolved',
          query: 'boom',
          sort: 'count',
          cursor: 'cursor-1',
        }),
      );

      expect(url?.searchParams.get('project')).toBe('1');
      expect(url?.searchParams.getAll('environment')).toEqual(['prod', 'staging']);
      expect(url?.searchParams.get('status')).toBe('unresolved');
      expect(url?.searchParams.get('query')).toBe('boom');
      expect(url?.searchParams.get('sort')).toBe('count');
      expect(url?.searchParams.get('cursor')).toBe('cursor-1');
    });

    it('omits params for empty filters', async () => {
      let url: URL | undefined;
      server.use(
        http.get(`${BASE}/issues`, ({ request }) => {
          url = new URL(request.url);
          return HttpResponse.json({ issues: [], next_cursor: null } satisfies IssuePage);
        }),
      );
      const api = setup();

      await firstValueFrom(api.issues({}));

      expect(Array.from(url!.searchParams.keys())).toHaveLength(0);
    });
  });

  describe('logs()', () => {
    it('serializes level/environment arrays and renames traceId to trace_id', async () => {
      let url: URL | undefined;
      server.use(
        http.get(`${BASE}/logs`, ({ request }) => {
          url = new URL(request.url);
          return HttpResponse.json({ logs: [], next_cursor: null } satisfies LogPage);
        }),
      );
      const api = setup();

      await firstValueFrom(
        api.logs({
          environment: ['prod'],
          level: ['error', 'fatal'],
          traceId: 'trace-abc',
          query: 'checkout',
        }),
      );

      expect(url?.searchParams.getAll('environment')).toEqual(['prod']);
      expect(url?.searchParams.getAll('level')).toEqual(['error', 'fatal']);
      expect(url?.searchParams.get('trace_id')).toBe('trace-abc');
      expect(url?.searchParams.get('query')).toBe('checkout');
      // live tail is only appended by logTailUrl(), not the plain page fetch.
      expect(url?.searchParams.has('live')).toBe(false);
    });
  });

  describe('logTailUrl()', () => {
    it('builds a live=true URL carrying the same filters, without making a request', () => {
      const api = setup();

      const url = api.logTailUrl({ environment: ['prod'], level: ['error'], query: 'boom' });

      expect(url).toContain('/api/internal/logs?');
      const parsed = new URL(url, 'http://localhost');
      expect(parsed.searchParams.get('live')).toBe('true');
      expect(parsed.searchParams.getAll('environment')).toEqual(['prod']);
      expect(parsed.searchParams.getAll('level')).toEqual(['error']);
      expect(parsed.searchParams.get('query')).toBe('boom');
    });

    it('still sets live=true when no other filters are given', () => {
      const api = setup();

      const url = api.logTailUrl({});

      const parsed = new URL(url, 'http://localhost');
      expect(parsed.searchParams.get('live')).toBe('true');
      expect(Array.from(parsed.searchParams.keys())).toEqual(['live']);
    });
  });

  describe('traces()', () => {
    it('serializes minDuration/maxDuration and stringifies hasErrors', async () => {
      let url: URL | undefined;
      server.use(
        http.get(`${BASE}/traces`, ({ request }) => {
          url = new URL(request.url);
          return HttpResponse.json({ traces: [], next_cursor: null } satisfies TracePage);
        }),
      );
      const api = setup();

      await firstValueFrom(
        api.traces({
          environment: ['prod'],
          minDuration: 100,
          maxDuration: 2000,
          hasErrors: true,
        }),
      );

      expect(url?.searchParams.getAll('environment')).toEqual(['prod']);
      expect(url?.searchParams.get('min_duration')).toBe('100');
      expect(url?.searchParams.get('max_duration')).toBe('2000');
      expect(url?.searchParams.get('has_errors')).toBe('true');
    });

    it('omits has_errors when hasErrors is false', async () => {
      let url: URL | undefined;
      server.use(
        http.get(`${BASE}/traces`, ({ request }) => {
          url = new URL(request.url);
          return HttpResponse.json({ traces: [], next_cursor: null } satisfies TracePage);
        }),
      );
      const api = setup();

      await firstValueFrom(api.traces({ hasErrors: false }));

      expect(url?.searchParams.has('has_errors')).toBe(false);
    });
  });
});