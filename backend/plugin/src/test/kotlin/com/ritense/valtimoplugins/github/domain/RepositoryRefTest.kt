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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RepositoryRefTest {
    @Test
    fun `should parse an owner and repository`() {
        val ref = RepositoryRef.parse("valtimo-platform/github-plugin")

        assertThat(ref.owner).isEqualTo("valtimo-platform")
        assertThat(ref.name).isEqualTo("github-plugin")
        assertThat(ref.toString()).isEqualTo("valtimo-platform/github-plugin")
    }

    @Test
    fun `should ignore surrounding whitespace`() {
        assertThat(RepositoryRef.parse("  ritense/coworker-plugin  ").toString())
            .isEqualTo("ritense/coworker-plugin")
    }

    /**
     * The repository is interpolated into the path of an authenticated call, and it is
     * usually derived from the text of a ticket or a case field. Each of these would address
     * an endpoint other than the one the caller named.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "ritense",
            "ritense/repo/extra",
            "ritense/../../user",
            "../ritense/repo",
            "ritense/..",
            "./repo",
            "ritense/repo?per_page=1",
            "ritense/repo#fragment",
            "ritense/re po",
            "",
            "/",
        ],
    )
    fun `should refuse anything that is not exactly owner slash repo`(value: String) {
        assertThatThrownBy { RepositoryRef.parse(value) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("owner/repo")
    }
}
