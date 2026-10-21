#!/usr/bin/env python3
"""pov-collector.py — Proof of Value over MEASURED Life Engine changes (KAN-1222).

For every workload of an environment, asks release-truth whether what runs is what git says, stage by stage
(P-16). When all five Proof of Value stages are PASS — MERGED (commit_sha, repository), BUILT (image_digest),
DEPLOYED (deployment_revision, effective_config_hash), RUNNING (health), ACCEPTED (functional_acceptance) — and
that (repository, commit) has no ValueEvent yet, it builds one: the artifact is the commit, its PR and the image
release-truth MEASURED; the acceptance is the release-truth report (its sha256 is the evidence); the
contributions come from the PR. This is the demo's mapping (scripts/demo/pov-e2e.sh) run on real changes.

  scripts/pov/pov-collector.py --dry-run                  print the ValueEvents it would record (no writes)
  scripts/pov/pov-collector.py                            record them (needs POV_TOKEN_FILE — a credentials gate)
  scripts/pov/pov-collector.py --workloads cryptobot,rag  only these workloads (default: the ALLOWLIST below)
  scripts/pov/pov-collector.py --max-anchors 3            at most N anchored events per UTC day (default 3)

Which workloads: an allowlist (cryptobot, command, personal-ops), not "everything that is PASS": release-truth can
pass a workload whose only acceptance is that the pod answers. ACCEPTED requires functional_acceptance=PASS.
Each anchor is a devnet transaction paid by the demo wallet, hence the daily cap; --dry-run says how many it would send.

Contribution UNITS are not measured here: the service splits 100 units per event by its fixed policy
(pov/equal-split/v1). The collector only names who took part, from data (trailers); it never invents shares.
Evidence is reproducible: the evidenceRef names the deploy/deploy commit release-truth ran from and the sha256 of its report.

Who contributed, honestly: on GitHub every PR is authored and merged by the owner's account (agents use it too),
so the author says nothing. The commits do: a `Co-Authored-By: Claude …` or `Agent: <role>` trailer marks an
agent IMPLEMENTER (identity `agent-<role>`, or `agent-claude-code`); without one the implementer is the owner.
The owner is the SPECIFIER (the one who asks for the change) and the REVIEWER (the one whose merge accepted it).

Read-only except the final POSTs: release-truth only reports, `gh`/`git` only read. Payouts (distribute) are
never called: they move funds. Exit: 0 ok · 1 a source failed · 2 no credential outside --dry-run (the gate).

Environment: DEPLOY_ROOT (release-truth's repo, default ~/Workspace/active/deploy/deploy) · WORKSPACE (the
workload source roots, default ~/Workspace/active) · POV_API_BASE (cryptobot) · POV_TOKEN_FILE (bearer token) ·
POV_OWNER (default sebas) · POV_PROJECT (default life-engine). Tests: POV_FIXTURES=<dir> replaces release-truth
(rt-<workload>.json), git (git-<workload>.json: {"slug","sha"}) and gh (pr-<sha7>.json, the `gh pr view` JSON).
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

HOME = os.path.expanduser("~")
DEPLOY_ROOT = os.environ.get("DEPLOY_ROOT", f"{HOME}/Workspace/active/deploy/deploy")
WORKSPACE = os.environ.get("WORKSPACE", f"{HOME}/Workspace/active")
OWNER = os.environ.get("POV_OWNER", "sebas")
PROJECT = os.environ.get("POV_PROJECT", "life-engine")
FIXTURES = os.environ.get("POV_FIXTURES")
ALLOWLIST = ["cryptobot", "command", "personal-ops"]

STAGES = [("MERGED", ["commit_sha", "repository"]), ("BUILT", ["image_digest"]),
          ("DEPLOYED", ["deployment_revision", "effective_config_hash"]), ("RUNNING", ["health"]),
          ("ACCEPTED", ["functional_acceptance"])]
AGENT_TRAILER = re.compile(r"^Agent:\s*([A-Za-z0-9._-]+)\s*$", re.M)
CLAUDE_TRAILER = re.compile(r"^Co-Authored-By:\s*Claude\b", re.M | re.I)


class SourceError(Exception):
    pass


def _run(cmd, cwd=None, timeout=120):
    try:
        out = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as ex:
        raise SourceError(f"{' '.join(cmd[:3])}: {ex}") from ex
    return out


def _fixture(name):
    path = os.path.join(FIXTURES, name)
    if not os.path.exists(path):
        raise SourceError(f"fixture missing: {name}")
    with open(path, "rb") as f:
        return f.read()


def registry(env):
    """{workload: sourceGitRoot} of one environment in release-truth's registry (its top-level `<env>:` block).
    A plain line reader, not a YAML parser: the file's shape is two-space workloads with four-space keys."""
    if FIXTURES:
        return json.loads(_fixture("workloads.json"))[env]
    path = os.path.join(DEPLOY_ROOT, "deployments", "release-truth-workloads.yml")
    out, inside, current = {}, False, None
    with open(path, encoding="utf-8") as f:
        for line in f:
            if re.match(r"^[A-Za-z0-9_-]+:\s*$", line):
                inside, current = line.split(":")[0] == env, None
            elif inside:
                m = re.match(r"^  ([A-Za-z0-9_.-]+):\s*$", line)
                if m:
                    current = m.group(1)
                    out[current] = None
                    continue
                m = re.match(r"^    sourceGitRoot:\s*(\S+)", line)
                if m and current:
                    out[current] = m.group(1)
    return out


