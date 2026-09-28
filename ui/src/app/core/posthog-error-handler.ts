import { HttpErrorResponse } from '@angular/common/http';
import { ErrorHandler, Injectable, Provider, inject } from '@angular/core';
import { PostHogService } from './posthog.service';

@Injectable({ providedIn: 'root' })
class PostHogErrorHandler extends ErrorHandler {
  private readonly posthogService = inject(PostHogService);

  override handleError(error: unknown): void {
    const extractedError = extractError(error);

    if (extractedError) {
      this.posthogService.posthog.captureException(extractedError);
    }

    super.handleError(error);
  }
}

function extractError(errorCandidate: unknown): Error | string | null {
  const error = unwrapZoneError(errorCandidate);

  if (error instanceof HttpErrorResponse) {
    return extractHttpError(error);
  }

  return typeof error === 'string' || isErrorLike(error) ? error : null;
}

function unwrapZoneError(error: unknown): unknown {
  if (error && typeof error === 'object' && 'ngOriginalError' in error) {
    return (error as { ngOriginalError: unknown }).ngOriginalError;
  }

  return error;
}

function extractHttpError(error: HttpErrorResponse): Error | string {
  if (isErrorLike(error.error)) {
    return error.error;
  }

  if (
    typeof ErrorEvent !== 'undefined' &&
    error.error instanceof ErrorEvent &&
    error.error.message
  ) {
    return error.error.message;
  }

  return error.message;
}

function isErrorLike(value: unknown): value is Error {
  if (value instanceof Error) {
    return true;
  }

  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    'name' in value &&
    'message' in value &&
    'stack' in value
  );
}

export function providePostHogErrorHandler(): Provider {
  return {
    provide: ErrorHandler,
    useClass: PostHogErrorHandler,
  };
}
