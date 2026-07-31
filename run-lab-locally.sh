#!/usr/bin/env bash
#
# Run a single workshop lab locally with Educates.
#
# For the chosen lab this script will:
#   1. derive a local workshop definition from resources/apply/<workshop-id>.yaml
#      (the same manifest used for the real deployment), pointing the workshop
#      files at a local web server instead of the in-cluster files server
#   2. bundle the lab's content (sample-app, workshop, ...) into assets.tar
#   3. serve assets.tar on http://localhost:8082 (host.docker.internal:8082)
#   4. deploy the workshop with `educates docker workshop deploy`
#
# Usage:
#   ./run-lab-locally.sh <lab>        # e.g. ./run-lab-locally.sh 02-fundamentals
#   ./run-lab-locally.sh              # lists the available labs
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LABS_DIR="$ROOT_DIR/labs"
APPLY_DIR="$ROOT_DIR/resources/apply"
PORT="${PORT:-8082}"

list_labs() {
  echo "Available labs:"
  for d in "$LABS_DIR"/*/; do
    name="$(basename "$d")"
    [ -f "$d/WORKSHOP_ID" ] || continue
    id="$(cat "$d/WORKSHOP_ID")"
    if [ -f "$APPLY_DIR/$id.yaml" ]; then
      printf '  %-32s (%s)\n' "$name" "$id"
    else
      printf '  %-32s (%s) -- no resources/apply/%s.yaml, skipped\n' "$name" "$id" "$id"
    fi
  done
}

LAB="${1:-}"
if [ -z "$LAB" ]; then
  list_labs
  exit 0
fi

LAB_DIR="$LABS_DIR/$LAB"
if [ ! -d "$LAB_DIR" ]; then
  echo "Error: lab '$LAB' not found under labs/." >&2
  echo >&2
  list_labs >&2
  exit 1
fi

if [ ! -f "$LAB_DIR/WORKSHOP_ID" ]; then
  echo "Error: $LAB_DIR/WORKSHOP_ID is missing." >&2
  exit 1
fi
WORKSHOP_ID="$(cat "$LAB_DIR/WORKSHOP_ID")"

APPLY_YAML="$APPLY_DIR/$WORKSHOP_ID.yaml"
if [ ! -f "$APPLY_YAML" ]; then
  echo "Error: no apply manifest at resources/apply/$WORKSHOP_ID.yaml for lab '$LAB'." >&2
  exit 1
fi

# Directories shipped to the workshop session (everything but the local tooling).
CONTENT_DIRS=()
for d in "$LAB_DIR"/*/; do
  name="$(basename "$d")"
  [ "$name" = "local-resources" ] && continue
  CONTENT_DIRS+=("$name")
done
if [ ${#CONTENT_DIRS[@]} -eq 0 ]; then
  echo "Error: no content directories found in $LAB_DIR." >&2
  exit 1
fi

BUILD_DIR="$LAB_DIR/local-resources"
mkdir -p "$BUILD_DIR"
WORKSHOP_YAML="$BUILD_DIR/workshop.yaml"
ASSETS_TAR="$BUILD_DIR/assets.tar"

echo "==> Lab:         $LAB"
echo "==> Workshop id: $WORKSHOP_ID"
echo "==> Content:     ${CONTENT_DIRS[*]}"

# --- 1. Generate the local workshop definition from the apply manifest --------
echo "==> Generating $WORKSHOP_YAML from resources/apply/$WORKSHOP_ID.yaml"
ASSETS_URL="http://host.docker.internal:$PORT/assets.tar"
INCLUDE_PATHS="$(printf '%s\n' "${CONTENT_DIRS[@]}")" \
ASSETS_URL="$ASSETS_URL" \
python3 - "$APPLY_YAML" "$WORKSHOP_YAML" <<'PY'
import os, sys, yaml

src, dst = sys.argv[1], sys.argv[2]
with open(src) as f:
    doc = yaml.safe_load(f)

spec = doc.setdefault("spec", {})
name = doc.get("metadata", {}).get("name", "workshop")

# Publish the locally built session image to the local registry.
spec["publish"] = {"image": f"localhost:5001/{name}:latest"}

# Pull the workshop files from the local web server instead of the
# in-cluster files server.
include_paths = [f"/{p}/**" for p in os.environ["INCLUDE_PATHS"].split()]
spec.setdefault("workshop", {})["files"] = [
    {"http": {"url": os.environ["ASSETS_URL"], "includePaths": include_paths}}
]

# The in-cluster files Service/Ingress/Deployment are not needed locally.
spec.pop("environment", None)

# Drop the WEBSERVER env var (points at the in-cluster files server).
env = spec.get("session", {}).get("env")
if env:
    spec["session"]["env"] = [e for e in env if e.get("name") != "WEBSERVER"]

with open(dst, "w") as f:
    yaml.safe_dump(doc, f, sort_keys=False)
PY

# --- 2. Bundle the lab content into assets.tar --------------------------------
# Stage a copy so the originals stay untouched, then strip setup steps that are
# slow and unnecessary for local content testing (IDE extension install and
# offline dependency download).
STAGE_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGE_DIR"' EXIT
for d in "${CONTENT_DIRS[@]}"; do
  cp -R "$LAB_DIR/$d" "$STAGE_DIR/$d"
done
# Drop Maven build output so it does not bloat the bundle (rm -rf is a no-op
# when the target/ directory is absent).
for app in sample-app spring-releases-mcp-server; do
  rm -rf "$STAGE_DIR/$app/target"
done
while IFS= read -r -d '' f; do
  sed -i.bak \
    -e '/code-server --install-extension/d' \
    -e '/mvnw dependency:go-offline/d' \
    -e '/docker pull grafana\/otel-lgtm:latest/d' \
    -e '/docker pull quay.io\/keycloak\/keycloak:/d' \
    "$f"
  rm -f "$f.bak"
done < <(find "$STAGE_DIR" -path '*/workshop/setup.d/*.sh' -print0)

echo "==> Building $ASSETS_TAR"
COPYFILE_DISABLE=1 tar --exclude='.DS_Store' \
  -cf "$ASSETS_TAR" -C "$STAGE_DIR" "${CONTENT_DIRS[@]}"

# --- 3. Serve assets.tar ------------------------------------------------------
echo "==> Serving $BUILD_DIR on http://localhost:$PORT"
( cd "$BUILD_DIR" && exec python3 -m http.server "$PORT" ) &
SERVER_PID=$!
cleanup() {
  echo
  echo "==> Stopping web server (pid $SERVER_PID)"
  kill "$SERVER_PID" 2>/dev/null || true
  rm -rf "$STAGE_DIR"
}
trap cleanup EXIT INT TERM

# Wait for the server to accept connections.
for _ in $(seq 1 20); do
  if curl -sf -o /dev/null "http://localhost:$PORT/assets.tar"; then
    break
  fi
  sleep 0.25
done

# --- 4. Tear down any previous instance and deploy ----------------------------
echo "==> Removing any previous '$WORKSHOP_ID' containers and volumes"
ids="$(docker ps -a --format '{{.ID}} {{.Image}}' \
  | grep 'ghcr.io/educates/educates-jdk21-environment' \
  | awk '{print $1}' || true)"
[ -n "$ids" ] && docker rm -f -v $ids >/dev/null 2>&1 || true
vols="$(docker volume ls --format '{{.Name}}' \
  | grep "^educates-cli--$WORKSHOP_ID" || true)"
[ -n "$vols" ] && docker volume rm $vols >/dev/null 2>&1 || true

echo "==> Deploying with educates"
educates docker workshop deploy -f "$WORKSHOP_YAML"

echo
echo "==> '$WORKSHOP_ID' deployed. Web server still running so the session can"
echo "    refetch its files. Press Ctrl+C to stop the server."
wait "$SERVER_PID"
