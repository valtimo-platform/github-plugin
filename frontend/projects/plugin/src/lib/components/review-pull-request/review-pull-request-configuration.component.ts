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

import {Component} from '@angular/core';
import {SelectItem} from '@valtimo/components';
import {Observable} from 'rxjs';
import {GitHubFunctionConfigurationComponent} from '../../base/github-function-configuration.component';
import {ReviewPullRequestConfig} from '../../models';

@Component({
  standalone: false,
  selector: 'valtimo-github-review-pull-request-configuration',
  templateUrl: './review-pull-request-configuration.component.html',
})
export class ReviewPullRequestConfigurationComponent extends GitHubFunctionConfigurationComponent<ReviewPullRequestConfig> {
  readonly eventItems$: Observable<SelectItem[]> = this.selectItems(
    'event',
    ['APPROVE', 'REQUEST_CHANGES', 'COMMENT']
  );

  protected requiredFields(): Array<keyof ReviewPullRequestConfig> {
    return ['pullRequestNumber', 'event'];
  }
}
