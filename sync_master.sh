git pull --rebase --ff-only
git fetch upstream master
git pull --rebase upstream master
git push
git fetch --tags upstream
git push --tags