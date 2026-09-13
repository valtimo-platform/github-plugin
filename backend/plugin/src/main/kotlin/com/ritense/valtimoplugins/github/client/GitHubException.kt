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

/**
 * A call GitHub refused, or answered with something the client could not use.
 *
 * [status] is the HTTP status where there was one, and null for a GraphQL error — GraphQL
 * answers 200 with an `errors` array, so "it failed" and "the transport failed" are
 * genuinely different questions there.
 */
class GitHubException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
