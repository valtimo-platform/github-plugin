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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.ritense.valtimo.contract.annotation.SkipComponentScan
import com.ritense.valtimoplugins.github.domain.GitHubConnectionProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI

/**
 * Talks to the GitHub REST and GraphQL APIs.
 *
 * Deliberately thin, and deliberately untyped. GitHub's payloads are wide, versioned and
 * added to, and thirty Kotlin response classes would be thirty things to keep in step with
 * an API that changes without asking. What a process actually needs out of a pull request
 * is a handful of fields, so the trimming happens once in
 * [com.ritense.valtimoplugins.github.service.GitHubProjections] and this layer stays a
 * transport.
 *
 * One instance serves every plugin configuration — see the note on
 * [GitHubConnectionProperties] for why the connection is a parameter rather than state.
 */
@Component
@SkipComponentScan
class GitHubClient(
    private val restClientBuilder: RestClient.Builder,
    private val objectMapper: ObjectMapper,
) {
    /** A single REST call. Use [getPaged] for anything that returns a list. */
    fun get(
        connection: GitHubConnectionProperties,
        path: String,
        query: Map<String, Any?> = emptyMap(),
    ): JsonNode = exchange(connection, HttpMethod.GET, uri(connection, path, query), null).body

    fun post(
        connection: GitHubConnectionProperties,
        path: String,
        body: Any?,
    ): JsonNode = exchange(connection, HttpMethod.POST, uri(connection, path), body).body

    fun patch(
        connection: GitHubConnectionProperties,
        path: String,
        body: Any?,
    ): JsonNode = exchange(connection, HttpMethod.PATCH, uri(connection, path), body).body

    fun put(
        connection: GitHubConnectionProperties,
        path: String,
        body: Any?,
    ): JsonNode = exchange(connection, HttpMethod.PUT, uri(connection, path), body).body

    fun delete(
        connection: GitHubConnectionProperties,
        path: String,
        query: Map<String, Any?> = emptyMap(),
    ): JsonNode = exchange(connection, HttpMethod.DELETE, uri(connection, path, query), null).body

    /**
     * A REST call by method name, for the `rest-request` escape hatch.
     *
     * [path] is relative to the configured REST root and is not otherwise constrained: this
     * is the one entry point that is meant to reach an endpoint nobody modelled, and the
     * bar on it is the configuration's own token.
     */
    fun request(
        connection: GitHubConnectionProperties,
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: Any? = null,
    ): JsonNode {
        // Not `HttpMethod.valueOf` alone: since Spring 6 that is no longer an enum and it
        // mints an HttpMethod for whatever string it is handed, so a typo would leave here
        // as a request with a nonsense verb rather than as an error.
        val name = method.trim().uppercase()
        val httpMethod =
            HttpMethod.values().firstOrNull { it.name() == name }
                ?: throw GitHubException(
                    "Unsupported HTTP method '$method'. Use one of " +
                        HttpMethod.values().joinToString(", ") { it.name() },
                )
        return exchange(connection, httpMethod, uri(connection, path, query), body).body
    }

    /**
     * Reads a list endpoint, following GitHub's `Link: rel="next"` until the list is
     * exhausted or [GitHubConnectionProperties.maxPages] pages have been read.
     *
     * [PagedResult.truncated] says which of those two happened, and callers are expected to
     * pass it on rather than swallow it: a truncated read looks exactly like a short one to
     * a process, and a process that branches on "no more open pull requests" would take the
     * wrong branch on a page budget that ran out.
     *
     * Search endpoints nest their results under `items`; ordinary list endpoints answer a
     * bare array. Both are handled, so a caller does not have to know which it asked for.
     */
    fun getPaged(
        connection: GitHubConnectionProperties,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        limit: Int? = null,
    ): PagedResult {
        val items = objectMapper.createArrayNode()
        var next: URI? = uri(connection, path, query + mapOf("per_page" to connection.perPage))
        var pages = 0
        var totalCount: Int? = null

        while (next != null && pages < connection.maxPages) {
            val response = exchange(connection, HttpMethod.GET, next, null)
            val body = response.body

            when {
                body.isArray -> items.addAll(body as ArrayNode)
                body.has("items") && body.get("items").isArray -> {
                    items.addAll(body.get("items") as ArrayNode)
                    totalCount = body.get("total_count")?.takeIf { it.isInt }?.asInt()
                }
                // A list endpoint that answered with a single object — `check-runs` and
                // `workflow_runs` both wrap their array in one. Take the first array field.
                else ->
                    body
                        .properties()
                        .firstOrNull { it.value.isArray }
                        ?.let { items.addAll(it.value as ArrayNode) }
            }

            pages++
            next = nextLink(response.headers)
            if (limit != null && items.size() >= limit) {
                return PagedResult(
                    items = trim(items, limit),
                    // Only truncated when something was actually left behind: either this
                    // page held more than was asked for, or GitHub offered another one. A
                    // list that happens to be exactly [limit] long was not cut short, and
                    // saying it was sends a process down the "there is more to do" branch
                    // on every run.
                    truncated = items.size() > limit || next != null,
                    totalCount = totalCount,
                )
            }
        }

        return PagedResult(
            items = if (limit != null) trim(items, limit) else items,
            truncated = next != null,
            totalCount = totalCount,
        )
    }

    /**
     * Reads an endpoint that answers with plain text, following the redirect to storage that
     * GitHub serves an Actions job log through.
     *
     * The redirect is followed by hand rather than by the http client, because the target is
     * a pre-signed URL on a host that is not GitHub — Azure blob storage — and a client
     * configured to follow redirects would carry the `Authorization` header there with it.
     * The signature is what authorises that second call; the token would only be a GitHub
     * credential handed to a third party that never needed it.
     *
     * Null when GitHub answered with neither a body nor somewhere to fetch one from, which
     * is what an expired log looks like. Callers are expected to say so rather than pass an
     * empty log off as a log that was read.
     */
    fun getText(
        connection: GitHubConnectionProperties,
        path: String,
    ): String? {
        val response = exchange(connection, HttpMethod.GET, uri(connection, path), null)
        val location = response.headers.location
        if (response.status in REDIRECTED && location != null) {
            return restClientBuilder
                .clone()
                .build()
                .get()
                .uri(location)
                .headers { it.set(HttpHeaders.USER_AGENT, USER_AGENT) }
                .retrieve()
                .body(String::class.java)
                ?.takeIf { it.isNotEmpty() }
        }
        // The raw bytes rather than the parsed body: a log is text, and a log whose first
        // line happens to parse as JSON — a bare timestamp does — would come back through
        // the tree as that one value with the rest of the log dropped.
        return String(response.raw, Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }

    /**
     * Runs a GraphQL document.
     *
     * GraphQL answers 200 with an `errors` array rather than an HTTP error, so a failure
     * here is invisible to the status check every other call relies on. It is turned into a
     * [GitHubException] instead: a process that asked for a pull request's review threads
     * and silently got `null` would go on to report nothing left to answer.
     */
    fun graphql(
        connection: GitHubConnectionProperties,
        query: String,
        variables: JsonNode? = null,
    ): JsonNode {
        val body =
            objectMapper.createObjectNode().apply {
                put("query", query)
                set<JsonNode>("variables", variables ?: objectMapper.createObjectNode())
            }

        val response = exchange(connection, HttpMethod.POST, connection.graphqlUri, body)
        val errors = response.body.get("errors")
        if (errors != null && errors.isArray && !errors.isEmpty) {
            val messages = errors.joinToString("; ") { it.get("message")?.asText() ?: it.toString() }
            throw GitHubException("GitHub GraphQL returned errors: $messages")
        }
        return response.body.get("data") ?: NullNode.instance
    }

    private fun exchange(
        connection: GitHubConnectionProperties,
        method: HttpMethod,
        uri: URI,
        body: Any?,
    ): Response {
        connection.validate()
        logger.debug { "GitHub $method $uri" }

        val entity =
            // A refusal has to leave here as a GitHubException whichever half of the stack
            // noticed it first. The lambda below raises one from the status it reads, but a
            // Valtimo application wraps the response stream in `LoggingRestClientCustomizer`,
            // which reads the head of an error response and raises Spring's
            // `RestClientResponseException` before the lambda gets that far. Without this
            // catch, callers that branch on a status — `createLabel` treating 422 as "the
            // label is already there" — silently never match, so a process whose first step
            // creates its working label ran exactly once.
            //
            // What this recovers is the status, and in a Valtimo application only the status:
            // that customizer builds its exception from `statusCode` and `statusText` alone
            // and drops the body it has just read, so `describe` has nothing to name the
            // rejected field with and the message is a bare "GitHub responded 422". The body
            // is still used when there is one — a `RestClientResponseException` from anywhere
            // that keeps it, the tests included — which is why `parseBody` is here.
            //
            // The trailing `true` on `exchange` is `close`: it decides whether RestClient
            // closes the response once the exchange function returns, and `false` makes that
            // the caller's job — which nothing here was doing, so every call leaked the
            // connection it borrowed. Apache's pool lends five per route and Valtimo waits
            // five seconds for one, so the sixth call and everything after it died with
            // "ConnectionRequestTimeoutException: Timeout deadline: 5000 MILLISECONDS" until
            // the application was restarted. RestClient closes in a finally, so this covers
            // the throwing path too, which is the one that matters: a repository that refuses
            // a write refuses it every time. The whole body is in `raw` before the function
            // returns, so there is nothing to keep the response open for.
            try {
                restClientBuilder
                    .clone()
                    .build()
                    .method(method)
                    .uri(uri)
                    .headers { headers ->
                        headers.setBearerAuth(connection.token)
                        headers.accept = listOf(MediaType.valueOf(ACCEPT))
                        headers.set(API_VERSION_HEADER, API_VERSION)
                        headers.set(HttpHeaders.USER_AGENT, USER_AGENT)
                    }.apply {
                        if (body != null) {
                            contentType(MediaType.APPLICATION_JSON)
                            body(body)
                        }
                    }.exchange({ _, response ->
                        val raw = response.body.readAllBytes()
                        val parsed = parseBody(raw)

                        if (response.statusCode.isError) {
                            throw GitHubException(
                                message = describe(response.statusCode.value(), parsed),
                                status = response.statusCode.value(),
                            )
                        }
                        Response(response.statusCode.value(), parsed, raw, response.headers)
                    }, true)
            } catch (e: RestClientResponseException) {
                throw GitHubException(
                    message = describe(e.statusCode.value(), parseBody(e.responseBodyAsByteArray)),
                    status = e.statusCode.value(),
                    cause = e,
                )
            }

        return entity ?: Response(0, NullNode.instance, ByteArray(0), HttpHeaders.EMPTY)
    }

    /** A body as JSON where it is JSON, as text where it is not, and null where there is none. */
    private fun parseBody(raw: ByteArray): JsonNode =
        if (raw.isEmpty()) {
            NullNode.instance
        } else {
            runCatching { objectMapper.readTree(raw) as JsonNode }
                .getOrElse { objectMapper.getNodeFactory().textNode(String(raw)) }
        }

    /**
     * GitHub's error bodies carry the reason a write was refused — a failed validation names
     * the field — and dropping that leaves a process author with a bare 422. Kept short so
     * it lands in a process log rather than a wall of it.
     */
    private fun describe(
        status: Int,
        body: JsonNode,
    ): String {
        val message = body.get("message")?.asText()
        val errors =
            body.get("errors")?.takeIf { it.isArray }?.joinToString("; ") { error ->
                error.get("message")?.asText()
                    ?: listOfNotNull(
                        error.get("field")?.asText(),
                        error.get("code")?.asText(),
                    ).joinToString(" ")
            }

        return listOfNotNull(
            "GitHub responded $status",
            message?.takeIf { it.isNotBlank() },
            errors?.takeIf { it.isNotBlank() },
        ).joinToString(": ").take(MAX_ERROR_LENGTH)
    }

    private fun uri(
        connection: GitHubConnectionProperties,
        path: String,
        query: Map<String, Any?> = emptyMap(),
    ): URI =
        UriComponentsBuilder
            .fromUri(connection.baseUri)
            .path(if (path.startsWith("/")) path else "/$path")
            .also { builder ->
                query
                    .filterValues { it != null && it.toString().isNotBlank() }
                    .forEach { (key, value) -> builder.queryParam(key, value) }
            }.build()
            .encode()
            .toUri()

    /**
     * `Link: <https://…&page=2>; rel="next", <…>; rel="last"`.
     *
     * The URL is used as GitHub gave it rather than rebuilt from a page number, because the
     * cursor-paginated endpoints (`/user/repos` since 2023, and the GraphQL-backed ones)
     * carry an opaque `after=` that a page counter cannot reproduce.
     */
    private fun nextLink(headers: HttpHeaders): URI? =
        headers
            .getFirst(HttpHeaders.LINK)
            ?.split(',')
            ?.firstOrNull { it.contains("rel=\"next\"") }
            ?.substringAfter('<', "")
            ?.substringBefore('>', "")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URI.create(it) }.getOrNull() }

    private fun trim(
        items: ArrayNode,
        limit: Int,
    ): ArrayNode =
        if (items.size() <= limit) {
            items
        } else {
            objectMapper.createArrayNode().apply { (0 until limit).forEach { add(items.get(it)) } }
        }

    /**
     * Not a data class: [raw] holds the bytes [body] was parsed from, and an array makes
     * generated equality mean something other than it reads as.
     */
    private class Response(
        val status: Int,
        val body: JsonNode,
        val raw: ByteArray,
        val headers: HttpHeaders,
    )

    /**
     * The items of one read, and whether the page budget ran out before the list did.
     *
     * [totalCount] is what a search endpoint reported it could have returned, and is null
     * for an ordinary list endpoint, which does not say.
     */
    data class PagedResult(
        val items: ArrayNode,
        val truncated: Boolean,
        val totalCount: Int? = null,
    )

    companion object {
        private val logger = KotlinLogging.logger {}

        private const val ACCEPT = "application/vnd.github+json"
        private const val API_VERSION_HEADER = "X-GitHub-Api-Version"

        /**
         * Pinned. GitHub's REST API is versioned by this header, and an unpinned client is
         * one that changes behaviour on GitHub's release schedule rather than on ours.
         */
        private const val API_VERSION = "2022-11-28"
        private const val USER_AGENT = "valtimo-github-plugin"
        private const val MAX_ERROR_LENGTH = 1000

        /** The statuses [getText] treats as "the body is somewhere else". */
        private val REDIRECTED = setOf(301, 302, 303, 307, 308)
    }
}
