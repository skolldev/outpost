import '@testing-library/jest-dom/vitest';
import { server } from './src/mocks/node';
import { beforeAll, afterEach, afterAll } from 'vitest';

// jsdom has no layout engine, so scrollIntoView is undefined. spartan's
// hlm-select scrolls the active option into view when its listbox opens; stub
// it to a no-op so those interactions don't crash under the test runner.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {};
}

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
