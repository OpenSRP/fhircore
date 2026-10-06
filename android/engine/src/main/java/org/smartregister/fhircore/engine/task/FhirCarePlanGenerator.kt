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

package org.smartregister.fhircore.engine.task

import android.content.Context
import androidx.annotation.VisibleForTesting
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.util.TerserUtil
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.get
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import org.hl7.fhir.r4.model.ActivityDefinition
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.BaseDateTimeType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.CarePlan
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Dosage
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.PlanDefinition
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StructureMap
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.hl7.fhir.r4.model.Timing
import org.hl7.fhir.r4.model.Timing.UnitsOfTime
import org.hl7.fhir.r4.utils.FHIRPathEngine
import org.hl7.fhir.r4.utils.StructureMapUtilities
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.event.EventType
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.util.extension.addResourceParameter
import org.smartregister.fhircore.engine.util.extension.asReference
import org.smartregister.fhircore.engine.util.extension.batchedSearch
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.extension.extractFhirpathDuration
import org.smartregister.fhircore.engine.util.extension.extractFhirpathPeriod
import org.smartregister.fhircore.engine.util.extension.extractId
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.isIn
import org.smartregister.fhircore.engine.util.extension.referenceValue
import org.smartregister.fhircore.engine.util.extension.updateDependentTaskDueDate
import org.smartregister.fhircore.engine.util.helper.TransformSupportServices
import timber.log.Timber

