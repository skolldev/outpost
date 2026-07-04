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
  Project,
  ProjectKey,
  Release,
  ReleaseArtifact,
  SessionUser,
} from './models';

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/internal';

  // auth
  login(email: string, password: string): Observable<SessionUser> {
    return this.http.post<SessionUser>(`${this.base}/auth/login`, { email, password });
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.base}/auth/logout`, {});
  }

  me(): Observable<SessionUser> {
    return this.http.get<SessionUser>(`${this.base}/auth/me`);
  }

  // issues & events
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

  // projects & keys
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

  // releases & artifacts
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

  // users
  users(): Observable<AppUser[]> {
    return this.http.get<AppUser[]>(`${this.base}/users`);
  }

  createUser(email: string, password: string, role: string): Observable<AppUser> {
    return this.http.post<AppUser>(`${this.base}/users`, { email, password, role });
  }
}