def deploy_sha():
    """The deploy/deploy commit release-truth runs from (read-only: never checkout/pull there — it serves PROD)."""
    if FIXTURES:
        return json.loads(_fixture("deploy.json"))["sha"]
    out = _run(["git", "-C", DEPLOY_ROOT, "rev-parse", "HEAD"])
    if out.returncode:
        raise SourceError("deploy/deploy: no git HEAD")
    return out.stdout.strip()


def release_truth(env, workload):
    """(raw bytes, parsed) of `release-truth.sh <env> <workload> --json`; its exit code says FAIL/UNKNOWN, the JSON says which."""
    if FIXTURES:
        raw = _fixture(f"rt-{workload}.json")
    else:
        out = _run([os.path.join(DEPLOY_ROOT, "deployments", "scripts", "release-truth.sh"), env, workload, "--json"],
                   cwd=DEPLOY_ROOT, timeout=180)
        raw = out.stdout.encode()
    try:
        return raw, json.loads(raw)
    except json.JSONDecodeError as ex:
        raise SourceError(f"release-truth {env} {workload}: not JSON") from ex


def git_source(workload, short_sha, root):
    """GitHub slug and full sha of the measured commit, from the workload's source checkout (sourceGitRoot)."""
    if FIXTURES:
        return json.loads(_fixture(f"git-{workload}.json"))
    if not root:
        raise SourceError(f"{workload}: the registry declares no sourceGitRoot")
    cwd = os.path.join(WORKSPACE, root)
    sha = _run(["git", "rev-parse", "--verify", f"{short_sha}^{{commit}}"], cwd=cwd)
    url = _run(["git", "remote", "get-url", "origin"], cwd=cwd)
    if sha.returncode or url.returncode:
        raise SourceError(f"{workload}: commit {short_sha} not found in {cwd}")
    slug = re.sub(r"^.*github\.com[:/]|\.git$", "", url.stdout.strip())
    return {"slug": slug, "sha": sha.stdout.strip()}


def pull_request(slug, sha):
    """The merged PR whose merge commit (or one of whose commits) is `sha`, with its commit messages; None when none."""
    if FIXTURES:
        try:
            return json.loads(_fixture(f"pr-{sha[:7]}.json"))
        except SourceError:
            return None
    found = _run(["gh", "pr", "list", "-R", slug, "--state", "merged", "--search", sha, "--json", "number", "-q", ".[0].number"])
    number = found.stdout.strip()
    if found.returncode or not number:
        return None
    view = _run(["gh", "pr", "view", number, "-R", slug, "--json", "number,url,title,mergedAt,commits"])
    if view.returncode:
        raise SourceError(f"gh pr view {number} -R {slug}: {view.stderr.strip()[:200]}")
    return json.loads(view.stdout)


