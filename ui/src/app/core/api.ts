import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  ApiToken,
  AppUser,
  EventDetail,
  EventPage,
  IssueDetail,
  IssueFilters,
  IssuePage,
  LogFilters,
  LogPage,
  Project,
  ProjectKey,
  Release,
  ReleaseArtifact,
  SessionUser,
  TraceDetail,
  TraceFilters,
  TracePage,
} from './models';

function logParams(filters: LogFilters): HttpParams {
  let params = new HttpParams();
  if (filters.project != null) params = params.set('project', filters.project);
  for (const env of filters.environment ?? []) params = params.append('environment', env);
  for (const level of filters.level ?? []) params = params.append('level', level);
  if (filters.traceId) params = params.set('trace_id', filters.traceId);
  if (filters.query) params = params.set('query', filters.query);
  if (filters.from) params = params.set('from', filters.from);
  if (filters.to) params = params.set('to', filters.to);
  if (filters.cursor) params = params.set('cursor', filters.cursor);
  return params;
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/internal';

  login(email: string, password: string): Observable<SessionUser> {
    return this.http.post<SessionUser>(`${this.base}/auth/login`, { email, password });
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.base}/auth/logout`, {});
  }

  me(): Observable<SessionUser> {
    return this.http.get<SessionUser>(`${this.base}/auth/me`);
  }

  issues(filters: IssueFilters): Observable<IssuePage> {
    let params = new HttpParams();
    if (filters.project != null) params = params.set('project', filters.project);
    for (const env of filters.environment ?? []) params = params.append('environment', env);
    if (filters.status) params = params.set('status', filters.status);
    if (filters.query) params = params.set('query', filters.query);
    if (filters.sort) params = params.set('sort', filters.sort);
    if (filters.from) params = params.set('from', filters.from);
    if (filters.cursor) params = params.set('cursor', filters.cursor);
    return this.http.get<IssuePage>(`${this.base}/issues`, { params });
  }

  issue(id: number): Observable<IssueDetail> {
    return this.http.get<IssueDetail>(`${this.base}/issues/${id}`);
  }

  updateIssueStatus(id: number, status: 'resolved' | 'unresolved'): Observable<IssueDetail> {
    return this.http.patch<IssueDetail>(`${this.base}/issues/${id}`, { status });
  }

  issueEvents(id: number, environment: string[], cursor?: string): Observable<EventPage> {
    let params = new HttpParams();
    for (const env of environment) params = params.append('environment', env);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<EventPage>(`${this.base}/issues/${id}/events`, { params });
  }

  event(id: string): Observable<EventDetail> {
    return this.http.get<EventDetail>(`${this.base}/events/${id}`);
  }

  logs(filters: LogFilters): Observable<LogPage> {
    return this.http.get<LogPage>(`${this.base}/logs`, { params: logParams(filters) });
  }

  /** URL for the SSE live tail (§9.3) — same filters, consumed via EventSource. */
  logTailUrl(filters: LogFilters): string {
    const params = logParams(filters).set('live', 'true');
    return `${this.base}/logs?${params.toString()}`;
  }

  traces(filters: TraceFilters): Observable<TracePage> {
    let params = new HttpParams();
    if (filters.project != null) params = params.set('project', filters.project);
    for (const env of filters.environment ?? []) params = params.append('environment', env);
    if (filters.release) params = params.set('release', filters.release);
    if (filters.query) params = params.set('query', filters.query);
    if (filters.minDuration != null) params = params.set('min_duration', filters.minDuration);
    if (filters.maxDuration != null) params = params.set('max_duration', filters.maxDuration);
    if (filters.hasErrors) params = params.set('has_errors', 'true');
    if (filters.from) params = params.set('from', filters.from);
    if (filters.to) params = params.set('to', filters.to);
    if (filters.cursor) params = params.set('cursor', filters.cursor);
    return this.http.get<TracePage>(`${this.base}/traces`, { params });
  }

  trace(traceId: string): Observable<TraceDetail> {
    return this.http.get<TraceDetail>(`${this.base}/traces/${encodeURIComponent(traceId)}`);
  }

  projects(): Observable<Project[]> {
    return this.http.get<Project[]>(`${this.base}/projects`);
  }

  createProject(slug: string, name: string, platform: string | null): Observable<Project> {
    return this.http.post<Project>(`${this.base}/projects`, { slug, name, platform });
  }

  projectEnvironments(projectId: number): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/projects/${projectId}/environments`);
  }

  projectKeys(projectId: number): Observable<ProjectKey[]> {
    return this.http.get<ProjectKey[]>(`${this.base}/projects/${projectId}/keys`);
  }

  createProjectKey(projectId: number): Observable<ProjectKey> {
    return this.http.post<ProjectKey>(`${this.base}/projects/${projectId}/keys`, {});
  }

  setKeyActive(projectId: number, keyId: number, isActive: boolean): Observable<ProjectKey> {
    return this.http.patch<ProjectKey>(`${this.base}/projects/${projectId}/keys/${keyId}`, {
      is_active: isActive,
    });
  }

  releases(projectId: number): Observable<Release[]> {
    const params = new HttpParams().set('project', projectId);
    return this.http.get<Release[]>(`${this.base}/releases`, { params });
  }

  releaseArtifacts(version: string, projectId: number): Observable<ReleaseArtifact[]> {
    const params = new HttpParams().set('project', projectId);
    return this.http.get<ReleaseArtifact[]>(
      `${this.base}/releases/${encodeURIComponent(version)}/artifacts`,
      { params },
    );
  }

  // API tokens (sentry-cli)
  tokens(): Observable<ApiToken[]> {
    return this.http.get<ApiToken[]>(`${this.base}/tokens`);
  }

  createToken(name: string): Observable<ApiToken> {
    return this.http.post<ApiToken>(`${this.base}/tokens`, { name });
  }

  deleteToken(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/tokens/${id}`);
  }

  users(): Observable<AppUser[]> {
    return this.http.get<AppUser[]>(`${this.base}/users`);
  }

  createUser(email: string, password: string, role: string): Observable<AppUser> {
    return this.http.post<AppUser>(`${this.base}/users`, { email, password, role });
  }
}
