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
# Both tests hold one test per lab flow. Every stub carries the flow that recorded it in
# its metadata, and a flow drops its own stubs before it records again, so one flow can be
# re-recorded on its own without spending an API call on the rest:
#
#   cd openai-mock-gen
#   OPENAI_API_KEY=sk-... ./mvnw test -Dtest='OpenAiRecordingTest#planAndExecute+OpenAiMockValidationTest#planAndExecute'
#
# and then ./generate-openai-mocks.sh --copy-only to push the result into the sample apps.
# Recording alone does not update them: the tests only write to openai-mock-gen.
#
# Requires OPENAI_API_KEY to be set (the recording test is skipped without it).
#
#   OPENAI_API_KEY=sk-... ./generate-openai-mocks.sh           # re-record every flow
#   OPENAI_API_KEY=sk-... ./generate-openai-mocks.sh --fresh    # throw the old fixtures away first
#   ./generate-openai-mocks.sh --copy-only                      # only push existing fixtures to the apps
#
set -euo pipefail

if [[ -z "${OPENAI_API_KEY:-}" && "${1:-}" != "--copy-only" ]]; then
  echo "Error: OPENAI_API_KEY is not set." >&2
  echo "Set it and re-run, e.g. OPENAI_API_KEY=sk-... ./generate-openai-mocks.sh" >&2
  exit 1
fi

# Resolve the repo root from this script's location so it works from any directory.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GEN_DIR="$ROOT_DIR/openai-mock-gen"
MOCK_SRC="$GEN_DIR/src/main/resources/mock"

# Every flow drops its own stubs before recording, so a full run replaces all of them. Use
# --fresh to also drop stubs of flows that no longer exist.
if [[ "${1:-}" == "--fresh" ]]; then
  echo "==> Removing the existing fixtures"
  rm -rf "$MOCK_SRC/mappings" "$MOCK_SRC/__files"
  mkdir -p "$MOCK_SRC/mappings" "$MOCK_SRC/__files"
fi

if [[ "${1:-}" == "--copy-only" ]]; then
  echo "==> Skipping the recording, copying the fixtures that are already there"
else
  echo "==> Recording and validating OpenAI mocks in openai-mock-gen"
  (cd "$GEN_DIR" && ./mvnw test)
fi

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
done < <(find "$ROOT_DIR/labs" -type d -path '*/sample-app*/src/main/resources' | sort)

echo "==> Done. Updated $count sample app(s)."
