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
import com.ritense.valtimoplugins.github.BaseTest
import com.ritense.valtimoplugins.github.client.GitHubClient
import com.ritense.valtimoplugins.github.client.GitHubException
import com.ritense.valtimoplugins.github.domain.GitHubConnectionProperties
import com.ritense.valtimoplugins.github.domain.RepositoryRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.URI
import java.util.Base64

class GitHubOperationsTest : BaseTest() {
    private lateinit var client: GitHubClient
    private lateinit var operations: GitHubOperations

    private val connection =
        GitHubConnectionProperties(
            baseUri = URI.create("https://api.github.com"),
            graphqlUri = URI.create("https://api.github.com/graphql"),
            token = "a-token",
            defaultRepository = null,
            perPage = 100,
            maxPages = 5,
        )
    private val repository = RepositoryRef("valtimo-platform", "github-plugin")

    @BeforeEach
    fun setUp() {
        client = mock()
        operations = GitHubOperations(client, objectMapper)
    }

    // ─── checks ─────────────────────────────────────────────────────────────

    /**
     * The two halves matter: Actions posts check runs, most external CI posts commit
     * statuses, and a process reading only the first would call a repository green while its
     * external build was red.
     */
    @Test
    fun `should call a commit red when a commit status failed and every check run passed`() {
        whenever(client.getPaged(any(), eq("/repos/$repository/commits/abc123/check-runs"), any(), anyOrNull()))
            .thenReturn(paged("""[{"name":"build","status":"completed","conclusion":"success"}]"""))
        whenever(client.get(any(), eq("/repos/$repository/commits/abc123/status"), any()))
            .thenReturn(json("""{"statuses":[{"context":"sonar","state":"failure"}]}"""))

        val result = operations.getCheckStatus(connection, repository, "abc123")

        assertThat(result.path("state").asText()).isEqualTo("failure")
        assertThat(result.path("total").asInt()).isEqualTo(2)
        assertThat(result.path("failing").asInt()).isEqualTo(1)
        assertThat(
            result
                .path("failingChecks")
                .first()
                .path("name")
                .asText(),
        ).isEqualTo("sonar")
    }

    @Test
    fun `should call a commit pending while a check run is still going`() {
        whenever(client.getPaged(any(), eq("/repos/$repository/commits/main/check-runs"), any(), anyOrNull()))
            .thenReturn(paged("""[{"name":"build","status":"in_progress","conclusion":null}]"""))
        whenever(client.get(any(), eq("/repos/$repository/commits/main/status"), any()))
            .thenReturn(json("""{"statuses":[]}"""))

        assertThat(operations.getCheckStatus(connection, repository, "main").path("state").asText())
            .isEqualTo("pending")
    }

    /**
     * A commit nobody built is not a commit that passed. Reporting `success` would let a
     * process merge a branch whose CI never ran.
     */
    @Test
    fun `should distinguish a commit with no checks at all from a green one`() {
        whenever(client.getPaged(any(), eq("/repos/$repository/commits/main/check-runs"), any(), anyOrNull()))
            .thenReturn(paged("[]"))
        whenever(client.get(any(), eq("/repos/$repository/commits/main/status"), any()))
            .thenReturn(json("""{"statuses":[]}"""))

        assertThat(operations.getCheckStatus(connection, repository, "main").path("state").asText())
            .isEqualTo("none")
    }

    // ─── labels ─────────────────────────────────────────────────────────────

    /**
     * A process that labels its own pull requests creates the label on the first run and
     * finds it there on the second. Failing then would mean a try/catch in every diagram
     * around a step that only exists to make a later step work.
     */
    @Test
    fun `should treat a label that already exists as success and say it did not create it`() {
        whenever(client.post(any(), eq("/repos/$repository/labels"), any()))
            .thenThrow(GitHubException("already_exists", status = 422))
        whenever(client.get(any(), eq("/repos/$repository/labels/maintained-by-ai"), any()))
            .thenReturn(json("""{"name":"maintained-by-ai","color":"0E8A16"}"""))

        val result = operations.createLabel(connection, repository, "maintained-by-ai", "0E8A16", null)

        assertThat(result.path("created").asBoolean()).isFalse()
        assertThat(result.path("name").asText()).isEqualTo("maintained-by-ai")
    }

