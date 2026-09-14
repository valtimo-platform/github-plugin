# Release notes

Overview of the changes per version of the GitHub plugin.

## 1.0.1

Two fixes, both to answers that were wrong rather than missing.

`get-job-logs` returned no log at all. GitHub serves an Actions log as a redirect to blob
storage, that redirect was not followed, and the empty answer was reported as a log that had
been read — so the one action whose purpose is to say why a job failed said nothing, and said
it had succeeded. The redirect is now followed, deliberately without the configuration's
token: the storage URL carries its own signature, and the host is not GitHub. A log that
GitHub will not hand over — an expired run, a job still starting — now reports `available`
false with the reason.

A list action that stopped exactly on its configured limit reported `truncated` true even
when the list had ended there. A process branching on that flag took the "there is more to
do" path on every run. Truncation now means something was actually left behind.

## 1.0.0

First release. Thirty-three actions covering issues, pull requests, reviews, checks, files,
branches and project boards, plus `rest-request` and `graphql-query` for anything they do not
reach.

Actions only — nothing in this plugin polls, and installing it adds no database tables. A
process that should run on a schedule says so with a BPMN timer start event.
