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
  template: `
    @if (issue(); as issue) {
      <div class="mb-6">
        <a
          routerLink="/issues"
          queryParamsHandling="merge"
          class="text-xs text-slate-500 hover:text-slate-300"
          >← Issues</a
        >
        <div class="mt-2 flex flex-wrap items-start justify-between gap-4">
          <div class="min-w-0">
            <h1 class="flex items-center gap-2 text-lg font-semibold text-white">
              <span
                class="rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase"
                [class]="levelClass(issue.level)"
                >{{ issue.level }}</span
              >
              <span class="truncate">{{ issue.title }}</span>
            </h1>
            @if (issue.culprit) {
              <p class="mt-1 text-sm text-slate-500">{{ issue.culprit }}</p>
            }
            <div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-400">
              <span>{{ issue.event_count }} events</span>
              <span>·</span>
              <span>first seen {{ timeAgo(issue.first_seen) }}</span>
              <span>·</span>
              <span>last seen {{ timeAgo(issue.last_seen) }}</span>
              @for (stat of issue.env_stats; track stat.environment) {
                <span
                  class="rounded-full border border-slate-700 px-2 py-0.5 text-[10px] text-slate-400"
                  >{{ stat.environment }}: {{ stat.event_count }}</span
                >
              }
            </div>
          </div>
          <button
            (click)="toggleStatus()"
            class="rounded px-3 py-1.5 text-sm font-medium"
            [class]="
              issue.status === 'resolved'
                ? 'border border-slate-700 text-slate-300 hover:border-slate-500'
                : 'bg-emerald-600 text-white hover:bg-emerald-500'
            "
          >
            {{ issue.status === 'resolved' ? 'Unresolve' : 'Resolve' }}
          </button>
        </div>
      </div>

      @if (event(); as event) {
        <div class="mb-4 flex items-center gap-2 text-sm">
          <div class="flex rounded border border-slate-700">
            <button
              [disabled]="!event.next_event_id"
              (click)="loadLatest()"
              class="border-r border-slate-700 px-2.5 py-1 text-slate-400 hover:text-white disabled:opacity-40"
            >
              « Latest
            </button>
            <button
              [disabled]="!event.next_event_id"
              (click)="loadEvent(event.next_event_id!)"
              class="border-r border-slate-700 px-2.5 py-1 text-slate-400 hover:text-white disabled:opacity-40"
            >
              ‹ Newer
            </button>
            <button
              [disabled]="!event.prev_event_id"
              (click)="loadEvent(event.prev_event_id!)"
              class="px-2.5 py-1 text-slate-400 hover:text-white disabled:opacity-40"
            >
              Older ›
            </button>
          </div>
          <span class="text-slate-400">{{ event.timestamp | date: 'medium' }}</span>
          <span
            class="rounded-full border border-slate-700 px-2 py-0.5 text-[10px] text-slate-400"
            >{{ event.environment }}</span
          >
          @if (event.release) {
            <span
              class="rounded-full border border-slate-700 px-2 py-0.5 text-[10px] text-slate-400"
              >{{ event.release }}</span
            >
          }
          <span class="ml-auto font-mono text-xs text-slate-600">{{ event.id }}</span>
        </div>

        <!-- Symbolication warning (§9.2) -->
        @if (symbolicationWarning(); as warning) {
          <div class="mb-4 rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm">
            <p class="font-medium text-amber-300">
              {{
                warning.status === 'partial'
                  ? 'Some frames could not be symbolicated — source maps are missing.'
                  : 'This stack trace is minified — no matching source maps were found.'
              }}
            </p>
            @if (warning.missing.length > 0) {
              <p class="mt-1 text-xs text-amber-200/70">Missing debug IDs:</p>
              <ul class="mt-1 space-y-0.5 font-mono text-xs text-amber-200/90">
                @for (miss of warning.missing; track miss.debug_id) {
                  <li>
                    {{ miss.debug_id }} <span class="text-amber-200/50">({{ miss.abs_path }})</span>
                  </li>
                }
              </ul>
            }
            <p class="mt-2 text-xs text-amber-200/70">
              Upload source maps with
              <code class="rounded bg-slate-900/60 px-1"
                >sentry-cli sourcemaps inject && sentry-cli sourcemaps upload</code
              >
              — see uploaded bundles on the
              <a
                routerLink="/releases"
                queryParamsHandling="merge"
                class="underline hover:text-amber-100"
                >Releases</a
              >
              page.
            </p>
          </div>
        }

        <!-- Stacktrace -->
        @if (exceptions().length > 0) {
          <section class="mb-6 rounded-lg border border-slate-800">
            <div class="flex items-center justify-between border-b border-slate-800 px-4 py-2">
              <h2 class="text-sm font-semibold text-slate-200">Stacktrace</h2>
              <label class="flex items-center gap-1.5 text-xs text-slate-400">
                <input
                  type="checkbox"
                  [checked]="showVendor()"
                  (change)="showVendor.set(!showVendor())"
                />
                Show vendor frames
              </label>
            </div>
            @for (exception of exceptions(); track $index) {
              <div class="px-4 py-3">
                <p class="mb-2 font-mono text-sm text-red-300">
                  {{ exception.type }}<span class="text-slate-400">: {{ exception.value }}</span>
                </p>
                <div class="overflow-x-auto rounded bg-slate-900 font-mono text-xs">
                  @for (frame of visibleFrames(exception); track $index) {
                    <div class="border-b border-slate-800/60 last:border-0">
                      <button
                        type="button"
                        class="block w-full px-3 py-1.5 text-left"
                        [class.opacity-50]="!frame.in_app"
                        [class.cursor-pointer]="hasContext(frame)"
                        [class.cursor-default]="!hasContext(frame)"
                        [class.hover:bg-slate-800/40]="hasContext(frame)"
                        (click)="toggleFrame(frame)"
                      >
                        @if (hasContext(frame)) {
                          <span class="mr-1 inline-block w-3 text-slate-500">{{
                            expandedFrames().has(frame) ? '▾' : '▸'
                          }}</span>
                        } @else {
                          <span class="mr-1 inline-block w-3"></span>
                        }
                        <span class="text-slate-300">{{ frame.module || frame.filename }}</span>
                        <span class="text-slate-500"> in </span>
                        <span class="text-amber-300">{{ frame.function || '?' }}</span>
                        @if (frame.lineno !== undefined) {
                          <span class="text-slate-500"> at line </span>
                          <span class="text-slate-300"
                            >{{ frame.lineno
                            }}{{ frame.colno !== undefined ? ':' + frame.colno : '' }}</span
                          >
                        }
                        @if (!frame.in_app) {
                          <span class="ml-2 rounded bg-slate-800 px-1 text-[10px] text-slate-500"
                            >vendor</span
                          >
                        }
                      </button>
                      @if (hasContext(frame) && expandedFrames().has(frame)) {
                        <div class="border-t border-slate-800/60 bg-slate-950/60 py-1">
                          @for (line of contextLines(frame); track line.no) {
                            <div
                              class="flex gap-3 px-3 leading-5"
                              [class.bg-red-500/15]="line.current"
                            >
                              <span class="w-10 shrink-0 select-none text-right text-slate-600">{{
                                line.no
                              }}</span>
                              <span
                                class="whitespace-pre"
                                [class]="line.current ? 'text-slate-100' : 'text-slate-400'"
                                >{{ line.text }}</span
                              >
                            </div>
                          }
                        </div>
                      }
                    </div>
                  }
                </div>
              </div>
            }
          </section>
        }

        <!-- Message (no stacktrace) -->
        @if (exceptions().length === 0 && event.message) {
          <section class="mb-6 rounded-lg border border-slate-800 px-4 py-3">
            <h2 class="mb-2 text-sm font-semibold text-slate-200">Message</h2>
            <p class="font-mono text-sm text-slate-300">{{ event.message }}</p>
          </section>
        }

        <div class="grid gap-6 lg:grid-cols-2">
          <!-- Breadcrumbs -->
          @if (breadcrumbs().length > 0) {
            <section class="rounded-lg border border-slate-800">
              <h2 class="border-b border-slate-800 px-4 py-2 text-sm font-semibold text-slate-200">
                Breadcrumbs
              </h2>
              <div class="max-h-96 overflow-y-auto">
                @for (crumb of breadcrumbs(); track $index) {
                  <div
                    class="flex gap-3 border-b border-slate-800/60 px-4 py-1.5 text-xs last:border-0"
                  >
                    <span class="w-14 shrink-0 text-slate-500">{{ crumbTime(crumb) }}</span>
                    <span class="w-24 shrink-0 truncate text-slate-400">{{
                      crumb.category || crumb.type || '—'
                    }}</span>
                    <span class="truncate text-slate-300">{{ crumbText(crumb) }}</span>
                  </div>
                }
              </div>
            </section>
          }

          <!-- Tags + contexts -->
          <section class="rounded-lg border border-slate-800">
            <h2 class="border-b border-slate-800 px-4 py-2 text-sm font-semibold text-slate-200">
              Tags & context
            </h2>
            <div class="px-4 py-3 text-xs">
              @if (tags().length > 0) {
                <div class="mb-3 flex flex-wrap gap-1.5">
                  @for (tag of tags(); track tag[0]) {
                    <span class="rounded-full border border-slate-700 px-2 py-0.5">
                      <span class="text-slate-500">{{ tag[0] }}:</span>
                      <span class="text-slate-300">{{ tag[1] }}</span>
                    </span>
                  }
                </div>
              }
              @for (ctx of contexts(); track ctx.name) {
                <div class="mb-2">
                  <h3 class="mb-1 font-semibold uppercase tracking-wide text-slate-500">
                    {{ ctx.name }}
                  </h3>
                  <div class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
                    @for (entry of ctx.entries; track entry[0]) {
                      <span class="text-slate-500">{{ entry[0] }}</span>
                      <span class="break-all text-slate-300">{{ entry[1] }}</span>
                    }
                  </div>
                </div>
              }
              @if (tags().length === 0 && contexts().length === 0) {
                <p class="text-slate-500">No tags or contexts on this event.</p>
              }
            </div>
          </section>
        </div>

        <!-- Logs around this event (§9.2): by trace_id, or ±60 s same-service window. -->
        <section class="mt-6 rounded-lg border border-slate-800">
          <div class="flex items-center justify-between border-b border-slate-800 px-4 py-2">
            <button
              (click)="toggleLogs()"
              class="flex items-center gap-1.5 text-sm font-semibold text-slate-200"
            >
              <span class="inline-block w-3 text-slate-500">{{ logsOpen() ? '▾' : '▸' }}</span>
              Logs around this event
              <span class="font-normal text-slate-500">
                {{ event.trace_id ? '(same trace)' : '(±60 s, same project)' }}
              </span>
            </button>
            @if (event.trace_id) {
              <a
                [routerLink]="['/logs']"
                [queryParams]="{ trace_id: event.trace_id }"
                queryParamsHandling="merge"
                class="text-xs text-sky-300 hover:text-sky-200"
                >Open in Logs →</a
              >
            }
          </div>
          @if (logsOpen()) {
            @if (eventLogs(); as records) {
              @if (records.length === 0) {
                <p class="px-4 py-6 text-center text-sm text-slate-500">
                  No log records found
                  {{ event.trace_id ? 'for this trace' : 'within ±60 s in this project' }}.
                </p>
              } @else {
                <div class="max-h-96 overflow-y-auto font-mono text-xs">
                  @for (record of records; track record.id) {
                    <div class="flex gap-3 border-b border-slate-800/60 px-4 py-1.5 last:border-0">
                      <span class="w-24 shrink-0 text-slate-500">{{
                        record.timestamp | date: 'HH:mm:ss.SSS'
                      }}</span>
                      <span
                        class="w-12 shrink-0 rounded px-1 text-center text-[10px] font-semibold uppercase"
                        [class]="levelClass(record.level)"
                        >{{ record.level }}</span
                      >
                      <span class="w-16 shrink-0 truncate text-[10px] leading-4 text-slate-500">{{
                        record.environment
                      }}</span>
                      <span class="truncate text-slate-300">{{ record.body }}</span>
                    </div>
                  }
                </div>
              }
            } @else {
              <p class="px-4 py-6 text-center text-sm text-slate-500">Loading logs…</p>
            }
          }
        </section>
      } @else {
        <p class="py-8 text-center text-sm text-slate-500">Loading event…</p>
      }
    } @else {
      <p class="py-12 text-center text-sm text-slate-500">Loading…</p>
    }
  `,
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
