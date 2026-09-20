#!/usr/bin/env python3
"""Publish this workspace's Kata issues to GitHub; preview unless --apply is set."""

import argparse
import json
import os
import re
import subprocess
from pathlib import Path


def command_json(arguments, payload=None):
    result = subprocess.run(
        arguments,
        input=None if payload is None else json.dumps(payload),
        text=True, capture_output=True, check=True,
    )
    return json.loads(result.stdout) if result.stdout.strip() else None


def github(path, method="GET", payload=None):
    arguments = ["gh", "api", path, "--method", method]
    if payload is not None:
        arguments += ["--input", "-"]
    return command_json(arguments, payload)


def list_github_issues(repo):
    issues = []
    page_number = 1
    while True:
        page = github(f"repos/{repo}/issues?state=all&per_page=100&page={page_number}")
        issues.extend(issue for issue in page if "pull_request" not in issue)
        if len(page) < 100:
            return issues
        page_number += 1


def marker(uid):
    return f"<!-- kata-issue:{uid} -->"


def state_reason(issue):
    return "completed" if issue.get("closed_reason") in ("done", "audit-no-change") else "not_planned"


def managed_body(entry, closes, remote_by_uid):
    issue = entry["issue"]
    lines = [marker(issue["uid"]), "<!-- kata-managed:start -->",
             f"Kata: `Tanks#{issue['short_id']}` · Status: **{issue['status']}**"]
    close = closes.get(issue["short_id"])
    if issue["status"] == "closed":
        lines += [f"Closure reason: `{issue.get('closed_reason', 'done')}`."]
        if close:
            lines += ["", close["message"]]
    lines += ["", issue.get("body") or "No description recorded in Kata."]
    labels = [label["label"] for label in entry.get("labels", [])]
    if labels:
        lines += ["", "Kata labels: " + ", ".join(f"`{label}`" for label in labels)]
    links = []
    for link in entry.get("links", []):
        outward = link["from"]["uid"] == issue["uid"]
        other = link["to"] if outward else link["from"]
        relation = link["type"]
        if not outward:
            relation = {"parent": "child", "blocks": "blocked by"}.get(relation, relation)
        remote = remote_by_uid.get(other["uid"])
        target = f"#{remote['number']}" if remote else f"`Tanks#{other['short_id']}`"
        links.append(f"- {relation}: {target} (`{other['short_id']}`)")
    if links:
        lines += ["", "Relationships:", "", *links]
    comments = entry.get("comments", [])
    if comments:
        lines += ["", "<details>", "<summary>Imported Kata history</summary>", ""]
        for comment in comments:
            lines += [f"**{comment['author']} — {comment['created_at']}**", "", comment["body"], ""]
        lines += ["</details>"]
    lines += ["", "<!-- kata-managed:end -->"]
    return "\n".join(lines) + "\n"


def merge_body(existing, generated):
    """Preserve GitHub text outside the section maintained by this exporter."""
    pattern = r"<!-- kata-issue:[A-Z0-9]+ -->\s*<!-- kata-managed:start -->.*?<!-- kata-managed:end -->\n?"
    updated, count = re.subn(pattern, lambda match: generated, existing, flags=re.DOTALL)
    if count != 1:
        raise ValueError("Expected exactly one managed Kata section; refusing to overwrite GitHub text")
    return updated


def main():
    os.chdir(Path(__file__).resolve().parents[1])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="michaelnoguera/Tanks", help="GitHub owner/repository")
    parser.add_argument("--apply", action="store_true", help="Create/update GitHub issues and local URL mappings")
    args = parser.parse_args()
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repo):
        parser.error("--repo must be owner/repository")

    project_issues = command_json(["kata", "list", "--status", "all", "--limit", "0", "--json"])["issues"]
    entries = [command_json(["kata", "show", issue["short_id"], "--json"])
               for issue in sorted(project_issues, key=lambda issue: issue["id"])]
    closes = {row["issue"]: row for row in command_json(["kata", "audit", "closes", "--json"])["rows"]}
    remote_by_uid = {}
    for remote in list_github_issues(args.repo):
        for uid in re.findall(r"<!-- kata-issue:([A-Z0-9]+) -->", remote.get("body") or ""):
            if uid in remote_by_uid:
                raise ValueError(f"Duplicate Kata marker {uid}; reconcile GitHub issues before syncing")
            remote_by_uid[uid] = remote

    for entry in entries:
        issue = entry["issue"]
        remote = remote_by_uid.get(issue["uid"])
        previous_url = issue.get("metadata", {}).get("github.url", "")
        if previous_url.startswith(f"https://github.com/{args.repo}/issues/"):
            if remote is None or remote["html_url"] != previous_url:
                raise ValueError(f"Mapping mismatch for {issue['short_id']}: restore its marker at {previous_url} before syncing")
        if remote is not None:
            merge_body(remote.get("body") or "", managed_body(entry, closes, remote_by_uid))

    created = 0
    for entry in entries:
        issue = entry["issue"]
        if issue["uid"] not in remote_by_uid:
            created += 1
            print(f"create {issue['short_id']}: {issue['title']}", flush=True)
            if args.apply:
                remote = github(f"repos/{args.repo}/issues", "POST", {
                    "title": issue["title"], "body": managed_body(entry, closes, remote_by_uid),
                })
                remote_by_uid[issue["uid"]] = remote
                if issue["status"] == "closed":
                    github(f"repos/{args.repo}/issues/{remote['number']}", "PATCH", {
                        "state": "closed", "state_reason": state_reason(issue),
                    })
                    remote["state"] = "closed"
                    remote["state_reason"] = state_reason(issue)

    changed = 0
    for entry in entries:
        issue = entry["issue"]
        remote = remote_by_uid.get(issue["uid"])
        if remote is None:
            continue
        generated = managed_body(entry, closes, remote_by_uid)
        body = merge_body(remote.get("body") or "", generated)
        payload = {"title": issue["title"], "body": body, "state": issue["status"]}
        if issue["status"] == "closed":
            payload["state_reason"] = state_reason(issue)
        if any(remote.get(key) != value for key, value in payload.items()):
            changed += 1
            print(f"update {issue['short_id']} -> #{remote['number']} ({issue['status']})", flush=True)
            if args.apply:
                github(f"repos/{args.repo}/issues/{remote['number']}", "PATCH", payload)
        if args.apply:
            key = "github.url"
            if issue.get("metadata", {}).get(key) != remote["html_url"]:
                subprocess.run(["kata", "meta", "set", issue["short_id"], key, remote["html_url"]],
                               check=True, capture_output=True, text=True)
    print(f"{'Applied' if args.apply else 'Previewed'} {len(entries)} Kata issues; {created} creations, {changed} updates.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.stderr or str(error)) from error
