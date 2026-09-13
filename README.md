# GitHub plugin

Read and write GitHub from a Valtimo process: issues, pull requests, reviews, checks, files
and project boards. Thirty-three service task actions, plus two escape hatches for anything
they do not cover.

It exists to make the pipeline that plugin-central runs as shell scripts expressible in BPMN
instead — pick up a ticket, branch, open a pull request, watch its checks, answer its review
threads, merge — with the orchestration modelled in Operaton rather than written in bash.

## What it does

| Area                | Actions                                                                                                                                |
|---------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| Repository and user | `get-repository`, `list-repositories`, `get-authenticated-user`                                                                        |
| Issues              | `list-issues`, `get-issue`, `search-issues`, `create-issue`, `update-issue`, `comment`                                                  |
| Pull requests       | `list-pull-requests`, `get-pull-request`, `create-pull-request`, `update-pull-request`, `set-pull-request-draft`, `merge-pull-request`  |
| Review              | `review-pull-request`, `request-reviewers`, `list-review-threads`, `reply-to-review-comment`, `resolve-review-thread`                   |
| Labels              | `create-label`                                                                                                                          |
| CI                  | `get-check-status`, `list-workflow-runs`, `get-workflow-run`, `get-job-logs`, `rerun-workflow`                                           |
| Files and branches  | `get-file-content`, `create-or-update-file`, `create-branch`                                                                            |
| Project boards      | `get-project-items`, `set-project-item-field`                                                                                           |
| Anything else       | `rest-request`, `graphql-query`                                                                                                         |

Every action writes its answer to a process variable named by `resultVariable`, so the next
step in the diagram can branch on it. An action without one is an action whose answer nobody
wanted — a comment posted, a label applied — and it writes nothing.

There is **no polling**. Nothing here wakes up on its own, and installing this plugin adds no
database tables; a process that should run every ten minutes says so with a BPMN timer start
event and calls `list-issues`.

## Trying it

The sandbox autodeploys a **GitHub** case type holding eight fixture processes that between
them exercise all 33 actions against a real repository, each step followed by a task showing
what it stored. Everything irreversible — merging, re-running CI, writing into somebody
else's review — is behind a checkbox that is off by default. See
[getting started](documentation/getting-started.md#seeing-it-work-the-fixtures).

## Documentation

- [Getting started](documentation/getting-started.md) — running the sandbox and its fixtures, and developing on it
- [Plugin documentation](documentation/plugin.md) — the actions, their properties and what they return
- [Release notes](documentation/release-notes.md) — version history

## Contact

Klaas Schuijtemaker (Ritense)
