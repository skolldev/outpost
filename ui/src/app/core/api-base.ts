/**
 * Root-absolute base for the internal API. The server serves this app and the
 * API from one origin at the host root, so the prefix is a constant — deploy
 * Outpost on its own subdomain rather than under a URL sub-path.
 */
export const API_BASE = '/api/internal';