@Singleton
class FhirCarePlanGenerator
@Inject
constructor(
  val fhirEngine: FhirEngine,
  val fhirPathEngine: FHIRPathEngine,
  val transformSupportServices: TransformSupportServices,
  val defaultRepository: DefaultRepository,
  val fhirResourceUtil: FhirResourceUtil,
  val workflowCarePlanGenerator: WorkflowCarePlanGenerator,
  @ApplicationContext val context: Context,
) {
  private val structureMapUtilities by lazy {
    StructureMapUtilities(
      transformSupportServices.simpleWorkerContext,
      transformSupportServices,
    )
  }

  suspend fun generateOrUpdateCarePlan(
    planDefinitionId: String,
    subject: Resource,
    data: Bundle = Bundle(),
    generateCarePlanWithWorkflowApi: Boolean = false,
    replaceExistingTasks: Boolean = false,
  ): CarePlan? {
    val planDefinition = defaultRepository.loadResource<PlanDefinition>(planDefinitionId)
    return planDefinition?.let {
      generateOrUpdateCarePlan(
        planDefinition = it,
        subject = subject,
        data = data,
        generateCarePlanWithWorkflowApi = generateCarePlanWithWorkflowApi,
        replaceExistingTasks = replaceExistingTasks,
      )
    }
  }

  /**
   * Generates the CarePlan for [planDefinition] or updates the existing one for [subject].
   *
   * Set [replaceExistingTasks] when the plan is re-applied after an edit (e.g. a corrected date of
   * birth). Tasks already on the CarePlan are then updated in place instead of being added a second
   * time; see [reconcileWithExistingTasks].
   */
  suspend fun generateOrUpdateCarePlan(
    planDefinition: PlanDefinition,
    subject: Resource,
    data: Bundle = Bundle(),
    generateCarePlanWithWorkflowApi: Boolean = false,
    replaceExistingTasks: Boolean = false,
  ): CarePlan? {
    val relatedEntityLocationTags =
      subject.meta.tag.filter {
        it.system == context.getString(R.string.sync_strategy_related_entity_location_system)
      }

    // Only one CarePlan per plan, update or init a new one if not exists
    val output =
      fhirEngine
        .batchedSearch<CarePlan> {
          filter(
            CarePlan.INSTANTIATES_CANONICAL,
            { value = planDefinition.referenceValue() },
          )
          filter(CarePlan.SUBJECT, { value = subject.referenceValue() })
          filter(
            CarePlan.STATUS,
            { value = of(CarePlan.CarePlanStatus.DRAFT.toCode()) },
            { value = of(CarePlan.CarePlanStatus.ACTIVE.toCode()) },
            { value = of(CarePlan.CarePlanStatus.ONHOLD.toCode()) },
            { value = of(CarePlan.CarePlanStatus.UNKNOWN.toCode()) },
          )
        }
        .map { it.resource }
        .firstOrNull()
        ?: CarePlan().apply {
          // TODO delete this section once all PlanDefinitions are using new recommended approach
          this.title = planDefinition.title
          this.description = planDefinition.description
          this.instantiatesCanonical = listOf(CanonicalType(planDefinition.asReference().reference))
          // Add the subject's Related Entity Location tag to the CarePlan
          relatedEntityLocationTags.forEach(this.meta::addTag)
        }

    val existingTasks =
      if (replaceExistingTasks && output.logicalId.isNotEmpty()) {
        loadCarePlanTasks(output, subject)
      } else {
        emptyList()
      }
    val existingActivityCount = output.activity.size

    val carePlanModified: Boolean =
      if (generateCarePlanWithWorkflowApi) {
        workflowCarePlanGenerator.applyPlanDefinitionOnPatient(
          planDefinition = planDefinition,
          patient = subject as Patient,
          data = data,
          output = output,
        )
        true
      } else {
        liteApplyPlanDefinitionOnPatient(planDefinition, data, subject, output)
      }

    output.contained.filterIsInstance<Task>().forEach { it.fixCarePlanReferences() }

    if (existingTasks.isNotEmpty()) {
      reconcileWithExistingTasks(output, existingTasks, existingActivityCount)
    }

    val carePlanTasks = output.contained.filterIsInstance<Task>()

    output.cleanPlanDefinitionCanonical()

    if (carePlanModified) saveCarePlan(output, relatedEntityLocationTags)

    if (carePlanTasks.isNotEmpty()) {
      fhirResourceUtil.updateUpcomingTasksToDue(
        subject = subject.asReference(),
        taskResourcesToFilterBy = carePlanTasks,
      )
    }

    return if (output.hasActivity()) output else null
  }

  /**
   * Matches the Tasks just generated into [carePlan] (contained) with the [existingTasks] of the
   * same CarePlan, pairing them by description and code, in execution period order. For every
   * matched pair:
   * - existing Task completed, in progress, failed or entered in error: the new Task is dropped and
   *   the existing one is kept untouched (recorded work is never changed);
   * - otherwise the new Task takes over the existing Task's id and identifier, so saving it updates
   *   the existing Task (new dates and status) instead of adding a duplicate.
   *
   * References between the new Tasks (partOf) and the CarePlan activities are rewritten to the
   * existing ids. Unmatched new Tasks are kept as they are.
   */
  @VisibleForTesting
  fun reconcileWithExistingTasks(
    carePlan: CarePlan,
    existingTasks: List<Task>,
    existingActivityCount: Int,
  ) {
    val newTasks = carePlan.contained.filterIsInstance<Task>()
    if (newTasks.isEmpty()) return

    val existingByKey =
      existingTasks
        .filter { it.taskMatchKey() != null }
        .sortedBy { it.executionPeriod?.start }
        .groupBy { it.taskMatchKey() }
        .mapValues { it.value.toMutableList() }

    val replacedIds = mutableMapOf<String, String>()
    val droppedTasks = mutableListOf<Task>()

    newTasks
      .sortedBy { it.executionPeriod?.start }
      .forEach { newTask ->
        val existing = existingByKey[newTask.taskMatchKey() ?: return@forEach]?.removeFirstOrNull()
        if (existing != null) {
          replacedIds[newTask.logicalId] = existing.logicalId
          if (
            existing.status.isIn(
              TaskStatus.COMPLETED,
              TaskStatus.INPROGRESS,
              TaskStatus.FAILED,
              TaskStatus.ENTEREDINERROR,
            )
          ) {
            droppedTasks.add(newTask)
          } else {
            newTask.id = existing.logicalId
            if (existing.hasIdentifier()) newTask.identifier = existing.identifier
            // A regenerated Task starts as requested (upcoming, not actionable); keep a Task that
            // was already due actionable while its new start date has passed
            if (
              existing.status == TaskStatus.READY &&
                newTask.status == TaskStatus.REQUESTED &&
                newTask.executionPeriod?.start?.after(Date())?.not() == true
            ) {
              newTask.status = TaskStatus.READY
            }
          }
        }
      }

    if (replacedIds.isEmpty()) return

    carePlan.contained.removeAll(droppedTasks)

    fun remap(reference: String?) =
      reference
        ?.takeIf { it.startsWith("${ResourceType.Task.name}/") }
        ?.let { replacedIds[it.extractLogicalIdUuid()] }
        ?.let { "${ResourceType.Task.name}/$it" }

    carePlan.contained.filterIsInstance<Task>().forEach { task ->
      task.partOf.forEach { partOf -> remap(partOf.reference)?.let { partOf.reference = it } }
    }

    // The activities may already reference the existing Tasks; point references to the Tasks
    // generated now at the existing ones, drop duplicates and activities left without Tasks
    val references = carePlan.activity.flatMap { it.outcomeReference }.map { it.reference }
    val referenced = references.filter { remap(it) == null }.toMutableSet()
    val emptiedActivities = mutableListOf<CarePlan.CarePlanActivityComponent>()
    carePlan.activity.forEach { activity ->
      val removed =
        activity.outcomeReference.removeAll { ref ->
          val target = remap(ref.reference) ?: return@removeAll false
          if (referenced.add(target)) {
            ref.reference = target
            false
          } else {
            true
          }
        }
      if (removed && activity.outcomeReference.isEmpty()) emptiedActivities.add(activity)
    }
    carePlan.activity
      .drop(existingActivityCount)
      .filter { it in emptiedActivities }
      .let { carePlan.activity.removeAll(it) }
  }

  /**
   * Loads the Tasks of [subject] that are based on [carePlan]. Matches on the CarePlan id rather
   * than the based-on search index, because Tasks generated on the device may carry a doubled
   * prefix (CarePlan/CarePlan/<id>) that the server only normalizes on upload.
   */
  @VisibleForTesting
  suspend fun loadCarePlanTasks(carePlan: CarePlan, subject: Resource): List<Task> =
    fhirEngine
      .batchedSearch<Task> { filter(Task.SUBJECT, { value = subject.referenceValue() }) }
      .map { it.resource }
      .filter { task ->
        task.basedOn.any { it.reference?.substringAfterLast('/') == carePlan.logicalId }
      }

  /** Rewrites a doubled CarePlan/CarePlan/<id> based-on reference to CarePlan/<id>. */
  private fun Task.fixCarePlanReferences() {
    val doubledPrefix = "${ResourceType.CarePlan.name}/${ResourceType.CarePlan.name}/"
    basedOn
      .filter { it.reference?.startsWith(doubledPrefix) == true }
      .forEach { it.reference = it.reference.removePrefix("${ResourceType.CarePlan.name}/") }
  }

  private fun Task.taskMatchKey(): String? {
    val text = description?.trim()
    if (text.isNullOrEmpty()) return null
    return text + "|" + code.coding.joinToString(",") { "${it.system}|${it.code}" }
  }

  // TODO refactor this code to remove hardcoded appended "PlanDefinition/" on
  // https://github.com/opensrp/fhircore/issues/3386
  private fun CarePlan.cleanPlanDefinitionCanonical() {
    val canonicalValue = this.instantiatesCanonical.first().value
    if (canonicalValue.contains('/').not()) {
      this.instantiatesCanonical = listOf(CanonicalType("PlanDefinition/$canonicalValue"))
    }
  }

  @VisibleForTesting
  fun invokeCleanPlanDefinitionCanonical(carePlan: CarePlan) =
    carePlan.cleanPlanDefinitionCanonical()

  /** Implements OpenSRP's $lite version of CarePlan & Tasks generation via StructureMap(s) */
  private suspend fun liteApplyPlanDefinitionOnPatient(
    planDefinition: PlanDefinition,
    data: Bundle,
    subject: Resource,
    output: CarePlan,
  ): Boolean {
    var carePlanModified = false
    planDefinition.action.forEach { action ->
      val input = Bundle().apply { entry.addAll(data.entry) }

      if (action.passesConditions(input, planDefinition, subject)) {
        val definition = action.activityDefinition(planDefinition)

        if (action.hasTransform()) {
          val taskPeriods = action.taskPeriods(definition, output)

          taskPeriods.forEachIndexed { index, period ->
            val source =
              Parameters().apply {
                addResourceParameter(CarePlan.SP_SUBJECT, subject)
                addResourceParameter(PlanDefinition.SP_DEFINITION, definition)
                // TODO find some other way (activity definition based) to pass additional data
                addResourceParameter(PlanDefinition.SP_DEPENDS_ON, data)
              }
            source.setParameter(Task.SP_PERIOD, period)
            source.setParameter(ActivityDefinition.SP_VERSION, IntegerType(index))
            val structureMapId = IdType(action.transform).idPart
            val structureMap = defaultRepository.loadResourceFromCache<StructureMap>(structureMapId)

            structureMapUtilities.transform(
              transformSupportServices.simpleWorkerContext,
              source,
              structureMap,
              output,
            )
          }
        }

        if (definition.hasDynamicValue()) {
          definition.dynamicValue.forEach { dynamicValue ->
            if (definition.kind == ActivityDefinition.ActivityDefinitionKind.CAREPLAN) {
              dynamicValue.expression.expression
                .let {
                  fhirPathEngine.evaluate(
                    null,
                    input,
                    planDefinition,
                    subject,
                    it,
                  )
                }
                ?.takeIf { it.isNotEmpty() }
                ?.let { evaluatedValue ->
                  // TODO handle cases where we explicitly need to set previous value as null,
                  // when passing null to Terser, it gives error NPE
                  Timber.d("${dynamicValue.path}, evaluatedValue: $evaluatedValue")
                  TerserUtil.setFieldByFhirPath(
                    FhirContext.forR4Cached(),
                    dynamicValue.path.removePrefix("${definition.kind.display}."),
                    output,
                    evaluatedValue.first(),
                  )
                }
            } else {
              throw UnsupportedOperationException("${definition.kind} not supported")
            }
          }
        }
        carePlanModified = true
      }
    }
    return carePlanModified
  }

  private suspend fun saveCarePlan(output: CarePlan, relatedEntityLocationTags: List<Coding>) {
    output
      .also { Timber.d(it.encodeResourceToString()) }
      .also { carePlan ->
        // Save embedded resources inside as independent entries, clear embedded and save carePlan
        val dependents = carePlan.contained.map { it }

        carePlan.contained.clear()

        defaultRepository.addOrUpdate(true, carePlan)

        dependents.forEach {
          relatedEntityLocationTags.forEach(it.meta::addTag)
          defaultRepository.addOrUpdate(true, it)
        }

        if (carePlan.status == CarePlan.CarePlanStatus.COMPLETED) {
          carePlan.activity
            .flatMap { it.outcomeReference }
            .filter { it.reference.startsWith(ResourceType.Task.name) }
            .mapNotNull { getTask(it.extractId()) }
            .forEach {
              if (
                it.status.isIn(
                  TaskStatus.REQUESTED,
                  TaskStatus.READY,
                  TaskStatus.INPROGRESS,
                )
              ) {
                cancelTaskByTaskId(
                  it.logicalId,
                  "${carePlan.fhirType()} ${carePlan.status}",
                )
              }
            }
        }
      }
  }

  suspend fun updateTaskDetailsByResourceId(
    id: String,
    status: TaskStatus,
    reason: String? = null,
  ) {
    getTask(id)
      ?.apply {
        this.status = status
        this.lastModified = Date()
        if (reason != null) this.statusReason = CodeableConcept().apply { text = reason }
      }
      ?.updateDependentTaskDueDate(defaultRepository)
      ?.run { defaultRepository.addOrUpdate(addMandatoryTags = true, resource = this) }
  }

  private suspend fun cancelTaskByTaskId(id: String, reason: String) {
    updateTaskDetailsByResourceId(id, TaskStatus.CANCELLED, reason)
  }

  suspend fun getTask(id: String) =
    kotlin.runCatching { fhirEngine.get<Task>(id) }.onFailure { Timber.e(it) }.getOrNull()

  @VisibleForTesting
  fun evaluateToDate(base: Base?, expression: String): BaseDateTimeType? =
    base?.let { fhirPathEngine.evaluate(it, expression).firstOrNull()?.dateTimeValue() }

  private fun PlanDefinition.PlanDefinitionActionComponent.passesConditions(
    focus: Resource?,
    root: Resource?,
    base: Base,
  ) =
    this.condition.all {
      require(it.kind == PlanDefinition.ActionConditionKind.APPLICABILITY) {
        "PlanDefinition.action.kind=${it.kind} not supported"
      }

      require(it.expression.language == Expression.ExpressionLanguage.TEXT_FHIRPATH.toCode()) {
        "PlanDefinition.expression.language=${it.expression.language} not supported"
      }

      fhirPathEngine.evaluateToBoolean(focus, root, base, it.expression.expression)
    }

  private fun PlanDefinition.PlanDefinitionActionComponent.activityDefinition(
    planDefinition: PlanDefinition,
  ) =
    planDefinition.contained
      .filter { it.resourceType == ResourceType.ActivityDefinition }
      .first { it.logicalId == this.definitionCanonicalType.value } as ActivityDefinition

  private fun PlanDefinition.PlanDefinitionActionComponent.taskPeriods(
    definition: ActivityDefinition,
    carePlan: CarePlan,
  ): List<Period> {
    return when {
      definition.hasDosage() -> extractTaskPeriodsFromDosage(definition.dosage, carePlan)
      definition.hasTiming() && !definition.hasTimingTiming() ->
        throw IllegalArgumentException(
          "Timing component should only be Timing. Can not handle ${timing.fhirType()}",
        )
      else -> extractTaskPeriodsFromTiming(definition.timingTiming, carePlan)
    }
  }

  private fun extractTaskPeriodsFromTiming(timing: Timing, carePlan: CarePlan): List<Period> {
    val taskPeriods = mutableListOf<Period>()
    // TODO handle properties used by older PlanDefintions. If any PlanDefinition is using
    // countMax, frequency, durationUnit of hour, consider as non compliant and assume
    // all handling would be done with a structure map. Once all PlanDefinitions are using
    // recommended approach and using plan definitions properly change line below to use
    // timing.repeat.count only
    val isLegacyPlanDefinition =
      (timing.repeat.hasFrequency() ||
        timing.repeat.hasCountMax() ||
        timing.repeat.durationUnit?.equals(UnitsOfTime.H) == true)
    val count = if (isLegacyPlanDefinition || !timing.repeat.hasCount()) 1 else timing.repeat.count

    val periodExpression = timing.extractFhirpathPeriod()
    val durationExpression = timing.extractFhirpathDuration()

    // Offset date for current task period; CarePlan start if all tasks generated at once
    // otherwise today means that tasks are generated on demand
    var offsetDate: BaseDateTimeType =
      DateTimeType(if (timing.repeat.hasCount()) carePlan.period.start else Date())

    for (i in 1..count) {
      if (periodExpression.isNotBlank() && offsetDate.hasValue()) {
        evaluateToDate(offsetDate, "\$this + $periodExpression")?.let { offsetDate = it }
      }

      Period()
        .apply {
          start = offsetDate.value
          end =
            if (durationExpression.isNotBlank() && offsetDate.hasValue()) {
              evaluateToDate(offsetDate, "\$this + $durationExpression")?.value
            } else {
              carePlan.period.end
            }
        }
        .also { taskPeriods.add(it) }
    }

    return taskPeriods
  }

  private fun extractTaskPeriodsFromDosage(
    dosage: List<Dosage>,
    carePlan: CarePlan,
  ): List<Period> {
    val taskPeriods = mutableListOf<Period>()
    dosage
      .flatMap { extractTaskPeriodsFromTiming(it.timing, carePlan) }
      .also { taskPeriods.addAll(it) }

    return taskPeriods
  }

  /**
   * Updates the due date of a dependent task based on the current status of the task. @param id The
   * ID of the dependent task to update. @return The updated task object. This function is used to
   * update the status of all CarePlans connected to a [QuestionnaireConfig]'s PlanDefinitions based
   * on the [QuestionnaireConfig]'s CarePlanConfigs and their configs filtering information.
   *
   * @param questionnaireConfig The QuestionnaireConfig that contains the CarePlanConfigs
   * @param subject The subject to evaluate CarePlanConfig FHIR path expressions against if the
   *   CarePlanConfig does not reference a resource.
   */
  suspend fun conditionallyUpdateResourceStatus(
    questionnaireConfig: QuestionnaireConfig,
    subject: Resource,
    bundle: Bundle,
  ) {
    questionnaireConfig.eventWorkflows
      .filter { it.eventType == EventType.RESOURCE_CLOSURE }
      .forEach { eventWorkFlow ->
        eventWorkFlow.eventResources.forEach { eventResource ->
          val currentResourceTriggerConditions =
            eventWorkFlow.triggerConditions.firstOrNull { it.eventResourceId == eventResource.id }
          val resourceClosureConditionsMet =
            evaluateToBoolean(
              subject = subject,
              bundle = bundle,
              triggerConditions = currentResourceTriggerConditions?.conditionalFhirPathExpressions,
              matchAll = currentResourceTriggerConditions?.matchAll!!,
            )

          if (resourceClosureConditionsMet) {
            defaultRepository.updateResourcesRecursively(
              eventResource,
              subject,
              eventWorkFlow,
            )
          }
        }
      }
  }

  fun evaluateToBoolean(
    subject: Resource,
    bundle: Bundle,
    triggerConditions: List<String>?,
    matchAll: Boolean = false,
  ): Boolean {
    return if (matchAll) {
      triggerConditions?.all { triggerCondition ->
        fhirPathEngine.evaluateToBoolean(bundle, null, subject, triggerCondition)
      } == true
    } else {
      triggerConditions?.any { triggerCondition ->
        fhirPathEngine.evaluateToBoolean(bundle, null, subject, triggerCondition)
      } == true
    }
  }
}