    @Test
    fun `should not swallow a refusal to create a label that is not about it existing`() {
        whenever(client.post(any(), eq("/repos/$repository/labels"), any()))
            .thenThrow(GitHubException("Resource not accessible by integration", status = 403))

        assertThatThrownBy { operations.createLabel(connection, repository, "x", null, null) }
            .isInstanceOf(GitHubException::class.java)
            .hasMessageContaining("not accessible")
    }

    @Test
    fun `should accept a colour written with a leading hash`() {
        whenever(client.post(any(), eq("/repos/$repository/labels"), any()))
            .thenReturn(json("""{"name":"feedback","color":"d73a4a"}"""))

        operations.createLabel(connection, repository, "feedback", "#d73a4a", null)

        verify(client).post(any(), eq("/repos/$repository/labels"), argThatColourIs("d73a4a"))
    }

    // ─── issues ─────────────────────────────────────────────────────────────

    /**
     * Removing a label that is not there leaves the issue in exactly the state the caller
     * asked for, and GitHub says 404. Anything else it says is a real refusal.
     */
    @Test
    fun `should accept removing a label the issue does not carry`() {
        whenever(client.delete(any(), eq("/repos/$repository/issues/12/labels/feedback"), any()))
            .thenThrow(GitHubException("Label does not exist", status = 404))
        whenever(client.get(any(), eq("/repos/$repository/issues/12"), any()))
            .thenReturn(json("""{"number":12,"title":"t"}"""))

        val result = updateIssueRemoving("feedback")

        assertThat(result.path("number").asInt()).isEqualTo(12)
    }

    @Test
    fun `should not swallow a refusal to remove a label that is not a 404`() {
        whenever(client.delete(any(), eq("/repos/$repository/issues/12/labels/feedback"), any()))
            .thenThrow(GitHubException("Resource not accessible by integration", status = 403))

        assertThatThrownBy { updateIssueRemoving("feedback") }
            .isInstanceOf(GitHubException::class.java)
    }

    /**
     * Labels and assignees go through their own endpoints, so an edit with nothing else in
     * it must not also PATCH the issue — a PATCH with an empty body is a write nobody asked
     * for, and it moves `updated_at` on a tracker other people are watching.
     */
    @Test
    fun `should not patch the issue when only labels change`() {
        whenever(client.post(any(), eq("/repos/$repository/issues/12/labels"), any()))
            .thenReturn(json("[]"))
        whenever(client.get(any(), eq("/repos/$repository/issues/12"), any()))
            .thenReturn(json("""{"number":12}"""))

        operations.updateIssue(
            connection,
            repository,
            12,
            null,
            null,
            null,
            null,
            listOf("created-by-ai"),
            emptyList(),
            emptyList(),
            emptyList(),
        )

        verify(client, never()).patch(any(), any(), any())
        verify(client).post(any(), eq("/repos/$repository/issues/12/labels"), any())
    }

    // ─── pull requests ──────────────────────────────────────────────────────

    /**
     * Not every repository branches off `main` — several of these branch off `master`, and
     * one off `v13`. Guessing would open the pull request against a branch that does not
     * exist, or worse, one that does and is not the one intended.
     */
    @Test
    fun `should ask the repository for its default branch when no base is given`() {
        whenever(client.get(any(), eq("/repos/$repository"), any()))
            .thenReturn(json("""{"default_branch":"v13"}"""))
        whenever(client.post(any(), eq("/repos/$repository/pulls"), any()))
            .thenReturn(json("""{"number":5,"base":{"ref":"v13"}}"""))

        operations.createPullRequest(connection, repository, "Title", null, "feature/x", null, false)

        verify(client).post(any(), eq("/repos/$repository/pulls"), argThatBaseIs("v13"))
    }

