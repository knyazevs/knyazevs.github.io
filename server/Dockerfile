# syntax=docker/dockerfile:1.7
#
# Kotlin/Native build — produces a ~7 MB static-ish Linux binary (glibc).
# Build stage: Gradle + konan toolchain на JVM.
# Runtime:     debian:13-slim (trixie) — glibc 2.41, apt ставит недостающие
#              для K/N libcrypt1 + libgcc-s1 одной командой. ADR-021.
#
# Альтернатива — Dockerfile.jvm (fat JAR поверх eclipse-temurin).
#
# Ключевые оптимизации:
#   1. Слои разнесены: файлы Gradle копируются ДО src — правка кода не
#      инвалидирует dependency-resolution-слой.
#   2. BuildKit cache mounts для ~/.gradle и ~/.konan — при сборке в Actions
#      с `cache-to/from: type=gha,mode=max` konan-тулчейн (~250 MB) и
#      артефакты Kotlin-plugin переживают между запусками.

FROM gradle:8-jdk21 AS build
WORKDIR /app

# 1) Файлы сборки — кэш слоя инвалидируется только при их изменении.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ ./gradle/

# 2) Прогрев кэшей konan/Gradle. Скачивает konan-тулчейн и зависимости,
#    но НЕ компилирует src. Cache mounts переживут последующие сборки.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    gradle --no-daemon --console=plain \
      -Pkotlin.native.ignoreDisabledTargets=true \
      dependencies || true

# 3) Исходники — самый частый инвалидант.
COPY src ./src

# 4) Линковка релиза. Cache mounts дают тёплый старт linkRelease-задачи.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    gradle --no-daemon --console=plain \
      linkReleaseExecutableLinuxX64

# ── Runtime ──────────────────────────────────────────────────────────────────
# debian:13-slim (trixie) — glibc 2.41, libgcc-s1, libcrypt1 через apt.
# Отказались от distroless: K/N-бинарь тянул libcrypt1 и libgcc_s.1, и каждую
# пришлось бы подкладывать руками. apt закрывает это разом.
# Оверхед ~20 MB против distroless/cc — приемлемо ради устойчивости и наличия
# shell для отладки внутри контейнера.
FROM debian:13-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates \
      libcrypt1 \
      libgcc-s1 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/build/bin/linuxX64/releaseExecutable/knyazevs-server.kexe /app/knyazevs-server
# Ktor читает application.yaml из WORKDIR через YamlConfigLoader; файл должен
# быть рядом с бинарём. Env-substitution делает сам Ktor по синтаксису $VAR.
COPY application.yaml /app/application.yaml
# docs/ и репозиторий целиком монтируются как volumes в docker-compose.prod.yml —
# так индекс не вшит в образ и обновляется без пересборки.
EXPOSE 8080
ENTRYPOINT ["/app/knyazevs-server"]