def stages_of(report):
    fields = report.get("fields") or {}
    verdicts = {stage: {n: (fields.get(n) or {}).get("verdict", "MISSING") for n in names} for stage, names in STAGES}
    return {stage: all(v == "PASS" for v in vs.values()) for stage, vs in verdicts.items()}, verdicts


def measured(report):
    fields = report.get("fields") or {}
    commit = re.findall(r"commit=([0-9a-f]{7,40})", (fields.get("commit_sha") or {}).get("note", ""))
    digest = re.findall(r"sha256:[0-9a-f]{64}", (fields.get("image_digest") or {}).get("note", ""))
    return (commit or [None])[0], (digest or [None])[0]


def contributions(pr):
    """SPECIFIER and REVIEWER: the owner. IMPLEMENTER: the agent the commit trailers name, else the owner."""
    messages = "\n".join((c.get("messageHeadline", "") + "\n" + c.get("messageBody", "")) for c in (pr or {}).get("commits", []))
    roles = sorted(set(AGENT_TRAILER.findall(messages)))
    if roles:
        implementers = [f"agent-{r.lower()}" for r in roles]
    elif CLAUDE_TRAILER.search(messages):
        implementers = ["agent-claude-code"]
    else:
        implementers = [OWNER]
    out = [{"identityId": OWNER, "role": "SPECIFIER"}]
    out += [{"identityId": i, "role": "IMPLEMENTER"} for i in implementers]
    out.append({"identityId": OWNER, "role": "REVIEWER"})
    return out


def value_event(env, workload, raw, report, src, pr, measured_with):
    stages, _ = stages_of(report)
    _, digest = measured(report)
    sha7 = src["sha"][:7]
    environment = env if env.endswith("-k8s") or not str(report.get("type", "")).startswith("k8s") else env + "-k8s"
    title = f"{workload} {sha7}" + (f" — PR #{pr['number']}" if pr else " — no PR found")
    artifact = {"commitSha": src["sha"]}
    if pr and pr.get("url"):
        artifact["prUrl"] = pr["url"]
    if digest:
        artifact["imageDigest"] = digest
    return {
        "projectId": PROJECT,
        "taskId": f"{workload}@{sha7}",
        "title": title[:200],
        "artifact": artifact,
        "acceptance": {
            "source": "release-truth",
            "environment": environment[:32],
            "stages": stages,
            "evidenceRef": f"release-truth@{measured_with[:7]} {env} {workload} sha256:{hashlib.sha256(raw).hexdigest()}",
            "acceptedAt": (report.get("evidence") or {}).get("observed_at"),
        },
        "contributions": contributions(pr),
    }


def collect(env, names, roots):
    """(events to record, skipped [(workload, reason)]): one event per (repository, commit), the first workload wins."""
    events, skipped, seen = [], [], set()
    measured_with = deploy_sha()
    for wl in names:
        try:
            raw, report = release_truth(env, wl)
        except SourceError as ex:
            skipped.append((wl, f"release-truth failed: {ex}"))
            continue
        stages, verdicts = stages_of(report)
        if not all(stages.values()):
            failing = ", ".join(f"{s}({' '.join(f'{k}={v}' for k, v in verdicts[s].items() if v != 'PASS')})"
                                for s, ok in stages.items() if not ok)
            skipped.append((wl, f"not accepted: {failing}"))
            continue
        commit, _ = measured(report)
        if not commit:
            skipped.append((wl, "release-truth measured no commit"))
            continue
        try:
            src = git_source(wl, commit, roots.get(wl))
            key = (src["slug"], src["sha"])
            if key in seen:
                skipped.append((wl, f"same change as an earlier workload ({src['slug']}@{src['sha'][:7]})"))
                continue
            seen.add(key)
            events.append(value_event(env, wl, raw, report, src, pull_request(src["slug"], src["sha"]), measured_with))
        except SourceError as ex:
            skipped.append((wl, str(ex)))
    return events, skipped


