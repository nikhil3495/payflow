# Push not completed

Failing command (run from WSL, with the Windows gh as credential helper
`!/mnt/c/Users/nikhil/.gh-cli/bin/gh.exe auth git-credential`):

    git push -u origin main

Error:

    ! [remote rejected] main -> main (refusing to allow an OAuth App to create or update workflow
    `.github/workflows/ci.yml` without `workflow` scope)

Cause: `gh auth status` shows the active token has scopes gist, read:org, repo. The `workflow` scope was NOT
actually granted. `.github/workflows/ci.yml` was left untouched.

Fix (needs a browser, so it could not be done unattended), then push:

    gh auth refresh -h github.com -s workflow
    cd ~/payflow && git push -u origin main

The repository github.com/nikhil3495/payflow is still private and empty. All commits are local.
