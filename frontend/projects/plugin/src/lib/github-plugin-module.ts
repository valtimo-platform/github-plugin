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

import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, InputModule, ParagraphModule, SelectModule} from '@valtimo/components';
import {GitHubConfigurationComponent} from './components/github-configuration/github-configuration.component';
import {GetRepositoryConfigurationComponent} from './components/get-repository/get-repository-configuration.component';
import {ListRepositoriesConfigurationComponent} from './components/list-repositories/list-repositories-configuration.component';
import {GetAuthenticatedUserConfigurationComponent} from './components/get-authenticated-user/get-authenticated-user-configuration.component';
import {ListIssuesConfigurationComponent} from './components/list-issues/list-issues-configuration.component';
import {GetIssueConfigurationComponent} from './components/get-issue/get-issue-configuration.component';
import {SearchIssuesConfigurationComponent} from './components/search-issues/search-issues-configuration.component';
import {CreateIssueConfigurationComponent} from './components/create-issue/create-issue-configuration.component';
import {UpdateIssueConfigurationComponent} from './components/update-issue/update-issue-configuration.component';
import {CommentConfigurationComponent} from './components/comment/comment-configuration.component';
import {ListPullRequestsConfigurationComponent} from './components/list-pull-requests/list-pull-requests-configuration.component';
import {GetPullRequestConfigurationComponent} from './components/get-pull-request/get-pull-request-configuration.component';
import {CreatePullRequestConfigurationComponent} from './components/create-pull-request/create-pull-request-configuration.component';
import {UpdatePullRequestConfigurationComponent} from './components/update-pull-request/update-pull-request-configuration.component';
import {SetPullRequestDraftConfigurationComponent} from './components/set-pull-request-draft/set-pull-request-draft-configuration.component';
import {MergePullRequestConfigurationComponent} from './components/merge-pull-request/merge-pull-request-configuration.component';
import {ReviewPullRequestConfigurationComponent} from './components/review-pull-request/review-pull-request-configuration.component';
import {RequestReviewersConfigurationComponent} from './components/request-reviewers/request-reviewers-configuration.component';
import {ListReviewThreadsConfigurationComponent} from './components/list-review-threads/list-review-threads-configuration.component';
import {ReplyToReviewCommentConfigurationComponent} from './components/reply-to-review-comment/reply-to-review-comment-configuration.component';
import {ResolveReviewThreadConfigurationComponent} from './components/resolve-review-thread/resolve-review-thread-configuration.component';
import {CreateLabelConfigurationComponent} from './components/create-label/create-label-configuration.component';
import {GetCheckStatusConfigurationComponent} from './components/get-check-status/get-check-status-configuration.component';
import {ListWorkflowRunsConfigurationComponent} from './components/list-workflow-runs/list-workflow-runs-configuration.component';
import {GetWorkflowRunConfigurationComponent} from './components/get-workflow-run/get-workflow-run-configuration.component';
import {GetJobLogsConfigurationComponent} from './components/get-job-logs/get-job-logs-configuration.component';
import {RerunWorkflowConfigurationComponent} from './components/rerun-workflow/rerun-workflow-configuration.component';
import {GetFileContentConfigurationComponent} from './components/get-file-content/get-file-content-configuration.component';
import {CreateOrUpdateFileConfigurationComponent} from './components/create-or-update-file/create-or-update-file-configuration.component';
import {CreateBranchConfigurationComponent} from './components/create-branch/create-branch-configuration.component';
import {GetProjectItemsConfigurationComponent} from './components/get-project-items/get-project-items-configuration.component';
import {SetProjectItemFieldConfigurationComponent} from './components/set-project-item-field/set-project-item-field-configuration.component';
import {RestRequestConfigurationComponent} from './components/rest-request/rest-request-configuration.component';
import {GraphqlQueryConfigurationComponent} from './components/graphql-query/graphql-query-configuration.component';

@NgModule({
  declarations: [
    GitHubConfigurationComponent,
    GetRepositoryConfigurationComponent,
    ListRepositoriesConfigurationComponent,
    GetAuthenticatedUserConfigurationComponent,
    ListIssuesConfigurationComponent,
    GetIssueConfigurationComponent,
    SearchIssuesConfigurationComponent,
    CreateIssueConfigurationComponent,
    UpdateIssueConfigurationComponent,
    CommentConfigurationComponent,
    ListPullRequestsConfigurationComponent,
    GetPullRequestConfigurationComponent,
    CreatePullRequestConfigurationComponent,
    UpdatePullRequestConfigurationComponent,
    SetPullRequestDraftConfigurationComponent,
    MergePullRequestConfigurationComponent,
    ReviewPullRequestConfigurationComponent,
    RequestReviewersConfigurationComponent,
    ListReviewThreadsConfigurationComponent,
    ReplyToReviewCommentConfigurationComponent,
    ResolveReviewThreadConfigurationComponent,
    CreateLabelConfigurationComponent,
    GetCheckStatusConfigurationComponent,
    ListWorkflowRunsConfigurationComponent,
    GetWorkflowRunConfigurationComponent,
    GetJobLogsConfigurationComponent,
    RerunWorkflowConfigurationComponent,
    GetFileContentConfigurationComponent,
    CreateOrUpdateFileConfigurationComponent,
    CreateBranchConfigurationComponent,
    GetProjectItemsConfigurationComponent,
    SetProjectItemFieldConfigurationComponent,
    RestRequestConfigurationComponent,
    GraphqlQueryConfigurationComponent,
  ],
  imports: [
    CommonModule,
    PluginTranslatePipeModule,
    FormModule,
    InputModule,
    ParagraphModule,
    SelectModule,
  ],
  exports: [
    GitHubConfigurationComponent,
    GetRepositoryConfigurationComponent,
    ListRepositoriesConfigurationComponent,
    GetAuthenticatedUserConfigurationComponent,
    ListIssuesConfigurationComponent,
    GetIssueConfigurationComponent,
    SearchIssuesConfigurationComponent,
    CreateIssueConfigurationComponent,
    UpdateIssueConfigurationComponent,
    CommentConfigurationComponent,
    ListPullRequestsConfigurationComponent,
    GetPullRequestConfigurationComponent,
    CreatePullRequestConfigurationComponent,
    UpdatePullRequestConfigurationComponent,
    SetPullRequestDraftConfigurationComponent,
    MergePullRequestConfigurationComponent,
    ReviewPullRequestConfigurationComponent,
    RequestReviewersConfigurationComponent,
    ListReviewThreadsConfigurationComponent,
    ReplyToReviewCommentConfigurationComponent,
    ResolveReviewThreadConfigurationComponent,
    CreateLabelConfigurationComponent,
    GetCheckStatusConfigurationComponent,
    ListWorkflowRunsConfigurationComponent,
    GetWorkflowRunConfigurationComponent,
    GetJobLogsConfigurationComponent,
    RerunWorkflowConfigurationComponent,
    GetFileContentConfigurationComponent,
    CreateOrUpdateFileConfigurationComponent,
    CreateBranchConfigurationComponent,
    GetProjectItemsConfigurationComponent,
    SetProjectItemFieldConfigurationComponent,
    RestRequestConfigurationComponent,
    GraphqlQueryConfigurationComponent,
  ],
})
export class GitHubPluginModule {}
