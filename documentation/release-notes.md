# Release notes

Overview of the changes per version of the GitHub plugin.

## 1.0.1

### Get job logs now actually shows the log

The action said it had read the log and then handed back nothing. Anyone using it to find out
why a build failed got an empty answer — and, worse, one that looked like a job that had run
fine and said nothing at all.

It now returns the last lines of the log, as many as the **Number of lines** field asks for.
When GitHub will not hand the log over — the run has expired, or the job has not started yet
— the result says so, with the reason, instead of passing an empty log off as a log that was
read.

If you have a process that picks up a failing build and reports back what went wrong, this is
the release that makes it work.

### A complete list is no longer reported as cut short

Every list action — issues, pull requests, repositories, workflow runs — reports `truncated`
next to its items, so a process can tell "this is all of them" from "there were more than I
was allowed to fetch".

When a list happened to hold exactly as many items as **Maximum number of results** allowed,
it was marked as cut short even though nothing had been left behind. A process branching on
that flag took the "there is more to fetch" route every single time. `truncated` now means
items were genuinely left out.

### Installing: the frontend has a new package name

The plugin's frontend is now published as `@valtimo-plugins/github-plugin`. It used to be
`@valtimo-plugins/github`, which stays available but is deprecated and gets no further
updates.

Nothing changes in the admin UI. Existing plugin configurations, process links and result
variables keep working, and there is nothing to redo in your diagrams. Only whoever installs
Valtimo has to point at the new name once. The backend is unchanged.

## 1.0.0

First release. Thirty-three actions covering issues, pull requests, reviews, checks, files,
branches and project boards, plus **REST request** and **GraphQL query** for the corners of
GitHub the other actions do not reach.

The plugin only acts when a process asks it to. It never watches GitHub on its own, so a
process that should check something on a schedule says so with a timer start event in the
diagram. Installing it adds nothing to the database.
