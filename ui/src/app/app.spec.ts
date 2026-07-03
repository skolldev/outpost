import { render, screen } from '@testing-library/angular';
import { App } from './app';

describe('App', () => {
  it('should render title', async () => {
    await render(App);
    expect(screen.getByText('Hello World')).toBeInTheDocument();
  });
});
