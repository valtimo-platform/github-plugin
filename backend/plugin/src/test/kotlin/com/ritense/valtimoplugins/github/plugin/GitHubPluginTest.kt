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

import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimoplugins.github.BaseTest
import com.ritense.valtimoplugins.github.client.GitHubException
import com.ritense.valtimoplugins.github.domain.RepositoryRef
import com.ritense.valtimoplugins.github.service.GitHubOperations
import com.ritense.valtimoplugins.github.service.GitHubProjections
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.delegate.DelegateExecution
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Base64

class GitHubPluginTest : BaseTest() {
    private lateinit var operations: GitHubOperations
    private lateinit var storageService: TemporaryResourceStorageService
    private lateinit var execution: DelegateExecution
    private lateinit var plugin: GitHubPlugin

    @BeforeEach
    fun setUp() {
        operations = mock()
        storageService = mock()
        execution = mock()
        plugin =
            GitHubPlugin(operations, storageService, objectMapper).apply {
                url = URI.create("https://api.github.com")
                token = "a-token"
            }
    }

    // ─── configuration ──────────────────────────────────────────────────────

    @Test
    fun `should derive the graphql endpoint of github dot com`() {
        assertThat(plugin.connection().graphqlUri).isEqualTo(URI.create("https://api.github.com/graphql"))
    }

    /**
     * Enterprise Server serves GraphQL at `/api/graphql`, beside the `/api/v3` REST root
     * rather than under it. Appending would address `/api/v3/graphql`, which is a 404.
     */
    @Test
    fun `should derive the graphql endpoint of an enterprise server beside the rest root`() {
        plugin.url = URI.create("https://github.example.nl/api/v3")

        assertThat(plugin.connection().graphqlUri).isEqualTo(URI.create("https://github.example.nl/api/graphql"))
    }

    @Test
    fun `should prefer an explicitly configured graphql endpoint`() {
        plugin.graphqlUrl = "https://elsewhere.example.nl/graphql"

        assertThat(plugin.connection().graphqlUri).isEqualTo(URI.create("https://elsewhere.example.nl/graphql"))
    }

