# Publish Kata tasks to GitHub

Kata is the local task ledger. The
[exporter](../scripts/sync-kata-github.py) publishes its current issue state to
[the fork's GitHub issues](https://github.com/michaelnoguera/Tanks/issues).
Run it with Python 3, Kata, and an authenticated GitHub CLI:

```sh
gh auth login --hostname github.com
python3 scripts/sync-kata-github.py
python3 scripts/sync-kata-github.py --apply
```

The first script command previews changes. `--apply` creates or updates issues
in `michaelnoguera/Tanks`; `--repo owner/name` selects another repository.
Issues must be enabled in that repository. The script always reads the Kata
project bound to this checkout, regardless of the working directory.

All project issues are included, including closed tasks. Completed tasks remain
closed; superseded tasks use GitHub's `not_planned` closure reason. Relationships
become issue links in the body. Labels and existing Kata comments are preserved
as body text, with comments under an imported-history dropdown. No GitHub
assignees are added. Each local issue receives a `github.url` mapping.

This is an explicit one-way sync from Kata to GitHub. Rerunning it updates the
same issues using their Kata ID markers. Kata controls titles, open/closed state,
and the marked body section. Text outside that section and GitHub comments are
left intact. Do not remove the ID or section markers; a damaged existing mapping
stops synchronization for repair. Run only one exporter at a time.

There is no background job or GitHub-to-Kata import configured. Kata 0.17.2's
[native GitHub sync](https://github.com/kenn-io/kata/blob/v0.17.2/docs/operations/github-sync.md)
only imports GitHub issues and does not bind them to these existing local tasks.
Keep it disabled to avoid creating duplicate tasks. Run this exporter after
local task updates when you want to refresh GitHub.
