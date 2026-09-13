# GitHub plugin

Reads and writes GitHub from a process: issues, pull requests, reviews, checks, files and
project boards.

## Configuration

| Property            | Required | Meaning                                                                                                          |
|---------------------|----------|------------------------------------------------------------------------------------------------------------------|
| `url`               | yes      | `https://api.github.com` for github.com; `https://your-host/api/v3` for Enterprise Server                         |
| `token`             | yes      | A personal access token, a fine-grained token or an app installation token                                        |
| `defaultRepository` | no       | `owner/repo`, used by every action that leaves its own `repository` empty                                         |
| `graphqlUrl`        | no       | Derived from `url` when empty, which is right for both github.com and Enterprise Server                           |
| `perPage`           | no       | Items per request. Default 100, which is GitHub's own maximum                                                     |
| `maxPages`          | no       | Pages one list action follows before stopping. Default 5                                                          |

### About the token

**Whatever this token may do, every process linked to this configuration may do.** The
actions do not check, and `rest-request` reaches anything the token reaches. A configuration
used only for reading a backlog should hold a token that can only read; if writes and reads
want different authority, that is two configurations, not one.

### About `maxPages`

A list action that runs out of pages sets `truncated` to `true` in its result. That flag
matters more than the items: a short list and a truncated one are otherwise indistinguishable,
and a gateway branching on "no pull requests left to handle" takes the wrong branch on a page
budget that ran out. Read it.

## Actions

Every action takes an optional `repository` (`owner/repo`, falling back to the configuration's
default) and an optional `resultVariable`. Only the properties specific to each action are
listed below.

`resultVariable` names the process variable the answer lands in. Leave it empty and the
answer is discarded, which is what you want for an action whose point is the write.

### Repository and user

| Action                   | Properties             | Returns                                                              |
|--------------------------|------------------------|----------------------------------------------------------------------|
| `get-repository`         | —                      | `defaultBranch`, `visibility`, `private`, `archived`, `hasIssues`     |
| `list-repositories`      | `owner`, `type`, `limit` | `items`, `count`, `truncated`                                      |
| `get-authenticated-user` | —                      | `login`, `name`, `type`, `url`                                       |

`list-repositories` tries the organisation endpoint first and falls back to the user one,
because a caller holding only a name cannot tell the two apart and GitHub answers 404 rather
than 400 when the name is the other kind.

### Issues

| Action          | Properties                                                                                              |
|-----------------|----------------------------------------------------------------------------------------------------------|
| `list-issues`   | `state`, `labels`, `assignee`, `creator`, `since`, `limit`                                               |
| `get-issue`     | `issueNumber`, `includeComments`, `includeTimeline`                                                      |
| `search-issues` | `query`, `sort`, `order`, `limit`                                                                        |
| `create-issue`  | `title`, `body`, `labels`, `assignees`                                                                   |
| `update-issue`  | `issueNumber`, `title`, `body`, `state`, `stateReason`, `addLabels`, `removeLabels`, `addAssignees`, `removeAssignees` |
| `comment`       | `issueNumber`, `body`                                                                                    |

**`list-issues` returns pull requests too.** GitHub serves both from `/issues`, so every open
pull request of the repository comes back from it. Nothing is filtered out here, because "all
open work" is as legitimate a question as "all open issues" — each item carries
`isPullRequest`, and a process that wants only issues checks it.

**Labels and assignees are add and remove, not set.** A "set" on a tracker other people are
using silently discards whatever somebody applied between the read and the write. Removing a
label that is not there is not an error.

**`update-issue` does not write what you did not fill in.** An edit that only changes labels
sends no PATCH at all, so it does not move `updated_at` on an issue other people are watching.

**`search-issues` reads an index, not the repository.** It lags writes by seconds to minutes,
and it stops at a thousand results. `totalCount` in the result is what GitHub said it had.

### Pull requests

| Action                    | Properties                                                                                       |
|---------------------------|----------------------------------------------------------------------------------------------------|
| `list-pull-requests`      | `state`, `base`, `head`, `limit`                                                                  |
| `get-pull-request`        | `pullRequestNumber`, `includeFiles`, `includeReviews`, `includeComments`, `includeChecks`         |
| `create-pull-request`     | `title`, `body`, `head`, `base`, `draft`                                                          |
| `update-pull-request`     | `pullRequestNumber`, `title`, `body`, `base`, `state`, `addLabels`, `removeLabels`                |
| `set-pull-request-draft`  | `pullRequestNumber`, `draft`                                                                      |
| `merge-pull-request`      | `pullRequestNumber`, `mergeMethod`, `commitTitle`, `commitMessage`, `expectedHeadSha`             |

**Leave `base` empty.** It falls back to the repository's own default branch, which is not
`main` everywhere — several of these repositories branch off `master`, and one off `v13`.

**`expectedHeadSha` on a merge is worth filling in.** With it, GitHub refuses the merge when
the branch moved since it was read, instead of merging a commit nobody reviewed. Take it from
`headSha` on an earlier `get-pull-request`.

**`set-pull-request-draft` is separate from `update-pull-request`** because REST cannot do it:
`draft` is read-only on the pulls endpoint, and the only way through is a GraphQL mutation.

### Review

| Action                    | Properties                                                     |
|---------------------------|-----------------------------------------------------------------|
| `review-pull-request`     | `pullRequestNumber`, `event`, `body`                            |
| `request-reviewers`       | `pullRequestNumber`, `reviewers`, `teamReviewers`               |
| `list-review-threads`     | `pullRequestNumber`, `onlyUnresolved`                           |
| `reply-to-review-comment` | `pullRequestNumber`, `commentId`, `body`                        |
| `resolve-review-thread`   | `threadId`, `resolve`                                           |

