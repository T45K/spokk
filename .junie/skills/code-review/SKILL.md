---
name: code-review
description: "Review the current diff for correctness bugs and reuse/simplification/efficiency cleanups at the given effort level (low/medium: fewer, high-confidence findings; high/max: broader coverage, may include uncertain findings). Pass --comment to post findings as inline PR comments, or --fix to apply the findings to the working tree after the review. TRIGGER when: user asks to review a diff, PR, or branch; user invokes /code-review."
---

# Code Review

Adapted from Claude Code's built-in `/code-review` skill. Fans out N independent finder passes via the **`code-review-finder`** subagent (one invocation per angle), dedups, then runs one verifier pass per candidate via the **`code-review-verifier`** subagent.

Subagents live in `.junie/agents/code-review-finder.md` and `.junie/agents/code-review-verifier.md` and are auto-dispatched by Junie when this skill requests them. Each finder is read-only (no Edit/Write) — only the orchestrator (or the `--fix` stage of `/simplify`) writes to the tree.

## Argument parsing

Argument shape: `[low|medium|high|max] [--fix] [--comment] [<target>]`

- First positional token chooses the **effort level**. Accept prefix matches (`low`, `med`, `hi`, `xhigh`, `max`). Default to **medium** when unspecified.
- `--fix` — after producing the findings list, apply them to the working tree (see "Applying fixes" section below).
- `--comment` — after producing the findings list, post them as inline PR comments (see "Posting to GitHub" section below).
- Remaining tokens form the **review target** (a PR number, branch name, or file path). If absent, review the current diff.

Print a one-line header before starting:
```
(Running a {level}-effort review{ and applying its findings|}.)
```

## Phase 0 — Gather the diff

