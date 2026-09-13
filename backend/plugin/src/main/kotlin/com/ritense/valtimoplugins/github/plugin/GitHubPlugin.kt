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

package com.ritense.valtimoplugins.github.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.plugin.annotation.Plugin
import com.ritense.plugin.annotation.PluginAction
import com.ritense.plugin.annotation.PluginActionProperty
import com.ritense.plugin.annotation.PluginProperty
import com.ritense.processlink.domain.ActivityTypeWithEventName.SERVICE_TASK_START
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimoplugins.github.client.GitHubException
import com.ritense.valtimoplugins.github.domain.GitHubConnectionProperties
import com.ritense.valtimoplugins.github.domain.RepositoryRef
import com.ritense.valtimoplugins.github.service.GitHubOperations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.delegate.DelegateExecution
import java.net.URI
import java.util.Base64

/**
 * Every GitHub operation a process might want, as service tasks.
 *
 * The actions map onto what a pipeline that keeps pull requests moving actually does: read
 * a backlog, pick up a ticket, branch, open a pull request, watch its checks, answer its
 * review threads, resolve them, and merge. Each one writes what it learned to a process
 * variable named by `resultVariable`, so the next step in the diagram can branch on it.
 *
 * Two actions — `rest-request` and `graphql-query` — take a raw path or document. They are
 * the deliberate escape hatch: GitHub's API is larger than any list of modelled actions and
 * grows without asking, and a process needing one endpoint nobody anticipated should not
 * need a new release of this plugin.
 */
