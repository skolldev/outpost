# Outpost

Outpost provides easily deployed, on-premises observability for individuals and teams that do not need Sentry's breadth or operational complexity. It retains compatibility with robust Sentry SDKs for telemetry collection.

## Language

### Organization

**Installation**:
One deployed Outpost system and its shared data. It is the ownership boundary for all Projects, Outpost Users, API Tokens, and settings.
_Avoid_: Organization, tenant, workspace

**Project**:
One independently instrumented application component whose telemetry Outpost receives and organizes. A larger product may contain multiple Projects, such as a frontend and backend.
_Avoid_: Application, service (when referring specifically to a Project)

**Telemetry**:
Operational data received by Outpost from Sentry SDKs in instrumented Projects.
_Avoid_: Uptime check

**Environment**:
A named deployment context within a Project from which telemetry originates, such as production, staging, or development.
_Avoid_: Stage

**Environment Name**:
The shared identity by which Environments in different Projects that carry the same name are grouped for cross-project filtering. The global environment filter selects Environment Names, not individual Environments, so choosing `production` matches every Project's `production` Environment at once.
_Avoid_: Environment (when referring to the cross-project name)

**Release**:
A named version of one Project, used to associate telemetry and debugging artifacts with the code that produced them.
_Avoid_: Deployment

### Ingestion and Access

**Project Key**:
A revocable public credential that allows an SDK to send telemetry to one Project. A Project may have multiple Project Keys.
_Avoid_: API token, secret

**DSN**:
The SDK connection identifier that combines Outpost's ingestion endpoint, a Project's identity, and a Project Key so telemetry is routed to the correct Project.
_Avoid_: API token, secret

**Sentry Compatibility**:
Outpost's ability to receive telemetry from Sentry SDKs and artifact uploads from `sentry-cli` through a supported subset of their protocols. It does not imply complete Sentry API or feature compatibility.
_Avoid_: Sentry clone, drop-in Sentry replacement

**API Token**:
A named secret credential used by automation to perform explicitly scoped Outpost API actions, such as uploading Artifacts.
_Avoid_: Project key, DSN

**Outpost User**:
A person with an account that can sign in to Outpost.
_Avoid_: User, application user

**Application User**:
An end user of an instrumented Project who is identified in an Event.
_Avoid_: User, Outpost user

**Admin**:
An Outpost User who can manage installation-wide resources and settings, including Projects, Project Keys, Uptime Monitors, API Tokens, Outpost Users, and data retention.
_Avoid_: Administrator

**Member**:
An Outpost User who can inspect telemetry and change Issue status but cannot manage installation-wide resources or settings.
_Avoid_: Read-only user

### Operations

**Data Retention Policy**:
The optional installation-wide limit on how long Outpost retains Events, Log Records, Transactions, and Spans. An Issue ceases to exist when none of its Events remain.
_Avoid_: Uptime retention

### Error Monitoring

**Issue**:
A Project-scoped group of error Events that Outpost considers occurrences of the same underlying failure.
_Avoid_: Bug, incident

**Event**:
One captured error or error-like occurrence belonging to exactly one Issue and one Project.
_Avoid_: Telemetry item, log

**Fingerprint**:
The grouping identity used to decide whether two Events belong to the same Issue within a Project.
_Avoid_: Signature, hash

**Culprit**:
Outpost's best available label for the transaction or code location most closely associated with an Issue.
_Avoid_: Root cause

**Attachment**:
A file sent alongside an Event to provide additional debugging context for that specific occurrence.
_Avoid_: Artifact

### Tracing and Logs

**Trace**:
A series of causally connected operations identified by one Trace ID, showing how an activity flows across one or more Projects. It is composed of Transactions and Spans and may correlate related Events and Log Records.
_Avoid_: Request (when the operation crosses more than one request)

**Transaction**:
A single instance of an activity a Project measures within a Trace, such as a page load, navigation, request, or asynchronous task. It may contain child Spans and need not be the root of the whole Trace.
_Avoid_: Database transaction, business transaction

**Span**:
An individual timed operation within a Trace, such as a database query, HTTP request, or UI rendering task. It records duration and may carry attributes that provide debugging context.
_Avoid_: Transaction

**Log Record**:
One timestamped logging occurrence emitted by a Project, containing a severity, message body, and optional attributes. It may be correlated with a Trace or Span.
_Avoid_: Log (for one occurrence), log entry

**Attribute**:
A named value attached to a Span or Log Record that adds operational context, such as an HTTP method, database operation, or cart value.
_Avoid_: Metadata

### Uptime Monitoring

**Uptime Monitor**:
A Project- and Environment-scoped configuration that periodically probes one URL to measure its availability and latency.
_Avoid_: Check, incident

**Uptime Check**:
The result of one probe performed by an Uptime Monitor, recording whether it succeeded, its latency, and any HTTP status or failure reason.
_Avoid_: Probe, incident

**Incident**:
A suspected service disruption associated with one Uptime Monitor, recognized after three consecutive failed Uptime Checks.
_Avoid_: Issue, outage

### Notifications

**Notification Channel**:
An Admin-configured destination that tells Outpost where and when to announce trigger occurrences, combining a delivery type, a destination URL, the triggers it fires on, and the Projects it covers.
_Avoid_: Alert, alert rule, webhook

**Notification**:
One message sent to one Notification Channel about one trigger occurrence, such as a new Issue or an Incident starting or resolving.
_Avoid_: Alert, webhook call, toast, user feedback message

### Source Reconstruction

**Artifact Bundle**:
An uploaded collection of source maps and minified source files for one Project Release, used to reconstruct original source locations in Events.
_Avoid_: Source map (for the whole bundle)

**Artifact**:
A source map or minified source file contained in an Artifact Bundle and used to reconstruct original source locations.
_Avoid_: Artifact bundle, file

**Debug ID**:
An identifier shared by a minified source file and its source map, allowing Outpost to match an Event's stack frames to the correct Artifacts independently of filenames.
_Avoid_: File name, release version

**Symbolication**:
The reconstruction of an Event's generated or minified stack frames into original source locations and function names using matching Artifacts.
_Avoid_: Deobfuscation
