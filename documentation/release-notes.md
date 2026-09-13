# Release notes

Overview of the changes per version of the GitHub plugin.

## 1.0.0

First release. Thirty-three actions covering issues, pull requests, reviews, checks, files,
branches and project boards, plus `rest-request` and `graphql-query` for anything they do not
reach.

Actions only — nothing in this plugin polls, and installing it adds no database tables. A
process that should run on a schedule says so with a BPMN timer start event.
