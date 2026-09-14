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

package com.ritense.valtimoplugins.github.client

import com.ritense.valtimoplugins.github.BaseTest
import com.ritense.valtimoplugins.github.domain.GitHubConnectionProperties
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.URI

class GitHubClientTest : BaseTest() {
    private lateinit var server: MockWebServer
    private lateinit var client: GitHubClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GitHubClient(RestClient.builder(), objectMapper)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `should authenticate and pin the api version on every call`() {
        enqueue("""{"login":"Klaas-Ritense"}""")

        client.get(connection(), "/user")

        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer a-token")
        assertThat(request.getHeader("Accept")).isEqualTo("application/vnd.github+json")
        // Pinned deliberately: without it, GitHub's default version moves under us.
        assertThat(request.getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28")
    }

    @Test
    fun `should follow the link header until the list is exhausted`() {
        enqueuePage("""[{"number":1},{"number":2}]""", next = "/repos/o/r/issues?page=2")
        enqueue("""[{"number":3}]""")

        val result = client.getPaged(connection(), "/repos/o/r/issues")

        assertThat(result.items.map { it.path("number").asInt() }).containsExactly(1, 2, 3)
        assertThat(result.truncated).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
    }

    /**
     * The flag matters more than the items. A process branching on "no pull requests left to
     * handle" takes the wrong branch when a page budget ran out, and a short list and a
     * truncated one are indistinguishable without it.
     */
    @Test
    fun `should report a read that ran out of pages as truncated`() {
        enqueuePage("""[{"number":1}]""", next = "/repos/o/r/issues?page=2")
        enqueuePage("""[{"number":2}]""", next = "/repos/o/r/issues?page=3")

        val result = client.getPaged(connection(maxPages = 2), "/repos/o/r/issues")

        assertThat(result.items).hasSize(2)
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `should stop at the requested limit and say the list went on`() {
        enqueuePage("""[{"number":1},{"number":2},{"number":3}]""", next = "/repos/o/r/issues?page=2")

        val result = client.getPaged(connection(), "/repos/o/r/issues", limit = 2)

        assertThat(result.items).hasSize(2)
        assertThat(result.truncated).isTrue()
    }

    /**
     * A list that happens to be exactly as long as the limit was not cut short. Reporting it
     * as truncated sends a process down the "there is more to do" branch on every run.
     */
    @Test
    fun `should not call a list truncated when it ended exactly on the limit`() {
        enqueue("""[{"number":1},{"number":2}]""")

        val result = client.getPaged(connection(), "/repos/o/r/issues", limit = 2)

        assertThat(result.items).hasSize(2)
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `should call a list that ended on the limit truncated when github offered another page`() {
        enqueuePage("""[{"number":1},{"number":2}]""", next = "/repos/o/r/issues?page=2")

        val result = client.getPaged(connection(), "/repos/o/r/issues", limit = 2)

        assertThat(result.items).hasSize(2)
        assertThat(result.truncated).isTrue()
    }

    /**
     * An Actions job log is not JSON: GitHub answers 302 to a pre-signed blob URL. Following
     * it is the only way to get the log at all — without it the body is empty and a caller
     * cannot tell that from a job that logged nothing.
     */
    @Test
    fun `should follow the redirect github serves a job log through`() {
        val blob = MockWebServer()
        blob.start()
        blob.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("setup\nBUILD FAILED"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", blob.url("/log.txt").toString()))

        val text = client.getText(connection(), "/repos/o/r/actions/jobs/1/logs")

        assertThat(text).isEqualTo("setup\nBUILD FAILED")
        blob.shutdown()
    }

    /**
     * The signature on the blob URL is what authorises the second call. Carrying the GitHub
     * token to a host that is not GitHub would hand a credential to a third party that never
     * needed it.
     */
    @Test
    fun `should not carry the github token to the storage host it is redirected to`() {
        val blob = MockWebServer()
        blob.start()
        blob.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("log"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", blob.url("/log.txt").toString()))

        client.getText(connection(), "/repos/o/r/actions/jobs/1/logs")

        assertThat(blob.takeRequest().getHeader("Authorization")).isNull()
        blob.shutdown()
    }

    @Test
    fun `should report no log rather than an empty one when github offers nothing to fetch`() {
        server.enqueue(MockResponse().setResponseCode(204))

        assertThat(client.getText(connection(), "/repos/o/r/actions/jobs/1/logs")).isNull()
    }

    @Test
    fun `should unwrap a search response and keep what github said the total was`() {
        enqueue("""{"total_count":1337,"incomplete_results":false,"items":[{"number":7}]}""")

        val result = client.getPaged(connection(), "/search/issues", mapOf("q" to "is:pr"))

        assertThat(result.items.map { it.path("number").asInt() }).containsExactly(7)
        assertThat(result.totalCount).isEqualTo(1337)
    }

    @Test
    fun `should unwrap a list that github nested in an object`() {
        enqueue("""{"total_count":2,"workflow_runs":[{"id":1},{"id":2}]}""")

        val result = client.getPaged(connection(), "/repos/o/r/actions/runs")

        assertThat(result.items.map { it.path("id").asInt() }).containsExactly(1, 2)
    }

    @Test
    fun `should carry github's reason for refusing a write into the exception`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"message":"Validation Failed",
                     "errors":[{"resource":"PullRequest","field":"head","message":"No commits between main and x"}]}
                    """.trimIndent(),
                ),
        )

        assertThatThrownBy { client.post(connection(), "/repos/o/r/pulls", mapOf("title" to "x")) }
            .isInstanceOf(GitHubException::class.java)
            .hasMessageContaining("422")
            .hasMessageContaining("Validation Failed")
            .hasMessageContaining("No commits between main and x")
    }

    @Test
    fun `should expose the status so a caller can tell a 404 from a refusal`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))

        val thrown = catchGitHubException { client.get(connection(), "/repos/o/r") }

        assertThat(thrown.status).isEqualTo(404)
    }

    /**
     * GraphQL answers 200 with an `errors` array, so nothing in the status check sees it. A
     * process that asked for a pull request's review threads and silently got nothing back
     * would report there was nothing left to answer.
     */
    @Test
    fun `should turn a graphql error into a failure rather than an empty answer`() {
        enqueue("""{"data":null,"errors":[{"message":"Could not resolve to a Repository"}]}""")

        assertThatThrownBy { client.graphql(connection(), "query { viewer { login } }") }
            .isInstanceOf(GitHubException::class.java)
            .hasMessageContaining("Could not resolve to a Repository")
    }

    @Test
    fun `should return the data of a successful graphql call`() {
        enqueue("""{"data":{"viewer":{"login":"Klaas-Ritense"}}}""")

        val data = client.graphql(connection(), "query { viewer { login } }")

        assertThat(data.path("viewer").path("login").asText()).isEqualTo("Klaas-Ritense")
    }

    @Test
    fun `should tolerate the empty body a write sometimes answers with`() {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.delete(connection(), "/repos/o/r/issues/1/labels/feedback")

        assertThat(result.isNull).isTrue()
    }

    @Test
    fun `should refuse an http method it does not know`() {
        assertThatThrownBy { client.request(connection(), "FETCH", "/user") }
            .isInstanceOf(GitHubException::class.java)
            .hasMessageContaining("FETCH")
    }

    private fun catchGitHubException(call: () -> Unit): GitHubException =
        runCatching(call).exceptionOrNull() as? GitHubException
            ?: error("Expected a GitHubException")

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(body),
        )
    }

    private fun enqueuePage(
        body: String,
        next: String,
    ) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Link", "<${server.url(next)}>; rel=\"next\", <${server.url(next)}>; rel=\"last\"")
                .setBody(body),
        )
    }

    private fun connection(maxPages: Int = 10) =
        GitHubConnectionProperties(
            baseUri = URI.create(server.url("/").toString().trimEnd('/')),
            graphqlUri = URI.create(server.url("/graphql").toString()),
            token = "a-token",
            defaultRepository = null,
            perPage = 100,
            maxPages = maxPages,
        )
}
