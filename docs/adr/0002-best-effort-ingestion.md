# Best-effort ingestion

Outpost uses a bounded in-memory queue for best-effort telemetry ingestion because it targets individuals and teams rather than very large telemetry volumes. A full queue applies backpressure to Sentry SDKs, while malformed or unprocessable items are dropped so ingestion remains healthy; Outpost accepts that acknowledged but unpersisted telemetry can be lost if the server stops, avoiding the deployment and operational complexity of a durable message broker.
