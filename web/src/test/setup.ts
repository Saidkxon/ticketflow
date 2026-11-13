import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Testing Library only cleans up by itself when test globals are on; we keep them off, so do it here.
afterEach(() => {
  cleanup();
});
