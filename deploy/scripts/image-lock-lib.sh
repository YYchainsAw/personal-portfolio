#!/usr/bin/env bash

# Source-only parser for the release image lock. The caller supplies the name
# of an associative array that receives the validated, immutable references.
portfolio_load_image_lock() {
  local lock_file="${1:-}" output_name="${2:-}"
  local line key value required
  [[ -f "$lock_file" && ! -L "$lock_file" ]] || {
    printf 'image-lock: lock file must be a regular non-symlink file\n' >&2
    return 1
  }
  [[ "$output_name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || {
    printf 'image-lock: output array name is invalid\n' >&2
    return 1
  }
  # shellcheck disable=SC2178
  declare -n output="$output_name"
  output=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" != *$'\r'* ]] || {
      printf 'image-lock: lock file must use LF line endings\n' >&2
      return 1
    }
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" =~ ^([A-Z_]+)=(.+)$ ]] || {
      printf 'image-lock: invalid entry\n' >&2
      return 1
    }
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    case "$key" in
      NODE_IMAGE)
        [[ "$value" =~ ^node:22\.18\.0-bookworm-slim@sha256:[0-9a-f]{64}$ ]] || {
          printf 'image-lock: NODE_IMAGE must be exact Node 22.18.0 tag@sha256\n' >&2
          return 1
        } ;;
      PLAYWRIGHT_IMAGE)
        [[ "$value" =~ ^mcr\.microsoft\.com/playwright:v1\.58\.2-noble@sha256:[0-9a-f]{64}$ ]] || {
          printf 'image-lock: PLAYWRIGHT_IMAGE must be exact Playwright 1.58.2 tag@sha256\n' >&2
          return 1
        } ;;
      JAVA_BUILD_IMAGE)
        [[ "$value" =~ ^eclipse-temurin:17-jdk-jammy@sha256:[0-9a-f]{64}$ ]] || {
          printf 'image-lock: JAVA_BUILD_IMAGE must be exact Temurin 17 JDK tag@sha256\n' >&2
          return 1
        } ;;
      JAVA_RUNTIME_IMAGE)
        [[ "$value" =~ ^eclipse-temurin:17-jre-jammy@sha256:[0-9a-f]{64}$ ]] || {
          printf 'image-lock: JAVA_RUNTIME_IMAGE must be exact Temurin 17 JRE tag@sha256\n' >&2
          return 1
        } ;;
      POSTGRES_IMAGE)
        [[ "$value" =~ ^postgres:17-bookworm@sha256:[0-9a-f]{64}$ ]] || {
          printf 'image-lock: POSTGRES_IMAGE must be exact PostgreSQL 17 tag@sha256\n' >&2
          return 1
        } ;;
      UBUNTU_APT_SNAPSHOT)
        [[ "$value" =~ ^20[0-9]{6}T[0-9]{6}Z$ ]] || {
          printf 'image-lock: invalid Ubuntu APT snapshot identifier\n' >&2
          return 1
        }
        ;;
      TARGET_PLATFORM)
        [[ "$value" == linux/amd64 ]] || {
          printf 'image-lock: production target platform must be linux/amd64\n' >&2
          return 1
        }
        ;;
      *)
        printf 'image-lock: unknown key: %s\n' "$key" >&2
        return 1
        ;;
    esac
    [[ -z "${output[$key]+x}" ]] || {
      printf 'image-lock: duplicate key: %s\n' "$key" >&2
      return 1
    }
    output[$key]="$value"
  done <"$lock_file"

  for required in NODE_IMAGE PLAYWRIGHT_IMAGE JAVA_BUILD_IMAGE JAVA_RUNTIME_IMAGE \
      POSTGRES_IMAGE UBUNTU_APT_SNAPSHOT TARGET_PLATFORM; do
    [[ -n "${output[$required]:-}" ]] || {
      printf 'image-lock: missing key: %s\n' "$required" >&2
      return 1
    }
  done
}

portfolio_require_locked_image() {
  local recorded="${1:-}" locked="${2:-}"
  [[ "$locked" =~ @sha256:[0-9a-f]{64}$ && "$recorded" == "$locked" ]] || {
    printf 'image-lock: recorded image differs from the source-pinned digest\n' >&2
    return 1
  }
}

portfolio_require_release_image_lock() {
  local release_json="${1:-}" lock_name="${2:-}"
  [[ -f "$release_json" && ! -L "$release_json" ]] || {
    printf 'image-lock: release.json must be a regular non-symlink file\n' >&2
    return 1
  }
  [[ "$lock_name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || {
    printf 'image-lock: lock array name is invalid\n' >&2
    return 1
  }
  # shellcheck disable=SC2178
  declare -n lock="$lock_name"
  jq -e \
    --arg node "${lock[NODE_IMAGE]:-}" \
    --arg playwright "${lock[PLAYWRIGHT_IMAGE]:-}" \
    --arg java_build "${lock[JAVA_BUILD_IMAGE]:-}" \
    --arg java_runtime "${lock[JAVA_RUNTIME_IMAGE]:-}" \
    --arg postgres "${lock[POSTGRES_IMAGE]:-}" \
    --arg snapshot "${lock[UBUNTU_APT_SNAPSHOT]:-}" \
    --arg platform "${lock[TARGET_PLATFORM]:-}" '
      .buildInputs.nodeImageRef == $node and
      .buildInputs.playwrightImageRef == $playwright and
      .buildInputs.javaBuildImageRef == $java_build and
      .buildInputs.javaRuntimeImageRef == $java_runtime and
      .postgresImageRef == $postgres and
      .buildInputs.ubuntuAptSnapshot == $snapshot and
      .buildInputs.targetPlatform == $platform
    ' "$release_json" >/dev/null || {
      printf 'image-lock: release build inputs differ from the source-pinned image lock\n' >&2
      return 1
    }
}
