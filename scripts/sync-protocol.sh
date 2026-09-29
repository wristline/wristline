#!/usr/bin/env bash
# Copies the bridge's protocol fixtures (protocol/v1/*.json) into app/src/main/assets/protocol/,
# where ProtocolTest decodes them and demo mode reads them, and records their origin in VERSION.
#
# Usage: scripts/sync-protocol.sh [<bridge-dir>|<npm-version>]
#   no argument     ../wristline-bridge (sibling checkout)
#   <bridge-dir>    a wristline-bridge checkout
#   <npm-version>   a published version, e.g. 0.1.0 (fetched with npm pack)
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$repo/app/src/main/assets/protocol"
arg="${1:-$repo/../wristline-bridge}"

if [[ -d "$arg" ]]; then
  src="$(cd "$arg" && pwd)"
  fixtures="$src/protocol/v1"
  version="$(sed -n 's/^  "version": "\(.*\)",$/\1/p' "$src/package.json" | head -n 1)"
  origin="wristline-bridge ${version:-unknown} checkout"
  if git -C "$src" rev-parse --git-dir >/dev/null 2>&1; then
    commit="$(git -C "$src" log -1 --format=%h -- protocol/v1)"
    [[ -n "$(git -C "$src" status --porcelain -- protocol/v1)" ]] && commit="$commit+uncommitted"
    origin="$origin, git ${commit:-none}"
  fi
elif [[ "$arg" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+].*)?$ ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  (cd "$tmp" && npm pack --silent "wristline-bridge@$arg" >/dev/null)
  tar -xzf "$tmp"/wristline-bridge-*.tgz -C "$tmp"
  fixtures="$tmp/package/protocol/v1"
  origin="wristline-bridge $arg from npm"
else
  echo "error: '$arg' is neither a directory nor a version" >&2
  exit 2
fi

shopt -s nullglob
files=("$fixtures"/*.json)
if (( ${#files[@]} == 0 )); then
  echo "error: no fixtures in $fixtures" >&2
  exit 1
fi

mkdir -p "$dest"
rm -f "$dest"/*.json
cp "${files[@]}" "$dest/"
printf '%s\n' "$origin" > "$dest/VERSION"
echo "Copied ${#files[@]} fixtures: $origin"
