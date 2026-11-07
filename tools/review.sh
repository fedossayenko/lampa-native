#!/bin/bash
# Claude Code review, run by hand (minutes, so not in a hook).
#   tools/review.sh                  the commits not on origin/main yet
#   tools/review.sh <base> [<head>]  any range
#   tools/review.sh --deep           the whole app: built from scratch today, would a different approach be
#                                    lighter or simpler? (run at the end of each PLAN.md step)
# Exit 1 on VERDICT: BLOCK. Read-only: only Read/Grep/Glob, no MCP servers, no user settings or hooks.
set -euo pipefail
cd "$(dirname "$0")/.."
claude_ro() {
    claude -p --tools "Read,Grep,Glob" --strict-mcp-config --setting-sources project --output-format text "$@"
}
if [ "${1:-}" = --deep ]; then
    report=$(claude_ro "$(cat tools/review-deep-prompt.md)")
else
    diff=$(git diff "${1:-origin/main}...${2:-HEAD}") # three dots: from where the branches split
    if [ -z "$diff" ]; then
        echo "nothing to review"
        exit 0
    fi
    report=$(printf '%s\n' "$diff" | claude_ro "$(cat tools/review-prompt.md)")
fi
printf '%s\n' "$report"
tail -n 3 <<< "$report" | grep -qx 'VERDICT: PASS'
