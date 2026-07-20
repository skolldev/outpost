import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';

import { adminGuard } from './admin.guard';
import { Session } from './session';

/** Runs the functional guard inside an injection context, like the router does. */
function invoke() {
  return TestBed.runInInjectionContext(() => adminGuard({} as never, {} as never)) as Promise<
    boolean | UrlTree
  >;
}

describe('adminGuard', () => {
  function setup(isAdmin: boolean) {
    const tree = {} as UrlTree;
    const router = { createUrlTree: vi.fn(() => tree) };
    const session = { ensureLoaded: vi.fn(async () => null), isAdmin: () => isAdmin };
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: router },
        { provide: Session, useValue: session },
      ],
    });
    return { tree, router, session };
  }

  it('allows admins through', async () => {
    const { session } = setup(true);
    await expect(invoke()).resolves.toBe(true);
    expect(session.ensureLoaded).toHaveBeenCalled();
  });

  it('redirects members to /issues', async () => {
    const { tree, router } = setup(false);
    await expect(invoke()).resolves.toBe(tree);
    expect(router.createUrlTree).toHaveBeenCalledWith(['/issues']);
  });
});
