#!/bin/sh
# Quality gate for the kaikai side, per .claude/rules/kaikai-code.md.
# FAIL lines break the build; WARN lines flag files drifting from the target.
set -eu

for tool in km jq; do
  command -v "$tool" >/dev/null || { echo "km-gate: $tool not found" >&2; exit 2; }
done

SCORE_FLOOR=80        # B
SCORE_TARGET=87       # A-
LOC_CAP=800
LOC_TARGET=400
COGCOM_FN_CAP=25
COGCOM_AVG_TARGET=5

scope() { km "$1" . --include-ext kai --exclude-dir adapters --exclude-dir tests --format json; }

findings=$(
  scope score --bottom 100000 | jq -r --argjson floor "$SCORE_FLOOR" --argjson target "$SCORE_TARGET" '
    .needs_attention[]
    | if .score < $floor then "FAIL  \(.path)  km score \(.grade) below B"
      elif .score < $target then "WARN  \(.path)  km score \(.grade) below A-"
      else empty end'

  scope indent | jq -r --argjson cap "$LOC_CAP" --argjson target "$LOC_TARGET" '
    .[]
    | if .code_lines > $cap then "FAIL  \(.path)  \(.code_lines) LOC above \($cap)"
      elif .code_lines > $target then "WARN  \(.path)  \(.code_lines) LOC above \($target)"
      else empty end'

  scope cogcom | jq -r --argjson cap "$COGCOM_FN_CAP" --argjson avg "$COGCOM_AVG_TARGET" '
    .[] | .path as $p
    | (.functions[] | select(.complexity > $cap)
       | "FAIL  \($p)  fn \(.name) cognitive complexity \(.complexity) above \($cap)"),
      (select(.avg_complexity > $avg)
       | "WARN  \($p)  average cognitive complexity \(.avg_complexity) above \($avg)")'

  scope dups | jq -r '
    .metrics.duplicate_groups
    | if . > 0 then "FAIL  (project)  \(.) duplicate group(s): run km dups" else empty end'
)

[ -n "$findings" ] && printf '%s\n' "$findings"
if printf '%s\n' "$findings" | grep -q '^FAIL'; then
  echo "km-gate: failed"
  exit 1
fi
echo "km-gate: ok"
