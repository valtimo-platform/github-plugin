# Release notes

Overview of the changes per version of the GitHub plugin.

## 1.0.2

### The plugin stopped reaching GitHub after five calls

Every call leaked the connection it borrowed. The pool hands out five per host, so the sixth
call and everything after it waited five seconds for a connection that was never coming back
and then failed with `ConnectionRequestTimeoutException` — and stayed broken until the
application was restarted.

A process running one or two actions never noticed. A process that walks a queue — read the
pull requests, then per pull request its reviews, its checks and its runs — hit it every time,
somewhere in the middle, with an error that named a timeout and said nothing about GitHub.

### Create label works on the second run

**Create label** treats "this label already exists" as success: it looks the existing label up
and carries on. That only works if the plugin can see GitHub's 422, and in a Valtimo
application it could not — the response is read by a logging interceptor that raises Spring's
own exception first, so the plugin's own error, the one carrying the status, was never built.

A process whose first step creates its working label therefore ran exactly once and failed on
every run after that, with `422 Unprocessable Entity` and nothing to say which of its steps
had a problem.

The status now survives whichever half of the stack noticed the refusal. The *reason* does not,
and cannot yet: that interceptor builds its exception from the status line alone and discards
the body it has just read, so a rejected write still reports `GitHub responded 422` without
naming the field GitHub objected to. Recovering that needs a change in Valtimo, not here.

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

### Installing: both halves have a new name

The plugin is now published under `github-plugin` rather than `github`, front and back:

| | Was | Is now |
| --- | --- | --- |
| Frontend | `@valtimo-plugins/github` | `@valtimo-plugins/github-plugin` |
| Backend | `com.ritense.valtimoplugins:github` | `com.ritense.valtimoplugins:github-plugin` |

The old names stay available but are deprecated and get no further updates.

Nothing changes in the admin UI. Existing plugin configurations, process links and result
variables keep working, and there is nothing to redo in your diagrams — the plugin is still
called **GitHub** on screen and still identifies itself as `github` underneath. Only whoever
installs Valtimo has to point at the two new names once.

## 1.0.0

First release. Thirty-three actions covering issues, pull requests, reviews, checks, files,
branches and project boards, plus **REST request** and **GraphQL query** for the corners of
GitHub the other actions do not reach.

The plugin only acts when a process asks it to. It never watches GitHub on its own, so a
process that should check something on a schedule says so with a timer start event in the
diagram. Installing it adds nothing to the database.
