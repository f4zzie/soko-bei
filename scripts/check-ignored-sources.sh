#!/usr/bin/env bash
# Fails if a source file is matched by .gitignore (or silently dropped from the backend Docker build context by
# .dockerignore). Motivation: a test package named `build` was once ignored by an unanchored `build/` rule.
#
#   scripts/check-ignored-sources.sh              # git check; docker-context check only if docker is usable
#   REQUIRE_DOCKER=1 scripts/check-ignored-sources.sh   # CI: the docker-context check must run
set -euo pipefail
cd "$(dirname "$0")/.."

# Real Gradle/IDE output directories, pruned by exact path so package directories named "build" are still scanned.
PRUNE=( -path ./.git -o -path ./.gradle -o -path ./.kotlin -o -path ./.idea
        -o -path ./build -o -path ./shared/build -o -path ./backend/build -o -path ./android/build
        -o -path ./android/.cxx -o -path ./.last-test-results )

list_sources() {
  find . \( "${PRUNE[@]}" \) -prune -o -type f \( \
      -name '*.kt' -o -name '*.kts' -o -name '*.cql' -o -name '*.xml' -o -name '*.sh' -o -name '*.md' \
      -o -name '*.toml' -o -name '*.properties' -o -name '*.json' -o -name 'Dockerfile' -o -name '*.yml' \
      -o -name '*.yaml' -o -name '*.pro' -o -name '*.conf' -o -name 'gradlew' -o -name 'gradlew.bat' \
      -o -name '.gitignore' -o -name '.dockerignore' -o -name '.env.example' \) ! -name local.properties -print | sed 's#^\./##' | sort
}

status=0
mapfile -t sources < <(list_sources)
echo "Scanning ${#sources[@]} source files against .gitignore"
if [ "${#sources[@]}" -eq 0 ]; then echo "FAIL: found no source files; run from the repository" >&2; exit 2; fi

# --no-index: judge the ignore rules themselves, even for files that are already tracked.
if ignored=$(printf '%s\n' "${sources[@]}" | git check-ignore -v --no-index --stdin); then
  echo "FAIL: source files matched by .gitignore:" >&2
  echo "$ignored" >&2
  status=1
else
  echo "OK: no source file is ignored by .gitignore"
fi

# Backend image context: everything the Dockerfile COPYs must survive .dockerignore.
docker_ok=0
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then docker_ok=1; fi
if [ "$docker_ok" = 1 ]; then
  img="soko-ctx-check:$$"
  trap 'docker rmi -f "$img" >/dev/null 2>&1 || true' EXIT
  # Mirror the build stage of backend/Dockerfile: every `COPY <src>... <dest>` source goes to /ctx/<src>.
  mapfile -t copied < <(awk '/^FROM /{stage++} stage==1 && /^COPY / && $2 !~ /^--/ {for(i=2;i<NF;i++) print $i}' backend/Dockerfile)
  if [ "${#copied[@]}" -eq 0 ]; then echo "FAIL: no COPY lines found in backend/Dockerfile build stage" >&2; exit 2; fi
  { echo "FROM busybox"; for c in "${copied[@]}"; do echo "COPY $c /ctx/$c"; done; } | docker build -q -t "$img" -f - . >/dev/null
  in_image=$(docker run --rm "$img" find /ctx -type f | sed 's#^/ctx/##' | sort)
  pattern=$(printf '%s\n' "${copied[@]}" | sed 's#/*$##' | paste -sd'|')
  expected=$(printf '%s\n' "${sources[@]}" | grep -E "^(${pattern})(/|$)" | sort)
  missing=$(comm -23 <(printf '%s\n' "$expected") <(printf '%s\n' "$in_image") || true)
  if [ -n "$missing" ]; then
    echo "FAIL: source files dropped from the backend Docker context by .dockerignore:" >&2
    echo "$missing" >&2
    status=1
  else
    echo "OK: all $(printf '%s\n' "$expected" | wc -l | tr -d ' ') source files under (${copied[*]}) reach the backend Docker context"
  fi
elif [ "${REQUIRE_DOCKER:-0}" = 1 ]; then
  echo "FAIL: docker is required for the .dockerignore check but is not usable" >&2
  status=1
else
  echo "SKIP: docker not usable here, .dockerignore check not run (CI runs it)"
fi
exit $status
