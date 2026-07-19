# Single-tenant installation

Each Outpost Installation is one trust and tenancy boundary: its Projects, Outpost Users, API Tokens, and settings are shared without an Organization or workspace layer. Sentry-compatible API paths may contain Organization segments, but Outpost does not use them for ownership or isolation; groups that require isolation deploy separate Installations, preserving the product's simple on-premises model instead of adding multi-tenant authorization and data partitioning.
