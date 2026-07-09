import { render, screen } from '@testing-library/angular';

import { LevelBadge } from './level-badge';

async function renderBadge(level: string | null | undefined) {
  return render(LevelBadge, { inputs: { level } });
}

describe('LevelBadge', () => {
  it('renders the raw level text', async () => {
    await renderBadge('error');

    expect(screen.getByText('error')).toBeInTheDocument();
  });

  it.each([
    ['fatal', 'fatal'],
    ['critical', 'fatal'],
    ['error', 'error'],
    ['err', 'error'],
    ['warn', 'warn'],
    ['warning', 'warn'],
    ['info', 'info'],
    ['information', 'info'],
  ])('maps level %s onto the %s style bucket', async (level, bucket) => {
    const view = await renderBadge(level);

    expect(view.fixture.componentInstance.classes()).toContain(`var(--level-${bucket})`);
  });

  it('is case-insensitive when resolving the level bucket', async () => {
    const view = await renderBadge('ERROR');

    expect(view.fixture.componentInstance.classes()).toContain('var(--level-error)');
  });

  it.each([null, undefined, 'debug', 'trace', ''])(
    'falls back to the muted bucket for unrecognized level %s',
    async (level) => {
      const view = await renderBadge(level);

      expect(view.fixture.componentInstance.classes()).toContain('var(--level-muted)');
    },
  );

  it('applies the muted background style for a null level', async () => {
    const view = await renderBadge(null);

    expect(view.fixture.componentInstance.classes()).toContain('var(--level-muted-bg)');
  });
});