**`list-review-threads` goes through GraphQL, and that is the point.** REST's
`/pulls/{n}/comments` returns comments with no notion of a thread and no resolved flag, so a
process reading it cannot tell an answered remark from an open one and would work through the
same feedback on every pass. Each thread here carries `resolved`, `outdated`, `path`, `line`
and its comments.

The two ids are different things and are not interchangeable:

- `commentId` — the numeric `id` of a comment, which `reply-to-review-comment` takes to answer
  inside that comment's own thread rather than as a new comment at the bottom.
- `threadId` — the thread's GraphQL node id, which `resolve-review-thread` takes.

Both come back from `list-review-threads`.

`review-pull-request` takes `APPROVE`, `REQUEST_CHANGES` or `COMMENT`. A body is required for
the latter two and optional on an approval. GitHub does not let an account approve its own
pull request, so the identity behind the token matters —
`get-authenticated-user` says which it is.

### Labels

`create-label` takes `name`, `color` and `description`. A label that is already there is
reported as it stands rather than failing, so a process that labels its own pull requests can
run this every time instead of needing a try/catch around a step whose only purpose is to make
a later step work. The result says `created` either way.

### CI

| Action               | Properties                                     |
|----------------------|------------------------------------------------|
| `get-check-status`   | `ref`                                          |
| `list-workflow-runs` | `branch`, `status`, `event`, `limit`           |
| `get-workflow-run`   | `runId`, `includeJobs`                         |
| `get-job-logs`       | `jobId`, `maxLines`                            |
| `rerun-workflow`     | `runId`, `onlyFailed`                          |

**`get-check-status` merges both of the things GitHub calls a check.** Check runs come from
GitHub Apps, which is what Actions is; commit statuses come from the older API, which is what
most external CI still posts. They are separate lists with separate vocabularies, and
"may this be merged" is a question about both — a process reading only check runs would call a
repository green while its external build was red.

`state` is `success`, `pending`, `failure`, or **`none`** when nothing has reported on the
commit at all. `none` is deliberately not `success`: a commit nobody built is not a commit
that passed, and conflating them would let a process merge a branch whose CI never ran.

`get-job-logs` keeps the **tail** of the log, because a failing job says why it failed at the
end and the beginning is several thousand lines of setup.

### Files and branches

| Action                  | Properties                                                                    |
|-------------------------|-------------------------------------------------------------------------------|
| `get-file-content`      | `path`, `ref`                                                                 |
| `create-or-update-file` | `path`, `commitMessage`, `content`, `resourceId`, `branch`, `expectedSha`     |
| `create-branch`         | `branchName`, `fromRef`                                                       |

`get-file-content` decodes text and hands back `content`; anything with a NUL byte in it comes
back as `contentBase64` with `binary` set, rather than as a mangled string. A directory path
lists its entries instead.

`create-or-update-file` takes either `content` (text typed into the process) or `resourceId`
(a file already in temporary resource storage) — one or the other, not both. The resource path
is the only one that can carry an image, which is the usual reason to want this action: a case
that produced screenshots publishes them, and a pull request links them instead of carrying
them.

It commits **straight to the branch**, with no pull request in between. The blob sha of an
existing file is looked up rather than demanded, so writing the same path twice replaces it
instead of failing; that does mean this write wins over a concurrent one, and `expectedSha`
is how to make it lose instead.

### Project boards

`get-project-items` reads the cards an issue sits on, each with every field value — sprint,
status, estimate. Projects V2 has no REST API at all, so this is GraphQL, and the field values
are a union that has to be asked for shape by shape.

`set-project-item-field` writes one field back and needs `projectId`, `itemId` and `fieldId`,
all three of which come from `get-project-items`. `valueType` picks which member of GitHub's
input the value goes into: `text`, `number`, `date`, `singleSelectOptionId` or `iterationId`.
The last two take the **id** of the option or iteration, not its label — also returned by
`get-project-items`.

### Anything else

`rest-request` (`method`, `path`, `body`) and `graphql-query` (`query`, `variables`) call
GitHub directly and store the answer **unmodified**. Everything else trims what comes back to
the fields a process has a use for; these two do not, which is exactly when to reach for them.

They exist because GitHub's API is larger than any list of actions and grows without asking,
and a process needing one endpoint nobody anticipated should not need a new release of this
plugin. The bar on them is the configuration's own token and nothing else.

## What the results look like

Answers are trimmed and renamed before they reach a process variable. Two reasons: a single
pull request is some fifteen kilobytes of nested objects, and a list action storing fifty of
those is a process variable nobody can read; and `user.login` and `author.login` are the same
fact under two names depending on which endpoint answered. Everything here reports an author
as `author`.

A list action returns `{items, count, truncated}` — and `totalCount` on a search. A single
object returns its fields directly.

## Errors

A call GitHub refuses throws, which fails the service task. The message carries GitHub's own
reason, including the field a failed validation named, because a bare 422 tells a process
author nothing.

Two refusals are deliberately **not** errors, both for the same reason — they describe a state
the caller asked for:

- creating a label, or a branch, that is already there
- removing a label an issue does not carry

GraphQL is the exception worth knowing about: it answers `200` with an `errors` array rather
than an HTTP error. Those are turned into failures too, so an action that asked for review
threads and got nothing back fails rather than reporting there is nothing left to answer.
