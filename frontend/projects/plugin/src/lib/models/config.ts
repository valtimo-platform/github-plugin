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

import {PluginConfigurationData} from '@valtimo/plugin';

/**
 * How a plugin configuration is stored. Keep in lockstep with the @PluginProperty fields of
 * GitHubPlugin in Kotlin.
 */
interface GitHubConfig extends PluginConfigurationData {
  url: string;
  token: string;
  defaultRepository?: string;
  graphqlUrl?: string;
  perPage?: number;
  maxPages?: number;
}

/**
 * One interface per action, mirroring its @PluginActionProperty parameters.
 *
 * `resultVariable` is on nearly all of them: it names the process variable the answer is
 * written to, and leaving it empty means the answer is discarded — which is what you want
 * for an action whose point is the write rather than the reply.
 */
interface GetRepositoryConfig {
  repository?: string;
  resultVariable?: string;
}

interface ListRepositoriesConfig {
  owner: string;
  type?: string;
  limit?: number;
  resultVariable?: string;
}

interface GetAuthenticatedUserConfig {
  resultVariable?: string;
}

interface ListIssuesConfig {
  repository?: string;
  state?: string;
  labels?: string;
  assignee?: string;
  creator?: string;
  since?: string;
  limit?: number;
  resultVariable?: string;
}

interface GetIssueConfig {
  repository?: string;
  issueNumber: number;
  includeComments?: boolean;
  includeTimeline?: boolean;
  resultVariable?: string;
}

interface SearchIssuesConfig {
  query: string;
  sort?: string;
  order?: string;
  limit?: number;
  resultVariable?: string;
}

interface CreateIssueConfig {
  repository?: string;
  title: string;
  body?: string;
  labels?: string;
  assignees?: string;
  resultVariable?: string;
}

interface UpdateIssueConfig {
  repository?: string;
  issueNumber: number;
  title?: string;
  body?: string;
  state?: string;
  stateReason?: string;
  addLabels?: string;
  removeLabels?: string;
  addAssignees?: string;
  removeAssignees?: string;
  resultVariable?: string;
}

interface CommentConfig {
  repository?: string;
  issueNumber: number;
  body: string;
  resultVariable?: string;
}

interface ListPullRequestsConfig {
  repository?: string;
  state?: string;
  base?: string;
  head?: string;
  limit?: number;
  resultVariable?: string;
}

interface GetPullRequestConfig {
  repository?: string;
  pullRequestNumber: number;
  includeFiles?: boolean;
  includeReviews?: boolean;
  includeComments?: boolean;
  includeChecks?: boolean;
  resultVariable?: string;
}

interface CreatePullRequestConfig {
  repository?: string;
  title: string;
  body?: string;
  head: string;
  base?: string;
  draft?: boolean;
  resultVariable?: string;
}

interface UpdatePullRequestConfig {
  repository?: string;
  pullRequestNumber: number;
  title?: string;
  body?: string;
  base?: string;
  state?: string;
  addLabels?: string;
  removeLabels?: string;
  resultVariable?: string;
}

interface SetPullRequestDraftConfig {
  repository?: string;
  pullRequestNumber: number;
  draft: boolean;
  resultVariable?: string;
}

interface MergePullRequestConfig {
  repository?: string;
  pullRequestNumber: number;
  mergeMethod?: string;
  commitTitle?: string;
  commitMessage?: string;
  expectedHeadSha?: string;
  resultVariable?: string;
}

interface ReviewPullRequestConfig {
  repository?: string;
  pullRequestNumber: number;
  event: string;
  body?: string;
  resultVariable?: string;
}

interface RequestReviewersConfig {
  repository?: string;
  pullRequestNumber: number;
  reviewers?: string;
  teamReviewers?: string;
  resultVariable?: string;
}

interface ListReviewThreadsConfig {
  repository?: string;
  pullRequestNumber: number;
  onlyUnresolved?: boolean;
  resultVariable?: string;
}

interface ReplyToReviewCommentConfig {
  repository?: string;
  pullRequestNumber: number;
  commentId: string;
  body: string;
  resultVariable?: string;
}

interface ResolveReviewThreadConfig {
  threadId: string;
  resolve?: boolean;
  resultVariable?: string;
}

interface CreateLabelConfig {
  repository?: string;
  name: string;
  color?: string;
  description?: string;
  resultVariable?: string;
}

interface GetCheckStatusConfig {
  repository?: string;
  ref: string;
  resultVariable?: string;
}

interface ListWorkflowRunsConfig {
  repository?: string;
  branch?: string;
  status?: string;
  event?: string;
  limit?: number;
  resultVariable?: string;
}

interface GetWorkflowRunConfig {
  repository?: string;
  runId: string;
  includeJobs?: boolean;
  resultVariable?: string;
}

interface GetJobLogsConfig {
  repository?: string;
  jobId: string;
  maxLines?: number;
  resultVariable?: string;
}

interface RerunWorkflowConfig {
  repository?: string;
  runId: string;
  onlyFailed?: boolean;
  resultVariable?: string;
}

interface GetFileContentConfig {
  repository?: string;
  path: string;
  ref?: string;
  resultVariable?: string;
}

interface CreateOrUpdateFileConfig {
  repository?: string;
  path: string;
  commitMessage: string;
  content?: string;
  resourceId?: string;
  branch?: string;
  expectedSha?: string;
  resultVariable?: string;
}

interface CreateBranchConfig {
  repository?: string;
  branchName: string;
  fromRef?: string;
  resultVariable?: string;
}

interface GetProjectItemsConfig {
  repository?: string;
  issueNumber: number;
  resultVariable?: string;
}

interface SetProjectItemFieldConfig {
  projectId: string;
  itemId: string;
  fieldId: string;
  valueType: string;
  value: string;
  resultVariable?: string;
}

interface RestRequestConfig {
  method?: string;
  path: string;
  body?: string;
  resultVariable?: string;
}

interface GraphqlQueryConfig {
  query: string;
  variables?: string;
  resultVariable?: string;
}

export {
  GitHubConfig,
  GetRepositoryConfig,
  ListRepositoriesConfig,
  GetAuthenticatedUserConfig,
  ListIssuesConfig,
  GetIssueConfig,
  SearchIssuesConfig,
  CreateIssueConfig,
  UpdateIssueConfig,
  CommentConfig,
  ListPullRequestsConfig,
  GetPullRequestConfig,
  CreatePullRequestConfig,
  UpdatePullRequestConfig,
  SetPullRequestDraftConfig,
  MergePullRequestConfig,
  ReviewPullRequestConfig,
  RequestReviewersConfig,
  ListReviewThreadsConfig,
  ReplyToReviewCommentConfig,
  ResolveReviewThreadConfig,
  CreateLabelConfig,
  GetCheckStatusConfig,
  ListWorkflowRunsConfig,
  GetWorkflowRunConfig,
  GetJobLogsConfig,
  RerunWorkflowConfig,
  GetFileContentConfig,
  CreateOrUpdateFileConfig,
  CreateBranchConfig,
  GetProjectItemsConfig,
  SetProjectItemFieldConfig,
  RestRequestConfig,
  GraphqlQueryConfig,
};
