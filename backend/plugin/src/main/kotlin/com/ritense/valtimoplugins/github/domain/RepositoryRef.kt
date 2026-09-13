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

/**
 * One `owner/repo`, parsed rather than passed around as a string.
 *
 * The parsing is a bar, not a convenience. A repository is almost always model or process
 * output — a field on a case, a variable derived from the text of an issue — and it is
 * interpolated straight into the path of an authenticated API call. `owner/repo` is the
 * only shape allowed through, so a value carrying `..`, a query string or a second path
 * segment is refused here instead of addressing some other endpoint.
 */
data class RepositoryRef(
    val owner: String,
    val name: String,
) {
    override fun toString(): String = "$owner/$name"

    companion object {
        private val SEGMENT = Regex("[A-Za-z0-9._-]+")

        fun parse(value: String): RepositoryRef {
            val parts = value.trim().split('/')
            require(parts.size == 2) { "Expected a repository as 'owner/repo', got '$value'" }
            val (owner, name) = parts
            require(SEGMENT.matches(owner) && SEGMENT.matches(name)) {
                "Expected a repository as 'owner/repo', got '$value'"
            }
            // `.` and `..` match SEGMENT but are path traversal, not repository names.
            require(owner !in TRAVERSAL && name !in TRAVERSAL) {
                "Expected a repository as 'owner/repo', got '$value'"
            }
            return RepositoryRef(owner, name)
        }

        private val TRAVERSAL = setOf(".", "..")
    }
}
