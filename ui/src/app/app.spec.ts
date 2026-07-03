import { render } from '@testing-library/angular';
import { App } from './app';

describe('App', () => {
  it('renders the router outlet shell', async () => {
    const { container } = await render(App);
    expect(container.querySelector('router-outlet')).not.toBeNull();
  });
});
