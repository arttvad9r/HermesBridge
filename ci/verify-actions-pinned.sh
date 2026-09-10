#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
workflow_dir="$repo_root/.github/workflows"

if [[ ! -d "$workflow_dir" ]]; then
  echo "Workflow directory not found: $workflow_dir" >&2
  exit 1
fi

status=0
found_external=0
while IFS= read -r uses_ref; do
  case "$uses_ref" in
    ./*|docker://*)
      continue
      ;;
  esac

  found_external=1
  if [[ ! "$uses_ref" =~ ^[^@[:space:]]+@[0-9a-f]{40}$ ]]; then
    echo "External GitHub Action is not pinned to a full commit SHA: $uses_ref" >&2
    status=1
  fi
done < <(
  grep -RhoE '^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+[^[:space:]#]+' "$workflow_dir" \
    | sed -E 's/^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+//' \
    || true
)

if [[ "$found_external" -eq 0 ]]; then
  echo "No external GitHub Actions found; verifier may no longer match workflow syntax." >&2
  exit 1
fi

exit "$status"
