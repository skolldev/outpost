# Single-instance deployment

Outpost deliberately supports one API-server instance. It targets individuals and teams that want to deploy on-premises observability quickly while retaining Sentry SDK compatibility; supporting multiple replicas would require distributed coordination for ingestion workers, schedulers, and live-log delivery, so Outpost accepts the lack of horizontal scaling and server high availability in exchange for lower operational complexity.
