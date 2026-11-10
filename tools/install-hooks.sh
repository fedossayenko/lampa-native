#!/bin/bash
# Once per clone: link .githooks/* into git's hooks folder (core.hooksPath stays untouched). In a worktree .git is a
# file and the hooks are the main checkout's, so that is where they are linked from.
set -euo pipefail
common=$(cd "$(git rev-parse --git-common-dir)" && pwd)
for hook in "$(dirname "$common")"/.githooks/*; do
    ln -sf "$hook" "$common/hooks/$(basename "$hook")"
    echo "linked $common/hooks/$(basename "$hook") -> $hook"
done