@Plugin(
    key = GitHubPlugin.PLUGIN_KEY,
    title = "GitHub Plugin",
    description =
        "Read and write GitHub from a process: issues, pull requests, reviews, checks, " +
            "files and project boards",
)
@Suppress("TooManyFunctions", "LongParameterList")
open class GitHubPlugin(
    private val operations: GitHubOperations,
    private val storageService: TemporaryResourceStorageService,
    private val objectMapper: ObjectMapper,
) {
    @PluginProperty(key = "url", secret = false)
    lateinit var url: URI

    /**
     * A personal access token, a fine-grained token or an app installation token.
     *
     * Whatever this token may do, every process linked to this configuration may do. A
     * configuration that only reads should hold a token that only reads — the actions do
     * not and cannot check, and `rest-request` reaches anything the token reaches.
     */
    @PluginProperty(key = "token", secret = true)
    lateinit var token: String

    /**
     * `owner/repo` for every action that leaves its own `repository` empty. Optional: a
     * configuration used across repositories leaves it out and fills it in per action.
     */
    @PluginProperty(key = "defaultRepository", secret = false, required = false)
    var defaultRepository: String? = null

    /** GraphQL endpoint. Derived from [url] when empty, which is right for github.com. */
    @PluginProperty(key = "graphqlUrl", secret = false, required = false)
    var graphqlUrl: String? = null

    @PluginProperty(key = "perPage", secret = false, required = false)
    var perPage: Int? = null

    @PluginProperty(key = "maxPages", secret = false, required = false)
    var maxPages: Int? = null

    // ─── repository and viewer ──────────────────────────────────────────────

    @PluginAction(
        key = "get-repository",
        title = "Get repository",
        description = "Reads a repository: default branch, visibility, whether it is archived",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun getRepository(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(execution, resultVariable, operations.getRepository(connection(), repo(repository)))

    @PluginAction(
        key = "list-repositories",
        title = "List repositories",
        description = "Lists the repositories of an organisation or user",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun listRepositories(
        execution: DelegateExecution,
        @PluginActionProperty owner: String,
        @PluginActionProperty type: String?,
        @PluginActionProperty limit: Int?,
        @PluginActionProperty resultVariable: String?,
    ) = store(execution, resultVariable, operations.listRepositories(connection(), owner, type, limit))

    @PluginAction(
        key = "get-authenticated-user",
        title = "Get authenticated user",
        description = "Reports which account this configuration's token belongs to",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun getAuthenticatedUser(
        execution: DelegateExecution,
        @PluginActionProperty resultVariable: String?,
    ) = store(execution, resultVariable, operations.getAuthenticatedUser(connection()))

    // ─── issues ─────────────────────────────────────────────────────────────

    @PluginAction(
        key = "list-issues",
        title = "List issues",
        description = "Lists the issues of a repository. Open pull requests come back too — see isPullRequest",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun listIssues(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty state: String?,
        @PluginActionProperty labels: String?,
        @PluginActionProperty assignee: String?,
        @PluginActionProperty creator: String?,
        @PluginActionProperty since: String?,
        @PluginActionProperty limit: Int?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.listIssues(connection(), repo(repository), state, labels, assignee, creator, since, limit),
    )

    @PluginAction(
        key = "get-issue",
        title = "Get issue",
        description = "Reads one issue, optionally with its comments and its timeline",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun getIssue(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty issueNumber: Int,
        @PluginActionProperty includeComments: Boolean?,
        @PluginActionProperty includeTimeline: Boolean?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.getIssue(
            connection(),
            repo(repository),
            issueNumber,
            includeComments ?: false,
            includeTimeline ?: false,
        ),
    )

    @PluginAction(
        key = "search-issues",
        title = "Search issues and pull requests",
        description = "Runs a GitHub search query across repositories, for example 'org:ritense is:pr is:open'",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun searchIssues(
        execution: DelegateExecution,
        @PluginActionProperty query: String,
        @PluginActionProperty sort: String?,
        @PluginActionProperty order: String?,
        @PluginActionProperty limit: Int?,
        @PluginActionProperty resultVariable: String?,
    ) = store(execution, resultVariable, operations.search(connection(), query, sort, order, limit))

    @PluginAction(
        key = "create-issue",
        title = "Create issue",
        description = "Opens a new issue",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun createIssue(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty title: String,
        @PluginActionProperty body: String?,
        @PluginActionProperty labels: String?,
        @PluginActionProperty assignees: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.createIssue(connection(), repo(repository), title, body, csv(labels), csv(assignees)),
    )

    @PluginAction(
        key = "update-issue",
        title = "Update issue",
        description = "Edits an issue's title, body or state, and adds or removes labels and assignees",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun updateIssue(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty issueNumber: Int,
        @PluginActionProperty title: String?,
        @PluginActionProperty body: String?,
        @PluginActionProperty state: String?,
        @PluginActionProperty stateReason: String?,
        @PluginActionProperty addLabels: String?,
        @PluginActionProperty removeLabels: String?,
        @PluginActionProperty addAssignees: String?,
        @PluginActionProperty removeAssignees: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.updateIssue(
            connection(),
            repo(repository),
            issueNumber,
            title,
            body,
            state,
            stateReason,
            csv(addLabels),
            csv(removeLabels),
            csv(addAssignees),
            csv(removeAssignees),
        ),
    )

    @PluginAction(
        key = "comment",
        title = "Comment on issue or pull request",
        description = "Posts a comment. A pull request takes its number here just like an issue",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun comment(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty issueNumber: Int,
        @PluginActionProperty body: String,
        @PluginActionProperty resultVariable: String?,
    ) = store(execution, resultVariable, operations.addComment(connection(), repo(repository), issueNumber, body))

    // ─── pull requests ──────────────────────────────────────────────────────

    @PluginAction(
        key = "list-pull-requests",
        title = "List pull requests",
        description = "Lists the pull requests of one repository",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun listPullRequests(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty state: String?,
        @PluginActionProperty base: String?,
        @PluginActionProperty head: String?,
        @PluginActionProperty limit: Int?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.listPullRequests(connection(), repo(repository), state, base, head, limit),
    )

    @PluginAction(
        key = "get-pull-request",
        title = "Get pull request",
        description = "Reads one pull request, optionally with its files, reviews, comments and check status",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun getPullRequest(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty pullRequestNumber: Int,
        @PluginActionProperty includeFiles: Boolean?,
        @PluginActionProperty includeReviews: Boolean?,
        @PluginActionProperty includeComments: Boolean?,
        @PluginActionProperty includeChecks: Boolean?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.getPullRequest(
            connection(),
            repo(repository),
            pullRequestNumber,
            includeFiles ?: false,
            includeReviews ?: false,
            includeComments ?: false,
            includeChecks ?: false,
        ),
    )

    @PluginAction(
        key = "create-pull-request",
        title = "Create pull request",
        description = "Opens a pull request from an existing branch. Leave the base empty for the default branch",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun createPullRequest(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty title: String,
        @PluginActionProperty body: String?,
        @PluginActionProperty head: String,
        @PluginActionProperty base: String?,
        @PluginActionProperty draft: Boolean?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.createPullRequest(connection(), repo(repository), title, body, head, base, draft ?: false),
    )

    @PluginAction(
        key = "update-pull-request",
        title = "Update pull request",
        description = "Edits a pull request's title, body, base or state, and adds or removes labels",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun updatePullRequest(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty pullRequestNumber: Int,
        @PluginActionProperty title: String?,
        @PluginActionProperty body: String?,
        @PluginActionProperty base: String?,
        @PluginActionProperty state: String?,
        @PluginActionProperty addLabels: String?,
        @PluginActionProperty removeLabels: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.updatePullRequest(
            connection(),
            repo(repository),
            pullRequestNumber,
            title,
            body,
            base,
            state,
            csv(addLabels),
            csv(removeLabels),
        ),
    )

    @PluginAction(
        key = "set-pull-request-draft",
        title = "Mark pull request ready or draft",
        description = "Moves a pull request between draft and ready for review",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun setPullRequestDraft(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty pullRequestNumber: Int,
        @PluginActionProperty draft: Boolean,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.setPullRequestDraft(connection(), repo(repository), pullRequestNumber, draft),
    )

    @PluginAction(
        key = "merge-pull-request",
        title = "Merge pull request",
        description = "Merges a pull request. Fill in the expected head sha to refuse a branch that moved",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun mergePullRequest(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty pullRequestNumber: Int,
        @PluginActionProperty mergeMethod: String?,
        @PluginActionProperty commitTitle: String?,
        @PluginActionProperty commitMessage: String?,
        @PluginActionProperty expectedHeadSha: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.mergePullRequest(
            connection(),
            repo(repository),
            pullRequestNumber,
            mergeMethod,
            commitTitle,
            commitMessage,
            expectedHeadSha,
        ),
    )

    @PluginAction(
        key = "review-pull-request",
        title = "Review pull request",
        description = "Approves, requests changes on, or comments on a pull request",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun reviewPullRequest(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty pullRequestNumber: Int,
        @PluginActionProperty event: String,
        @PluginActionProperty body: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.reviewPullRequest(connection(), repo(repository), pullRequestNumber, event, body),
    )

    @PluginAction(
        key = "request-reviewers",
        title = "Request reviewers",
        description = "Asks people or teams to review a pull request",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun requestReviewers(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty pullRequestNumber: Int,
        @PluginActionProperty reviewers: String?,
        @PluginActionProperty teamReviewers: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.requestReviewers(
            connection(),
            repo(repository),
            pullRequestNumber,
            csv(reviewers),
            csv(teamReviewers),
        ),
    )

    @PluginAction(
        key = "list-review-threads",
        title = "List review threads",
        description = "Reads the inline review conversations of a pull request, with whether each is resolved",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun listReviewThreads(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty pullRequestNumber: Int,
        @PluginActionProperty onlyUnresolved: Boolean?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.listReviewThreads(
            connection(),
            repo(repository),
            pullRequestNumber,
            onlyUnresolved ?: false,
        ),
    )

    @PluginAction(
        key = "reply-to-review-comment",
        title = "Reply to review comment",
        description = "Answers a reviewer inside their own thread, rather than as a new comment",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun replyToReviewComment(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty pullRequestNumber: Int,
        @PluginActionProperty commentId: String,
        @PluginActionProperty body: String,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.replyToReviewComment(
            connection(),
            repo(repository),
            pullRequestNumber,
            commentId.trim().toLongOrNull()
                ?: throw GitHubException("commentId '$commentId' is not a comment id"),
            body,
        ),
    )

    @PluginAction(
        key = "resolve-review-thread",
        title = "Resolve review thread",
        description = "Marks a review conversation resolved, or reopens it",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun resolveReviewThread(
        execution: DelegateExecution,
        @PluginActionProperty threadId: String,
        @PluginActionProperty resolve: Boolean?,
        @PluginActionProperty resultVariable: String?,
    ) = store(execution, resultVariable, operations.resolveReviewThread(connection(), threadId, resolve ?: true))

    // ─── labels ─────────────────────────────────────────────────────────────

    @PluginAction(
        key = "create-label",
        title = "Create label",
        description = "Creates a label, and reports the existing one when it is already there",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun createLabel(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty name: String,
        @PluginActionProperty color: String?,
        @PluginActionProperty description: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.createLabel(connection(), repo(repository), name, color, description),
    )

    // ─── checks and workflow runs ───────────────────────────────────────────

    @PluginAction(
        key = "get-check-status",
        title = "Get check status",
        description = "Reports whether a commit, branch or tag is green, across check runs and commit statuses",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun getCheckStatus(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty ref: String,
        @PluginActionProperty resultVariable: String?,
    ) = store(execution, resultVariable, operations.getCheckStatus(connection(), repo(repository), ref))

    @PluginAction(
        key = "list-workflow-runs",
        title = "List workflow runs",
        description = "Lists GitHub Actions runs of a repository",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun listWorkflowRuns(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty branch: String?,
        @PluginActionProperty status: String?,
        @PluginActionProperty event: String?,
        @PluginActionProperty limit: Int?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.listWorkflowRuns(connection(), repo(repository), branch, status, event, limit),
    )

    @PluginAction(
        key = "get-workflow-run",
        title = "Get workflow run",
        description = "Reads one Actions run, optionally with its jobs and which of their steps failed",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun getWorkflowRun(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty runId: String,
        @PluginActionProperty includeJobs: Boolean?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.getWorkflowRun(connection(), repo(repository), id(runId, "runId"), includeJobs ?: true),
    )

    @PluginAction(
        key = "get-job-logs",
        title = "Get job logs",
        description = "Reads the tail of one Actions job's log, which is where a failing job says why",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun getJobLogs(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty jobId: String,
        @PluginActionProperty maxLines: Int?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.getJobLogs(
            connection(),
            repo(repository),
            id(jobId, "jobId"),
            maxLines ?: DEFAULT_LOG_LINES,
        ),
    )

    @PluginAction(
        key = "rerun-workflow",
        title = "Re-run workflow",
        description = "Re-runs an Actions run, by default only the jobs that failed",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun rerunWorkflow(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty runId: String,
        @PluginActionProperty onlyFailed: Boolean?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.rerunFailedJobs(connection(), repo(repository), id(runId, "runId"), onlyFailed ?: true),
    )

    // ─── contents and refs ──────────────────────────────────────────────────

    @PluginAction(
        key = "get-file-content",
        title = "Get file content",
        description = "Reads a file, or lists a directory, out of a repository",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun getFileContent(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty path: String,
        @PluginActionProperty ref: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(execution, resultVariable, operations.getFileContent(connection(), repo(repository), path, ref))

    /**
     * Commits a file straight to a branch, with no pull request in between.
     *
     * The content comes either from [content] typed into the process, or from a file already
     * in temporary resource storage under [resourceId] — which is the only one of the two
     * that can carry an image, and images are the usual reason to want this: publishing the
     * screenshots a case produced, so a pull request can link them instead of carrying them.
     */
    @PluginAction(
        key = "create-or-update-file",
        title = "Create or update file",
        description = "Commits a file to a branch. Takes text, or a file from temporary resource storage",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun createOrUpdateFile(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty path: String,
        @PluginActionProperty commitMessage: String,
        @PluginActionProperty content: String?,
        @PluginActionProperty resourceId: String?,
        @PluginActionProperty branch: String?,
        @PluginActionProperty expectedSha: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.createOrUpdateFile(
            connection(),
            repo(repository),
            path,
            commitMessage,
            contentBase64(content, resourceId),
            branch,
            expectedSha,
        ),
    )

    @PluginAction(
        key = "create-branch",
        title = "Create branch",
        description = "Branches off another ref, or off the default branch when none is given",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun createBranch(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty branchName: String,
        @PluginActionProperty fromRef: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.createBranch(connection(), repo(repository), branchName, fromRef),
    )

    // ─── projects v2 ────────────────────────────────────────────────────────

    @PluginAction(
        key = "get-project-items",
        title = "Get project items",
        description = "Reads the project cards an issue sits on, with every field value — sprint, status, estimate",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun getProjectItems(
        execution: DelegateExecution,
        @PluginActionProperty repository: String?,
        @PluginActionProperty issueNumber: Int,
        @PluginActionProperty resultVariable: String?,
    ) = store(execution, resultVariable, operations.getProjectItems(connection(), repo(repository), issueNumber))

    @PluginAction(
        key = "set-project-item-field",
        title = "Set project item field",
        description = "Sets one field on one project card. Ids come from 'Get project items'",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun setProjectItemField(
        execution: DelegateExecution,
        @PluginActionProperty projectId: String,
        @PluginActionProperty itemId: String,
        @PluginActionProperty fieldId: String,
        @PluginActionProperty valueType: String,
        @PluginActionProperty value: String,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.setProjectItemField(connection(), projectId, itemId, fieldId, valueType, value),
    )

    // ─── escape hatches ─────────────────────────────────────────────────────

    @PluginAction(
        key = "rest-request",
        title = "REST request",
        description = "Calls any GitHub REST endpoint. The answer is stored unmodified, not trimmed",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun restRequest(
        execution: DelegateExecution,
        @PluginActionProperty method: String?,
        @PluginActionProperty path: String,
        @PluginActionProperty body: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.restRequest(connection(), method ?: "GET", path, parseJson(body, "body")),
    )

    @PluginAction(
        key = "graphql-query",
        title = "GraphQL query",
        description = "Runs any GitHub GraphQL document. The data is stored unmodified, not trimmed",
        activityTypes = [SERVICE_TASK_START],
    )
    open fun graphqlQuery(
        execution: DelegateExecution,
        @PluginActionProperty query: String,
        @PluginActionProperty variables: String?,
        @PluginActionProperty resultVariable: String?,
    ) = store(
        execution,
        resultVariable,
        operations.graphqlQuery(connection(), query, parseJson(variables, "variables")),
    )

    // ─── plumbing ───────────────────────────────────────────────────────────

    /**
     * Resolves the configuration into the value object the client works with, applying
     * defaults here rather than in the frontend so that a configuration created through the
     * API behaves identically to one created in the admin UI.
     */
    fun connection(): GitHubConnectionProperties =
        GitHubConnectionProperties(
            baseUri = url,
            graphqlUri =
                graphqlUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { URI.create(it) }
                    ?: GitHubConnectionProperties.deriveGraphqlUri(url),
            token = token,
            defaultRepository = defaultRepository?.takeIf { it.isNotBlank() },
            perPage = perPage ?: DEFAULT_PER_PAGE,
            maxPages = maxPages ?: DEFAULT_MAX_PAGES,
        ).also { it.validate() }

    private fun repo(repository: String?): RepositoryRef {
        val value =
            repository?.takeIf { it.isNotBlank() }
                ?: defaultRepository?.takeIf { it.isNotBlank() }
                ?: throw GitHubException(
                    "No repository given, and this GitHub configuration has no default repository",
                )
        return RepositoryRef.parse(value)
    }

    /**
     * Writes the result of an action to a process variable.
     *
     * Converted out of Jackson's tree into plain maps and lists first: the process engine
     * serialises variables, and a JsonNode in a variable is a class the engine has no
     * business knowing about. An action with no `resultVariable` is one whose answer nobody
     * wanted — a comment posted, a label applied — and writes nothing.
     */
    private fun store(
        execution: DelegateExecution,
        resultVariable: String?,
        result: JsonNode,
    ) {
        val name = resultVariable?.trim()?.takeIf { it.isNotEmpty() } ?: return
        execution.setVariable(name, objectMapper.convertValue(result, Any::class.java))
        logger.debug { "Stored GitHub result in process variable '$name'" }
    }

    /**
     * Splits a comma-separated list of labels, logins or teams.
     *
     * A comma rather than a repeated property because these are configured in a single text
     * field in the admin UI, and because the values themselves cannot contain one: a GitHub
     * login cannot, and a label with a comma in it is rare enough to be worth not supporting
     * rather than making every ordinary case harder.
     */
    private fun csv(value: String?): List<String> =
        value
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun id(
        value: String,
        field: String,
    ): Long =
        value.trim().toLongOrNull()
            ?: throw GitHubException("$field '$value' is not a numeric id")

    private fun contentBase64(
        content: String?,
        resourceId: String?,
    ): String {
        require(!content.isNullOrEmpty() || !resourceId.isNullOrBlank()) {
            "Either content or resourceId is required"
        }
        require(content.isNullOrEmpty() || resourceId.isNullOrBlank()) {
            "Give either content or resourceId, not both"
        }
        val bytes =
            if (!resourceId.isNullOrBlank()) {
                storageService.getResourceContentAsInputStream(resourceId).use { it.readAllBytes() }
            } else {
                content!!.toByteArray(Charsets.UTF_8)
            }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun parseJson(
        value: String?,
        field: String,
    ): JsonNode? =
        value
            ?.takeIf { it.isNotBlank() }
            ?.let {
                runCatching { objectMapper.readTree(it) }
                    .getOrElse { failure -> throw GitHubException("$field is not valid JSON", cause = failure) }
            }

    companion object {
        private val logger = KotlinLogging.logger {}

        const val PLUGIN_KEY = "github"

        private const val DEFAULT_PER_PAGE = 100
        private const val DEFAULT_MAX_PAGES = 5
        private const val DEFAULT_LOG_LINES = 200
    }
}
