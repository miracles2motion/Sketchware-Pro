#!/usr/bin/env python3
"""
Checks app/src/main/assets/openui/spec.json integrity and compares its
synced_commit against upstream thesysdev/openui commits touching ONLY
the language spec, ThemeProvider tokens, or genui-lib components
(ignoring web docs, Next.js website, and Node CI commits).

Usage:
  python3 scripts/check_openui_upstream.py [--offline]
"""

import json
import pathlib
import sys
import urllib.parse
import urllib.request

SPEC_PATH = pathlib.Path(__file__).resolve().parent.parent / (
    "app/src/main/assets/openui/spec.json"
)


def validate_local_spec(spec: dict) -> list[str]:
    errors: list[str] = []
    for key in (
        "spec_version",
        "upstream_repo",
        "synced_commit",
        "watched_paths",
        "neutral_scales",
        "semantic_status",
        "spacing_dp",
        "radius_dp",
        "corner_profiles",
        "density_profiles",
        "typography_sp",
        "text_roles",
        "components",
    ):
        if key not in spec:
            errors.append(f"Missing top-level key: {key}")
    for scale_name, shades in spec.get("neutral_scales", {}).items():
        for req_shade in ("25", "50", "100", "500", "900", "950", "1000"):
            if req_shade not in shades:
                errors.append(f"neutral_scales.{scale_name} missing shade {req_shade}")
    for prof_name, prof in spec.get("corner_profiles", {}).items():
        for k in ("card", "button", "input", "chip", "tag", "callout"):
            if k not in prof:
                errors.append(f"corner_profiles.{prof_name} missing {k}")
    return errors


def fetch_json(url: str):
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "SketchwarePro-OpenUI-SyncCheck/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    offline = "--offline" in sys.argv
    if not SPEC_PATH.is_file():
        print(f"ERROR: Spec file not found at {SPEC_PATH}", file=sys.stderr)
        return 1

    spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
    errors = validate_local_spec(spec)
    if errors:
        for e in errors:
            print(f"SPEC ERROR: {e}", file=sys.stderr)
        return 1

    repo = spec["upstream_repo"]
    synced = spec["synced_commit"]
    watched = spec["watched_paths"]
    print(
        f"Local spec OK: version={spec['spec_version']} "
        f"synced={synced[:10]} ({spec.get('synced_at', '')}) "
        f"components={len(spec['components'])}"
    )

    if offline:
        return 0

    try:
        synced_commit_data = fetch_json(
            f"https://api.github.com/repos/{repo}/commits/{synced}"
        )
        since_date = synced_commit_data["commit"]["committer"]["date"]
    except Exception as exc:
        print(f"WARNING: Could not reach GitHub API ({exc}); local spec is valid.")
        return 0

    updates: dict[str, list[tuple[str, str]]] = {}
    for path in watched:
        q = urllib.parse.urlencode({"path": path, "since": since_date, "per_page": 15})
        commits = fetch_json(f"https://api.github.com/repos/{repo}/commits?{q}")
        newer = [
            (c["sha"][:10], c["commit"]["message"].splitlines()[0])
            for c in commits
            if c["sha"] != synced
        ]
        if newer:
            updates[path] = newer

    if not updates:
        print(f"UP TO DATE with {repo} across all {len(watched)} watched paths.")
    else:
        print(f"Upstream {repo} has relevant spec/design-system commits since {synced[:10]}:")
        for path, items in updates.items():
            print(f"  [{path}]")
            for sha, msg in items:
                print(f"    - {sha}: {msg}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
