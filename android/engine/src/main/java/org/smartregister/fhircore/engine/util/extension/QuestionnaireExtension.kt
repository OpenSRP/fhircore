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

import com.google.android.fhir.datacapture.extensions.asStringValue
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.datacapture.extensions.targetStructureMap
import java.util.Locale
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseStatus
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.engine.configuration.LinkIdType
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.UniqueIdAssignmentConfig
import org.smartregister.fhircore.engine.domain.model.ActionParameter
import org.smartregister.fhircore.engine.domain.model.ActionParameterType
import org.smartregister.fhircore.engine.domain.model.RuleConfig
import org.smartregister.fhircore.engine.domain.model.isEditable
import org.smartregister.fhircore.engine.domain.model.isReadOnly
import org.smartregister.fhircore.engine.domain.model.isSummary
import org.smartregister.fhircore.engine.util.castToType

fun QuestionnaireResponse.QuestionnaireResponseItemComponent.asLabel() =
  if (this.linkId != null) {
    this.linkId
      .replace("_", " ")
      .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
      .plus(": ")
  } else {
    ""
  }

fun Questionnaire.extractByStructureMap() =
  this.targetStructureMap != null ||
    this.extension.any { it.url.contains("sdc-questionnaire-itemExtractionContext") }

fun Questionnaire.cqfLibraryIds() =
  this.extension
    .filter { it.url.contains("cqf-library", ignoreCase = true) }
    .mapNotNull { it.cqfLibraryValue()?.replace("Library/", "") }

fun Questionnaire.cqfLibraryUrls() =
  this.extension
    .filter { it.url.contains("cqf-library", ignoreCase = true) }
    .mapNotNull { it.cqfLibraryValue() }

/** Supports valueString and valueCanonical for cqf-library extensions. */
private fun org.hl7.fhir.r4.model.Extension.cqfLibraryValue(): String? {
  val value = this.value ?: return null
  return when (value) {
    is org.hl7.fhir.r4.model.CanonicalType -> value.value
    is org.hl7.fhir.r4.model.UriType -> value.value
    is org.hl7.fhir.r4.model.StringType -> value.value
    else -> value.asStringValue()?.takeIf { it.isNotBlank() }
  }
}

fun QuestionnaireResponse.findSubject(bundle: Bundle?) =
  IdType(this.subject.reference).let { subject ->
    bundle?.entry?.find { it.resource.logicalId == subject.idPart }?.resource
  }

fun Questionnaire.find(linkId: String): Questionnaire.QuestionnaireItemComponent? {
  val result = mutableListOf<Questionnaire.QuestionnaireItemComponent>()
  item.find(FieldType.LINK_ID, linkId, result)
  return result.firstOrNull()
}

fun QuestionnaireResponse.find(
  linkId: String,
): QuestionnaireResponse.QuestionnaireResponseItemComponent? {
  return item.find(linkId, null)
}

fun List<QuestionnaireResponse.QuestionnaireResponseItemComponent>.find(
  linkId: String,
  default: QuestionnaireResponse.QuestionnaireResponseItemComponent?,
): QuestionnaireResponse.QuestionnaireResponseItemComponent? {
  var result = default
  run loop@{
    forEach {
      if (it.linkId == linkId) {
        result = it
        return@loop
      } else if (it.item.isNotEmpty()) {
        result = it.item.find(linkId, result)
      } else if (it.hasAnswer()) {
        it.answer.forEach { result = it.item.find(linkId, result) }
      }
    }
  }

  return result
}

enum class FieldType {
  DEFINITION,
  LINK_ID,
  TYPE,
}

fun Questionnaire.find(
  fieldType: FieldType,
  value: String,
): List<Questionnaire.QuestionnaireItemComponent> {
  val result = mutableListOf<Questionnaire.QuestionnaireItemComponent>()
  item.find(fieldType, value, result)
  return result
}

