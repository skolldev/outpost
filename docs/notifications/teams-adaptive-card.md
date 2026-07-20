# Teams Adaptive Card notification

The **Teams** Notification Channel posts a Microsoft Teams **Adaptive Card** to a
[Teams Workflows](https://support.microsoft.com/en-us/office/send-messages-in-teams-using-incoming-webhooks-323660ec-12ca-40b1-a1d3-a3df47e808c4)
incoming webhook URL, so a channel shows a readable card — title, key facts, and
a button that deep-links into Outpost — instead of raw JSON.

Unlike the [Generic JSON payload](generic-json-payload.md), the card layout is
**presentation, not a versioned contract**: there is no `version` field and the
shape may change freely between Outpost releases. Receivers are Teams itself, not
custom integrations, so there is nothing to keep stable. This doc describes the
current shape for operators; do not build a receiver against it (use a Generic
JSON channel for that).

## Envelope

Teams Workflows webhooks require the Adaptive Card wrapped in a `message`
envelope; posting a bare card (or the legacy MessageCard `{"text": ...}`) returns
`400`. Outpost sends:

```json
{
  "type": "message",
  "attachments": [
    {
      "contentType": "application/vnd.microsoft.card.adaptive",
      "content": {
        "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
        "type": "AdaptiveCard",
        "version": "1.4",
        "body": [ /* title, heading, FactSet */ ],
        "actions": [ { "type": "Action.OpenUrl", "title": "…", "url": "…" } ]
      }
    }
  ]
}
```

## What each trigger shows

Every card carries the same summary facts as the corresponding Generic JSON
payload plus one `Action.OpenUrl` deep link into Outpost (built from
`outpost.public-url`, honoring a reverse-proxy sub-path).

| Trigger             | Heading      | Facts                                          | Link target |
| ------------------- | ------------ | ---------------------------------------------- | ----------- |
| `new_issue`         | Issue title  | Project, Culprit, Environment, First seen      | the Issue   |
| `incident_started`  | Monitor URL  | Project, Monitor, Environment, Failure, Opened | Uptime page |
| `incident_resolved` | Monitor URL  | Project, Monitor, Environment, Downtime, Resolved | Uptime page |
| `test`              | Confirmation | —                                              | Settings    |

A fact with no value (e.g. an Event with no environment or culprit) is omitted
rather than shown as `null`.
