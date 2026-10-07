#!/bin/sh
# kalku on public projects, each at a pinned commit: what the fixtures are
# too small to show, found without leaning on anybody's private code.
#
#   tools/field.sh [name...]
#
# Each project is cloned under the machine's temporary directory, never
# into this tree. Its own suite is run first, plainly; kalku is run only
# on a project whose suite passes here, because a suite that fails for
# want of a tool or a service says nothing about kalku. Then kalku
# measures the files the list names, in a project it was never set up in,
# and the project's tree is checked for anything written into it.
#
#   KALKU          the binary to try (default: the one built here)
#   KALKU_ELIXIR   the Elixir kalku to summon (default: this checkout's)
#   FIELD_DIR      where the clones go (default: $TMPDIR/kalku-field)
#   FIELD_LIMIT    how many wekufe a run casts (default: 20)
#
# Exits 1 when kalku failed on a project whose suite passes, or wrote in
# its tree.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
list="$root/tools/field/elixir.txt"
kalku=${KALKU:-$root/_build/kalku}
work=${FIELD_DIR:-${TMPDIR:-/tmp}/kalku-field}
limit=${FIELD_LIMIT:-20}
KALKU_ELIXIR=${KALKU_ELIXIR:-$root/adapters/elixir}
export KALKU_ELIXIR

[ -x "$kalku" ] || { echo "field: no kalku at $kalku; run \`make build\` or set KALKU" >&2; exit 2; }
mkdir -p "$work"
rm -f "$work/broke"

wanted() {
  [ $# -eq 0 ] && return 0
  name=$1; shift
  [ $# -eq 0 ] && return 0
  for w in "$@"; do [ "$w" = "$name" ] && return 0; done
  return 1
}

# One commit and nothing else: a pinned project is the same project on
# every machine, and a shallow fetch of it is all a run needs.
fetched() {
  dir=$1; url=$2; sha=$3
  if [ ! -d "$dir/.git" ]; then
    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" remote add origin "$url"
  fi
  if [ "$(git -C "$dir" rev-parse -q --verify HEAD 2>/dev/null || true)" != "$sha" ]; then
    git -C "$dir" fetch -q --depth 1 origin "$sha"
    git -C "$dir" checkout -q --detach FETCH_HEAD
  fi
}

seconds() { date +%s; }

printf '%-9s %-40s %8s %8s  %s\n' project "its own suite" plain kalku "what kalku said"

grep -v '^[[:space:]]*#' "$list" | grep -v '^[[:space:]]*$' | while read -r name url sha files; do
  wanted "$name" "$@" || continue
  dir="$work/$name"
  logs="$work/$name.logs"
  mkdir -p "$logs"

  fetched "$dir" "$url" "$sha"
  (cd "$dir" && mix deps.get >"$logs/deps.log" 2>&1) || {
    printf '%-9s %s\n' "$name" "skipped: its dependencies could not be fetched ($logs/deps.log)"
    continue
  }

  from=$(seconds)
  if (cd "$dir" && MIX_ENV="test" mix test >"$logs/plain.log" 2>&1); then plain=0; else plain=$?; fi
  plain_s=$(( $(seconds) - from ))
  suite=$(grep -E '^([0-9]+ (tests|doctests|properties)|Result: )' "$logs/plain.log" | tail -1 | sed 's/^Result: //' || true)

  if [ "$plain" -ne 0 ]; then
    printf '%-9s %-40s %7ss %8s  %s\n' "$name" "${suite:-did not run}" "$plain_s" - \
      "skipped: its own suite does not pass here ($logs/plain.log)"
    continue
  fi

  # A reni from an earlier try would hide what a first run costs.
  rm -rf "${TMPDIR:-/tmp}/kalku/$name"
  stamp="$logs/before"
  : >"$stamp"
  sleep 1

  from=$(seconds)
  # shellcheck disable=SC2086
  if (cd "$dir" && "$kalku" run $files --limit "$limit" >"$logs/kalku.out" 2>"$logs/kalku.err"); then
    status=0
  else
    status=$?
  fi
  kalku_s=$(( $(seconds) - from ))

  # Anything in the project newer than the moment before the run is
  # something the run wrote there, ignored by git or not.
  (cd "$dir" && find . -path ./.git -prune -o -type f -newer "$stamp" -print) >"$logs/written"
  written=$(wc -l <"$logs/written" | tr -d ' ')

  if [ "$status" -ge 2 ]; then
    said="FAILED ($status): $(grep '^kalku: ' "$logs/kalku.err" | grep -v 'no .kalku.toml' | tail -1)"
  else
    said=$(tail -1 "$logs/kalku.out")
  fi
  [ "$written" -eq 0 ] || said="$said; WROTE $written file(s) in the project ($logs/written)"

  printf '%-9s %-40s %7ss %7ss  %s\n' "$name" "$suite" "$plain_s" "$kalku_s" "$said"
  if [ "$status" -ge 2 ] || [ "$written" -ne 0 ]; then echo "$name" >>"$work/broke"; fi
done

if [ -s "$work/broke" ]; then
  rm -f "$work/broke"
  exit 1
fi
