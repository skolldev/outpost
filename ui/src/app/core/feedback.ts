import { Injectable } from '@angular/core';
import { toast } from '@spartan-ng/brain/sonner';

/**
 * The single seam for transient user feedback. Components emit success/error
 * messages through this service rather than calling sonner's `toast` directly,
 * so the dependency and its policy live in one place and specs can spy the
 * seam. See docs/adr/0007-ui-feedback-via-toast-seam.md.
 */
@Injectable({ providedIn: 'root' })
export class Feedback {
  /** Ephemeral confirmation of a completed action; auto-dismisses (~4s). */
  success(message: string): void {
    toast.success(message);
  }

  /** A failed action; persists longer (10s) so it is not missed. */
  error(message: string): void {
    toast.error(message, { duration: 10_000 });
  }
}
