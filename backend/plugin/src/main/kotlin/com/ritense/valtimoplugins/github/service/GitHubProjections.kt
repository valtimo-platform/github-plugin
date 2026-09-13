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
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Trims GitHub's answers down to the fields a process has a use for.
 *
 * Two reasons this exists rather than the raw payload going straight into a process
 * variable. One is size: a single pull request is some fifteen kilobytes of nested user,
 * repository and branch objects repeated for head and base, and a list action writing
 * fifty of those is a process variable nobody can read and a document nobody wants to
 * store. The other is shape — `user.login` and `author.login` are the same fact under two
 * names depending on which endpoint answered, and a process author should not have to know
 * which one they are looking at. Everything here reports an author as `author`.
 *
 * The escape hatches (`rest-request`, `graphql-query`) deliberately skip this: when the
 * modelled shape is not what you need, the raw one still is.
 */
object GitHubProjections {
    private val nodes = JsonNodeFactory.instance

    fun repository(node: JsonNode): ObjectNode =
        obj {
            put("id", node.path("id").asLong())
            put("name", node.text("name"))
            put("description", node.text("description"))
            put("visibility", node.text("visibility"))
            put("archived", node.path("archived").asBoolean(false))
            put("disabled", node.path("disabled").asBoolean(false))
            put("fork", node.path("fork").asBoolean(false))
            put("fullName", node.text("full_name"))
            put("owner", node.path("owner").text("login"))
            put("private", node.path("private").asBoolean(false))
            put("defaultBranch", node.text("default_branch"))
            put("url", node.text("html_url"))
            put("hasIssues", node.path("has_issues").asBoolean(false))
            put("updatedAt", node.text("updated_at"))
            put("pushedAt", node.text("pushed_at"))
            put("openIssuesCount", node.path("open_issues_count").asInt(0))
        }

    /**
     * An issue, or a pull request seen through the issues API — GitHub serves both from
     * `/issues`, and `isPullRequest` is how a caller tells them apart. Anything reading an
     * issue list has to: `list-issues` on a repository returns its open pull requests too.
     */
    fun issue(
        node: JsonNode,
        comments: ArrayNode? = null,
        timeline: ArrayNode? = null,
    ): ObjectNode =
        obj {
            put("number", node.path("number").asInt())
            put("title", node.text("title"))
            put("body", node.text("body"))
            put("state", node.text("state"))
            put("stateReason", node.text("state_reason"))
            put("author", node.path("user").text("login"))
            put("url", node.text("html_url"))
            put("createdAt", node.text("created_at"))
            put("updatedAt", node.text("updated_at"))
            put("closedAt", node.text("closed_at"))
            put("commentCount", node.path("comments").asInt(0))
            put("isPullRequest", node.has("pull_request"))
            put("draft", node.path("draft").asBoolean(false))
            set<ArrayNode>("labels", labelNames(node.path("labels")))
            set<ArrayNode>("assignees", logins(node.path("assignees")))
            node.path("milestone").takeIf { it.isObject }?.let { put("milestone", it.text("title")) }
            comments?.let { set<ArrayNode>("comments", it) }
            timeline?.let { set<ArrayNode>("timeline", it) }
        }

    fun comment(node: JsonNode): ObjectNode =
        obj {
            put("id", node.path("id").asLong())
            put("author", node.path("user").text("login"))
            put("body", node.text("body"))
            put("url", node.text("html_url"))
            put("createdAt", node.text("created_at"))
            put("updatedAt", node.text("updated_at"))
        }

    /**
     * One entry of an issue's timeline. Heterogeneous by nature — a label added, a commit
     * referenced, a pull request cross-referencing this issue — so only the fields every
     * event has are lifted, plus the cross-reference itself, which is the one plugin-central
     * actually reads: it is how "is there already a pull request for this ticket" is
     * answered without searching.
     */
    fun timelineEvent(node: JsonNode): ObjectNode =
        obj {
            put("event", node.text("event"))
            put("actor", node.path("actor").text("login"))
            put("createdAt", node.text("created_at"))
            node.path("label").takeIf { it.isObject }?.let { put("label", it.text("name")) }
            node.path("source").path("issue").takeIf { it.isObject }?.let { source ->
                put("sourceNumber", source.path("number").asInt())
                put("sourceUrl", source.text("html_url"))
                put("sourceState", source.text("state"))
                put("sourceIsPullRequest", source.has("pull_request"))
                put("sourceRepository", source.path("repository").text("full_name"))
            }
        }

