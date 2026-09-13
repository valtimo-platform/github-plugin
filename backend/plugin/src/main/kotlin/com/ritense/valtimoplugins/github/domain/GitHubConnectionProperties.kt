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

package com.ritense.valtimoplugins.github.domain

import java.net.URI

/**
 * Everything one plugin configuration knows about the GitHub it talks to.
 *
 * Passed to every [com.ritense.valtimoplugins.github.client.GitHubClient] call rather than
 * held as state on the client: the client is a singleton, and two process instances
 * configured against two installations — say github.com and an Enterprise Server — post on
 * two threads. A field set by one thread and read by the other would send one installation's
 * request with the other's credentials.
 */
data class GitHubConnectionProperties(
    /** REST root. `https://api.github.com` for github.com, `https://host/api/v3` for GHES. */
    val baseUri: URI,
    /** GraphQL endpoint, derived from [baseUri] when not configured explicitly. */
    val graphqlUri: URI,
    val token: String,
    /**
     * `owner/repo` used by every action that leaves its own `repository` empty. Optional:
     * a configuration that works across repositories leaves it unset and fills it in per
     * action, typically from a process variable.
     */
    val defaultRepository: String?,
    /** Items per page asked of GitHub on a list call. GitHub caps this at 100. */
    val perPage: Int,
    /**
     * How many pages one list action follows before it stops and says so.
     *
     * A cap rather than "read everything", because a list action feeds a process variable:
     * an unbounded read of a busy repository is an unbounded process variable, and the
     * failure mode of that is a case that cannot be saved rather than a slow one.
     */
    val maxPages: Int,
) {
    fun validate() {
        require(token.isNotBlank()) { "GitHub token is required" }
        require(baseUri.scheme != null && baseUri.host != null) { "GitHub url '$baseUri' is not absolute" }
        require(perPage in 1..MAX_PER_PAGE) { "perPage must be between 1 and $MAX_PER_PAGE, was $perPage" }
        require(maxPages >= 1) { "maxPages must be at least 1, was $maxPages" }
        defaultRepository?.takeIf { it.isNotBlank() }?.let { RepositoryRef.parse(it) }
    }

    companion object {
        /** GitHub's own ceiling on `per_page`; asking for more is silently reduced. */
        const val MAX_PER_PAGE = 100

        /**
         * GraphQL lives next to REST but not under it: github.com serves
         * `https://api.github.com/graphql`, Enterprise Server serves `/api/graphql`
         * beside the `/api/v3` REST root rather than `/api/v3/graphql`.
         */
        fun deriveGraphqlUri(baseUri: URI): URI {
            val base = baseUri.toString().trimEnd('/')
            return if (base.endsWith("/api/v3")) {
                URI.create(base.removeSuffix("/v3") + "/graphql")
            } else {
                URI.create("$base/graphql")
            }
        }
    }
}
