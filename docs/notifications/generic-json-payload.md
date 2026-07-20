# Generic JSON notification payload

The **Generic JSON** Notification Channel posts a documented, versioned JSON
payload to any HTTP receiver you configure. This is a public contract: within a
`version` it changes **additively only** (new fields may appear; existing fields
keep their name and meaning), so a receiver that ignores unknown fields will not
break on an Outpost upgrade.

All keys are `snake_case`, matching Outpost's wire convention. Delivery is an
HTTP `POST` with `Content-Type: application/json`, best-effort with a few
in-process retries (see ADR 0005). There is no signature header (ADR 0006): the
webhook URL itself is the credential.

## Envelope

Every payload has these two top-level fields:

| Field     | Type    | Notes                                                          |
| --------- | ------- | ------------------------------------------------------------- |
| `version` | integer | Contract version. Currently always `1`.                       |
| `type`    | string  | Trigger discriminator. Switch on this to read the rest.       |

Known `type` values: `new_issue`, `incident_started`, `incident_resolved`, and
`test` (all below).

## `type: "new_issue"`

Sent when an Event arrives whose fingerprint has never been seen before — i.e. a
new Issue was created. Repeat Events of the same fingerprint, and regressions of
a resolved Issue, do **not** fire this trigger.

```json
{
  "version": 1,
  "type": "new_issue",
  "project": {
    "id": 42,
    "slug": "shop",
    "name": "Shop"
  },
  "issue": {
    "id": 100,
    "title": "TypeError: Cannot read properties of undefined (reading 'user')",
    "culprit": "main.js in handleClick",
    "environment": "production",
    "first_seen": "2026-07-20T10:00:00Z",
    "link": "https://outpost.example.com/issues/100"
  }
}
```

| Field                 | Type           | Notes                                                                             |
| --------------------- | -------------- | --------------------------------------------------------------------------------- |
| `project.id`          | integer        | Project id.                                                                       |
| `project.slug`        | string         | Project slug.                                                                     |
| `project.name`        | string         | Project display name.                                                             |
| `issue.id`            | integer        | Issue id (matches the `link` target).                                             |
| `issue.title`         | string         | Grouped Issue title (exception type + message, or the message).                   |
| `issue.culprit`       | string \| null | Best-guess origin (in-app frame or transaction). `null` when none was derivable. |
| `issue.environment`   | string \| null | Environment of the first Event. `null` when the Event carried none.               |
| `issue.first_seen`    | string         | ISO-8601 UTC instant the Issue was first seen.                                     |
| `issue.link`          | string         | Deep link into Outpost, built from `outpost.public-url`.                          |

### Notes for receivers

- Treat `culprit` and `environment` as nullable.
- `link` honors a reverse-proxy sub-path if Outpost is served under one
  (`OUTPOST_PUBLIC_URL` including a path).
- New fields may be added within `version: 1`; ignore ones you do not recognize.

## `type: "incident_started"`

Sent when an Uptime Monitor's **third consecutive failed check** opens an
Incident. It fires **once per Incident** — not on every failed check, and not
when an already-open Incident merely records a newer failure reason. A Monitor is
identified by the URL it probes (there is no separate monitor name), so
`monitor.url` is both its identity and the probed URL.

Both incident triggers respect the channel's trigger selection and its Project
and Environment filters. Uptime Monitors are Project- and Environment-scoped, so
an incident always carries an `environment`; a channel with a non-empty
Environment filter only fires when that environment is listed.

```json
{
  "version": 1,
  "type": "incident_started",
  "project": {
    "id": 42,
    "slug": "shop",
    "name": "Shop"
  },
  "monitor": {
    "id": 9,
    "url": "https://shop.example.com/health",
    "environment": "production",
    "link": "https://outpost.example.com/uptime"
  },
  "incident": {
    "failure_reason": "HTTP 503",
    "opened_at": "2026-07-20T10:00:00Z"
  }
}
```

| Field                     | Type           | Notes                                                                  |
| ------------------------- | -------------- | ---------------------------------------------------------------------- |
| `project.id`              | integer        | Project id.                                                            |
| `project.slug`            | string         | Project slug.                                                          |
| `project.name`            | string         | Project display name.                                                  |
| `monitor.id`              | integer        | Uptime Monitor id.                                                     |
| `monitor.url`             | string         | The probed URL — the Monitor's identity.                               |
| `monitor.environment`     | string         | Environment the Monitor is scoped to.                                  |
| `monitor.link`            | string         | Deep link to the Uptime status page, built from `outpost.public-url`.  |
| `incident.failure_reason` | string \| null | The failing check's HTTP status or connection error. `null` if none.  |
| `incident.opened_at`      | string         | ISO-8601 UTC instant the Incident opened.                             |

## `type: "incident_resolved"`

Sent when the **next successful check** closes an open Incident. It fires **once
per recovery** — never on a success while the Monitor is already healthy. It
carries the same `project` and `monitor` blocks as `incident_started`, plus the
downtime.

```json
{
  "version": 1,
  "type": "incident_resolved",
  "project": {
    "id": 42,
    "slug": "shop",
    "name": "Shop"
  },
  "monitor": {
    "id": 9,
    "url": "https://shop.example.com/health",
    "environment": "production",
    "link": "https://outpost.example.com/uptime"
  },
  "incident": {
    "opened_at": "2026-07-20T10:00:00Z",
    "closed_at": "2026-07-20T12:05:30Z",
    "downtime_seconds": 7530,
    "downtime_human": "2h 5m 30s"
  }
}
```

| Field                       | Type    | Notes                                                                        |
| --------------------------- | ------- | ---------------------------------------------------------------------------- |
| `project.*`, `monitor.*`    |         | Identical to `incident_started`.                                             |
| `incident.opened_at`        | string  | ISO-8601 UTC instant the Incident opened.                                    |
| `incident.closed_at`        | string  | ISO-8601 UTC instant the Incident closed (this recovery).                    |
| `incident.downtime_seconds` | integer | Whole seconds the Incident was open (`closed_at − opened_at`). Canonical.    |
| `incident.downtime_human`   | string  | Compact human-readable downtime (e.g. `2h 5m 30s`); presentation sugar.      |

Compute your own duration from the two timestamps if you prefer;
`downtime_seconds` is the canonical value and `downtime_human` is convenience.

## `type: "test"`

Sent when an Admin clicks **Test** on a channel in Settings. It bypasses trigger
matching and both filters — it always goes to the one channel being tested — but
otherwise runs the full delivery pipeline (formatting, a history row, async send
with retries), so a `2xx` response confirms the whole path end to end. It respects
the channel's `enabled` flag: a disabled channel is not tested.

```json
{
  "version": 1,
  "type": "test",
  "channel": {
    "id": 4,
    "name": "Ops JSON"
  },
  "message": "Test notification from Outpost — the channel \"Ops JSON\" is configured correctly.",
  "fired_at": "2026-07-20T10:00:00Z",
  "link": "https://outpost.example.com/settings"
}
```

| Field          | Type    | Notes                                                          |
| -------------- | ------- | ------------------------------------------------------------- |
| `channel.id`   | integer | Id of the channel being tested.                               |
| `channel.name` | string  | Display name of the channel being tested.                     |
| `message`      | string  | Human-readable confirmation string; safe to display as-is.    |
| `fired_at`     | string  | ISO-8601 UTC instant the test was fired.                      |
| `link`         | string  | Deep link to Outpost settings, built from `outpost.public-url`. |

A receiver can treat a `test` delivery like any other: acknowledge with a `2xx`.
Distinguish it from real events by switching on `type` so a test does not page
anyone.
