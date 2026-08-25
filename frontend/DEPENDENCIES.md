# Dependencies

Which depndencies are included why?

| Dependency | Reason |
| --- | --- |
| jest | Unit Tests, industry standard |
| ts-jest | Typescript support for Unit Tests |
| sqlite3 | Sqlite Driver, Standard, use case did not require an ORM |
| ts-node | Typescript Compiler, Standard |
| express | Lightweight, industry standard, non-intrusive application routing and middleware framework
| http-errors | for express, recommended by express-application-generator
| morgan | for express, recommended by express-application-generator
| pug | Template Engine, recommended by express-application-generator
| tsconfig-paths | Path resolution @src for development
| nodemon | Watches for file changes during development
| dotenv | Loads a `.env` file into `process.env` for local development, so config/secrets can be injected via environment variables instead of hardcoded in source; deployed environments set real env vars/secrets and don't need a `.env` file
| express-session | Session cookie handling for the OIDC login flow
| openid-client | Standards-compliant OpenID Connect / OAuth 2.0 client (Authorization Code + PKCE); works against any compliant provider, not tied to a specific vendor. v6 is ESM-only - it's loaded here via Node's native `require(esm)` interop rather than migrating this (otherwise CommonJS) project to ESM, which is why `engines.node` requires `^20.19.0 \|\| >=22.12.0`
| eslint / typescript-eslint / @eslint/js | Static analysis / code quality checks
| eslint-config-prettier | Turns off ESLint stylistic rules that would conflict with Prettier
| prettier | Code formatting, kept separate from lint (correctness) concerns