    @Test
    fun `should refuse a configuration whose default repository is not owner slash repo`() {
        plugin.defaultRepository = "ritense"

        assertThatThrownBy { plugin.connection() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("owner/repo")
    }

    // ─── the repository of an action ────────────────────────────────────────

    @Test
    fun `should fall back to the configuration's default repository`() {
        plugin.defaultRepository = "ritense/coworker-plugin"
        whenever(operations.getRepository(any(), any())).thenReturn(GitHubProjections.obj { })

        plugin.getRepository(execution, null, null)

        verify(operations).getRepository(any(), eq(RepositoryRef("ritense", "coworker-plugin")))
    }

    @Test
    fun `should let an action override the default repository`() {
        plugin.defaultRepository = "ritense/coworker-plugin"
        whenever(operations.getRepository(any(), any())).thenReturn(GitHubProjections.obj { })

        plugin.getRepository(execution, "valtimo-platform/slack-plugin", null)

        verify(operations).getRepository(any(), eq(RepositoryRef("valtimo-platform", "slack-plugin")))
    }

    /**
     * Saying so is the point. A configuration meant to work across repositories has no
     * default, and an action that forgot to fill one in would otherwise fail somewhere
     * inside a URL rather than at the thing that is actually missing.
     */
    @Test
    fun `should say plainly when neither the action nor the configuration names a repository`() {
        assertThatThrownBy { plugin.getRepository(execution, null, null) }
            .isInstanceOf(GitHubException::class.java)
            .hasMessageContaining("no default repository")
    }

    // ─── result variables ───────────────────────────────────────────────────

    /**
     * Converted out of Jackson's tree first: the engine serialises process variables, and a
     * JsonNode is a class it has no business knowing about.
     */
    @Test
    fun `should store a result as plain maps and lists`() {
        whenever(operations.getIssue(any(), any(), eq(12), any(), any()))
            .thenReturn(
                GitHubProjections.obj {
                    put("number", 12)
                    set<com.fasterxml.jackson.databind.node.ArrayNode>(
                        "labels",
                        GitHubProjections.array(listOf("bug", "feedback")),
                    )
                },
            )

        plugin.getIssue(execution, "o/r", 12, null, null, "issue")

        val captor = argumentCaptor<Any>()
        verify(execution).setVariable(eq("issue"), captor.capture())
        @Suppress("UNCHECKED_CAST")
        val stored = captor.firstValue as Map<String, Any?>
        assertThat(stored["number"]).isEqualTo(12)
        assertThat(stored["labels"]).isEqualTo(listOf("bug", "feedback"))
    }

    @Test
    fun `should write nothing when the action was not asked for a result`() {
        whenever(operations.addComment(any(), any(), eq(12), any())).thenReturn(GitHubProjections.obj { })

        plugin.comment(execution, "o/r", 12, "Thanks", null)

        verify(execution, never()).setVariable(any(), any())
    }

    @Test
    fun `should treat a blank result variable name as no result variable`() {
        whenever(operations.addComment(any(), any(), eq(12), any())).thenReturn(GitHubProjections.obj { })

        plugin.comment(execution, "o/r", 12, "Thanks", "   ")

        verify(execution, never()).setVariable(any(), any())
    }

    // ─── comma separated properties ─────────────────────────────────────────

    @Test
    fun `should split a comma separated list of labels and ignore the spacing`() {
        whenever(operations.createIssue(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(GitHubProjections.obj { })

        plugin.createIssue(execution, "o/r", "Title", null, " bug , feedback ,, ", "Klaas-Ritense", null)

        verify(operations).createIssue(
            any(),
            any(),
            eq("Title"),
            anyOrNull(),
            eq(listOf("bug", "feedback")),
            eq(listOf("Klaas-Ritense")),
        )
    }

    @Test
    fun `should pass an empty list rather than a list holding an empty string`() {
        whenever(operations.createIssue(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(GitHubProjections.obj { })

        plugin.createIssue(execution, "o/r", "Title", null, "", null, null)

        verify(operations).createIssue(any(), any(), any(), anyOrNull(), eq(emptyList()), eq(emptyList()))
    }

    // ─── file content ───────────────────────────────────────────────────────

    @Test
    fun `should encode typed content as base64`() {
        whenever(operations.createOrUpdateFile(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(GitHubProjections.obj { })

        plugin.createOrUpdateFile(execution, "o/r", "a.md", "msg", "hello", null, null, null, null)

        verify(operations).createOrUpdateFile(
            any(),
            any(),
            eq("a.md"),
            eq("msg"),
            eq(Base64.getEncoder().encodeToString("hello".toByteArray())),
            anyOrNull(),
            anyOrNull(),
        )
    }

    /**
     * The resource path is the only one of the two that can carry an image, and images are
     * the usual reason to want this action at all.
     */
    @Test
    fun `should take content from temporary resource storage when given a resource id`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        whenever(storageService.getResourceContentAsInputStream("res-1")).thenReturn(ByteArrayInputStream(bytes))
        whenever(operations.createOrUpdateFile(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(GitHubProjections.obj { })

        plugin.createOrUpdateFile(execution, "o/r", "run.png", "msg", null, "res-1", "main", null, null)

        verify(operations).createOrUpdateFile(
            any(),
            any(),
            eq("run.png"),
            eq("msg"),
            eq(Base64.getEncoder().encodeToString(bytes)),
            eq("main"),
            anyOrNull(),
        )
    }

    @Test
    fun `should refuse a file write that names neither content nor a resource`() {
        assertThatThrownBy {
            plugin.createOrUpdateFile(execution, "o/r", "a.md", "msg", null, null, null, null, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("content or resourceId")
    }

    @Test
    fun `should refuse a file write that names both content and a resource`() {
        assertThatThrownBy {
            plugin.createOrUpdateFile(execution, "o/r", "a.md", "msg", "hello", "res-1", null, null, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not both")
    }

    // ─── escape hatches ─────────────────────────────────────────────────────

    @Test
    fun `should parse the body of a rest request as json`() {
        whenever(operations.restRequest(any(), any(), any(), anyOrNull()))
            .thenReturn(GitHubProjections.obj { })

        plugin.restRequest(execution, "POST", "/repos/o/r/issues", """{"title":"x"}""", null)

        val captor = argumentCaptor<com.fasterxml.jackson.databind.JsonNode>()
        verify(operations).restRequest(any(), eq("POST"), eq("/repos/o/r/issues"), captor.capture())
        assertThat(captor.firstValue.path("title").asText()).isEqualTo("x")
    }

    @Test
    fun `should default a rest request with no method to a read`() {
        whenever(operations.restRequest(any(), any(), any(), anyOrNull()))
            .thenReturn(GitHubProjections.obj { })

        plugin.restRequest(execution, null, "/user", null, null)

        verify(operations).restRequest(any(), eq("GET"), eq("/user"), eq(null))
    }

    @Test
    fun `should say which property held the invalid json`() {
        assertThatThrownBy { plugin.graphqlQuery(execution, "query {}", "not json", null) }
            .isInstanceOf(GitHubException::class.java)
            .hasMessageContaining("variables is not valid JSON")
    }

    // ─── numeric ids given as text ──────────────────────────────────────────

    /**
     * Run and job ids are 64-bit and arrive as process variables, so they are taken as text
     * and converted here. A value that is not a number is a wiring mistake worth naming.
     */
    @Test
    fun `should say which property was not a numeric id`() {
        assertThatThrownBy { plugin.getWorkflowRun(execution, "o/r", "latest", null, null) }
            .isInstanceOf(GitHubException::class.java)
            .hasMessageContaining("runId 'latest' is not a numeric id")
    }

    @Test
    fun `should accept a run id that arrived with whitespace around it`() {
        whenever(operations.getWorkflowRun(any(), any(), eq(123456789012L), any()))
            .thenReturn(GitHubProjections.obj { })

        plugin.getWorkflowRun(execution, "o/r", " 123456789012 ", null, null)

        verify(operations).getWorkflowRun(any(), any(), eq(123456789012L), any())
    }
}
