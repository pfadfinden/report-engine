# syntax=docker/dockerfile:1

FROM node:26-alpine AS builder
WORKDIR /usr/src/app

COPY package.json package-lock.json ./
# --ignore-scripts: native deps (better-sqlite3) ship prebuilt binaries for linux-musl and load
# those directly; running their install scripts would instead invoke node-gyp, which needs a
# C toolchain + Python this image doesn't have.
RUN npm ci --no-audit --no-fund --ignore-scripts

COPY tsconfig.json ./
COPY scripts ./scripts
COPY src ./src
RUN npm run build


# Used by ../docker-compose.yml for local development: source is bind-mounted in at
# runtime and nodemon restarts on change, so this stage never COPYs src itself.
# Not meant to be run standalone without that mount.
FROM node:26-alpine AS dev
ENV NODE_ENV=development
WORKDIR /usr/src/app

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund --ignore-scripts

# so `npm install` also works when run interactively against this container after adding a
# new dependency, not just the automated npm ci above
RUN chown -R node:node /usr/src/app
USER node
EXPOSE 3000
CMD ["npm", "run", "dev"]


# Hardened, minimal runtime image
FROM node:26-alpine AS production
ENV NODE_ENV=production
WORKDIR /usr/src/app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund --ignore-scripts && npm cache clean --force

COPY --from=builder /usr/src/app/dist ./dist

RUN chown -R node:node /usr/src/app
USER node

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD node -e "require('http').get({host:'localhost',port:process.env.PORT||3000,path:'/healthz'},r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"

CMD ["npm", "run", "serve"]
