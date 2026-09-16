import { toast } from '@spartan-ng/brain/sonner';

import { Feedback } from './feedback';

// Spies sonner's `toast` directly to pin the lifetimes Feedback owns; other specs spy the Feedback seam.
describe('Feedback', () => {
  it('emits a success toast with sonner’s default lifetime', () => {
    const success = vi.spyOn(toast, 'success').mockReturnValue(0);

    new Feedback().success('Saved.');

    expect(success).toHaveBeenCalledWith('Saved.');
    success.mockRestore();
  });

  it('emits an error toast that persists for 10s', () => {
    const error = vi.spyOn(toast, 'error').mockReturnValue(0);

    new Feedback().error('Failed.');

    expect(error).toHaveBeenCalledWith('Failed.', { duration: 10_000 });
    error.mockRestore();
  });
});
