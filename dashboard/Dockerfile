# PgLens dashboard image — Next.js standalone output on Node 24 LTS (ADR-0046).
# Build context: dashboard/ (see deploy/compose/docker-compose.yml).

# 1. Dependencies, exactly as locked. pnpm's release-age and trust-policy gates apply here too
#    (pnpm-workspace.yaml); dependency install scripts stay off.
FROM node:24.21.0-alpine AS deps
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

# 2. The production build.
FROM node:24.21.0-alpine AS build
WORKDIR /app
RUN corepack enable
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN pnpm build

# 3. Runtime: only the traced server files and static assets — no pnpm, no dev dependencies.
#    Files stay root-owned (read-only to the app); it runs as a fixed non-root UID, like the server.
FROM node:24.21.0-alpine
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
USER 10001
EXPOSE 3000
CMD ["node", "server.js"]
