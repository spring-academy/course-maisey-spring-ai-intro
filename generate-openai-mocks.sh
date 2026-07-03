#!/usr/bin/env bash
#
# Regenerates the OpenAI WireMock fixtures used by the workshop sample apps.
#
# It runs the tests in openai-mock-gen. OpenAiRecordingTest (@Order(1)) proxies the
# chat flows to the real OpenAI API and records the interactions into
# openai-mock-gen/src/main/resources/mock, then OpenAiMockValidationTest (@Order(2))
# replays them through the mock server to make sure they still match. Finally the
# generated mock/ folder is copied into every lab sample app.
#
# Requires OPENAI_API_KEY to be set (the recording test is skipped without it).
#
#   OPENAI_API_KEY=sk-... ./generate-openai-mocks.sh
#
set -euo pipefail

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "Error: OPENAI_API_KEY is not set." >&2
  echo "Set it and re-run, e.g. OPENAI_API_KEY=sk-... ./generate-openai-mocks.sh" >&2
  exit 1
fi

# Resolve the repo root from this script's location so it works from any directory.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GEN_DIR="$ROOT_DIR/openai-mock-gen"
MOCK_SRC="$GEN_DIR/src/main/resources/mock"

echo "==> Recording and validating OpenAI mocks in openai-mock-gen"
(cd "$GEN_DIR" && ./mvnw test)

if [[ ! -d "$MOCK_SRC" ]]; then
  echo "Error: expected generated fixtures at $MOCK_SRC but none were found." >&2
  exit 1
fi

echo "==> Copying fixtures into every sample app"
count=0
while IFS= read -r resources_dir; do
  target="$resources_dir/mock"
  rm -rf "$target"
  mkdir -p "$target"
  cp -R "$MOCK_SRC/." "$target/"
  echo "    -> ${target#"$ROOT_DIR/"}"
  count=$((count + 1))
done < <(find "$ROOT_DIR/labs" -type d -path '*/sample-app/src/main/resources' | sort)

echo "==> Done. Updated $count sample app(s)."
