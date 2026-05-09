# syntax=docker/dockerfile:1.6
#
# SvelteKit production image. Multi-stage so the runtime layer doesn't
# carry pnpm + the build toolchain. The build stage installs the pnpm
# workspace and builds the Node-adapter output; the runtime stage is
# a slim node:22-alpine that runs `node build`.
#
# adapter-node emits a self-contained app at apps/web/build/ — index.js
# bootstraps an HTTP server on $PORT (default 3000) that handles every
# route. No node_modules at runtime.

# ---- build ----------------------------------------------------------------
FROM node:22-alpine AS build

# Corepack ships with node:22 and pins pnpm to whatever package.json's
# `packageManager` field says (or to a default). We keep the install
# self-contained so build hosts don't need pnpm pre-installed.
RUN corepack enable

WORKDIR /repo

# Copy lockfile + workspace manifests FIRST so dependency installs cache
# independently of source changes.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/

# `--config.node-linker=hoisted` produces a flat node_modules. Vite's
# config loader transforms vite.config.ts to a temp `.mjs` and runs it
# under Node's resolver — under pnpm's default isolated/symlink linker
# inside a Docker workspace context, that resolver can't find vite's
# own modules. Hoisted node_modules sidesteps that. We pay a bit of
# duplication on disk for a build-time resolve that just works.
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --filter ./apps/web... --config.node-linker=hoisted

# Copy sources and build.
COPY apps/web/ apps/web/

RUN pnpm --filter ./apps/web run build

# ---- runtime --------------------------------------------------------------
FROM node:22-alpine AS runtime

RUN corepack enable

WORKDIR /app

# adapter-node ships build/ as the standalone server but leaves entries
# in `dependencies` as external imports (they get resolved from
# node_modules at runtime). Copy the build output plus enough manifest
# context to run an isolated prod-only install.
COPY --from=build /repo/apps/web/build ./build
COPY --from=build /repo/package.json /repo/pnpm-lock.yaml /repo/pnpm-workspace.yaml ./
COPY --from=build /repo/apps/web/package.json ./apps/web/

# Prod-only install — devDeps stay out of the runtime image. Hoisted
# linker again, same reason as the build stage: SvelteKit's runtime
# loader resolves modules through Node's algorithm and pnpm's
# isolated symlinks confuse it.
RUN --mount=type=cache,id=pnpm-store-runtime,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --prod --filter ./apps/web... --config.node-linker=hoisted

# Drop to non-root after the install — runtime never writes outside /tmp.
RUN addgroup -S kliniq && adduser -S -G kliniq kliniq && chown -R kliniq:kliniq /app
USER kliniq

EXPOSE 3000

ENV NODE_ENV=production
ENV PORT=3000
ENV HOST=0.0.0.0

# wget is in base node:22-alpine via busybox; the /healthz route lands
# on a SvelteKit endpoint that just returns 200.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD wget -q --spider http://localhost:3000/healthz || exit 1

CMD ["node", "build"]
