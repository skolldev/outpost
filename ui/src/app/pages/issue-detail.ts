import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { Api } from '../core/api';
import { GlobalFilters } from '../core/filters';
import {
  Breadcrumb,
  EventDetail,
  IssueDetail,
  LogRecord,
  SentryException,
  StackFrame,
} from '../core/models';
import { levelClass, timeAgo } from '../shared/ui';

/** Issue detail (§9 page 2): stacktrace, breadcrumbs, tags, contexts, event navigator. */
@Component({
  selector: 'app-issue-detail',
  imports: [DatePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './issue-detail.html',
})
export class IssueDetailPage {
  private readonly api = inject(Api);
  readonly filters = inject(GlobalFilters);

  readonly id = input.required<string>();

  readonly issue = signal<IssueDetail | null>(null);
  readonly event = signal<EventDetail | null>(null);
  readonly showVendor = signal(true);
  readonly expandedFrames = signal<ReadonlySet<StackFrame>>(new Set());
  readonly logsOpen = signal(false);
  readonly eventLogs = signal<LogRecord[] | null>(null);

  readonly timeAgo = timeAgo;
  readonly levelClass = levelClass;

  constructor() {
    effect(() => {
      const issueId = Number(this.id());
      if (!Number.isNaN(issueId)) {
        void this.loadIssue(issueId);
      }
    });
  }

  /** Primary (last) exception first, like Sentry. */
  readonly exceptions = computed<SentryException[]>(() => {
    const values = this.event()?.data.exception?.values ?? [];
    return [...values].reverse();
  });

  /** Missing-source-map banner data (§9.2), or null when fully symbolicated. */
  readonly symbolicationWarning = computed(() => {
    const event = this.event();
    if (!event) return null;
    const status = event.symbolication_status;
    if (status !== 'missing_sourcemap' && status !== 'partial') return null;
    return { status, missing: event.data._outpost_symbolication?.missing ?? [] };
  });

  readonly breadcrumbs = computed<Breadcrumb[]>(() => {
    const raw = this.event()?.data.breadcrumbs;
    if (!raw) return [];
    return Array.isArray(raw) ? raw : (raw.values ?? []);
  });

  readonly tags = computed<[string, string][]>(() => {
    const raw = this.event()?.data.tags;
    if (!raw) return [];
    return Array.isArray(raw) ? (raw as [string, string][]) : Object.entries(raw);
  });

  readonly contexts = computed<{ name: string; entries: [string, string][] }[]>(() => {
    const event = this.event();
    if (!event) return [];
    const sections: { name: string; entries: [string, string][] }[] = [];
    const user = event.data.user;
    if (user && Object.keys(user).length > 0) {
      sections.push({ name: 'user', entries: this.flatten(user) });
    }
    const contexts = event.data.contexts ?? {};
    for (const name of ['browser', 'os', 'runtime', 'device']) {
      const ctx = contexts[name];
      if (ctx && Object.keys(ctx).length > 0) {
        sections.push({ name, entries: this.flatten(ctx) });
      }
    }
    const request = event.data.request;
    if (request && (request.url || request.method)) {
      sections.push({
        name: 'request',
        entries: this.flatten({ method: request.method, url: request.url }),
      });
    }
    return sections;
  });

  visibleFrames(exception: SentryException): StackFrame[] {
    // Frames arrive oldest → newest; show newest first.
    const frames = [...(exception.stacktrace?.frames ?? [])].reverse();
    return this.showVendor() ? frames : frames.filter((f) => f.in_app);
  }

  hasContext(frame: StackFrame): boolean {
    return frame.context_line != null;
  }

  toggleFrame(frame: StackFrame): void {
    if (!this.hasContext(frame)) return;
    const expanded = new Set(this.expandedFrames());
    if (!expanded.delete(frame)) {
      expanded.add(frame);
    }
    this.expandedFrames.set(expanded);
  }

  /** pre/context/post lines with their original line numbers, current line marked. */
  contextLines(frame: StackFrame): { no: number; text: string; current: boolean }[] {
    const pre = frame.pre_context ?? [];
    const post = frame.post_context ?? [];
    const line = frame.lineno ?? 0;
    return [
      ...pre.map((text, i) => ({ no: line - pre.length + i, text, current: false })),
      { no: line, text: frame.context_line ?? '', current: true },
      ...post.map((text, i) => ({ no: line + 1 + i, text, current: false })),
    ];
  }

  crumbTime(crumb: Breadcrumb): string {
    if (crumb.timestamp == null) return '';
    const date =
      typeof crumb.timestamp === 'number'
        ? new Date(crumb.timestamp * 1000)
        : new Date(crumb.timestamp);
    return date.toLocaleTimeString(undefined, { hour12: false });
  }

  crumbText(crumb: Breadcrumb): string {
    if (crumb.message) return crumb.message;
    if (crumb.data) {
      return Object.entries(crumb.data)
        .map(([k, v]) => `${k}=${String(v)}`)
        .join(' ');
    }
    return '';
  }

  private flatten(obj: Record<string, unknown>): [string, string][] {
    return Object.entries(obj)
      .filter(([, v]) => v != null && typeof v !== 'object')
      .map(([k, v]) => [k, String(v)] as [string, string]);
  }

  private async loadIssue(issueId: number): Promise<void> {
    this.issue.set(await firstValueFrom(this.api.issue(issueId)));
    await this.loadLatest();
  }

  async loadLatest(): Promise<void> {
    const issue = this.issue();
    if (!issue) return;
    const page = await firstValueFrom(this.api.issueEvents(issue.id, this.filters.environments()));
    if (page.events.length > 0) {
      await this.loadEvent(page.events[0].id);
    }
  }

  async loadEvent(eventId: string): Promise<void> {
    const event = await firstValueFrom(this.api.event(eventId));
    // Auto-expand the newest in-app frame with source context, like Sentry.
    const frames = [...(event.data.exception?.values?.at(-1)?.stacktrace?.frames ?? [])].reverse();
    const first =
      frames.find((f) => f.in_app && this.hasContext(f)) ?? frames.find((f) => this.hasContext(f));
    this.expandedFrames.set(first ? new Set([first]) : new Set());
    this.event.set(event);
    this.eventLogs.set(null);
    if (this.logsOpen()) void this.loadEventLogs();
  }

  toggleLogs(): void {
    this.logsOpen.set(!this.logsOpen());
    if (this.logsOpen() && this.eventLogs() === null) void this.loadEventLogs();
  }

  /** Correlated logs (§9.2): by trace_id, else a ±60 s window in the same project. */
  private async loadEventLogs(): Promise<void> {
    const event = this.event();
    if (!event) return;
    const filters = event.trace_id
      ? { traceId: event.trace_id }
      : {
          project: event.project_id,
          from: new Date(new Date(event.timestamp).getTime() - 60_000).toISOString(),
          to: new Date(new Date(event.timestamp).getTime() + 60_000).toISOString(),
        };
    const page = await firstValueFrom(this.api.logs(filters));
    this.eventLogs.set(page.logs);
  }

  toggleStatus(): void {
    const issue = this.issue();
    if (!issue) return;
    const status = issue.status === 'resolved' ? 'unresolved' : 'resolved';
    void firstValueFrom(this.api.updateIssueStatus(issue.id, status)).then((updated) =>
      this.issue.set(updated),
    );
  }
}
