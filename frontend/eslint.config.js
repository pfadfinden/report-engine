const js = require('@eslint/js');
const tseslint = require('typescript-eslint');
const eslintConfigPrettier = require('eslint-config-prettier');
const globals = require('globals');

module.exports = tseslint.config(
  {
    ignores: ['dist/**', 'node_modules/**', 'coverage/**'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
    rules: {
      // unused function parameters are common in this codebase's port/adapter interfaces
      '@typescript-eslint/no-unused-vars': ['warn', { args: 'none' }],
      // this codebase consistently mixes `import` with CommonJS `require()` (express-generator
      // style modules, dynamic requires in bin/www.ts) - not worth fighting file by file
      '@typescript-eslint/no-require-imports': 'off',
      'no-var': 'off',
      // express error-handling middleware conventionally types `err` as `any`
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },
  // must stay last: turns off stylistic rules that would conflict with prettier
  eslintConfigPrettier,
);
