#!/usr/bin/env bash
# Pulls a branch and rebuilds. The GitHub deploy key is pinned to this script
# (forced command), so a leaked key can do nothing but redeploy a branch of this
# repo -- which is why the branch is validated here rather than trusted.
set -euo pipefail
SRC=/opt/laya-src
REPO=https://github.com/impin2rex/Laya.git

# The branch to deploy comes from the SSH client, two ways:
#   - as an argument, when the key may run commands and CI calls this directly;
#   - as SSH_ORIGINAL_COMMAND, when the key is pinned to this script by a forced
#     command and sshd hands the requested command over instead of running it.
# Taking the last word covers both, since CI sends "... laya-deploy <branch>".
BRANCH="${1:-}"
[ -n "$BRANCH" ] || BRANCH="${SSH_ORIGINAL_COMMAND##* }"
[ -n "$BRANCH" ] || BRANCH=main
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
