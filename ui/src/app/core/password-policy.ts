/**
 * Mirrors the server's `UserService.MIN_PASSWORD_LENGTH` — keep both in sync when either changes.
 */
export const MIN_PASSWORD_LENGTH = 8;

export const MIN_PASSWORD_LENGTH_MESSAGE = `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
