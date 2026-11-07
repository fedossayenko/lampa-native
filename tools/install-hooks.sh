#!/bin/bash
# Once per clone: link .githooks/* into .git/hooks (git's default hook folder; core.hooksPath stays untouched).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
for hook in .githooks/*; do
    ln -sf "../../$hook" ".git/hooks/$(basename "$hook")"
    echo "linked .git/hooks/$(basename "$hook") -> $hook"
done
