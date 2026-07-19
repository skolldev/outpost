# PostgreSQL as the sole durable dependency

Outpost stores configuration, telemetry, uptime history, and source artifacts in PostgreSQL so an Installation needs no durable message broker, search engine, time-series database, or object store. This deliberately trades extreme ingestion scale and specialized query capabilities for simpler on-premises deployment, operation, and backup.