    @Test
    fun `should refuse a review that requests changes without saying what they are`() {
        assertThatThrownBy {
            operations.reviewPullRequest(connection, repository, 5, "REQUEST_CHANGES", "  ")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("body is required")

        verify(client, never()).post(any(), any(), any())
    }

    @Test
    fun `should allow an approval with no words`() {
        whenever(client.post(any(), eq("/repos/$repository/pulls/5/reviews"), any()))
            .thenReturn(json("""{"id":1,"state":"APPROVED"}"""))

        assertThat(operations.reviewPullRequest(connection, repository, 5, "approve", null).path("state").asText())
            .isEqualTo("APPROVED")
    }

    @Test
    fun `should refuse a review verdict github has no name for`() {
        assertThatThrownBy { operations.reviewPullRequest(connection, repository, 5, "LGTM", "x") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("APPROVE")
    }

    // ─── review threads ─────────────────────────────────────────────────────

    /**
     * The resolved flag is the whole reason this goes through GraphQL: REST's comment list
     * has no notion of a thread, so a process reading it cannot tell an answered remark from
     * an open one and would work through the same feedback on every pass.
     */
    @Test
    fun `should drop resolved threads when only the open ones were asked for`() {
        whenever(client.graphql(any(), any(), anyOrNull()))
            .thenReturn(
                json(
                    """
                    {"repository":{"pullRequest":{"reviewThreads":{
                      "pageInfo":{"hasNextPage":false,"endCursor":null},
                      "nodes":[
                        {"id":"T1","isResolved":true,"path":"a.kt","line":1,
                         "comments":{"nodes":[{"databaseId":1,"author":{"login":"x"},"body":"old"}]}},
                        {"id":"T2","isResolved":false,"path":"b.kt","line":2,
                         "comments":{"nodes":[{"databaseId":2,"author":{"login":"y"},"body":"new"}]}}
                      ]}}}}
                    """.trimIndent(),
                ),
            )

        val result = operations.listReviewThreads(connection, repository, 5, onlyUnresolved = true)

        assertThat(result.path("count").asInt()).isEqualTo(1)
        assertThat(
            result
                .path("items")
                .first()
                .path("id")
                .asText(),
        ).isEqualTo("T2")
        assertThat(
            result
                .path("items")
                .first()
                .path("comments")
                .first()
                .path("id")
                .asLong(),
        ).isEqualTo(2)
    }

    @Test
    fun `should keep following graphql pages while there are more threads`() {
        whenever(client.graphql(any(), any(), anyOrNull()))
            .thenReturn(threadPage("T1", hasNext = true, cursor = "c1"))
            .thenReturn(threadPage("T2", hasNext = false, cursor = null))

        val result = operations.listReviewThreads(connection, repository, 5, onlyUnresolved = false)

        assertThat(result.path("count").asInt()).isEqualTo(2)
        verify(client, times(2)).graphql(any(), any(), anyOrNull())
    }

    // ─── file contents ──────────────────────────────────────────────────────

    @Test
    fun `should decode a text file`() {
        val encoded = Base64.getEncoder().encodeToString("# Coding guidelines\n".toByteArray())
        whenever(client.get(any(), eq("/repos/$repository/contents/CODING-GUIDELINES.md"), any()))
            .thenReturn(json("""{"path":"CODING-GUIDELINES.md","sha":"s1","content":"$encoded"}"""))

        val result = operations.getFileContent(connection, repository, "CODING-GUIDELINES.md", null)

        assertThat(result.path("content").asText()).isEqualTo("# Coding guidelines\n")
        assertThat(result.path("binary").asBoolean()).isFalse()
    }

    /**
     * A repository holds PNGs as readily as it holds markdown, and turning one into a
     * mojibake string is worse than saying it is binary and handing back the base64.
     */
    @Test
    fun `should leave a binary file as base64 rather than mangling it into text`() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x1A))
        whenever(client.get(any(), eq("/repos/$repository/contents/run.gif"), any()))
            .thenReturn(json("""{"path":"run.gif","sha":"s1","content":"$encoded"}"""))

        val result = operations.getFileContent(connection, repository, "run.gif", null)

