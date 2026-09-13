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

package com.ritense.valtimoplugins.github.autoconfiguration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.plugin.service.PluginService
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimoplugins.github.client.GitHubClient
import com.ritense.valtimoplugins.github.plugin.GitHubPluginFactory
import com.ritense.valtimoplugins.github.service.GitHubOperations
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.web.client.RestClient

/**
 * Wires the plugin up.
 *
 * No entities, no repositories and no scheduling: every action of this plugin is a
 * synchronous call made while a service task is executing, and nothing is remembered
 * between them. That is why there is no Liquibase changelog here either — installing this
 * plugin adds no tables.
 */
@AutoConfiguration
class GitHubAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(GitHubClient::class)
    fun gitHubClient(
        restClientBuilder: RestClient.Builder,
        objectMapper: ObjectMapper,
    ): GitHubClient = GitHubClient(restClientBuilder, objectMapper)

    @Bean
    @ConditionalOnMissingBean(GitHubOperations::class)
    fun gitHubOperations(
        gitHubClient: GitHubClient,
        objectMapper: ObjectMapper,
    ): GitHubOperations = GitHubOperations(gitHubClient, objectMapper)

    @Bean
    @ConditionalOnMissingBean(GitHubPluginFactory::class)
    fun gitHubPluginFactory(
        pluginService: PluginService,
        gitHubOperations: GitHubOperations,
        storageService: TemporaryResourceStorageService,
        objectMapper: ObjectMapper,
    ): GitHubPluginFactory = GitHubPluginFactory(pluginService, gitHubOperations, storageService, objectMapper)
}
