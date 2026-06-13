---
name: simplify
description: "Review the current diff and apply the fixes — equivalent to /code-review --fix at medium effort. Surfaces correctness bugs and reuse/simplification/efficiency cleanups, then directly edits the working tree to address them. TRIGGER when: user asks to simplify, clean up, or auto-fix the current diff; user invokes /simplify."
---

# Simplify

`simplify` is a thin alias for `code-review --fix`. It runs the same review
pipeline as the [`code-review`](../code-review/SKILL.md) skill, then applies
the findings directly to the working tree instead of just reporting them.

## Argument parsing

Argument shape: `[low|medium|high|max] [--comment] [<target>]`

- First positional token chooses the **effort level**. Accept prefix matches
  (`low`, `med`, `hi`, `max`). Default to **medium** when unspecified.
- `--comment` — also post each finding as an inline PR comment (see the
  code-review skill's "Posting to GitHub" section).
- Remaining tokens form the review target (PR number, branch, or path). If
  absent, review the current diff.

Print a one-line header before starting:
```
(Running a {level}-effort review and applying its findings.)
```

## Pipeline

Run the full [`code-review`](../code-review/SKILL.md) pipeline at the chosen
level, then **always** apply the "Applying fixes (--fix)" stage:

### Phase 0 — Gather the diff

Run `git diff @{upstream}...HEAD` (or `git diff main...HEAD` / `git diff HEAD~1`
if there's no upstream) to get the unified diff under review. If there are
uncommitted changes, or the range diff is empty, also run `git diff HEAD` and
include the working-tree changes in scope — the review often runs before the
commit. If a PR number, branch name, or file path was passed as an argument,
review that target instead.

### Phases 1–3

Run the **Phase 1 (find candidates)**, **Phase 2 (verify)**, and **Phase 3
(sweep — max only)** stages exactly as defined in the `code-review` skill, at
the chosen effort level. Produce the findings list per the **Output** section
of `code-review`.

### Apply

Apply the findings to the working tree instead of stopping at the report: fix
each one directly — correctness bugs and reuse/simplification/efficiency
cleanups alike.

Skip any finding whose fix would:
- change intended behavior,
- require changes well outside the reviewed diff, or
- that you judge to be a false positive.

Note each skip rather than arguing with it. Finish with a brief summary of
what was fixed and what was skipped.

> See [`../code-review/SKILL.md`](../code-review/SKILL.md) for the full set of
> finder angles (A–E, Reuse, Simplification, Efficiency, Altitude), verify
> modes (precision vs recall-biased), and output spec. This skill does not
> duplicate them.
