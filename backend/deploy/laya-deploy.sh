#!/usr/bin/env bash
# Pulls a branch and rebuilds. The GitHub deploy key is pinned to this script
# (forced command), so a leaked key can do nothing but redeploy a branch of this
# repo -- which is why the branch is validated here rather than trusted.
set -euo pipefail
SRC=/opt/laya-src
REPO=https://github.com/impin2rex/Laya.git

# The SSH client's command line is the branch to deploy, if it sent one.
BRANCH="${SSH_ORIGINAL_COMMAND:-main}"
BRANCH="${BRANCH:-main}"
# Anything but a plain branch name is refused: no flags, no refspecs, no URLs,
# no shell metacharacters reaching git.
if ! printf '%s' "$BRANCH" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._/-]{0,100}$'; then
  echo "refusing branch '$BRANCH'" >&2
  exit 1
fi

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$BRANCH" "$REPO" "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin "refs/heads/$BRANCH"
git -C "$SRC" reset --hard FETCH_HEAD
echo "deploying $BRANCH: $(git -C "$SRC" log -1 --format='%h %s')"
bash "$SRC/backend/deploy/setup.sh"
