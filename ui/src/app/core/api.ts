import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from './api-base';
import {
  ApiToken,
  AppUser,
  DataRetentionSetting,
  EventDetail,
  EventPage,
  IssueDetail,
  IssueFilters,
  IssuePage,
  LogFilters,
  LogPage,
  NotificationChannel,
  NotificationChannelInput,
  NotificationHistoryEntry,
  NotificationTestResult,
  Project,
  ProjectKey,
  Release,
  ReleaseArtifact,
  SessionUser,
  TraceDetail,
  TraceFilters,
  TracePage,
  UptimeMonitor,
  UptimeOverview,
  UptimeTestResult,
} from './models';
import { issueParams, logParams, QueryParams, traceParams } from './query-params';

/** Turn a plain params record (arrays → repeated params) into HttpParams. */
function httpParams(params: QueryParams): HttpParams {
  return new HttpParams({ fromObject: params });
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly base = API_BASE;

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
    return this.http.get<IssuePage>(`${this.base}/issues`, {
      params: httpParams(issueParams(filters)),
    });
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
    return this.http.get<LogPage>(`${this.base}/logs`, { params: httpParams(logParams(filters)) });
  }

  /** URL for the SSE live tail (§9.3) — same filters, consumed via EventSource. */
  logTailUrl(filters: LogFilters): string {
    const params = httpParams(logParams(filters)).set('live', 'true');
    return `${this.base}/logs?${params.toString()}`;
  }

  traces(filters: TraceFilters): Observable<TracePage> {
    return this.http.get<TracePage>(`${this.base}/traces`, {
      params: httpParams(traceParams(filters)),
    });
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

  // Uptime monitoring
  uptimeMonitors(): Observable<UptimeMonitor[]> {
    return this.http.get<UptimeMonitor[]>(`${this.base}/uptime/monitors`);
  }

  createUptimeMonitor(body: {
    project_id: number;
    environment: string;
    url: string;
    interval_seconds: number;
    timeout_seconds: number;
  }): Observable<UptimeMonitor> {
    return this.http.post<UptimeMonitor>(`${this.base}/uptime/monitors`, body);
  }

  updateUptimeMonitor(
    id: number,
    body: {
      project_id: number;
      environment: string;
      url: string;
      interval_seconds: number;
      timeout_seconds: number;
    },
  ): Observable<UptimeMonitor> {
    return this.http.patch<UptimeMonitor>(`${this.base}/uptime/monitors/${id}`, body);
  }

  deleteUptimeMonitor(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/uptime/monitors/${id}`);
  }

  testUptimeMonitor(url: string, timeoutSeconds: number): Observable<UptimeTestResult> {
    return this.http.post<UptimeTestResult>(`${this.base}/uptime/monitors/test`, {
      url,
      timeout_seconds: timeoutSeconds,
    });
  }

  uptimeOverview(): Observable<UptimeOverview> {
    return this.http.get<UptimeOverview>(`${this.base}/uptime/overview`);
  }

  // Notification channels (admin-only)
  notificationChannels(): Observable<NotificationChannel[]> {
    return this.http.get<NotificationChannel[]>(`${this.base}/notifications/channels`);
  }

  createNotificationChannel(body: NotificationChannelInput): Observable<NotificationChannel> {
    return this.http.post<NotificationChannel>(`${this.base}/notifications/channels`, body);
  }

  updateNotificationChannel(
    id: number,
    body: NotificationChannelInput,
  ): Observable<NotificationChannel> {
    return this.http.patch<NotificationChannel>(`${this.base}/notifications/channels/${id}`, body);
  }

  deleteNotificationChannel(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/notifications/channels/${id}`);
  }

  testNotificationChannel(id: number): Observable<NotificationTestResult> {
    return this.http.post<NotificationTestResult>(
      `${this.base}/notifications/channels/${id}/test`,
      {},
    );
  }

  notificationChannelHistory(id: number): Observable<NotificationHistoryEntry[]> {
    return this.http.get<NotificationHistoryEntry[]>(
      `${this.base}/notifications/channels/${id}/history`,
    );
  }

  users(): Observable<AppUser[]> {
    return this.http.get<AppUser[]>(`${this.base}/users`);
  }

  createUser(email: string, password: string, role: string): Observable<AppUser> {
    return this.http.post<AppUser>(`${this.base}/users`, { email, password, role });
  }

  dataRetention(): Observable<DataRetentionSetting> {
    return this.http.get<DataRetentionSetting>(`${this.base}/settings/data-retention`);
  }

  updateDataRetention(setting: DataRetentionSetting): Observable<DataRetentionSetting> {
    return this.http.put<DataRetentionSetting>(`${this.base}/settings/data-retention`, setting);
  }
}
