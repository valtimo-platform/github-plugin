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

import {Directive, EventEmitter, inject, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {
  FunctionConfigurationComponent,
  FunctionConfigurationData,
  PluginTranslationService,
} from '@valtimo/plugin';
import {SelectItem} from '@valtimo/components';
import {TranslateService} from '@ngx-translate/core';
import {BehaviorSubject, combineLatest, map, Observable, Subscription, take} from 'rxjs';

/**
 * What every action configuration screen of this plugin has in common.
 *
 * There are more than thirty of them and they differ only in which fields they show and
 * which of those are required. Written out per component that would be thirty copies of the
 * same save subscription and the same validity plumbing, and thirty places for one of them
 * to drift — a component that forgets to unsubscribe, or that emits an invalid
 * configuration, is not something a reviewer would spot in the thirtieth copy.
 *
 * Subclasses declare their required fields and nothing else. Dependencies are taken with
 * `inject()` rather than through a constructor so that a subclass does not have to declare
 * one purely to pass them up.
 */
@Directive()
export abstract class GitHubFunctionConfigurationComponent<T extends object>
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<T>;
  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<FunctionConfigurationData> =
    new EventEmitter<FunctionConfigurationData>();

  protected readonly translateService = inject(TranslateService);
  protected readonly pluginTranslationService = inject(PluginTranslationService);

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<T | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  /**
   * The fields without which this action cannot run. Everything else is optional, and an
   * optional field left empty means "let the backend decide" rather than "send an empty
   * string" — the backend treats blank as absent throughout.
   */
  protected abstract requiredFields(): Array<keyof T>;

  ngOnInit(): void {
    this.openSaveSubscription();
  }

  ngOnDestroy(): void {
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formValue: T): void {
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  /**
   * Builds the options of a dropdown, relabelled whenever the language changes.
   *
   * The labels come from this plugin's own translations under `<field>.<value>`, so the
   * value that reaches the backend is the one GitHub knows — `REQUEST_CHANGES`, not
   * whatever it is called in Dutch.
   */
  protected selectItems(field: string, values: string[]): Observable<SelectItem[]> {
    return this.translateService.stream('key').pipe(
      map(() =>
        values.map(value => ({
          id: value,
          text: this.pluginTranslationService.instant(`${field}.${value}`, this.pluginId),
        }))
      )
    );
  }

  /**
   * A field counts as filled in when it has a value — which is not the same as being
   * truthy. `false` on a checkbox and `0` in a number field are answers, and treating them
   * as missing would make a step that legitimately says "no" unsavable.
   */
  private handleValid(formValue: T): void {
    const valid = this.requiredFields().every(field => {
      const value = formValue?.[field];
      return value !== undefined && value !== null && value !== '';
    });

    this.valid$.next(valid);
    this.valid.emit(valid);
  }

  private openSaveSubscription(): void {
    this.saveSubscription = this.save$?.subscribe(() => {
      combineLatest([this.formValue$, this.valid$])
        .pipe(take(1))
        .subscribe(([formValue, valid]) => {
          if (valid) {
            this.configuration.emit(formValue!);
          }
        });
    });
  }
}