    fun pullRequest(node: JsonNode): ObjectNode =
        obj {
            put("number", node.path("number").asInt())
            put("title", node.text("title"))
            put("body", node.text("body"))
            put("state", node.text("state"))
            put("draft", node.path("draft").asBoolean(false))
            put("author", node.path("user").text("login"))
            put("url", node.text("html_url"))
            put("createdAt", node.text("created_at"))
            put("updatedAt", node.text("updated_at"))
            put("mergedAt", node.text("merged_at"))
            put("closedAt", node.text("closed_at"))
            put("merged", node.path("merged").asBoolean(false))
            // Absent on a list response, and null on a detail response while GitHub is
            // still computing it — which is a third state, not a `false`.
            node.path("mergeable").takeIf { !it.isMissingNode && !it.isNull }?.let {
                put("mergeable", it.asBoolean())
            }
            put("mergeableState", node.text("mergeable_state"))
            put("headRef", node.path("head").text("ref"))
            put("headSha", node.path("head").text("sha"))
            put("headRepository", node.path("head").path("repo").text("full_name"))
            put("baseRef", node.path("base").text("ref"))
            put("baseRepository", node.path("base").path("repo").text("full_name"))
            put("changedFiles", node.path("changed_files").asInt(0))
            put("additions", node.path("additions").asInt(0))
            put("deletions", node.path("deletions").asInt(0))
            set<ArrayNode>("labels", labelNames(node.path("labels")))
            set<ArrayNode>("assignees", logins(node.path("assignees")))
            set<ArrayNode>("requestedReviewers", logins(node.path("requested_reviewers")))
        }

    /** The body somebody typed into the Approve / Request changes box, plus its verdict. */
    fun review(node: JsonNode): ObjectNode =
        obj {
            put("id", node.path("id").asLong())
            put("author", node.path("user").text("login"))
            put("state", node.text("state"))
            put("body", node.text("body"))
            put("url", node.text("html_url"))
            put("submittedAt", node.text("submitted_at"))
        }

    fun pullRequestFile(node: JsonNode): ObjectNode =
        obj {
            put("filename", node.text("filename"))
            put("status", node.text("status"))
            put("additions", node.path("additions").asInt(0))
            put("deletions", node.path("deletions").asInt(0))
            put("changes", node.path("changes").asInt(0))
            put("previousFilename", node.text("previous_filename"))
        }

    /**
     * A check run or a commit status, normalised onto one shape.
     *
     * GitHub keeps them apart — a check run has `conclusion`, a status has `state` — and
     * "is this pull request green" is a question about both. A process asking it should not
     * have to merge two lists itself.
     */
    fun check(
        name: String?,
        status: String?,
        conclusion: String?,
        url: String?,
        kind: String,
    ): ObjectNode =
        obj {
            put("name", name)
            put("status", status)
            put("conclusion", conclusion)
            put("url", url)
            put("kind", kind)
        }

    fun workflowRun(node: JsonNode): ObjectNode =
        obj {
            put("id", node.path("id").asLong())
            put("name", node.text("name"))
            put("runNumber", node.path("run_number").asInt())
            put("attempt", node.path("run_attempt").asInt())
            put("event", node.text("event"))
            put("status", node.text("status"))
            put("conclusion", node.text("conclusion"))
            put("branch", node.text("head_branch"))
            put("headSha", node.text("head_sha"))
            put("url", node.text("html_url"))
            put("createdAt", node.text("created_at"))
            put("updatedAt", node.text("updated_at"))
        }

    fun job(node: JsonNode): ObjectNode =
        obj {
            put("id", node.path("id").asLong())
            put("name", node.text("name"))
            put("status", node.text("status"))
            put("conclusion", node.text("conclusion"))
            put("url", node.text("html_url"))
            put("startedAt", node.text("started_at"))
            put("completedAt", node.text("completed_at"))
            set<ArrayNode>(
                "failedSteps",
                array(
                    node
                        .path("steps")
                        .filter { it.text("conclusion") == "failure" }
                        .map { step ->
                            obj {
                                put("name", step.text("name"))
                                put("number", step.path("number").asInt())
                            }
                        },
                ),
            )
        }

    fun label(node: JsonNode): ObjectNode =
        obj {
            put("name", node.text("name"))
            put("color", node.text("color"))
            put("description", node.text("description"))
        }

    fun labelNames(node: JsonNode): ArrayNode =
        array(
            node.mapNotNull { label ->
                if (label.isTextual) label.asText() else label.text("name")
            },
        )

    fun logins(node: JsonNode): ArrayNode = array(node.mapNotNull { it.text("login") })

    /** Wraps a list action's result so the truncation flag travels with the items. */
    fun list(
        items: ArrayNode,
        truncated: Boolean,
        totalCount: Int? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): ObjectNode =
        obj {
            set<ArrayNode>("items", items)
            put("count", items.size())
            put("truncated", truncated)
            totalCount?.let { put("totalCount", it) }
            extra.forEach { (key, value) ->
                when (value) {
                    null -> putNull(key)
                    is JsonNode -> set<JsonNode>(key, value)
                    is Boolean -> put(key, value)
                    is Int -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }

    fun array(values: Iterable<JsonNode>): ArrayNode = nodes.arrayNode().addAll(values.toList())

    @JvmName("arrayOfStrings")
    fun array(values: Iterable<String>): ArrayNode = nodes.arrayNode().apply { values.forEach { add(it) } }

    fun obj(build: ObjectNode.() -> Unit): ObjectNode = nodes.objectNode().apply(build)

    /** Null rather than the string `"null"`, and null rather than `""`, for an absent field. */
    private fun JsonNode.text(field: String): String? =
        get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() }
}
