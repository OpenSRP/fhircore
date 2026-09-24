/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.engine.util.extension

import java.util.Locale
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Task
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.util.helper.LocalizationHelper

fun Task.hasPastEnd() =
  this.hasExecutionPeriod() &&
    this.executionPeriod.hasEnd() &&
    this.executionPeriod.end.before(yesterday())

fun Task.hasStarted() =
  this.hasExecutionPeriod() && this.executionPeriod.hasStart() && executionStartIsBeforeOrToday()

fun Task.isReady() =
  this.hasExecutionPeriod() &&
    ((executionStartIsBeforeOrToday() && executionEndIsAfterOrToday()) ||
      (executionStartIsBeforeOrToday() && !this.executionPeriod.hasEnd()))

fun Task.executionStartIsBeforeOrToday() =
  this.executionPeriod.hasStart() &&
    with(this.executionPeriod.start) { this.before(today()) || this.isToday() }

fun Task.executionEndIsAfterOrToday() =
  this.executionPeriod.hasEnd() &&
    with(this.executionPeriod.end) { this.after(today()) || this.isToday() }

fun Task.TaskStatus.toCoding() = Coding(this.system, this.toCode(), this.display)

fun Task.isPastExpiry() =
  this.hasRestriction() &&
    this.restriction.hasPeriod() &&
    this.restriction.period.hasEnd() &&
    !this.restriction.period.end.after(today())

fun Task.isUpcoming() =
  this.hasStatus() &&
    this.status == Task.TaskStatus.REQUESTED &&
    this.hasExecutionPeriod() &&
    this.executionPeriod.hasStart() &&
    this.executionPeriod.start.after(today())

fun Task.isOverDue() =
  (this.hasStatus() &&
    (this.status == Task.TaskStatus.INPROGRESS || this.status == Task.TaskStatus.READY)) &&
    this.executionPeriod.hasEnd() &&
    this.executionPeriod.end.before(today())

fun Task.isDue() = this.hasStatus() && this.status == Task.TaskStatus.READY

/**
 * Retrieves the localized description of the Task using the translation configuration.
 *
 * This function:
 * 1. Takes the task description (typically from ActivityDefinition.productCodeableConcept.text)
 * 2. Converts it to a translation property key (e.g., "Discuss Confidentiality" →
 *    "discuss.confidentiality")
 * 3. Looks up the translation in the configured translation bundles for the current locale
 * 4. Falls back to the original description if no translation is found
 *
 * @param configurationRegistry The ConfigurationRegistry instance to access the localization helper
 * @return The localized task description, or the original description if no translation found
 */
fun Task.getLocalizedDescription(configurationRegistry: ConfigurationRegistry): String {
  val description = this.description ?: return ""
  val translationKey = description.translationPropertyKey()
  val template = "{{$translationKey}}"
  val translatedText =
    configurationRegistry.localizationHelper.parseTemplate(
      LocalizationHelper.STRINGS_BASE_BUNDLE_NAME,
      Locale.getDefault(),
      template,
    )
  return if (translatedText == template) description else translatedText
}
