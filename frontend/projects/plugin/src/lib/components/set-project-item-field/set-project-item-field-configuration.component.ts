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
import {SetProjectItemFieldConfig} from '../../models';

@Component({
  standalone: false,
  selector: 'valtimo-github-set-project-item-field-configuration',
  templateUrl: './set-project-item-field-configuration.component.html',
})
export class SetProjectItemFieldConfigurationComponent extends GitHubFunctionConfigurationComponent<SetProjectItemFieldConfig> {
  readonly valueTypeItems$: Observable<SelectItem[]> = this.selectItems(
    'valueType',
    ['text', 'number', 'date', 'singleSelectOptionId', 'iterationId']
  );

  protected requiredFields(): Array<keyof SetProjectItemFieldConfig> {
    return ['projectId', 'itemId', 'fieldId', 'valueType', 'value'];
  }
}
