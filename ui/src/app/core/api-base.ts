/**
 * Root-absolute internal-API base derived from <base href> (set at build time
 * via `ng build --base-href`): '/api/internal' at base '/',
 * '/outpost/api/internal' at base '/outpost/'.
 */
export const API_BASE = new URL('api/internal', document.baseURI).pathname;
