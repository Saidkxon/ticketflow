#!/usr/bin/env bash
# Documentation checks run by CI (and by hand: bash scripts/check-docs.sh).
# Fails with a list of problems, or prints OK.
set -u
cd "$(git rev-parse --show-toplevel)"
errors=0
fail() { echo "ERROR: $*"; errors=$((errors + 1)); }

mapfile -t md_files < <(git ls-files '*.md')

# 1. No CR characters and a final newline in every tracked Markdown file
for f in "${md_files[@]}"; do
  if grep -q $'\r' "$f"; then fail "$f contains CR (Windows line endings)"; fi
  if [ -s "$f" ] && [ -n "$(tail -c1 "$f")" ]; then fail "$f has no final newline"; fi
done

# 2. Relative Markdown links must point to existing files
for f in "${md_files[@]}"; do
  dir=$(dirname "$f")
  while IFS= read -r link; do
    target="${link%%#*}"
    case "$target" in ''|http://*|https://*|mailto:*) continue ;; esac
    if [ ! -e "$dir/$target" ]; then fail "$f links to missing file: $link"; fi
  done < <(grep -o -E '\]\([^)]+\)' "$f" | sed -E 's/^\]\(//; s/\)$//')
done

# 3. ADR index: every ADR is listed, and each status matches the ADR header
index=docs/adr/README.md
for adr in docs/adr/[0-9][0-9][0-9][0-9]-*.md; do
  name=$(basename "$adr")
  case "$name" in 0000-template.md) continue ;; esac
  num=${name%%-*}
  row=$(grep -F "[$num]($name)" "$index" || true)
  if [ -z "$row" ]; then fail "$name is not listed in $index"; continue; fi
  header=$(grep -m1 -F '**Status**' "$adr" | sed -E 's/^\| \*\*Status\*\* \| //; s/ \|$//')
  index_status=$(echo "$row" | awk -F'|' '{gsub(/^ +| +$/, "", $4); print $4}')
  header_word=${header%% *}
  if [ "$header_word" != "${index_status%% *}" ]; then
    fail "$name: header says '$header' but index says '$index_status'"
  fi
done

# 4. References to ADRs must point to ADR files that exist
for f in "${md_files[@]}"; do
  for n in $(grep -o -E 'ADR-[0-9]{4}' "$f" | sort -u | sed 's/ADR-//'); do
    ls docs/adr/"$n"-*.md >/dev/null 2>&1 || fail "$f references ADR-$n, which does not exist"
  done
done

if [ "$errors" -gt 0 ]; then echo "$errors problem(s) found."; exit 1; fi
echo "OK: documentation checks passed (${#md_files[@]} Markdown files)."
