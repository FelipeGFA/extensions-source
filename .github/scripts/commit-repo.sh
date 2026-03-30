#!/bin/bash
set -e

git config --global user.email "FelipeGFA@users.noreply.github.com"
git config --global user.name "FelipeGFA-bot"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push

    curl https://purge.jsdelivr.net/gh/FelipeGFA/extensoes@repo/index.min.json
else
    echo "No changes to commit"
fi
