#!/bin/sh
# kalku on public projects, each at a pinned commit: what the fixtures are
# too small to show, found without leaning on anybody's private code.
#
#   tools/field.sh [name...]
#
# Each project is cloned under the machine's temporary directory, never
# into this tree, or written there by `tools/field/generate.exs`. Its own
# suite is run first, plainly. Then kalku measures the files the list
# names, in a project it was never set up in, and the project's tree is
# checked for anything in it that is not as its own suite left it.
#
# A test that fails before anything is cast judges nothing, so kalku goes
# on without it and has to say so: a run that left tests out and did not
# name them would be giving the score of part of a suite as the suite's.
# That holds whether the test fails for everybody, for want of a tool or a
# service, or only under kalku, for where the build is.
#
#   KALKU          the binary to try (default: the one built here)
#   KALKU_ELIXIR   the Elixir kalku to summon (default: this checkout's)
#   FIELD_DIR      where the clones go (default: $TMPDIR/kalku-field)
#   FIELD_LIMIT    how many wekufe a run casts (default: 20)
#
# Exits 1 when kalku failed on a project, left tests out without naming
# them, or wrote in a project's tree.
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

# Every file of a project with a sum of what it holds, in an order two
# listings can be compared in. By what a file holds and not by when it was
# written: a project's own tests write files too, the same ones under
# `mix test` as under kalku, and writing a file again is not changing it.
held() {
  (cd "$1" && find . -path ./.git -prune -o -type f -exec cksum {} + |
    awk '{ sum = $1 "-" $2; $1 = ""; $2 = ""; sub(/^ +/, ""); print sum "\t" $0 }' | LC_ALL=C sort)
}

printf '%-9s %-40s %8s %8s  %s\n' project "its own suite" plain kalku "what kalku said"

grep -v '^[[:space:]]*#' "$list" | grep -v '^[[:space:]]*$' | while read -r name url sha files; do
  wanted "$name" "$@" || continue
  dir="$work/$name"
  logs="$work/$name.logs"
  mkdir -p "$logs"

  if [ "$url" = generated ]; then
    # What to generate is written where a commit would be, commas for spaces.
    # shellcheck disable=SC2046
    [ -f "$dir/mix.exs" ] ||
      elixir "$root/tools/field/generate.exs" "$dir" $(echo "$sha" | tr ',' ' ') 2>"$logs/generate.log"
  else
    fetched "$dir" "$url" "$sha"
  fi
  (cd "$dir" && mix deps.get >"$logs/deps.log" 2>&1) || {
    printf '%-9s %s\n' "$name" "skipped: its dependencies could not be fetched ($logs/deps.log)"
    continue
  }

  from=$(seconds)
  if (cd "$dir" && MIX_ENV="test" mix test >"$logs/plain.log" 2>&1); then plain=0; else plain=$?; fi
  plain_s=$(( $(seconds) - from ))
  suite=$(grep -E '^([0-9]+ (tests|doctests|properties)|Result: )' "$logs/plain.log" | tail -1 | sed 's/^Result: //' || true)

  # A reni from an earlier try would hide what a first run costs.
  rm -rf "${TMPDIR:-/tmp}/kalku/$name"
  held "$dir" >"$logs/before"

  from=$(seconds)
  # shellcheck disable=SC2086
  if (cd "$dir" && "$kalku" run $files --limit "$limit" >"$logs/kalku.out" 2>"$logs/kalku.err"); then
    status=0
  else
    status=$?
  fi
  kalku_s=$(( $(seconds) - from ))

  # A file that is not what the project's own suite left is something the
  # run wrote there, ignored by git or not.
  held "$dir" >"$logs/after"
  comm -13 "$logs/before" "$logs/after" | cut -f2- >"$logs/written"
  written=$(wc -l <"$logs/written" | tr -d ' ')

  score=$(grep -E '^(PARTIAL: .* · )?score ' "$logs/kalku.out" | tail -1 || true)
  named=$(grep -c '^left out, failing before anything was cast' "$logs/kalku.out" || true)
  refusal=$(grep '^kalku: the suite is already failing' "$logs/kalku.err" || true)
  bad=0
  if [ -n "$refusal" ]; then
    said="refused, nothing in its suite passing: ${refusal#kalku: }"
  elif [ -z "$score" ]; then
    said="FAILED ($status): $(grep '^kalku: ' "$logs/kalku.err" | grep -v 'no .kalku.toml' | tail -1)"
    bad=1
  elif [ "$plain" -ne 0 ] && [ "$named" -eq 0 ]; then
    said="MEASURED A SUITE THAT FAILS AND NAMED NO TEST LEFT OUT ($logs/plain.log): $score"
    bad=1
  else
    said=$score
  fi
  [ "$written" -eq 0 ] || said="$said; WROTE $written file(s) in the project ($logs/written)"

  printf '%-9s %-40s %7ss %7ss  %s\n' "$name" "${suite:-did not run}" "$plain_s" "$kalku_s" "$said"
  if [ "$bad" -ne 0 ] || [ "$written" -ne 0 ]; then echo "$name" >>"$work/broke"; fi
done

if [ -s "$work/broke" ]; then
  rm -f "$work/broke"
  exit 1
fi
