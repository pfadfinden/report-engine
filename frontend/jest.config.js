/** @type {import('jest').Config} */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  // Playwright's own tests (e2e/*.spec.ts, run via "npm run test:e2e") must not be picked up
  // here - Playwright explicitly refuses to run a test defined while executing inside Jest.
  testPathIgnorePatterns: ['/node_modules/', '<rootDir>/e2e/'],
  // Only active with --coverage (see package.json's "test:coverage") - collectCoverage stays off
  // by default so a plain "npm test" isn't slowed down by instrumentation on every run.
  collectCoverageFrom: ['src/**/*.ts', '!src/**/*.test.ts', '!src/public/**', '!src/templates/**'],
  coverageDirectory: 'coverage',
  coverageReporters: ['html', 'text-summary', 'lcov'],
};