        assertThat(result.path("binary").asBoolean()).isTrue()
        assertThat(result.path("contentBase64").asText()).isEqualTo(encoded)
        assertThat(result.has("content")).isFalse()
    }

    @Test
    fun `should list a directory rather than pretend it is a file`() {
        whenever(client.get(any(), eq("/repos/$repository/contents/.claude/context"), any()))
            .thenReturn(json("""[{"name":"a.md","path":".claude/context/a.md","type":"file","size":10}]"""))

        val result = operations.getFileContent(connection, repository, ".claude/context", null)

        assertThat(result.path("type").asText()).isEqualTo("directory")
        assertThat(
            result
                .path("entries")
                .first()
                .path("name")
                .asText(),
        ).isEqualTo("a.md")
    }

    /**
     * GitHub requires the blob sha to replace a file. Looking it up rather than demanding it
     * is what keeps "write this file" one step in a diagram that runs more than once.
     */
    @Test
    fun `should look up the existing blob sha so a second write replaces rather than fails`() {
        whenever(client.get(any(), eq("/repos/$repository/contents/docs/run.md"), any()))
            .thenReturn(json("""{"sha":"existing-sha"}"""))
        whenever(client.put(any(), eq("/repos/$repository/contents/docs/run.md"), any()))
            .thenReturn(json("""{"content":{"path":"docs/run.md","sha":"new-sha"},"commit":{"sha":"c1"}}"""))

        val result = operations.createOrUpdateFile(connection, repository, "docs/run.md", "msg", "eA==", "main", null)

        assertThat(result.path("updated").asBoolean()).isTrue()
        assertThat(result.path("sha").asText()).isEqualTo("new-sha")
    }

    @Test
    fun `should create a file that is not there yet`() {
        whenever(client.get(any(), eq("/repos/$repository/contents/docs/new.md"), any()))
            .thenThrow(GitHubException("Not Found", status = 404))
        whenever(client.put(any(), eq("/repos/$repository/contents/docs/new.md"), any()))
            .thenReturn(json("""{"content":{"path":"docs/new.md","sha":"s"},"commit":{"sha":"c1"}}"""))

        assertThat(
            operations
                .createOrUpdateFile(connection, repository, "docs/new.md", "msg", "eA==", null, null)
                .path("updated")
                .asBoolean(),
        ).isFalse()
    }

    // ─── branches ───────────────────────────────────────────────────────────

    @Test
    fun `should report an existing branch instead of failing on a second run`() {
        whenever(client.get(any(), eq("/repos/$repository"), any()))
            .thenReturn(json("""{"default_branch":"main"}"""))
        whenever(client.get(any(), eq("/repos/$repository/commits/main"), any()))
            .thenReturn(json("""{"sha":"base-sha"}"""))
        whenever(client.post(any(), eq("/repos/$repository/git/refs"), any()))
            .thenThrow(GitHubException("Reference already exists", status = 422))
        whenever(client.get(any(), eq("/repos/$repository/git/ref/heads/feature/x"), any()))
            .thenReturn(json("""{"object":{"sha":"existing-sha"}}"""))

        val result = operations.createBranch(connection, repository, "feature/x", null)

        assertThat(result.path("created").asBoolean()).isFalse()
        assertThat(result.path("sha").asText()).isEqualTo("existing-sha")
    }

    // ─── projects ───────────────────────────────────────────────────────────

    @Test
    fun `should refuse a project field value type github has no input for`() {
        assertThatThrownBy {
            operations.setProjectItemField(connection, "P", "I", "F", "singleSelectOption", "x")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("singleSelectOptionId")
    }

    @Test
    fun `should refuse a number field whose value is not a number`() {
        assertThatThrownBy {
            operations.setProjectItemField(connection, "P", "I", "F", "number", "three")
        }.isInstanceOf(GitHubException::class.java)
            .hasMessageContaining("not a number")
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun updateIssueRemoving(label: String) =
        operations.updateIssue(
            connection,
            repository,
            12,
            null,
            null,
            null,
            null,
            emptyList(),
            listOf(label),
            emptyList(),
            emptyList(),
        )

    private fun threadPage(
        id: String,
        hasNext: Boolean,
        cursor: String?,
    ): JsonNode =
        json(
            """
            {"repository":{"pullRequest":{"reviewThreads":{
              "pageInfo":{"hasNextPage":$hasNext,"endCursor":${cursor?.let { "\"$it\"" } ?: "null"}},
              "nodes":[{"id":"$id","isResolved":false,"path":"a.kt","line":1,"comments":{"nodes":[]}}]
            }}}}
            """.trimIndent(),
        )

    private fun paged(body: String) =
        GitHubClient.PagedResult(objectMapper.readTree(body) as com.fasterxml.jackson.databind.node.ArrayNode, false)

    private fun json(body: String): JsonNode = objectMapper.readTree(body)

    private fun argThatColourIs(colour: String) =
        org.mockito.kotlin.argThat<Any> { (this as JsonNode).path("color").asText() == colour }

    private fun argThatBaseIs(base: String) =
        org.mockito.kotlin.argThat<Any> { (this as JsonNode).path("base").asText() == base }
}
