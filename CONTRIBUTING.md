# Developing in this repository

This repo has five independently built modules:

- **`frontend/`** - TypeScript/Express web app (report selection UI, OIDC login).
- **`preprocessor/`** - Java/Maven CLI that compiles report definitions into the SQLite metadata DB the frontend reads.
- **`executor/`** - Java library wrapping JasperReports (fill/export), shared by the two executor deployments below.
- **`azure-report-executor/`** - Azure Functions deployment of the report-execution API (cloud, queue-backed).
- **`local-report-executor/`** - Docker/self-hosted deployment of the same report-execution API.

`frontend/` has its own CI workflow that only runs when files under it change; the four Java modules
share one workflow (see [CI](#ci) below) that runs whenever any of them, or the root `pom.xml`, change.

## Prerequisites

- Docker and the Docker Compose plugin (`docker compose ...`, not the old standalone `docker-compose`)
- Node.js 22+ and npm, if you want to run the frontend outside of Docker
- JDK 25 and Maven, if you're working on the preprocessor

## Frontend

### Running it

OIDC login needs an identity provider to log in against - this repo doesn't bundle one, so point it
at your own Keycloak (or other OIDC-compliant provider) instance:

```sh
cp frontend/.env.example frontend/.env
# fill in OIDC_ISSUER_URL / OIDC_CLIENT_ID / (OIDC_CLIENT_SECRET if confidential) and AUTH_SESSION_SECRET
```

`local-report-executor`'s Docker image copies in a pre-built jar rather than building it itself
(see [local-report-executor/Dockerfile](local-report-executor/Dockerfile)) - build it once via
the Maven reactor before the first `docker compose up` / `docker compose build`, and again after
changing `local-report-executor/` or `executor/`:

```sh
mvn -B -pl local-report-executor -am package
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
- [.github/workflows/java-ci.yaml](.github/workflows/java-ci.yaml) - triggered by changes under
  `executor/**`, `azure-report-executor/**`, `preprocessor/**`, `local-report-executor/**`, or the
  root `pom.xml`: Maven build (builds and tests all four Java modules).
- [.github/workflows/release.yaml](.github/workflows/release.yaml) - on pushes to `main` and version
  tags: publishes the preprocessor JAR as a release artefact (tag pushes only), and publishes Docker
  images for the preprocessor, `local-report-executor`, and the frontend's production image to GHCR.
  Images are tagged with the branch name (e.g. `main`) on every push; a version tag push (`vX.Y.Z`)
  additionally tags the image `X.Y.Z` and `latest` - `latest` is never produced from a plain branch
  push, only from an actual release tag.
