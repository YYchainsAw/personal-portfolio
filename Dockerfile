# syntax=docker/dockerfile:1.7

ARG NODE_IMAGE=node:22.18.0-bookworm-slim
ARG JAVA_BUILD_IMAGE=eclipse-temurin:17-jdk-jammy
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
ARG SOURCE_DATE_EPOCH=0
ARG UBUNTU_APT_SNAPSHOT=20260718T000000Z

FROM ${NODE_IMAGE} AS public-deps
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

FROM public-deps AS public-test
COPY frontend/ ./
RUN npm run test:export && npm run type-check

FROM public-deps AS public-build
COPY frontend/ ./
RUN npm run build

FROM ${NODE_IMAGE} AS admin-deps
WORKDIR /src/admin-web
COPY admin-web/package.json admin-web/package-lock.json ./
RUN npm ci

FROM admin-deps AS admin-test
COPY admin-web/ ./
RUN npm run test -- --run && npm run type-check

FROM admin-deps AS admin-build
COPY admin-web/ ./
RUN npm run build

FROM ${JAVA_BUILD_IMAGE} AS server-test
ARG SOURCE_DATE_EPOCH
WORKDIR /src/backend-parent
COPY backend-parent/ ./
RUN ./mvnw -B -Dproject.build.outputTimestamp="${SOURCE_DATE_EPOCH}" verify

FROM ${JAVA_BUILD_IMAGE} AS server-build
ARG SOURCE_DATE_EPOCH
WORKDIR /src/backend-parent
COPY backend-parent/ ./
COPY --from=public-build /src/frontend/dist/.vite/manifest.json \
  portfolio-server/src/main/resources/public-assets/.vite/manifest.json
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
RUN sed -i "s|^deb http|deb [snapshot=${UBUNTU_APT_SNAPSHOT}] http|" /etc/apt/sources.list \
    && grep -Eq "^deb \[snapshot=${UBUNTU_APT_SNAPSHOT}\] http" /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/* /var/cache/ldconfig/aux-cache \
      /var/cache/debconf/*-old /var/lib/dpkg/status-old /var/log/apt/* \
      /var/log/alternatives.log /var/log/dpkg.log \
    && printf 'portfolio:x:10001:\n' >> /etc/group \
    && printf 'portfolio:x:10001:10001:Portfolio runtime:/home/portfolio:/usr/sbin/nologin\n' >> /etc/passwd \
    && install -d -o 10001 -g 10001 -m 0750 /home/portfolio \
    && touch -h --date="@${SOURCE_DATE_EPOCH}" \
      /etc /etc/apt /etc/apt/sources.list /etc/group /etc/passwd \
      /home /home/portfolio \
      /var/cache /var/cache/apt /var/cache/debconf /var/cache/ldconfig \
      /var/lib/dpkg /var/log /var/log/apt
COPY --from=server-build --chown=portfolio:portfolio \
  /runtime-root/ /
WORKDIR /app
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/portfolio-server.jar"]