Run `git diff @{upstream}...HEAD` (or `git diff main...HEAD` / `git diff HEAD~1`
if there's no upstream) to get the unified diff under review. If there are
uncommitted changes, or the range diff is empty, also run `git diff HEAD` and
include the working-tree changes in scope — the review often runs before the
commit. If a PR number, branch name, or file path was passed as an argument,
review that target instead. Treat this diff as the review scope.

---

## Level: low

`low effort → 1 diff pass → no verify → ≤4 findings`

### Turn 1 — read

One tool call: read the unified diff (`git diff @{upstream}...HEAD; git diff HEAD`
to cover both committed and uncommitted changes, or `git diff main...HEAD` /
the target passed as an argument). Skip test/fixture
hunks (`test/`, `spec/`, `__tests__/`, `*_test.*`, `*.test.*`,
`fixtures/`, `testdata/`) — test-file changes are not reviewed at this level.
No subagents, no full-file reads.

### Turn 2 — findings

Flag runtime-correctness bugs visible from the hunk alone: inverted/wrong
condition, off-by-one, null/undefined deref where adjacent lines show the value
can be absent, removed guard, falsy-zero check, missing `await`,
wrong-variable copy-paste, error swallowed in a catch that should propagate.
Also flag — still from the hunk alone — new code that duplicates an existing
helper visible in the diff context, and dead code the diff leaves behind.
Do **not** flag style, naming, perf, missing tests, or anything outside the
hunk.

Output at most **4 findings**, most-severe first, one line each:
`path/to/file.ext:123 — what's wrong and the concrete failure`. If nothing
qualifies, output exactly `(none)`.

---

## Level: medium

`medium effort → 3+3+1 angles × 6 candidates → 1-vote verify → ≤8 findings`

You are reviewing for **precision** at medium effort: every finding you surface
should be one a maintainer would act on.

(Run Phase 0 first.)

### Phase 1 — Find candidates (3 correctness + 3 cleanup + 1 altitude angle, up to 6 each)

Delegate **7 independent passes** to the `code-review-finder` subagent — one
invocation per angle. For each invocation, pass the full angle description (see
"Correctness angles" / "Cleanup angles" below) plus `candidate_cap: 6` and the
review target (if any) as `$prompt`.

Use angles **A, B, C** and **Reuse, Simplification, Efficiency, Altitude**.

Do NOT let one finder's conclusions suppress another's — if two angles flag
the same line for different reasons, record both. The dedup happens in Phase 2.

Pass every candidate with a nameable failure scenario through — finders that
silently drop half-believed candidates bypass the verify step and are the
dominant cause of misses.

(Run Phase 2 — 1-vote, 3-state verify.)

(Apply Output spec with cap=8.)

---

## Level: high

`high effort → 3+3+1 angles × 6 candidates → 1-vote verify (recall-biased) → ≤10 findings`

You are reviewing for **recall** at high effort: catch every real bug a careful
reviewer would catch in one sitting. At this level, catching real bugs matters
more than avoiding false positives. Err on the side of surfacing.

(Run Phase 0 first.)

### Phase 1 — Find candidates (3 correctness + 3 cleanup + 1 altitude angle, up to 6 each)

Delegate **7 independent passes** to the `code-review-finder` subagent — one
invocation per angle, with `candidate_cap: 6`.

Use angles **A, B, C** and **Reuse, Simplification, Efficiency, Altitude**.

Pass every candidate with a nameable failure scenario through.

(Run Phase 2 — recall-biased verify.)

(Apply Output spec with cap=10.)

---

## Level: max

`max effort → 5+3+1 angles × 8 candidates → 1-vote verify → sweep → ≤15 findings`

You are reviewing for **recall** at maximum effort: catch every real bug. At
this level, catching real bugs matters more than avoiding false positives — a
missed bug ships. Err on the side of surfacing.

(Run Phase 0 first.)

### Phase 1 — Find candidates (5 correctness + 3 cleanup + 1 altitude angle, up to 8 each)

Delegate **9 independent passes** to the `code-review-finder` subagent — one
invocation per angle, with `candidate_cap: 8`.

Use angles **A, B, C, D, E** and **Reuse, Simplification, Efficiency, Altitude**.

Do NOT let one finder's conclusions suppress another's — if two angles flag
the same line for different reasons, record both.

(Run Phase 2 — recall-biased verify.)

This is recall mode — a single non-REFUTED vote carries the finding. Do NOT
drop on uncertainty.

(Run Phase 3 — Sweep for gaps.)

(Apply Output spec with cap=15.)

---

## Correctness angles

### Angle A — line-by-line diff scan

Read every hunk in the diff, line by line. Then Read the enclosing function for
each hunk — bugs in unchanged lines of a touched function are in scope (the PR
re-exposes or fails to fix them). For every line ask: what input, state, timing,
or platform makes this line wrong? Look for inverted/wrong conditions,
off-by-one, null/undefined deref, missing `await`, falsy-zero checks,
wrong-variable copy-paste, error swallowed in catch, unescaped regex metachars.

### Angle B — removed-behavior auditor

For every line the diff DELETES or replaces, name the invariant or behavior it
enforced, then search the new code for where that invariant is re-established.
If you can't find it, that's a candidate: a removed guard, a dropped error
path, a narrowed validation, a deleted test that was covering a real case.

### Angle C — cross-file tracer

For each function the diff changes, find its callers (Grep for the symbol) and
check whether the change breaks any call site: a new precondition, a changed
return shape, a new exception, a timing/ordering dependency. Also check callees:
does a parallel change in the same PR make a call unsafe?

### Angle D — language-pitfall specialist  *(max only)*

Scan for the classic pitfalls of the diff's language/framework — for example:
JS falsy-zero, `==` coercion, closure-captured loop var; Python mutable default
args, late-binding closures; Go nil-map write, range-var capture; SQL injection;
timezone/DST drift; float equality. Flag any instance the diff introduces.

### Angle E — wrapper/proxy correctness  *(max only)*

When the PR adds or modifies a type that wraps another (cache, proxy, decorator,
adapter): check that every method routes to the wrapped instance and not back
through a registry/session/global — e.g. a caching provider holding a
`delegate` field that resolves IDs via `session.get(...)` instead of
`delegate.get(...)` will re-enter the cache or recurse. Also check that the
wrapper forwards all the methods the callers actually use.

---

## Cleanup angles

### Reuse

The angles above hunt for bugs; this one and the next two hunt for cleanup in
the changed code. Flag new code that re-implements something the codebase
already has — Grep shared/utility modules and files adjacent to the change,
and name the existing helper to call instead.

### Simplification

Flag unnecessary complexity the diff adds: redundant or derivable state,
copy-paste with slight variation, deep nesting, dead code left behind. Name
the simpler form that does the same job.

### Efficiency

Flag wasted work the diff introduces: redundant computation or repeated I/O,
independent operations run sequentially, blocking work added to startup or
hot paths. Name the cheaper alternative.

### Altitude

Check that each change is implemented at the right depth, not as a fragile
bandaid. Special cases layered on shared infrastructure are a sign the fix
isn't deep enough — prefer generalizing the underlying mechanism over adding
special cases.

**Cleanup/altitude output shape:** use the same `file`/`line`/`summary` shape; in
`failure_scenario`, state the concrete cost (what is duplicated, wasted, or
harder to maintain) instead of a crash. Correctness bugs always outrank
cleanup and altitude findings when the output cap forces a cut.

---

## Phase 2 — Verify (1-vote, 3-state)  *(precision mode — used at medium)*

Collect all candidate arrays from the finder subagents. Dedup candidates that
point at the same line/mechanism, keeping the one with the most concrete
failure scenario.

For each remaining candidate, delegate one invocation to the
`code-review-verifier` subagent. Pass `mode: "precision"`, the candidate JSON,
and the review scope (diff range, target) as `$prompt`. The verifier returns
`CONFIRMED / PLAUSIBLE / REFUTED`.

Keep candidates where the vote is CONFIRMED or PLAUSIBLE.

## Phase 2 — Verify (1-vote, recall-biased)  *(used at high/max)*

Collect all candidate arrays from the finder subagents. Dedup near-duplicates
(same defect, same location, same reason → keep one).

For each remaining candidate, delegate one invocation to the
`code-review-verifier` subagent with `mode: "recall-biased"`, the candidate
JSON, and the review scope as `$prompt`.

Keep **CONFIRMED and PLAUSIBLE**. Drop REFUTED.

---

## Phase 3 — Sweep for gaps  *(max only)*

Delegate **one more finder pass** to the `code-review-finder` subagent. Pass it
the verified list (as `already_found`) plus this sweep-specific angle as
`$prompt`:

> Act as a fresh reviewer who has the verified list. Re-read the diff and
> enclosing functions looking ONLY for defects not already listed. Do not
> re-derive or re-confirm anything already there — the job is gaps. Focus on
> what the first pass tends to miss: moved/extracted code that dropped a guard
> or anchor; second-tier footguns (dataclass default evaluated once, `hash()`
> non-determinism, lock-scope shrink, predicate methods with side effects);
> setup/teardown asymmetry in tests; config defaults flipped. Surface up to 8
> additional candidates, each naming a defect not already on the list. If
> nothing new, return `[]`.

Run the sweep results through Phase 2 (recall-biased verify) before merging
into the final list.

---

## Output

Return findings as a JSON array of at most **N** objects (N depends on the level: low=4, medium=8, high=10, max=15):

```json
[
  {
    "file": "path/to/file.ext",
    "line": 123,
    "summary": "one-sentence statement of the bug",
    "failure_scenario": "concrete inputs/state → wrong output/crash"
  }
]
```

Ranked most-severe first. If more than N survive, keep the N most
severe. If nothing survives verification, return `[]`.

---

## Posting to GitHub (--comment)

When `--comment` is set: after producing the findings list, if the review
target is a GitHub PR, post each finding as an inline PR comment via
`gh api repos/{owner}/{repo}/pulls/{pr}/comments` (one call per finding;
include a `suggestion` block only when it fully fixes the issue). If `gh` is
not available, print the findings instead. If the target is not a PR, print
the findings to the terminal and note that `--comment` was ignored.

---

## Applying fixes (--fix)

When `--fix` is set: after producing the findings list, apply the findings to
the working tree instead of stopping at the report. Fix each one directly —
correctness bugs and reuse/simplification/efficiency cleanups alike. Skip any
finding whose fix would change intended behavior, require changes well outside
the reviewed diff, or that you judge to be a false positive — note the skip
rather than arguing with it. Finish with a brief summary of what was fixed and
what was skipped.

> The `/simplify` skill is equivalent to invoking `/code-review --fix` at the
> default (medium) effort.
