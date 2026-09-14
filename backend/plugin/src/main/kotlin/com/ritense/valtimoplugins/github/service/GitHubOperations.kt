/*
 * Copyright 2015-2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.valtimoplugins.github.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.valtimo.contract.annotation.SkipComponentScan
import com.ritense.valtimoplugins.github.client.GitHubClient
import com.ritense.valtimoplugins.github.client.GitHubException
import com.ritense.valtimoplugins.github.domain.GitHubConnectionProperties
import com.ritense.valtimoplugins.github.domain.RepositoryRef
import com.ritense.valtimoplugins.github.service.GitHubProjections.array
import com.ritense.valtimoplugins.github.service.GitHubProjections.list
import com.ritense.valtimoplugins.github.service.GitHubProjections.obj
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * Every GitHub operation the plugin offers, one method each.
 *
 * Separate from [com.ritense.valtimoplugins.github.plugin.GitHubPlugin] so that the plugin
 * class stays what it is meant to be — a declaration of what the admin UI may configure —
 * and the calls that do the work are testable without a process engine. The plugin class
 * holds no logic beyond resolving its own configuration and writing a result variable.
 */
@Component
@SkipComponentScan
@Suppress("TooManyFunctions", "LongParameterList")
class GitHubOperations(
    private val client: GitHubClient,
    private val objectMapper: ObjectMapper,
) {
    // ─── repository and viewer ──────────────────────────────────────────────

    fun getRepository(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
    ): ObjectNode = GitHubProjections.repository(client.get(connection, "/repos/$repository"))

    /**
     * Every repository of an organisation, or of a user when [owner] names one.
     *
     * The organisation endpoint is tried first and a 404 falls back to the user endpoint,
     * because a caller holding only a name cannot tell the two apart and GitHub answers 404
     * — not 400 — when the name is the other kind.
     */
    fun listRepositories(
        connection: GitHubConnectionProperties,
        owner: String,
        type: String?,
        limit: Int?,
    ): ObjectNode {
        val query = mapOf("type" to type, "sort" to "full_name")
        val paged =
            try {
                client.getPaged(connection, "/orgs/$owner/repos", query, limit)
            } catch (e: GitHubException) {
                if (e.status == NOT_FOUND) {
                    client.getPaged(connection, "/users/$owner/repos", query, limit)
                } else {
                    throw e
                }
            }
        return list(array(paged.items.map { GitHubProjections.repository(it) }), paged.truncated)
    }

    fun getAuthenticatedUser(connection: GitHubConnectionProperties): ObjectNode {
        val user = client.get(connection, "/user")
        return obj {
            put("login", user.path("login").asText(null))
            put("name", user.path("name").asText(null))
            put("type", user.path("type").asText(null))
            put("url", user.path("html_url").asText(null))
        }
    }

    // ─── issues ─────────────────────────────────────────────────────────────

    /**
     * Note that GitHub's `/issues` serves pull requests too — every open pull request of a
     * repository comes back from this call. Each item carries `isPullRequest` so a process
     * can drop them; nothing is filtered here, because "all open work" is as legitimate a
     * question as "all open issues".
     */
    fun listIssues(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        state: String?,
        labels: String?,
        assignee: String?,
        creator: String?,
        since: String?,
        limit: Int?,
    ): ObjectNode {
        val paged =
            client.getPaged(
                connection,
                "/repos/$repository/issues",
                mapOf(
                    "state" to (state ?: "open"),
                    "labels" to labels,
                    "assignee" to assignee,
                    "creator" to creator,
                    "since" to since,
                    "sort" to "updated",
                    "direction" to "desc",
                ),
                limit,
            )
        return list(array(paged.items.map { GitHubProjections.issue(it) }), paged.truncated)
    }

    fun getIssue(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        includeComments: Boolean,
        includeTimeline: Boolean,
    ): ObjectNode {
        val issue = client.get(connection, "/repos/$repository/issues/$number")
        val comments =
            if (includeComments) {
                array(
                    client
                        .getPaged(connection, "/repos/$repository/issues/$number/comments")
                        .items
                        .map { GitHubProjections.comment(it) },
                )
            } else {
                null
            }
        val timeline =
            if (includeTimeline) {
                array(
                    client
                        .getPaged(connection, "/repos/$repository/issues/$number/timeline")
                        .items
                        .map { GitHubProjections.timelineEvent(it) },
                )
            } else {
                null
            }
        return GitHubProjections.issue(issue, comments, timeline)
    }

    /**
     * GitHub's issue search, which is also how pull requests are searched across
     * repositories — `is:pr` in [query] is the only difference.
     *
     * The search index is not the repository: it lags writes by seconds to minutes, and it
     * silently drops results past a thousand. `totalCount` on the result is what GitHub said
     * it had, so a caller can see when the second of those happened.
     */
    fun search(
        connection: GitHubConnectionProperties,
        query: String,
        sort: String?,
        order: String?,
        limit: Int?,
    ): ObjectNode {
        require(query.isNotBlank()) { "A search query is required" }
        val paged =
            client.getPaged(
                connection,
                "/search/issues",
                mapOf("q" to query, "sort" to sort, "order" to order),
                limit,
            )
        return list(
            array(paged.items.map { GitHubProjections.issue(it) }),
            paged.truncated,
            paged.totalCount,
        )
    }

    fun createIssue(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        title: String,
        body: String?,
        labels: List<String>,
        assignees: List<String>,
    ): ObjectNode {
        val payload =
            obj {
                put("title", title)
                body?.let { put("body", it) }
                if (labels.isNotEmpty()) set<ArrayNode>("labels", array(labels))
                if (assignees.isNotEmpty()) set<ArrayNode>("assignees", array(assignees))
            }
        return GitHubProjections.issue(client.post(connection, "/repos/$repository/issues", payload))
    }

    /**
     * Edits an issue — or a pull request's issue half: title, body, state, labels and
     * assignees are the same endpoint for both.
     *
     * Labels and assignees are add/remove rather than set, because "set" on a concurrent
     * tracker silently discards whatever somebody applied between the read and the write.
     * Removals go through their own endpoints for the same reason.
     */
    fun updateIssue(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        title: String?,
        body: String?,
        state: String?,
        stateReason: String?,
        addLabels: List<String>,
        removeLabels: List<String>,
        addAssignees: List<String>,
        removeAssignees: List<String>,
    ): ObjectNode {
        val payload =
            obj {
                title?.let { put("title", it) }
                body?.let { put("body", it) }
                state?.let { put("state", it) }
                stateReason?.let { put("state_reason", it) }
            }
        if (!payload.isEmpty) {
            client.patch(connection, "/repos/$repository/issues/$number", payload)
        }

        if (addLabels.isNotEmpty()) {
            client.post(
                connection,
                "/repos/$repository/issues/$number/labels",
                obj { set<ArrayNode>("labels", array(addLabels)) },
            )
        }
        removeLabels.forEach { label ->
            // 404 means the label was not on the issue, which is the state the caller asked
            // for. Anything else is a real refusal and is not swallowed.
            runCatching {
                client.delete(connection, "/repos/$repository/issues/$number/labels/$label")
            }.onFailure { failure ->
                if ((failure as? GitHubException)?.status != NOT_FOUND) throw failure
            }
        }
        if (addAssignees.isNotEmpty()) {
            client.post(
                connection,
                "/repos/$repository/issues/$number/assignees",
                obj { set<ArrayNode>("assignees", array(addAssignees)) },
            )
        }
        if (removeAssignees.isNotEmpty()) {
            client.request(
                connection,
                "DELETE",
                "/repos/$repository/issues/$number/assignees",
                body = obj { set<ArrayNode>("assignees", array(removeAssignees)) },
            )
        }

        return GitHubProjections.issue(client.get(connection, "/repos/$repository/issues/$number"))
    }

    /** Comments on an issue or a pull request — one endpoint serves both. */
    fun addComment(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        body: String,
    ): ObjectNode {
        require(body.isNotBlank()) { "A comment body is required" }
        return GitHubProjections.comment(
            client.post(connection, "/repos/$repository/issues/$number/comments", obj { put("body", body) }),
        )
    }

    // ─── pull requests ──────────────────────────────────────────────────────

    fun listPullRequests(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        state: String?,
        base: String?,
        head: String?,
        limit: Int?,
    ): ObjectNode {
        val paged =
            client.getPaged(
                connection,
                "/repos/$repository/pulls",
                mapOf(
                    "state" to (state ?: "open"),
                    "base" to base,
                    "head" to head,
                    "sort" to "updated",
                    "direction" to "desc",
                ),
                limit,
            )
        return list(array(paged.items.map { GitHubProjections.pullRequest(it) }), paged.truncated)
    }

    fun getPullRequest(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        includeFiles: Boolean,
        includeReviews: Boolean,
        includeComments: Boolean,
        includeChecks: Boolean,
    ): ObjectNode {
        val pullRequest = client.get(connection, "/repos/$repository/pulls/$number")
        val projected = GitHubProjections.pullRequest(pullRequest)

        if (includeFiles) {
            val files = client.getPaged(connection, "/repos/$repository/pulls/$number/files")
            projected.set<ArrayNode>("files", array(files.items.map { GitHubProjections.pullRequestFile(it) }))
            projected.put("filesTruncated", files.truncated)
        }
        if (includeReviews) {
            val reviews = client.getPaged(connection, "/repos/$repository/pulls/$number/reviews")
            projected.set<ArrayNode>("reviews", array(reviews.items.map { GitHubProjections.review(it) }))
        }
        if (includeComments) {
            val comments = client.getPaged(connection, "/repos/$repository/issues/$number/comments")
            projected.set<ArrayNode>("comments", array(comments.items.map { GitHubProjections.comment(it) }))
        }
        if (includeChecks) {
            val sha = pullRequest.path("head").path("sha").asText(null)
            if (sha != null) projected.set<ObjectNode>("checks", getCheckStatus(connection, repository, sha))
        }
        return projected
    }

    fun createPullRequest(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        title: String,
        body: String?,
        head: String,
        base: String?,
        draft: Boolean,
    ): ObjectNode {
        val payload =
            obj {
                put("title", title)
                put("head", head)
                // Falls back to whatever the repository calls its default branch, rather
                // than assuming `main`: plenty of these repositories branch off `master`,
                // and one of them off `v13`.
                put("base", base?.takeIf { it.isNotBlank() } ?: defaultBranch(connection, repository))
                body?.let { put("body", it) }
                put("draft", draft)
            }
        return GitHubProjections.pullRequest(client.post(connection, "/repos/$repository/pulls", payload))
    }

    fun updatePullRequest(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        title: String?,
        body: String?,
        base: String?,
        state: String?,
        addLabels: List<String>,
        removeLabels: List<String>,
    ): ObjectNode {
        val payload =
            obj {
                title?.let { put("title", it) }
                body?.let { put("body", it) }
                base?.let { put("base", it) }
                state?.let { put("state", it) }
            }
        if (!payload.isEmpty) {
            client.patch(connection, "/repos/$repository/pulls/$number", payload)
        }
        if (addLabels.isNotEmpty() || removeLabels.isNotEmpty()) {
            updateIssue(
                connection = connection,
                repository = repository,
                number = number,
                title = null,
                body = null,
                state = null,
                stateReason = null,
                addLabels = addLabels,
                removeLabels = removeLabels,
                addAssignees = emptyList(),
                removeAssignees = emptyList(),
            )
        }
        return GitHubProjections.pullRequest(client.get(connection, "/repos/$repository/pulls/$number"))
    }

    /**
     * Marks a pull request ready for review, or back to draft.
     *
     * Not part of `update-pull-request` because REST cannot do it: `draft` is read-only on
     * the pulls endpoint and the only way through is the GraphQL mutation pair.
     */
    fun setPullRequestDraft(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        draft: Boolean,
    ): ObjectNode {
        val nodeId =
            client.get(connection, "/repos/$repository/pulls/$number").path("node_id").asText(null)
                ?: throw GitHubException("Pull request $repository#$number has no node id")

        val mutation =
            if (draft) {
                "mutation(\$id:ID!) { convertPullRequestToDraft(input:{pullRequestId:\$id}) " +
                    "{ pullRequest { number isDraft } } }"
            } else {
                "mutation(\$id:ID!) { markPullRequestReadyForReview(input:{pullRequestId:\$id}) " +
                    "{ pullRequest { number isDraft } } }"
            }
        client.graphql(connection, mutation, obj { put("id", nodeId) })
        return GitHubProjections.pullRequest(client.get(connection, "/repos/$repository/pulls/$number"))
    }

    fun mergePullRequest(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        mergeMethod: String?,
        commitTitle: String?,
        commitMessage: String?,
        expectedHeadSha: String?,
    ): ObjectNode {
        val payload =
            obj {
                put("merge_method", mergeMethod?.lowercase() ?: "merge")
                commitTitle?.let { put("commit_title", it) }
                commitMessage?.let { put("commit_message", it) }
                // Optional, and worth filling in: GitHub refuses the merge when the branch
                // moved since the caller looked, instead of merging a commit nobody read.
                expectedHeadSha?.let { put("sha", it) }
            }
        val result = client.put(connection, "/repos/$repository/pulls/$number/merge", payload)
        return obj {
            put("merged", result.path("merged").asBoolean(false))
            put("sha", result.path("sha").asText(null))
            put("message", result.path("message").asText(null))
        }
    }

    fun reviewPullRequest(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        event: String,
        body: String?,
    ): ObjectNode {
        val normalised = event.trim().uppercase()
        require(normalised in REVIEW_EVENTS) {
            "Review event must be one of ${REVIEW_EVENTS.joinToString(", ")}, was '$event'"
        }
        require(normalised == "APPROVE" || !body.isNullOrBlank()) {
            "A body is required for a $normalised review"
        }
        val payload =
            obj {
                put("event", normalised)
                body?.takeIf { it.isNotBlank() }?.let { put("body", it) }
            }
        return GitHubProjections.review(
            client.post(connection, "/repos/$repository/pulls/$number/reviews", payload),
        )
    }

    fun requestReviewers(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        reviewers: List<String>,
        teamReviewers: List<String>,
    ): ObjectNode {
        require(reviewers.isNotEmpty() || teamReviewers.isNotEmpty()) {
            "At least one reviewer or team is required"
        }
        val payload =
            obj {
                if (reviewers.isNotEmpty()) set<ArrayNode>("reviewers", array(reviewers))
                if (teamReviewers.isNotEmpty()) set<ArrayNode>("team_reviewers", array(teamReviewers))
            }
        return GitHubProjections.pullRequest(
            client.post(connection, "/repos/$repository/pulls/$number/requested_reviewers", payload),
        )
    }

    /**
     * The inline review conversations on a pull request, each with its comments and whether
     * it has been resolved.
     *
     * GraphQL rather than REST, and that is the whole reason this action exists separately
     * from the comment list: REST's `/pulls/{n}/comments` returns comments with no notion of
     * a thread and no resolved flag, so a process reading it cannot tell an answered remark
     * from an open one and would work through the same feedback on every pass.
     */
    fun listReviewThreads(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        onlyUnresolved: Boolean,
    ): ObjectNode {
        val threads = objectMapper.createArrayNode()
        var cursor: String? = null
        var pages = 0
        var hasNext: Boolean

        do {
            val data =
                client.graphql(
                    connection,
                    REVIEW_THREADS_QUERY,
                    obj {
                        put("owner", repository.owner)
                        put("name", repository.name)
                        put("number", number)
                        cursor?.let { put("cursor", it) }
                    },
                )
            val page = data.path("repository").path("pullRequest").path("reviewThreads")
            page.path("nodes").forEach { thread ->
                val resolved = thread.path("isResolved").asBoolean(false)
                if (onlyUnresolved && resolved) return@forEach
                threads.add(
                    obj {
                        put("id", thread.path("id").asText(null))
                        put("resolved", resolved)
                        put("outdated", thread.path("isOutdated").asBoolean(false))
                        put("path", thread.path("path").asText(null))
                        put("line", thread.path("line").asInt(0))
                        set<ArrayNode>(
                            "comments",
                            array(
                                thread.path("comments").path("nodes").map { comment ->
                                    obj {
                                        put("id", comment.path("databaseId").asLong())
                                        put("nodeId", comment.path("id").asText(null))
                                        put("author", comment.path("author").path("login").asText(null))
                                        put("body", comment.path("body").asText(null))
                                        put("url", comment.path("url").asText(null))
                                        put("createdAt", comment.path("createdAt").asText(null))
                                    }
                                },
                            ),
                        )
                    },
                )
            }
            cursor = page.path("pageInfo").path("endCursor").asText(null)
            hasNext = page.path("pageInfo").path("hasNextPage").asBoolean(false)
            pages++
        } while (hasNext && cursor != null && pages < connection.maxPages)

        return list(threads, truncated = hasNext)
    }

    /**
     * Answers one inline review comment inside its own thread.
     *
     * [commentId] is the numeric id of any comment in the thread — the `id` field of a
     * comment from [listReviewThreads], not the thread's node id. Replying to the thread
     * rather than posting a new top-level comment is what keeps the conversation where the
     * reviewer left it.
     */
    fun replyToReviewComment(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        number: Int,
        commentId: Long,
        body: String,
    ): ObjectNode {
        require(body.isNotBlank()) { "A reply body is required" }
        return GitHubProjections.comment(
            client.post(
                connection,
                "/repos/$repository/pulls/$number/comments/$commentId/replies",
                obj { put("body", body) },
            ),
        )
    }

    /** [threadId] is the thread's GraphQL node id, as returned by [listReviewThreads]. */
    fun resolveReviewThread(
        connection: GitHubConnectionProperties,
        threadId: String,
        resolve: Boolean,
    ): ObjectNode {
        require(threadId.isNotBlank()) { "A review thread id is required" }
        val mutation =
            if (resolve) {
                "mutation(\$id:ID!) { resolveReviewThread(input:{threadId:\$id}) " +
                    "{ thread { id isResolved } } }"
            } else {
                "mutation(\$id:ID!) { unresolveReviewThread(input:{threadId:\$id}) " +
                    "{ thread { id isResolved } } }"
            }
        val data = client.graphql(connection, mutation, obj { put("id", threadId) })
        val thread =
            data
                .properties()
                .firstOrNull()
                ?.value
                ?.path("thread")
        return obj {
            put("id", thread?.path("id")?.asText(null) ?: threadId)
            put("resolved", thread?.path("isResolved")?.asBoolean(resolve) ?: resolve)
        }
    }

    // ─── labels ─────────────────────────────────────────────────────────────

    /**
     * Creates a label, and treats "it already exists" as success.
     *
     * A process that labels its own pull requests has to make sure the label exists first,
     * and on the second run it already does. Failing there would mean every such process
     * needs a try/catch around a step whose only purpose is to make a later step work.
     */
    fun createLabel(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        name: String,
        color: String?,
        description: String?,
    ): ObjectNode {
        require(name.isNotBlank()) { "A label name is required" }
        val payload =
            obj {
                put("name", name)
                color?.takeIf { it.isNotBlank() }?.let { put("color", it.removePrefix("#")) }
                description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            }
        return try {
            GitHubProjections.label(client.post(connection, "/repos/$repository/labels", payload)).also {
                it.put("created", true)
            }
        } catch (e: GitHubException) {
            if (e.status != UNPROCESSABLE) throw e
            GitHubProjections.label(client.get(connection, "/repos/$repository/labels/$name")).also {
                it.put("created", false)
            }
        }
    }

    // ─── checks and workflow runs ───────────────────────────────────────────

    /**
     * Whether a commit is green, across both of the things GitHub calls a check.
     *
     * Check runs (from a GitHub App, which is what Actions is) and commit statuses (from
     * the older API, which is what most external CI still posts) are separate lists with
     * separate vocabularies. Merged here onto one `state` — `success`, `pending` or
     * `failure` — because "may this be merged" is a question about both, and a process that
     * only read check runs would call a repository green while its external build was red.
     */
    fun getCheckStatus(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        ref: String,
    ): ObjectNode {
        require(ref.isNotBlank()) { "A ref is required" }

        val checkRuns =
            client
                .getPaged(connection, "/repos/$repository/commits/$ref/check-runs")
                .items
                .map { run ->
                    GitHubProjections.check(
                        name = run.path("name").asText(null),
                        status = run.path("status").asText(null),
                        conclusion = run.path("conclusion").asText(null),
                        url = run.path("html_url").asText(null),
                        kind = "check_run",
                    )
                }

        val combined = client.get(connection, "/repos/$repository/commits/$ref/status")
        val statuses =
            combined.path("statuses").map { status ->
                GitHubProjections.check(
                    name = status.path("context").asText(null),
                    status = if (status.path("state").asText("") == "pending") "in_progress" else "completed",
                    conclusion = status.path("state").asText(null),
                    url = status.path("target_url").asText(null),
                    kind = "status",
                )
            }

        val all = checkRuns + statuses
        val failing =
            all.filter { it.path("conclusion").asText("") in FAILING_CONCLUSIONS }
        val pending =
            all.filter { it.path("status").asText("") != "completed" }

        return obj {
            put("ref", ref)
            put(
                "state",
                when {
                    failing.isNotEmpty() -> "failure"
                    pending.isNotEmpty() -> "pending"
                    all.isEmpty() -> "none"
                    else -> "success"
                },
            )
            put("total", all.size)
            put("failing", failing.size)
            put("pending", pending.size)
            set<ArrayNode>("checks", array(all))
            set<ArrayNode>("failingChecks", array(failing))
        }
    }

    fun listWorkflowRuns(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        branch: String?,
        status: String?,
        event: String?,
        limit: Int?,
    ): ObjectNode {
        val paged =
            client.getPaged(
                connection,
                "/repos/$repository/actions/runs",
                mapOf("branch" to branch, "status" to status, "event" to event),
                limit,
            )
        return list(array(paged.items.map { GitHubProjections.workflowRun(it) }), paged.truncated)
    }

    fun getWorkflowRun(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        runId: Long,
        includeJobs: Boolean,
    ): ObjectNode {
        val run = client.get(connection, "/repos/$repository/actions/runs/$runId")
        val projected = GitHubProjections.workflowRun(run)

        if (includeJobs) {
            val jobs =
                client
                    .getPaged(connection, "/repos/$repository/actions/runs/$runId/jobs", mapOf("filter" to "latest"))
                    .items
                    .map { GitHubProjections.job(it) }
            projected.set<ArrayNode>("jobs", array(jobs))
            projected.set<ArrayNode>(
                "failedJobs",
                array(jobs.filter { it.path("conclusion").asText("") in FAILING_CONCLUSIONS }),
            )
        }
        return projected
    }

    /**
     * The log of one job, tail-first.
     *
     * GitHub answers this endpoint with a redirect to blob storage and plain text rather
     * than JSON, which is why it goes through [GitHubClient.getText] rather than the JSON
     * calls every other action uses. [maxLines] keeps the tail — a failing job says why it
     * failed at the end, and the beginning is several thousand lines of setup that would
     * fill a process variable with nothing.
     *
     * A log GitHub would not hand over — an expired run, a job still starting — reports
     * `available` false with the reason, rather than an empty log that a process would read
     * as a job that said nothing.
     */
    fun getJobLogs(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        jobId: Long,
        maxLines: Int,
    ): ObjectNode {
        val text =
            runCatching { client.getText(connection, "/repos/$repository/actions/jobs/$jobId/logs") }
                .getOrElse { failure ->
                    logger.warn(failure) { "Could not read logs of job $jobId in $repository" }
                    return unavailable(jobId, failure.message)
                }
                ?: return unavailable(jobId, "GitHub returned no log for job $jobId")

        val lines = text.lines()
        val tail = if (lines.size > maxLines) lines.takeLast(maxLines) else lines

        return obj {
            put("jobId", jobId)
            put("available", true)
            put("truncated", lines.size > maxLines)
            put("totalLines", lines.size)
            put("log", tail.joinToString("\n"))
        }
    }

    fun rerunFailedJobs(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        runId: Long,
        onlyFailed: Boolean,
    ): ObjectNode {
        val path =
            if (onlyFailed) {
                "/repos/$repository/actions/runs/$runId/rerun-failed-jobs"
            } else {
                "/repos/$repository/actions/runs/$runId/rerun"
            }
        client.post(connection, path, objectMapper.createObjectNode())
        return obj {
            put("runId", runId)
            put("rerequested", true)
            put("onlyFailed", onlyFailed)
        }
    }

    // ─── contents and refs ──────────────────────────────────────────────────

    /**
     * Reads one file out of a repository.
     *
     * Decoded to text when it is text, and left as base64 in [contentBase64] when it is not
     * — a repository holds PNGs as readily as it holds markdown, and turning one into a
     * mojibake string is worse than saying it is binary.
     */
    fun getFileContent(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        path: String,
        ref: String?,
    ): ObjectNode {
        require(path.isNotBlank()) { "A file path is required" }
        val file = client.get(connection, "/repos/$repository/contents/$path", mapOf("ref" to ref))

        if (file.isArray) {
            return obj {
                put("path", path)
                put("type", "directory")
                set<ArrayNode>(
                    "entries",
                    array(
                        file.map { entry ->
                            obj {
                                put("name", entry.path("name").asText(null))
                                put("path", entry.path("path").asText(null))
                                put("type", entry.path("type").asText(null))
                                put("size", entry.path("size").asInt(0))
                            }
                        },
                    ),
                )
            }
        }

        val encoded = file.path("content").asText("").replace("\n", "")
        val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrNull()
        // A NUL byte is the cheap and reliable tell that this is not text. Anything else -- a
        // UTF-8 sequence that does not decode -- comes back with replacement characters, which
        // is what a reader of a mislabelled file should see rather than nothing at all.
        val text = bytes?.let { String(it, Charsets.UTF_8) }?.takeIf { !it.contains('\u0000') }

        return obj {
            put("path", file.path("path").asText(path))
            put("type", "file")
            put("sha", file.path("sha").asText(null))
            put("size", file.path("size").asInt(0))
            put("url", file.path("html_url").asText(null))
            if (text != null) put("content", text) else put("contentBase64", encoded)
            put("binary", text == null)
        }
    }

    /**
     * Writes a file, creating it or replacing what is there.
     *
     * The blob sha of the existing file is looked up rather than asked for, because GitHub
     * requires it on an update and refusing without one would make "write this file" two
     * steps in every process that ever runs twice. That does mean a write here wins over a
     * concurrent one; pass [expectedSha] to make it lose instead.
     */
    fun createOrUpdateFile(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        path: String,
        message: String,
        contentBase64: String,
        branch: String?,
        expectedSha: String?,
    ): ObjectNode {
        require(path.isNotBlank()) { "A file path is required" }
        require(message.isNotBlank()) { "A commit message is required" }

        val sha =
            expectedSha
                ?: runCatching {
                    client
                        .get(connection, "/repos/$repository/contents/$path", mapOf("ref" to branch))
                        .path("sha")
                        .asText(null)
                }.getOrNull()

        val payload =
            obj {
                put("message", message)
                put("content", contentBase64)
                branch?.takeIf { it.isNotBlank() }?.let { put("branch", it) }
                sha?.let { put("sha", it) }
            }
        val result = client.put(connection, "/repos/$repository/contents/$path", payload)

        return obj {
            put("path", result.path("content").path("path").asText(path))
            put("sha", result.path("content").path("sha").asText(null))
            put("url", result.path("content").path("html_url").asText(null))
            put("downloadUrl", result.path("content").path("download_url").asText(null))
            put("commitSha", result.path("commit").path("sha").asText(null))
            put("updated", sha != null)
        }
    }

    /**
     * Branches [branchName] off [fromRef], and reports the branch as it stands if it is
     * already there — same reasoning as [createLabel].
     */
    fun createBranch(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        branchName: String,
        fromRef: String?,
    ): ObjectNode {
        require(branchName.isNotBlank()) { "A branch name is required" }
        val base = fromRef?.takeIf { it.isNotBlank() } ?: defaultBranch(connection, repository)
        val baseSha =
            client.get(connection, "/repos/$repository/commits/$base").path("sha").asText(null)
                ?: throw GitHubException("Could not resolve '$base' in $repository")

        return try {
            val created =
                client.post(
                    connection,
                    "/repos/$repository/git/refs",
                    obj {
                        put("ref", "refs/heads/$branchName")
                        put("sha", baseSha)
                    },
                )
            obj {
                put("branch", branchName)
                put("sha", created.path("object").path("sha").asText(baseSha))
                put("from", base)
                put("created", true)
            }
        } catch (e: GitHubException) {
            if (e.status != UNPROCESSABLE) throw e
            val existing = client.get(connection, "/repos/$repository/git/ref/heads/$branchName")
            obj {
                put("branch", branchName)
                put("sha", existing.path("object").path("sha").asText(null))
                put("from", base)
                put("created", false)
            }
        }
    }

    // ─── projects v2 ────────────────────────────────────────────────────────

    /**
     * The project cards an issue sits on, with every field value on each.
     *
     * Only reachable through GraphQL — Projects V2 has no REST API at all — and the field
     * values are a union, so each shape has to be asked for by name. Sprint, status and
     * estimate all come back from here; which of them a board uses is the board's business.
     */
    fun getProjectItems(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
        issueNumber: Int,
    ): ObjectNode {
        val data =
            client.graphql(
                connection,
                PROJECT_ITEMS_QUERY,
                obj {
                    put("owner", repository.owner)
                    put("name", repository.name)
                    put("number", issueNumber)
                },
            )

        val items =
            data
                .path("repository")
                .path("issue")
                .path("projectItems")
                .path("nodes")
                .map { item ->
                    obj {
                        put("itemId", item.path("id").asText(null))
                        put("projectId", item.path("project").path("id").asText(null))
                        put("projectTitle", item.path("project").path("title").asText(null))
                        put("projectNumber", item.path("project").path("number").asInt(0))
                        set<ArrayNode>("fields", array(item.path("fieldValues").path("nodes").mapNotNull(::fieldValue)))
                    }
                }

        return list(array(items), truncated = false)
    }

    /**
     * Sets one field on one project card.
     *
     * [valueType] picks which member of GitHub's `ProjectV2FieldValue` input the value goes
     * into: `text`, `number`, `date`, `singleSelectOptionId` or `iterationId`. The last two
     * take an option or iteration id rather than its label — read them back from
     * [getProjectItems], which returns both.
     */
    fun setProjectItemField(
        connection: GitHubConnectionProperties,
        projectId: String,
        itemId: String,
        fieldId: String,
        valueType: String,
        value: String,
    ): ObjectNode {
        require(projectId.isNotBlank() && itemId.isNotBlank() && fieldId.isNotBlank()) {
            "projectId, itemId and fieldId are all required"
        }
        val type = valueType.trim()
        require(type in PROJECT_VALUE_TYPES) {
            "valueType must be one of ${PROJECT_VALUE_TYPES.joinToString(", ")}, was '$valueType'"
        }

        val valueNode =
            obj {
                when (type) {
                    "number" ->
                        put(
                            "number",
                            value.toDoubleOrNull()
                                ?: throw GitHubException("Value '$value' is not a number"),
                        )
                    else -> put(type, value)
                }
            }

        val data =
            client.graphql(
                connection,
                PROJECT_SET_FIELD_MUTATION,
                obj {
                    put("projectId", projectId)
                    put("itemId", itemId)
                    put("fieldId", fieldId)
                    set<ObjectNode>("value", valueNode)
                },
            )

        return obj {
            put(
                "itemId",
                data
                    .path("updateProjectV2ItemFieldValue")
                    .path("projectV2Item")
                    .path("id")
                    .asText(itemId),
            )
            put("fieldId", fieldId)
            put("value", value)
        }
    }

    // ─── escape hatches ─────────────────────────────────────────────────────

    fun restRequest(
        connection: GitHubConnectionProperties,
        method: String,
        path: String,
        body: JsonNode?,
    ): JsonNode {
        require(path.isNotBlank()) { "A path is required" }
        return client.request(connection, method, path, body = body)
    }

    fun graphqlQuery(
        connection: GitHubConnectionProperties,
        query: String,
        variables: JsonNode?,
    ): JsonNode {
        require(query.isNotBlank()) { "A GraphQL query is required" }
        return client.graphql(connection, query, variables)
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun unavailable(
        jobId: Long,
        reason: String?,
    ): ObjectNode =
        obj {
            put("jobId", jobId)
            put("available", false)
            put("reason", reason)
        }

    private fun defaultBranch(
        connection: GitHubConnectionProperties,
        repository: RepositoryRef,
    ): String =
        client.get(connection, "/repos/$repository").path("default_branch").asText(null)
            ?: throw GitHubException("Could not determine the default branch of $repository")

    private fun fieldValue(node: JsonNode): ObjectNode? {
        val field = node.path("field")
        val name = field.path("name").asText(null) ?: return null
        return obj {
            put("fieldId", field.path("id").asText(null))
            put("name", name)
            put("type", node.path("__typename").asText(null))
            when {
                node.has("text") -> put("value", node.path("text").asText(null))
                node.has("name") -> {
                    put("value", node.path("name").asText(null))
                    put("optionId", node.path("optionId").asText(null))
                }
                node.has("title") -> {
                    put("value", node.path("title").asText(null))
                    put("iterationId", node.path("iterationId").asText(null))
                    put("startDate", node.path("startDate").asText(null))
                    put("duration", node.path("duration").asInt(0))
                }
                node.has("date") -> put("value", node.path("date").asText(null))
                node.has("number") -> put("value", node.path("number").asText(null))
                else -> putNull("value")
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        private const val NOT_FOUND = 404
        private const val UNPROCESSABLE = 422

        private val REVIEW_EVENTS = setOf("APPROVE", "REQUEST_CHANGES", "COMMENT")
        private val FAILING_CONCLUSIONS = setOf("failure", "timed_out", "cancelled", "action_required", "error")
        private val PROJECT_VALUE_TYPES =
            setOf("text", "number", "date", "singleSelectOptionId", "iterationId")

        private val REVIEW_THREADS_QUERY =
            """
            query(${'$'}owner:String!, ${'$'}name:String!, ${'$'}number:Int!, ${'$'}cursor:String) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                pullRequest(number: ${'$'}number) {
                  reviewThreads(first: 100, after: ${'$'}cursor) {
                    pageInfo { hasNextPage endCursor }
                    nodes {
                      id isResolved isOutdated path line
                      comments(first: 50) {
                        nodes { id databaseId author { login } body url createdAt }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()

        private val PROJECT_ITEMS_QUERY =
            """
            query(${'$'}owner:String!, ${'$'}name:String!, ${'$'}number:Int!) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                issue(number: ${'$'}number) {
                  projectItems(first: 20) {
                    nodes {
                      id
                      project { id title number }
                      fieldValues(first: 30) {
                        nodes {
                          __typename
                          ... on ProjectV2ItemFieldTextValue {
                            text field { ... on ProjectV2FieldCommon { id name } }
                          }
                          ... on ProjectV2ItemFieldNumberValue {
                            number field { ... on ProjectV2FieldCommon { id name } }
                          }
                          ... on ProjectV2ItemFieldDateValue {
                            date field { ... on ProjectV2FieldCommon { id name } }
                          }
                          ... on ProjectV2ItemFieldSingleSelectValue {
                            name optionId field { ... on ProjectV2FieldCommon { id name } }
                          }
                          ... on ProjectV2ItemFieldIterationValue {
                            title iterationId startDate duration
                            field { ... on ProjectV2FieldCommon { id name } }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()

        private val PROJECT_SET_FIELD_MUTATION =
            """
            mutation(
              ${'$'}projectId:ID!, ${'$'}itemId:ID!, ${'$'}fieldId:ID!, ${'$'}value:ProjectV2FieldValue!
            ) {
              updateProjectV2ItemFieldValue(
                input: { projectId: ${'$'}projectId, itemId: ${'$'}itemId, fieldId: ${'$'}fieldId, value: ${'$'}value }
              ) { projectV2Item { id } }
            }
            """.trimIndent()
    }
}