fun List<Questionnaire.QuestionnaireItemComponent>.find(
  fieldType: FieldType,
  value: String,
  target: MutableList<Questionnaire.QuestionnaireItemComponent>,
) {
  forEach {
    when (fieldType) {
      FieldType.DEFINITION -> {
        if (it.definition?.contentEquals(value, true) == true) {
          target.add(it)
        }
      }
      FieldType.LINK_ID -> {
        if (it.linkId == value) {
          target.add(it)
        }
      }
      FieldType.TYPE -> {
        if (it.type == Questionnaire.QuestionnaireItemType.valueOf(value)) target.add(it)
      }
    }

    if (it.item.isNotEmpty()) {
      it.item.find(fieldType, value, target)
    }
  }
}

val Questionnaire.QuestionnaireItemComponent.initialExpression: Expression?
  get() =
    this.getExtensionByUrl(EXTENSION_INITIAL_EXPRESSION_URL)?.let { it.castToExpression(it.value) }

const val EXTENSION_INITIAL_EXPRESSION_URL: String =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression"

/** Pre-Populate Questionnaire items with initial values */
// TODO: handle interpolation for null values on rules engine and not where the values are used
fun List<Questionnaire.QuestionnaireItemComponent>.prePopulateInitialValues(
  interpolationPrefix: String,
  prePopulationParams: List<ActionParameter>,
) {
  forEach { item ->
    prePopulationParams
      .firstOrNull {
        it.linkId == item.linkId && it.value.isNotEmpty() && !it.value.contains(interpolationPrefix)
      }
      ?.let { actionParam ->
        /**
         * Removes the initialExpression when the same linkId will be populated by config
         * pre-populate.
         */
        if (item.hasExtension(EXTENSION_INITIAL_EXPRESSION_URL)) {
          item.removeExtension(EXTENSION_INITIAL_EXPRESSION_URL)
        }
        if (item.type == Questionnaire.QuestionnaireItemType.CHOICE) {
          item.answerOption
            .filter {
              (it.value is Coding) &&
                if (actionParam.value.contains(",")) {
                  actionParam.value.split(",").contains((it.value as Coding).code)
                } else {
                  actionParam.value == (it.value as Coding).code
                }
            }
            .forEach { it.initialSelected = true }
        } else {
          item.initial =
            arrayListOf(
              Questionnaire.QuestionnaireItemInitialComponent().apply {
                value = actionParam.dataType?.let { actionParam.value.castToType(it) }
              },
            )
        }
      }
    if (item.item.isNotEmpty()) {
      item.item.prePopulateInitialValues(interpolationPrefix, prePopulationParams)
    }
  }
}

/**
 * Pre-populates questionnaire with computed values from the Rules engine as well as include initial
 * values set on configured [QuestionnaireConfig.barcodeLinkId] or
 * [QuestionnaireConfig.uniqueIdAssignment] properties.
 */
