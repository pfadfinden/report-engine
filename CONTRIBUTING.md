# Developing in this repository

This repo has two independently built modules:

- **`frontend/`** - TypeScript/Express web app (report selection UI, OIDC login).
- **`preprocessor/`** - Java/Maven CLI that compiles report definitions into the SQLite metadata DB the frontend reads.

Each has its own CI workflow (see [CI](#ci) below) that only runs when files under its module change.

## Prerequisites

- Docker and the Docker Compose plugin (`docker compose ...`, not the old standalone `docker-compose`)
- Node.js 22+ and npm, if you want to run the frontend outside of Docker
- JDK 21 and Maven, if you're working on the preprocessor

## Frontend

### Running it

OIDC login needs an identity provider to log in against - this repo doesn't bundle one, so point it
at your own Keycloak (or other OIDC-compliant provider) instance:

```sh
cp frontend/.env.example frontend/.env
# fill in OIDC_ISSUER_URL / OIDC_CLIENT_ID / (OIDC_CLIENT_SECRET if confidential) and AUTH_SESSION_SECRET
```

Then either run it in Docker:

```sh
docker compose up
```

This builds the frontend from its `dev` image stage (see [frontend/Dockerfile](frontend/Dockerfile)),
bind-mounts `frontend/src` into the container so `nodemon` restarts on every change, reads
`frontend/.env` for config, and starts it at http://localhost:3000.

Or run it directly on the host:

```sh
cd frontend
npm install
npm run dev   # hot-reloading dev server via nodemon + ts-node, reads .env via dotenv
```

### Before committing

```sh
npm run typecheck   # tsc --noEmit
npm run lint         # eslint
npm run format       # prettier --write
npm test
```

`npm run lint` and `npm run format:check` are also run in CI and will fail the build.

### Building the production image

```sh
cp frontend/.env.example frontend/.env   # real secrets - never commit this file
docker compose -f docker-compose.prod.yml up -d --build
```

This builds the `production` stage of `frontend/Dockerfile` (multi-stage: dependencies and TypeScript
build happen in a `builder` stage, only the compiled `dist/` and production dependencies ship in the
final image). The resulting container runs as a non-root user, with a read-only root filesystem, all
Linux capabilities dropped, and no dev tooling. It does not bundle a metadata DB - set
`REPORTS_DB_SOURCE=remote` (the default in `docker-compose.prod.yml`) and point it at a released
`reports.db`.

## Preprocessor (Java)

```sh
cd preprocessor
mvn -B package
```

See [preprocessor/README.md](preprocessor/README.md) for what it does and how it's used in CI to
compile report definitions.

## CI

- [.github/workflows/frontend-ci.yaml](.github/workflows/frontend-ci.yaml) - triggered by changes under
  `frontend/**`: typecheck, lint, test, build, and a production Docker image build.
- [.github/workflows/preprocessor-ci.yaml](.github/workflows/preprocessor-ci.yaml) - triggered by
  changes under `preprocessor/**` or the root `pom.xml`: Maven build.
- [.github/workflows/release.yaml](.github/workflows/release.yaml) - publishes the preprocessor JAR and
  its Docker image on pushes to `main` and version tags.
