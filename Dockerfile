# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

ARG NODE_IMAGE=node:22.18.0-bookworm-slim@sha256:752ea8a2f758c34002a0461bd9f1cee4f9a3c36d48494586f60ffce1fc708e0e
ARG PLAYWRIGHT_IMAGE=mcr.microsoft.com/playwright:v1.58.2-noble@sha256:6446946a1d9fd62d9ae501312a2d76a43ee688542b21622056a372959b65d63d
ARG JAVA_BUILD_IMAGE=eclipse-temurin:17-jdk-jammy@sha256:723151f3fc88ca2060153ee08ab8dbbea7983d6ed6f2622fe440acf178737c94
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy@sha256:475d8e96b4b2bfe08999e5e854755c773af1581acdf959a4545d88f0696a2339
ARG SOURCE_DATE_EPOCH=0
ARG UBUNTU_APT_SNAPSHOT=20260718T000000Z

FROM ${NODE_IMAGE} AS node-toolchain

FROM node-toolchain AS public-deps
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

FROM public-deps AS public-test
COPY frontend/ ./
RUN npm run test:export && npm run test:unit && npm run type-check

FROM ${PLAYWRIGHT_IMAGE} AS public-e2e
COPY --from=node-toolchain /usr/local/ /usr/local/
ENV CI=1 \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
WORKDIR /src/frontend
COPY --from=public-deps /src/frontend/node_modules/ ./node_modules/
COPY frontend/ ./
RUN test "$(node --version)" = 'v22.18.0' \
    && test "$(npm --version)" = '10.9.3' \
    && test "$(npx --no-install playwright --version)" = 'Version 1.58.2'
RUN --network=none npm run test:e2e

FROM public-deps AS public-build
COPY frontend/ ./
RUN npm run build

FROM node-toolchain AS admin-deps
WORKDIR /src/admin-web
COPY admin-web/package.json admin-web/package-lock.json ./
RUN npm ci

FROM admin-deps AS admin-test
COPY admin-web/ ./
RUN npm run test -- --run && npm run type-check

FROM admin-deps AS admin-build
COPY admin-web/ ./
RUN npm run build

FROM ${JAVA_BUILD_IMAGE} AS java-build-base
ARG UBUNTU_APT_SNAPSHOT
RUN --mount=type=bind,source=docker/verify-ubuntu-apt-snapshot.sh,target=/usr/local/libexec/verify-ubuntu-apt-snapshot.sh,readonly \
    bash -euo pipefail -c '\
      verifier=/usr/local/libexec/verify-ubuntu-apt-snapshot.sh; \
      bash "$verifier" prepare "$UBUNTU_APT_SNAPSHOT" /etc/apt; \
      rm -rf /var/lib/apt/lists/*; \
      if ! apt-get -o Acquire::Check-Valid-Until=false update > /tmp/apt-update.log 2>&1; then \
        cat /tmp/apt-update.log >&2; exit 1; \
      fi; \
      cat /tmp/apt-update.log; \
      apt-get indextargets \
        --format "\$(SITE)|\$(RELEASE)|\$(IDENTIFIER)|\$(FILENAME)" \
        > /tmp/apt-index-targets; \
      bash "$verifier" verify "$UBUNTU_APT_SNAPSHOT" \
        /tmp/apt-update.log /tmp/apt-index-targets /etc/apt /var/lib/apt/lists; \
      apt-get install -y --no-install-recommends unzip; \
      rm -rf /var/lib/apt/lists/* /var/cache/apt/* \
        /tmp/apt-update.log /tmp/apt-index-targets; \
    '

# Release compliance is generated inside this fixed Ubuntu/JDK/Node/Python
# toolchain. OCI SBOMs are supplied as read-only evidence by the host-side,
# digest-pinned Syft gate, so this container has neither Docker nor its socket.
FROM java-build-base AS compliance-runner
ARG UBUNTU_APT_SNAPSHOT
RUN --mount=type=bind,source=docker/verify-ubuntu-apt-snapshot.sh,target=/usr/local/libexec/verify-ubuntu-apt-snapshot.sh,readonly \
    bash -euo pipefail -c '\
      verifier=/usr/local/libexec/verify-ubuntu-apt-snapshot.sh; \
      bash "$verifier" prepare "$UBUNTU_APT_SNAPSHOT" /etc/apt; \
      rm -rf /var/lib/apt/lists/*; \
      if ! apt-get -o Acquire::Check-Valid-Until=false update > /tmp/apt-update.log 2>&1; then \
        cat /tmp/apt-update.log >&2; exit 1; \
      fi; \
      cat /tmp/apt-update.log; \
      apt-get indextargets \
        --format "\$(SITE)|\$(RELEASE)|\$(IDENTIFIER)|\$(FILENAME)" \
        > /tmp/apt-index-targets; \
      bash "$verifier" verify "$UBUNTU_APT_SNAPSHOT" \
        /tmp/apt-update.log /tmp/apt-index-targets /etc/apt /var/lib/apt/lists; \
      apt-get install -y --no-install-recommends \
        gcc jq libc6-dev python3 python3-dev python3-venv zstd; \
      rm -rf /var/lib/apt/lists/* /var/cache/apt/* \
        /tmp/apt-update.log /tmp/apt-index-targets; \
    '