suspend fun Questionnaire.prepopulateWithComputedConfigValues(
  questionnaireConfig: QuestionnaireConfig,
  actionParameters: List<ActionParameter>?,
  questionnaireConfigRulesComputeFunc: (List<RuleConfig>) -> Map<String, Any>,
  extractUniqueIdAssignmentFunc: suspend (UniqueIdAssignmentConfig, Map<String, Any>) -> String,
): Map<String, Any> {
  require(questionnaireConfig.id.isNotBlank())

  // Compute questionnaire config rules and add extra questionnaire params to action parameters

  val questionnaireComputedValues =
    questionnaireConfig.configRules?.let { questionnaireConfigRulesComputeFunc.invoke(it) }
      ?: emptyMap()

  val allActionParameters =
    actionParameters?.plus(
      questionnaireConfig.extraParams?.map { it.interpolate(questionnaireComputedValues) }
        ?: emptyList(),
    )

  if (questionnaireConfig.isReadOnly() || questionnaireConfig.isEditable()) {
    item.prepareQuestionsForReadingOrEditing(
      readOnly = questionnaireConfig.isReadOnly(),
      readOnlyLinkIds =
        questionnaireConfig.readOnlyLinkIds
          ?: questionnaireConfig.linkIds
            ?.filter { it.type == LinkIdType.READ_ONLY }
            ?.map { it.linkId },
    )
  }

  if (questionnaireConfig.isEditable()) {
    item.prepareQuestionsForEditing(
      readOnlyLinkIds = questionnaireConfig.readOnlyLinkIds,
    )
  }

  // Pre-populate questionnaire items with configured values
  allActionParameters
    ?.filter { (it.paramType == ActionParameterType.PREPOPULATE && it.value.isNotEmpty()) }
    ?.let { actionParam -> item.prePopulateInitialValues(DEFAULT_PLACEHOLDER_PREFIX, actionParam) }

  // Set barcode to the configured linkId default: "patient-barcode"
  if (!questionnaireConfig.resourceIdentifier.isNullOrEmpty()) {
    (questionnaireConfig.barcodeLinkId
        ?: questionnaireConfig.linkIds?.firstOrNull { it.type == LinkIdType.BARCODE }?.linkId)
      ?.let { barcodeLinkId ->
        find(barcodeLinkId)?.apply {
          if (hasExtension(EXTENSION_INITIAL_EXPRESSION_URL)) {
            removeExtension(EXTENSION_INITIAL_EXPRESSION_URL)
          }

          initial =
            mutableListOf(
              Questionnaire.QuestionnaireItemInitialComponent()
                .setValue(StringType(questionnaireConfig.resourceIdentifier)),
            ) // TODO should this be resource identifier or OpenSrp unique ID?
          readOnly = true
        }
      }
  }

  prepopulateUniqueIdAssignment(
    questionnaireConfig,
    questionnaireComputedValues,
    extractUniqueIdAssignmentFunc,
  )

  return questionnaireComputedValues
}

/** Pre-populates questionnaire with computed [QuestionnaireConfig.uniqueIdAssignment]. */
suspend fun Questionnaire.prepopulateUniqueIdAssignment(
  questionnaireConfig: QuestionnaireConfig,
  questionnaireComputedValues: Map<String, Any>,
  extractUniqueIdAssignmentFunc: suspend (UniqueIdAssignmentConfig, Map<String, Any>) -> String,
) {
  // Set configured OpenSRPId on Questionnaire
  questionnaireConfig.uniqueIdAssignment?.let { uniqueIdAssignmentConfig ->
    find(uniqueIdAssignmentConfig.linkId)?.apply {
      if (initial.isNotEmpty() && !initial.first().isEmpty) return@apply

      val extractedId =
        extractUniqueIdAssignmentFunc(
          questionnaireConfig.uniqueIdAssignment,
          questionnaireComputedValues,
        )
      if (extractedId.isNotEmpty()) {
        if (hasExtension(EXTENSION_INITIAL_EXPRESSION_URL)) {
          removeExtension(EXTENSION_INITIAL_EXPRESSION_URL)
        }

        initial =
          mutableListOf(
            Questionnaire.QuestionnaireItemInitialComponent().setValue(StringType(extractedId)),
          )
      }
      readOnly = extractedId.isNotEmpty() && uniqueIdAssignmentConfig.readOnly
    }
  }
}

/**
 * Determines the [QuestionnaireResponse.Status] depending on the [saveDraft] and [isEditable]
 * values contained in the [QuestionnaireConfig]
 *
 * returns [COMPLETED] when [isEditable] or [isSummary] is [true] returns [INPROGRESS] when
 * [saveDraft] is [true]
 */
fun QuestionnaireConfig.questionnaireResponseStatus(): String? {
  return if (this.isEditable() || this.isSummary()) {
    QuestionnaireResponseStatus.COMPLETED.toCode()
  } else if (this.saveDraft) {
    QuestionnaireResponseStatus.INPROGRESS.toCode()
  } else {
    null
  }
}
