/**
 * The client-side mirror of the server's password rule
 * (`UserService.MIN_PASSWORD_LENGTH`, which both password-setting endpoints
 * validate against). Every form that sets a password — an Admin creating an
 * Outpost User, a user changing their own — validates against this, so the
 * rule moves in one place when the server's does.
 */
export const MIN_PASSWORD_LENGTH = 8;

/** Phrased once so both forms reject a short password in the same words. */
export const MIN_PASSWORD_LENGTH_MESSAGE = `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
