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

package com.ritense.valtimoplugins.github

import com.ritense.plugin.repository.PluginActionDefinitionRepository
import com.ritense.plugin.repository.PluginDefinitionRepository
import com.ritense.valtimoplugins.github.plugin.GitHubPlugin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Checks that what the plugin declares is what Valtimo deployed.
 *
 * This is the one thing unit tests cannot see. `@PluginAction` is read by a scanner at
 * startup, and an action whose annotation is malformed — a parameter type the scanner cannot
 * map, a duplicate key — does not fail a compile and does not fail a mocked test. It is
 * simply absent from the admin UI, which is the kind of defect that surfaces when somebody
 * goes looking for an action that ought to be there.
 */
internal class PluginDefinitionIT : BaseIntegrationTest() {
    @Autowired
    lateinit var pluginDefinitionRepository: PluginDefinitionRepository

    @Autowired
    lateinit var pluginActionDefinitionRepository: PluginActionDefinitionRepository

    @Test
    fun `should deploy the plugin definition`() {
        val definition = pluginDefinitionRepository.findById(GitHubPlugin.PLUGIN_KEY)

        assertThat(definition).isPresent
        assertThat(definition.get().title).isEqualTo("GitHub Plugin")
    }

    @Test
    fun `should deploy every action the plugin declares`() {
        val deployed =
            pluginActionDefinitionRepository
                .findAll()
                .filter { it.id.pluginDefinition.key == GitHubPlugin.PLUGIN_KEY }
                .map { it.id.key }

        // Named rather than counted. A count tells you something changed; this tells you
        // which action went missing, and it is the list the frontend's
        // functionConfigurationComponents has to match key for key.
        assertThat(deployed).containsExactlyInAnyOrder(
            "get-repository",
            "list-repositories",
            "get-authenticated-user",
            "list-issues",
            "get-issue",
            "search-issues",
            "create-issue",
            "update-issue",
            "comment",
            "list-pull-requests",
            "get-pull-request",
            "create-pull-request",
            "update-pull-request",
            "set-pull-request-draft",
            "merge-pull-request",
            "review-pull-request",
            "request-reviewers",
            "list-review-threads",
            "reply-to-review-comment",
            "resolve-review-thread",
            "create-label",
            "get-check-status",
            "list-workflow-runs",
            "get-workflow-run",
            "get-job-logs",
            "rerun-workflow",
            "get-file-content",
            "create-or-update-file",
            "create-branch",
            "get-project-items",
            "set-project-item-field",
            "rest-request",
            "graphql-query",
        )
    }
}