COPY --from=node-toolchain /usr/local/ /usr/local/
RUN node --version \
    && npm --version \
    && java -version \
    && /usr/bin/python3 -c 'import sys; assert sys.version_info[:2] == (3, 10)' \
    && ! command -v docker

FROM java-build-base AS server-test
ARG SOURCE_DATE_EPOCH
WORKDIR /src/backend-parent
COPY backend-parent/ ./
RUN ./mvnw -B -Dproject.build.outputTimestamp="${SOURCE_DATE_EPOCH}" verify

FROM java-build-base AS server-build
ARG SOURCE_DATE_EPOCH
WORKDIR /src/backend-parent
COPY backend-parent/ ./
COPY --from=public-build /src/frontend/dist/.vite/manifest.json \
  portfolio-server/src/main/resources/public-assets/.vite/manifest.json
COPY --from=public-build /src/frontend/dist/favicon.svg \
  portfolio-server/src/main/resources/static/favicon.svg
RUN ./mvnw -B -pl portfolio-server -am -DskipTests \
  -Dproject.build.outputTimestamp="${SOURCE_DATE_EPOCH}" package \
    && install -d -o 0 -g 0 -m 0755 /runtime-root/app \
    && install -o 0 -g 0 -m 0644 \
      portfolio-server/target/portfolio-server.jar \
      /runtime-root/app/portfolio-server.jar \
    && touch -h --date="@${SOURCE_DATE_EPOCH}" \
      /runtime-root /runtime-root/app /runtime-root/app/portfolio-server.jar

FROM scratch AS release-files
COPY --from=public-build /src/frontend/dist/ /public-assets/
COPY --from=admin-build /src/admin-web/dist/ /admin/

FROM ${JAVA_RUNTIME_IMAGE} AS runtime
ARG SOURCE_DATE_EPOCH
ARG UBUNTU_APT_SNAPSHOT
RUN --mount=type=bind,source=docker/verify-ubuntu-apt-snapshot.sh,target=/usr/local/libexec/verify-ubuntu-apt-snapshot.sh,readonly \
    bash -euo pipefail -c '\
      verifier=/usr/local/libexec/verify-ubuntu-apt-snapshot.sh; \
      bash "$verifier" prepare "$UBUNTU_APT_SNAPSHOT" /etc/apt; \
      rm -rf /var/lib/apt/lists/*; \
      if ! apt-get -o Acquire::Check-Valid-Until=false update > /tmp/apt-update.log 2>&1; then \
        cat /tmp/apt-update.log >&2; exit 1; \
      fi; \
      cat /tmp/apt-update.log; \
      apt-get indextargets \
        --format "\$(SITE)|\$(RELEASE)|\$(IDENTIFIER)|\$(FILENAME)" \
        > /tmp/apt-index-targets; \
      bash "$verifier" verify "$UBUNTU_APT_SNAPSHOT" \
        /tmp/apt-update.log /tmp/apt-index-targets /etc/apt /var/lib/apt/lists; \
      apt-get install -y --no-install-recommends curl; \
      rm -rf /var/lib/apt/lists/* /var/cache/apt/* /var/cache/ldconfig/aux-cache \
      /var/cache/debconf/*-old /var/lib/dpkg/status-old /var/log/apt/* \
      /var/log/alternatives.log /var/log/dpkg.log \
        /tmp/apt-update.log /tmp/apt-index-targets; \
      printf "portfolio:x:10001:\n" >> /etc/group; \
      printf "portfolio:x:10001:10001:Portfolio runtime:/home/portfolio:/usr/sbin/nologin\n" >> /etc/passwd; \
      install -d -o 10001 -g 10001 -m 0750 /home/portfolio; \
      touch -h --date="@${SOURCE_DATE_EPOCH}" \
      /etc /etc/apt /etc/apt/sources.list /etc/group /etc/passwd \
      /home /home/portfolio \
      /var/cache /var/cache/apt /var/cache/debconf /var/cache/ldconfig \
      /var/lib/dpkg /var/log /var/log/apt; \
    '
COPY --from=server-build --chown=portfolio:portfolio \
  /runtime-root/ /
WORKDIR /app
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/portfolio-server.jar"]