# ---- recording (behind the credentials gate) -----------------------------------------------------------------

def _api(method, path, token, body=None):
    base = os.environ.get("POV_API_BASE", "").rstrip("/")
    req = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as ex:
        return ex.code, None


def record(events, token, max_anchors, today):
    """Idempotent: a commit that already has a ValueEvent is not recorded again. At most `max_anchors` events
    (= devnet transactions) per UTC day, counting the ones already recorded today. Identities are created if missing."""
    status, existing = _api("GET", "/api/cryptobot/value-events?limit=200", token)
    if status != 200:
        raise SourceError(f"GET value-events → {status}")
    done = {((e.get("artifact") or {}).get("commitSha")) for e in (existing or [])}
    budget = max_anchors - sum(1 for e in (existing or []) if str(e.get("createdAt", "")).startswith(today)
                               and e.get("projectId") == PROJECT)
    recorded = []
    for ev in events:
        if ev["artifact"]["commitSha"] in done:
            continue
        if budget <= 0:
            print(f"pov-collector: daily anchor cap reached ({max_anchors}); {ev['taskId']} waits for tomorrow", file=sys.stderr)
            continue
        budget -= 1
        for c in ev["contributions"]:
            if _api("GET", f"/api/cryptobot/identities/{c['identityId']}", token)[0] == 404:
                agent = c["identityId"].startswith("agent-")
                _api("POST", "/api/cryptobot/identities", token, {
                    "id": c["identityId"], "kind": "AGENT" if agent else "HUMAN",
                    "displayName": c["identityId"], **({"ownerId": OWNER} if agent else {})})
        status, created = _api("POST", "/api/cryptobot/value-events?anchor=true", token, ev)
        if status not in (200, 201):
            raise SourceError(f"POST value-events {ev['taskId']} → {status}")
        recorded.append((ev["taskId"], (created or {}).get("id")))
    return recorded


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--env", default="uat")
    ap.add_argument("--workloads", help="comma list (default: every workload of --env)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--max-anchors", type=int, default=3)
    args = ap.parse_args()
    if args.max_anchors < 0:
        ap.error("--max-anchors must be >= 0")
    roots = registry(args.env)
    known = set(roots)
    names = [w for w in (args.workloads.split(",") if args.workloads else ALLOWLIST) if w]
    unknown = [w for w in names if w not in known]
    if unknown:
        ap.error(f"not in release-truth's {args.env} registry: {', '.join(unknown)}")
    events, skipped = collect(args.env, names, roots)
    if args.dry_run:
        print(json.dumps({"env": args.env, "events": events,
                          "skipped": [{"workload": w, "reason": r} for w, r in skipped],
                          "anchors": {"wouldSend": min(len(events), args.max_anchors), "dailyCap": args.max_anchors,
                                      "note": "upper bound: commits already recorded are skipped when recording"},
                          "units": "100 per event, split by the service's fixed policy pov/equal-split/v1 — not measured"},
                         ensure_ascii=False, indent=2))
        return 0
    token_file = os.environ.get("POV_TOKEN_FILE")
    if not token_file or not os.path.exists(token_file) or not os.environ.get("POV_API_BASE"):
        print("pov-collector: no credential (POV_TOKEN_FILE/POV_API_BASE) — recording is a credentials gate; use --dry-run",
              file=sys.stderr)
        return 2
    with open(token_file, encoding="utf-8") as f:
        token = f.read().strip()
    try:
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        for task, eid in record(events, token, args.max_anchors, today):
            print(f"recorded {task} → {eid}")
    except SourceError as ex:
        print(f"pov-collector: {ex}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
