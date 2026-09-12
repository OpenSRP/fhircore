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

package org.smartregister.fhircore.quest.ui.questionnaire

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.datacapture.mapping.ResourceMapper
import com.google.android.fhir.datacapture.mapping.StructureMapExtractionContext
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.search.Search
import com.google.android.fhir.search.filter.TokenParamFilterCriterion
import com.google.android.fhir.workflow.FhirOperator
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Date
import java.util.LinkedList
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.context.IWorkerContext
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Basic
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Group
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.ListResource
import org.hl7.fhir.r4.model.ListResource.ListEntryComponent
import org.hl7.fhir.r4.model.MedicationRequest
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent
import org.hl7.fhir.r4.model.RelatedPerson
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.StructureMap
import org.smartregister.fhircore.engine.BuildConfig
import org.smartregister.fhircore.engine.configuration.ConfigType
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.configuration.GroupResourceConfig
import org.smartregister.fhircore.engine.configuration.LinkIdType
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.app.ApplicationConfiguration
import org.smartregister.fhircore.engine.configuration.app.CodingSystemUsage
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.domain.model.ActionParameter
import org.smartregister.fhircore.engine.domain.model.ActionParameterType
import org.smartregister.fhircore.engine.domain.model.isEditable
import org.smartregister.fhircore.engine.domain.model.isReadOnly
import org.smartregister.fhircore.engine.domain.model.isSummary
import org.smartregister.fhircore.engine.rulesengine.RulesExecutor
import org.smartregister.fhircore.engine.task.FhirCarePlanGenerator
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.allItems
import org.smartregister.fhircore.engine.util.extension.appendOrganizationInfo
import org.smartregister.fhircore.engine.util.extension.appendPractitionerInfo
import org.smartregister.fhircore.engine.util.extension.appendRelatedEntityLocation
import org.smartregister.fhircore.engine.util.extension.asReference
import org.smartregister.fhircore.engine.util.extension.batchedSearch
import org.smartregister.fhircore.engine.util.extension.clearText
import org.smartregister.fhircore.engine.util.extension.cqfLibraryUrls
import org.smartregister.fhircore.engine.util.extension.extractByStructureMap
import org.smartregister.fhircore.engine.util.extension.extractId
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuidFromURI
import org.smartregister.fhircore.engine.util.extension.find
import org.smartregister.fhircore.engine.util.extension.generateMissingId
import org.smartregister.fhircore.engine.util.extension.initialExpression
import org.smartregister.fhircore.engine.util.extension.isIn
import org.smartregister.fhircore.engine.util.extension.prepopulateWithComputedConfigValues
import org.smartregister.fhircore.engine.util.extension.questionnaireResponseStatus
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.engine.util.extension.updateLastUpdated
import org.smartregister.fhircore.engine.util.fhirpath.FhirPathDataExtractor
import org.smartregister.fhircore.engine.util.helper.TransformSupportServices
import org.smartregister.fhircore.engine.util.validation.ResourceValidationRequest
import org.smartregister.fhircore.engine.util.validation.ResourceValidationRequestHandler
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.util.QuestionnaireResponseUtils
import timber.log.Timber

@HiltViewModel
class QuestionnaireViewModel
@Inject
constructor(
  val defaultRepository: DefaultRepository,
  val dispatcherProvider: DispatcherProvider,
  val fhirCarePlanGenerator: FhirCarePlanGenerator,
  val rulesExecutor: RulesExecutor,
  val transformSupportServices: TransformSupportServices,
  val sharedPreferencesHelper: SharedPreferencesHelper,
  val fhirOperator: FhirOperator,
  val fhirValidatorRequestHandlerProvider: Lazy<ResourceValidationRequestHandler>,
  val fhirPathDataExtractor: FhirPathDataExtractor,
  val configurationRegistry: ConfigurationRegistry,
) : ViewModel() {
  private val authenticatedOrganizationIds by lazy {
    sharedPreferencesHelper.read<List<String>>(ResourceType.Organization.name)
  }

  private val practitionerId: String? by lazy {
    sharedPreferencesHelper
      .read(SharedPreferenceKey.PRACTITIONER_ID.name, null)
      ?.extractLogicalIdUuid()
  }

  val applicationConfiguration: ApplicationConfiguration by lazy {
    configurationRegistry.retrieveConfiguration(ConfigType.Application)
  }

  private val _questionnaireFormUpdateMutableStateflow =
    MutableStateFlow<QuestionnaireFormUpdate>(
      QuestionnaireFormUpdate.ShowQuestionnaireResponse(null),
    )
  val questionnaireFormUpdateStateflow: StateFlow<QuestionnaireFormUpdate>
    get() = _questionnaireFormUpdateMutableStateflow

  var uniqueIdResource: Resource? = null

  lateinit var currentQuestionnaire: Questionnaire
    private set

  fun setQuestionnaire(questionnaire: Questionnaire) {
    currentQuestionnaire = questionnaire
    currentQuestionnaire.url = "Questionnaire/${currentQuestionnaire.logicalId}"
  }

  /**
   * This function retrieves the [Questionnaire] as configured via the [QuestionnaireConfig]. The
   * retrieved [Questionnaire] can then be pre-populated.
   */
  suspend fun retrieveQuestionnaire(
    questionnaireConfig: QuestionnaireConfig,
  ): Questionnaire? {
    if (questionnaireConfig.id.isEmpty() || questionnaireConfig.id.isBlank()) return null
    return defaultRepository.loadResourceFromCache<Questionnaire>(questionnaireConfig.id)
  }

  /**
   * This function performs data extraction against the [QuestionnaireResponse]. All the resources
   * generated from a successful extraction by StructureMap or definition are stored in the
   * database. The [QuestionnaireResponse] is also stored in the database regardless of the outcome
   * of [ResourceMapper.extract]. This function will optionally generate CarePlan using the
   * PlanDefinition resource configured in [QuestionnaireConfig.planDefinitions]. The
   * [QuestionnaireConfig.eventWorkflows] contains configurations to cascade update the statuses of
   * resources to in-active (close) that are related to the current [QuestionnaireResponse.subject]
   */
  fun handleQuestionnaireSubmission(
    questionnaire: Questionnaire,
    currentQuestionnaireResponse: QuestionnaireResponse,
    questionnaireConfig: QuestionnaireConfig,
    actionParameters: List<ActionParameter>,
    context: Context,
    onQuestionnaireResponseInvalid: () -> Unit,
    onSuccessfulSubmission: (List<IdType>, QuestionnaireResponse) -> Unit,
  ) {
    viewModelScope.launch(SupervisorJob()) {
      val questionnaireResponseValid =
        validateQuestionnaireResponse(
          questionnaire,
          currentQuestionnaireResponse,
          context,
        )

      if (questionnaireConfig.saveQuestionnaireResponse && !questionnaireResponseValid) {
        onQuestionnaireResponseInvalid.invoke()
        return@launch
      }

      val idTypes =
        async(dispatcherProvider.default()) {
          currentQuestionnaireResponse.processMetadata(
            questionnaire = questionnaire,
            questionnaireConfig = questionnaireConfig,
            context = context,
          )

          val bundle =
            performExtraction(
              extractByStructureMap = questionnaire.extractByStructureMap(),
              questionnaire = questionnaire,
              questionnaireResponse = currentQuestionnaireResponse,
              context = context,
            )

          defaultRepository.applyDbTransaction {
            performSave(
              bundle,
              questionnaire,
              questionnaireConfig,
              currentQuestionnaireResponse,
              context,
              actionParameters,
            )
          }

          bundle.entry?.map { IdType(it.resource.resourceType.name, it.resource.logicalId) }
            ?: emptyList()
        }

      onSuccessfulSubmission(
        idTypes.await(),
        currentQuestionnaireResponse,
      )
    }
  }

  private suspend fun performSave(
    bundle: Bundle,
    questionnaire: Questionnaire,
    questionnaireConfig: QuestionnaireConfig,
    currentQuestionnaireResponse: QuestionnaireResponse,
    context: Context,
    actionParameters: List<ActionParameter>,
  ) {
    saveExtractedResources(
      bundle = bundle,
      questionnaire = questionnaire,
      questionnaireConfig = questionnaireConfig,
      questionnaireResponse = currentQuestionnaireResponse,
      context = context,
    )

    updateResourcesLastUpdatedProperty(actionParameters)

    val bundleCopy =
      bundle.copyBundle(currentQuestionnaireResponse).also {
        val extractedResources = it.entry.map { entry -> entry.resource }
        validateWithFhirValidator(*extractedResources.toTypedArray())
      }

    // Important to load subject resource to retrieve ID (as reference) correctly
    val subjectIdType: IdType? =
      if (currentQuestionnaireResponse.subject.reference.isNullOrEmpty()) {
        null
      } else {
        IdType(currentQuestionnaireResponse.subject.reference)
      }

    if (subjectIdType != null && !questionnaireConfig.isReadOnly()) {
      val subject =
        loadResource(
          ResourceType.valueOf(subjectIdType.resourceType),
          subjectIdType.idPart,
        )

      subject?.let {
        generateCarePlanExecuteCQL(
          it,
          questionnaireConfig,
          bundle = bundleCopy,
          questionnaire,
        )
      }
    }

    softDeleteResources(questionnaireConfig)

    retireUsedQuestionnaireUniqueId(questionnaireConfig, currentQuestionnaireResponse)

    if (
      questionnaireConfig.linkIds?.any { it.type == LinkIdType.REPEATED_GROUP_DELETION } == true
    ) {
      processRepeatedGroupItems(
        questionnaireResponse = currentQuestionnaireResponse,
        questionnaire = questionnaire,
        questionnaireConfig = questionnaireConfig,
      )
    }
  }

  private suspend fun generateCarePlanExecuteCQL(
    subject: Resource,
    questionnaireConfig: QuestionnaireConfig,
    bundle: Bundle,
    questionnaire: Questionnaire,
  ) = coroutineScope {
    generateCarePlan(
      subject = subject,
      bundle = bundle,
      questionnaireConfig = questionnaireConfig,
    )

    executeCql(
      subject = subject,
      bundle = bundle,
      questionnaire = questionnaire,
      questionnaireConfig = questionnaireConfig,
    )

    fhirCarePlanGenerator.conditionallyUpdateResourceStatus(
      questionnaireConfig = questionnaireConfig,
      subject = subject,
      bundle = bundle,
    )
  }

  fun validateWithFhirValidator(vararg resource: Resource) {
    if (BuildConfig.DEBUG) {
      fhirValidatorRequestHandlerProvider
        .get()
        .handleResourceValidationRequest(
          request =
            ResourceValidationRequest(
              *resource,
            ),
        )
    }
  }

  suspend fun retireUsedQuestionnaireUniqueId(
    questionnaireConfig: QuestionnaireConfig,
    questionnaireResponse: QuestionnaireResponse,
  ) {
    if (questionnaireConfig.uniqueIdAssignment != null) {
      val uniqueIdLinkId = questionnaireConfig.uniqueIdAssignment!!.linkId
      val submittedUniqueId =
        questionnaireResponse.find(uniqueIdLinkId)?.answer?.first()?.value.toString()

      // Update Group resource. Can be extended in future to support other resources
      if (uniqueIdResource is Group) {
        with(uniqueIdResource as Group) {
          val characteristic = this.characteristic[this.quantity]
          if (
            characteristic.hasValueCodeableConcept() &&
              characteristic.valueCodeableConcept.text == submittedUniqueId
          ) {
            characteristic.exclude = true
            this.quantity++
            this.active =
              this.quantity <
                this.characteristic.size // Mark Group as inactive when all IDs are retired
            defaultRepository.addOrUpdate(resource = this)
          }
        }
      }
      Timber.i(
        "ID '$submittedUniqueId' used'",
      )
    }
  }

  /**
   * This function handles processing of resources that have been deleted from a repeat group widget
   * A string consisting of comma-separated IDs of resources that are initially loaded on the repeat
   * group are cross-referenced with IDs of resources in the [QuestionnaireResponse.contained] field
   * to determine which resources have been deleted. The [QuestionnaireResponse.contained] has a
   * list of resources that are left when the Questionnaire is submitted.
   *
   * The current implementation only handles [MedicationRequest] resources. The logic for updating
   * these resources is hard-coded at the moment
   *
   * @param questionnaireResponse The QuestionnaireResponse generated when the form is submitted
   * @param questionnaire The Questionnaire being worked on
   * @param questionnaireConfig The [QuestionnaireConfig] containing the [LindIds] config The
   *   [LindIds] config contains the following [resourceType] Type of resource [linkId] LinkId for
   *   the questionnaire field containing the string of containing comma-separated resource Ids that
   *   show which resources are available when the Repeat Group widget is initially loaded. Sample
   *   of this would be ["medication-request-id1,medication-request-id2,medication-request-id3"]
   */
  suspend fun processRepeatedGroupItems(
    questionnaireResponse: QuestionnaireResponse,
    questionnaire: Questionnaire,
    questionnaireConfig: QuestionnaireConfig,
  ) {
    val listResource = questionnaireResponse.contained[0] as ListResource
    val repeatedGroupLinkIdConfig =
      questionnaireConfig.linkIds?.firstOrNull { it.type == LinkIdType.REPEATED_GROUP_DELETION }
    if (repeatedGroupLinkIdConfig == null) {
      return
    }
    val containedResourceIds =
      listResource.entry
        .filter { it.item.reference.contains("${repeatedGroupLinkIdConfig.resourceType?.name}") }
        .mapNotNull { it.item.reference.extractLogicalIdUuid() }

    val initialResourceIdsString: String =
      questionnaire.item
        .firstOrNull { it.linkId == repeatedGroupLinkIdConfig.linkId }
        ?.initial
        ?.firstOrNull()
        ?.value
        .toString()

    if (initialResourceIdsString.isBlank()) {
      return
    }

    initialResourceIdsString.split(DELIMITER).forEach { resourceId ->
      if (!containedResourceIds.contains(resourceId)) {
        val medicationRequest =
          MedicationRequest().apply {
            id = resourceId
            status = MedicationRequest.MedicationRequestStatus.STOPPED
          }
        defaultRepository.addOrUpdate(resource = medicationRequest)
      }
    }
  }

  suspend fun saveExtractedResources(
    bundle: Bundle,
    questionnaire: Questionnaire,
    questionnaireConfig: QuestionnaireConfig,
    questionnaireResponse: QuestionnaireResponse,
    context: Context,
  ) {
    val extractionDate = Date()

    // Create a ListResource to store the references for generated resources
    val listResource =
      ListResource().apply {
        id = UUID.randomUUID().toString()
        status = ListResource.ListStatus.CURRENT
        mode = ListResource.ListMode.WORKING
        title = CONTAINED_LIST_TITLE
        date = extractionDate
      }

    val subjectType = questionnaireSubjectType(questionnaire, questionnaireConfig)

    val previouslyExtractedResources =
      retrievePreviouslyExtractedResources(
        questionnaireConfig = questionnaireConfig,
        subjectType = subjectType,
        questionnaire = questionnaire,
      )

    val extractedResourceUniquePropertyExpressionsMap =
      questionnaireConfig.extractedResourceUniquePropertyExpressions?.associateBy {
        it.resourceType
      } ?: emptyMap()

    bundle.entry
      ?.mapNotNull { it.resource }
      ?.forEach { entryResource ->
        entryResource.applyResourceMetadata(questionnaireConfig, questionnaireResponse, context)
        if (
          questionnaireResponse.subject.reference.isNullOrEmpty() &&
            subjectType != null &&
            entryResource.resourceType == subjectType &&
            entryResource.logicalId.isNotEmpty()
        ) {
          questionnaireResponse.subject = entryResource.logicalId.asReference(subjectType)
        }

        if (questionnaireConfig.isEditable()) {
          if (entryResource.resourceType == subjectType) {
            entryResource.id = questionnaireResponse.subject.extractId()
          } else if (
            extractedResourceUniquePropertyExpressionsMap.containsKey(entryResource.resourceType) &&
              previouslyExtractedResources.containsKey(
                entryResource.resourceType,
              )
          ) {
            val fhirPathExpression =
              extractedResourceUniquePropertyExpressionsMap
                .getValue(entryResource.resourceType)
                .fhirPathExpression

            val currentResourceIdentifier =
              withContext(dispatcherProvider.default()) {
                fhirPathDataExtractor.extractValue(
                  base = entryResource,
                  expression = fhirPathExpression,
                )
              }

            // Search for resource with property value matching extracted value
            val resource =
              previouslyExtractedResources.getValue(entryResource.resourceType).find {
                val extractedValue =
                  withContext(dispatcherProvider.default()) {
                    fhirPathDataExtractor.extractValue(
                      base = it,
                      expression = fhirPathExpression,
                    )
                  }
                extractedValue.isNotEmpty() &&
                  extractedValue.equals(currentResourceIdentifier, true)
              }

            // Found match use the id on current resource; override identifiers for RelatedPerson
            if (resource != null) {
              entryResource.id = resource.logicalId
              if (entryResource is RelatedPerson && resource is RelatedPerson) {
                entryResource.identifier = resource.identifier
              }
            }
          }
        }

        // Set Encounter on QR if the ResourceType is Encounter
        if (entryResource.resourceType == ResourceType.Encounter) {
          questionnaireResponse.setEncounter(entryResource.asReference())
        }

        // Set the Group's Related Entity Location metadata tag on Resource before saving.
        entryResource.applyRelatedEntityLocationMetaTag(questionnaireConfig, context, subjectType)

        defaultRepository.addOrUpdate(true, resource = entryResource)

        updateGroupManagingEntity(
          resource = entryResource,
          groupIdentifier = questionnaireConfig.groupResource?.groupIdentifier,
          managingEntityRelationshipCode = questionnaireConfig.managingEntityRelationshipCode,
        )
        addMemberToGroup(
          resource = entryResource,
          memberResourceType = questionnaireConfig.groupResource?.memberResourceType,
          groupIdentifier = questionnaireConfig.groupResource?.groupIdentifier,
        )

        // Track ids for resources in ListResource added to the QuestionnaireResponse.contained
        val listEntryComponent =
          ListEntryComponent().apply {
            deleted = false
            date = extractionDate
            item = entryResource.asReference()
          }
        listResource.addEntry(listEntryComponent)
      }

    // Reference extracted resources in QR then save it if subject exists
    questionnaireResponse.apply { addContained(listResource) }

    if (
      !questionnaireResponse.subject.reference.isNullOrEmpty() &&
        questionnaireConfig.saveQuestionnaireResponse
    ) {
      // Set the Group's Related Entity Location meta tag on QuestionnaireResponse then save.
      questionnaireResponse.applyRelatedEntityLocationMetaTag(
        questionnaireConfig = questionnaireConfig,
        context = context,
        subjectType = subjectType,
      )
      defaultRepository.addOrUpdate(resource = questionnaireResponse)
    }
  }

  private suspend fun Resource.applyRelatedEntityLocationMetaTag(
    questionnaireConfig: QuestionnaireConfig,
    context: Context,
    subjectType: ResourceType?,
  ) {
    val resourceIdPair =
      when {
        !questionnaireConfig.resourceIdentifier.isNullOrEmpty() && subjectType != null -> {
          Pair(subjectType, questionnaireConfig.resourceIdentifier!!)
        }
        !questionnaireConfig.groupResource?.groupIdentifier.isNullOrEmpty() &&
          questionnaireConfig.groupResource?.removeGroup != true &&
          questionnaireConfig.groupResource?.removeMember != true -> {
          Pair(ResourceType.Group, questionnaireConfig.groupResource!!.groupIdentifier)
        }
        else -> null
      }
    if (resourceIdPair != null) {
      val (resourceType, resourceId) = resourceIdPair
      val resource = loadResource(resourceType = resourceType, resourceIdentifier = resourceId)
      var relatedEntityLocationTags =
        resource?.meta?.tag?.filter { coding ->
          coding.system ==
            context.getString(
              org.smartregister.fhircore.engine.R.string
                .sync_strategy_related_entity_location_system,
            )
        }

      if (relatedEntityLocationTags.isNullOrEmpty()) {
        relatedEntityLocationTags =
          retrieveRelatedEntityTagsLinkedToSubject(context, resourceIdPair)
      }

      relatedEntityLocationTags?.forEach {
        val existingTag = this.meta.getTag(it.system, it.code)
        if (existingTag == null) {
          this.meta.addTag(it)
        }
      }
    }
  }

  private suspend fun retrieveRelatedEntityTagsLinkedToSubject(
    context: Context,
    resourceIdPair: Pair<ResourceType, String>,
  ): List<Coding>? {
    val system =
      context.getString(
        org.smartregister.fhircore.engine.R.string.sync_strategy_related_entity_location_system,
      )
    val display =
      context.getString(
        org.smartregister.fhircore.engine.R.string.sync_strategy_related_entity_location_display,
      )
    val (resourceType, resourceId) = resourceIdPair

    if (resourceType == ResourceType.Location) {
      return listOf(Coding(system, resourceId, display))
    }

    applicationConfiguration.codingSystems
      .find { it.usage == CodingSystemUsage.LOCATION_LINKAGE }
      ?.coding
      ?.let { linkageResourceCode ->
        val search =
          Search(ResourceType.List).apply {
            filter(
              ListResource.CODE,
              {
                value =
                  of(
                    Coding(
                      linkageResourceCode.system,
                      linkageResourceCode.code,
                      linkageResourceCode.display,
                    ),
                  )
              },
            )
            filter(ListResource.ITEM, { value = "$resourceType/$resourceId" })
          }

        return defaultRepository.search<ListResource>(search).map { listResource ->
          Coding(system, listResource.subject.extractId(), display)
        }
      }

    return null
  }

  private suspend fun retrievePreviouslyExtractedResources(
    questionnaireConfig: QuestionnaireConfig,
    subjectType: ResourceType?,
    questionnaire: Questionnaire,
  ): MutableMap<ResourceType, MutableList<Resource>> {
    val referencedResources = mutableMapOf<ResourceType, MutableList<Resource>>()
    if (
      questionnaireConfig.isEditable() &&
        !questionnaireConfig.resourceIdentifier.isNullOrEmpty() &&
        subjectType != null
    ) {
      defaultRepository
        .searchQuestionnaireResponse(
          resourceId = questionnaireConfig.resourceIdentifier!!,
          resourceType = questionnaireConfig.resourceType ?: subjectType,
          questionnaireId = questionnaire.logicalId,
          encounterId = questionnaireConfig.encounterId,
          questionnaireResponseStatus = questionnaireConfig.questionnaireResponseStatus(),
        )
        ?.contained
        ?.asSequence()
        ?.filterIsInstance<ListResource>()
        ?.filter { it.title.equals(CONTAINED_LIST_TITLE, true) }
        ?.flatMap { it.entry }
        ?.forEach {
          val idType = IdType(it.item.reference)
          val resource = loadResource(ResourceType.fromCode(idType.resourceType), idType.idPart)
          if (resource != null) {
            referencedResources.getOrPut(resource.resourceType) { mutableListOf() }.add(resource)
          }
        }
    }
    return referencedResources
  }

  private fun Bundle.copyBundle(currentQuestionnaireResponse: QuestionnaireResponse): Bundle =
    this.copy().apply {
      addEntry(
        Bundle.BundleEntryComponent().apply { resource = currentQuestionnaireResponse },
      )
    }

  private fun QuestionnaireResponse.processMetadata(
    questionnaire: Questionnaire,
    questionnaireConfig: QuestionnaireConfig,
    context: Context,
  ) {
    status = QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
    authored = Date()

    questionnaire.useContext
      .filter { it.hasValueCodeableConcept() }
      .forEach { it.valueCodeableConcept.coding.forEach { coding -> this.meta.addTag(coding) } }
    applyResourceMetadata(questionnaireConfig, this, context)
    setQuestionnaire("${questionnaire.resourceType}/${questionnaire.logicalId}")

    // Set subject if exists
    val resourceType = questionnaireSubjectType(questionnaire, questionnaireConfig)
    val resourceIdentifier = questionnaireConfig.resourceIdentifier
    if (resourceType != null && !resourceIdentifier.isNullOrEmpty()) {
      subject = resourceIdentifier.asReference(resourceType)
    }
  }

  private fun questionnaireSubjectType(
    questionnaire: Questionnaire,
    questionnaireConfig: QuestionnaireConfig,
  ): ResourceType? {
    val questionnaireSubjectType = questionnaire.subjectType.firstOrNull()?.code
    return questionnaireConfig.resourceType
      ?: questionnaireSubjectType?.let {
        ResourceType.valueOf(
          it,
        )
      }
  }

  private fun Resource?.applyResourceMetadata(
    questionnaireConfig: QuestionnaireConfig,
    questionnaireResponse: QuestionnaireResponse,
    context: Context,
  ) =
    this?.apply {
      appendOrganizationInfo(authenticatedOrganizationIds)
      appendPractitionerInfo(practitionerId)
      appendRelatedEntityLocation(questionnaireResponse, questionnaireConfig, context)
      updateLastUpdated()
      generateMissingId()
    }

  /**
   * Perform StructureMap or Definition based definition. The result of this function returns a
   * Bundle that contains the resources that were generated via the [ResourceMapper.extract]
   * operation otherwise returns null if an exception is encountered.
   */
  suspend fun performExtraction(
    extractByStructureMap: Boolean,
    questionnaire: Questionnaire,
    questionnaireResponse: QuestionnaireResponse,
    context: Context,
  ): Bundle =
    runCatching {
        if (extractByStructureMap) {
          ResourceMapper.extract(
            questionnaire = questionnaire,
            questionnaireResponse = questionnaireResponse,
            structureMapExtractionContext =
              StructureMapExtractionContext(
                transformSupportServices = transformSupportServices,
                structureMapProvider = { structureMapUrl: String?, _: IWorkerContext ->
                  structureMapUrl?.substringAfterLast("/")?.let { structureMapId ->
                    defaultRepository.loadResourceFromCache<StructureMap>(structureMapId)
                  }
                },
              ),
          )
        } else {
          ResourceMapper.extract(
            questionnaire = questionnaire,
            questionnaireResponse = questionnaireResponse,
          )
        }
      }
      .onFailure { exception ->
        Timber.e(exception)
        viewModelScope.launch(dispatcherProvider.main()) {
          if (exception is NullPointerException && exception.message!!.contains("StructureMap")) {
            context.showToast(
              context.getString(R.string.structure_map_missing_message),
              Toast.LENGTH_LONG,
            )
          } else {
            context.showToast(
              context.getString(R.string.structuremap_failed, questionnaire.name),
              Toast.LENGTH_LONG,
            )
          }
        }
      }
      .getOrDefault(Bundle())

  /**
   * This function saves [QuestionnaireResponse] as draft if any of the [QuestionnaireResponse.item]
   * has an answer.
   */
  suspend fun saveDraftQuestionnaire(
    questionnaireResponse: QuestionnaireResponse,
    questionnaireConfig: QuestionnaireConfig,
  ) {
    val hasAnswer = questionnaireResponse.allItems.any { it.hasAnswer() }
    questionnaireResponse.questionnaire =
      questionnaireConfig.id.asReference(ResourceType.Questionnaire).reference
    if (
      !questionnaireConfig.resourceIdentifier.isNullOrBlank() &&
        questionnaireConfig.resourceType != null
    ) {
      questionnaireResponse.subject =
        questionnaireConfig.resourceIdentifier!!.asReference(
          questionnaireConfig.resourceType!!,
        )
    }
    if (hasAnswer) {
      questionnaireResponse.status = QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS
      defaultRepository.addOrUpdate(
        addMandatoryTags = true,
        resource = questionnaireResponse,
      )
    }
  }

  /**
   * This function updates the _lastUpdated property of resources configured by the
   * [ActionParameter.paramType] of [ActionParameterType.UPDATE_DATE_ON_EDIT]. Each time a
   * questionnaire is submitted, the affected resources last modified/updated date will also be
   * updated.
   */
  suspend fun updateResourcesLastUpdatedProperty(actionParameters: List<ActionParameter>?) {
    val updateOnEditParams =
      actionParameters?.filter {
        it.paramType == ActionParameterType.UPDATE_DATE_ON_EDIT && it.value.isNotEmpty()
      }

    updateOnEditParams?.forEach { param ->
      try {
        defaultRepository.run {
          val resourceType = param.resourceType
          if (resourceType != null) {
            loadResource(resourceType, param.value)?.let { addOrUpdate(resource = it) }
          } else {
            val valueResourceType = param.value.substringBefore("/")
            val valueResourceId = param.value.substringAfter("/")
            addOrUpdate(
              resource =
                loadResource(
                  valueResourceId,
                  ResourceType.valueOf(valueResourceType),
                ),
            )
          }
        }
      } catch (resourceNotFoundException: ResourceNotFoundException) {
        Timber.e(
          resourceNotFoundException,
          "Unable to update ${param.value} resource's _lastUpdated",
        )
      } catch (_: IllegalArgumentException) {
        Timber.e(
          "No enum constant org.hl7.fhir.r4.model.ResourceType.${
                        param.value.substringBefore(
                            "/",
                        )
                    }",
        )
      }
    }
  }

  suspend fun validateQuestionnaireResponse(
    questionnaire: Questionnaire,
    questionnaireResponse: QuestionnaireResponse,
    context: Context,
  ) =
    withContext(dispatcherProvider.default()) {
      QuestionnaireResponseUtils.validateQuestionnaireResponse(
        questionnaire = questionnaire,
        questionnaireResponse = questionnaireResponse,
        context = context,
      )
    }

  suspend fun executeCql(
    subject: Resource,
    bundle: Bundle,
    questionnaire: Questionnaire,
    questionnaireConfig: QuestionnaireConfig? = null,
  ) {
    questionnaireConfig?.cqlInputResources?.forEach { resourceId ->
      val basicResource = defaultRepository.loadResource(resourceId) as Basic?
      bundle.addEntry(Bundle.BundleEntryComponent().setResource(basicResource))
    }

    val libraryFilters =
      questionnaire.cqfLibraryUrls().map {
        val apply: TokenParamFilterCriterion.() -> Unit = {
          value = of(it.extractLogicalIdUuidFromURI(ResourceType.Library))
        }
        apply
      }

    if (libraryFilters.isNotEmpty()) {
      defaultRepository.fhirEngine
        .batchedSearch<Library> {
          filter(
            Resource.RES_ID,
            *libraryFilters.toTypedArray(),
          )
        }
        .forEach { librarySearchResult ->
          val result: Parameters =
            fhirOperator.evaluateLibrary(
              librarySearchResult.resource.url,
              subject.asReference().reference,
              null,
              bundle,
              null,
            ) as Parameters

          val resources =
            result.parameter.mapNotNull { cqlResultParameterComponent ->
              (cqlResultParameterComponent.value ?: cqlResultParameterComponent.resource)?.let {
                resultParameterResource ->
                if (BuildConfig.DEBUG) {
                  Timber.d(
                    "CQL :: Param found: ${cqlResultParameterComponent.name} with value: ${
                                            getStringRepresentation(
                                                resultParameterResource,
                                            )
                                        }",
                  )
                }

                if (
                  cqlResultParameterComponent.name.equals(OUTPUT_PARAMETER_KEY) &&
                    resultParameterResource.isResource
                ) {
                  defaultRepository.create(
                    true,
                    resultParameterResource as Resource,
                  )
                  resultParameterResource
                } else {
                  null
                }
              }
            }

          validateWithFhirValidator(*resources.toTypedArray())
        }
    }
  }

  private fun getStringRepresentation(base: Base): String =
    if (base.isResource) {
      FhirContext.forR4Cached().newJsonParser().encodeResourceToString(base as Resource)
    } else {
      base.toString()
    }

  /**
   * Evaluates CQL-based `initialExpression` extensions (`text/cql-identifier` or `text/cql`) when
   * the Questionnaire declares one or more `cqf-library` extensions.
   *
   * Results are written to [Questionnaire.QuestionnaireItemComponent.initial] and the CQL
   * `initialExpression` is removed so [ResourceMapper.populate] can seed the
   * [QuestionnaireResponse] from those initials.
   */
  private suspend fun evaluateCqlInitialExpressions(
    questionnaire: Questionnaire,
    launchContextResources: List<Resource>,
  ) {
    val cqlLibraryUrls = questionnaire.cqfLibraryUrls()
    if (cqlLibraryUrls.isEmpty()) return

    val subject =
      launchContextResources.firstOrNull { res ->
        questionnaire.subjectType.firstOrNull()?.code.equals(res.resourceType.name, ignoreCase = true)
      } ?: launchContextResources.firstOrNull()

    if (subject == null) return

    val expressionSet = mutableSetOf<String>()
    collectCqlInitialExpressionItems(questionnaire.item, expressionSet)
    if (expressionSet.isEmpty()) return

    val dataBundle =
      Bundle().apply {
        launchContextResources.forEach { addEntry(Bundle.BundleEntryComponent().setResource(it)) }
      }

    val inputParameters = Parameters()
    launchContextResources.firstOrNull { it.resourceType == ResourceType.Encounter }?.let { enc ->
      inputParameters.addParameter(
        Parameters.ParametersParameterComponent().apply {
          name = "encounterid"
          value = StringType(enc.logicalId)
        },
      )
    }
    (subject as? Patient)?.let { p ->
      inputParameters.addParameter(
        Parameters.ParametersParameterComponent().apply {
          name = "patient"
          value = StringType(p.logicalId)
        },
      )
    }

    cqlLibraryUrls.distinct().forEach { libraryUrl ->
      runCatching {
          val resultParameters =
            fhirOperator.evaluateLibrary(
              libraryUrl,
              subject.asReference().reference,
              if (inputParameters.hasParameter()) inputParameters else null,
              dataBundle,
              expressionSet,
            ) as? Parameters
              ?: return@forEach

          applyCqlExpressionResultsToInitial(questionnaire.item, resultParameters, expressionSet)
        }
        .onFailure { e ->
          Timber.e(e, "Failed to evaluate CQL initialExpression using library $libraryUrl")
        }
    }
  }

  private fun collectCqlInitialExpressionItems(
    items: List<Questionnaire.QuestionnaireItemComponent>,
    expressionSet: MutableSet<String>,
  ) {
    items.forEach { item ->
      item.initialExpression?.let { expr ->
        if (
          !expr.expression.isNullOrBlank() &&
            expr.language in CQL_INITIAL_EXPRESSION_LANGUAGES
        ) {
          expressionSet.add(expr.expression)
        }
      }
      if (item.item.isNotEmpty()) {
        collectCqlInitialExpressionItems(item.item, expressionSet)
      }
    }
  }

  private fun applyCqlExpressionResultsToInitial(
    items: List<Questionnaire.QuestionnaireItemComponent>,
    resultParameters: Parameters,
    expressionSet: Set<String>,
  ) {
    items.forEach { item ->
      item.initialExpression?.let { expr ->
        val exprName = expr.expression
        if (
          !exprName.isNullOrBlank() &&
            expressionSet.contains(exprName) &&
            expr.language in CQL_INITIAL_EXPRESSION_LANGUAGES
        ) {
          val param = resultParameters.getParameter(exprName)
          if (param != null) {
            val cqlResultValue = (param.value ?: param.resource) as? org.hl7.fhir.r4.model.Type
            if (cqlResultValue != null) {
              // Avoid ResourceMapper rejecting items that have both initial and initialExpression.
              item.removeExtension(org.smartregister.fhircore.engine.util.extension.EXTENSION_INITIAL_EXPRESSION_URL)
              item.initial =
                mutableListOf(
                  Questionnaire.QuestionnaireItemInitialComponent().apply {
                    value = cqlResultValue
                  },
                )
            }
          }
        }
      }
      if (item.item.isNotEmpty()) {
        applyCqlExpressionResultsToInitial(item.item, resultParameters, expressionSet)
      }
    }
  }

  /**
   * This function generates CarePlans for the [QuestionnaireResponse.subject] using the configured
   * [QuestionnaireConfig.planDefinitions]
   */
  suspend fun generateCarePlan(
    subject: Resource,
    bundle: Bundle,
    questionnaireConfig: QuestionnaireConfig,
  ) {
    questionnaireConfig.planDefinitions?.forEach { planId ->
      if (planId.isNotEmpty()) {
        runCatching {
            val carePlan =
              fhirCarePlanGenerator.generateOrUpdateCarePlan(
                planDefinitionId = planId,
                subject = subject,
                data = bundle,
                generateCarePlanWithWorkflowApi =
                  questionnaireConfig.generateCarePlanWithWorkflowApi,
              )
            carePlan?.let { validateWithFhirValidator(it) }
          }
          .onFailure { Timber.e(it) }
      }
    }
  }

  /** Update the [Group.managingEntity] */
  private suspend fun updateGroupManagingEntity(
    resource: Resource,
    groupIdentifier: String?,
    managingEntityRelationshipCode: String?,
  ) {
    // Load the group from the database to get the updated Resource always.
    val group =
      groupIdentifier?.extractLogicalIdUuid()?.let { loadResource(ResourceType.Group, it) }
        as Group?

    if (
      group != null &&
        resource is RelatedPerson &&
        !resource.relationshipFirstRep.codingFirstRep.code.isNullOrEmpty() &&
        resource.relationshipFirstRep.codingFirstRep.code == managingEntityRelationshipCode
    ) {
      defaultRepository.addOrUpdate(
        resource = group.apply { managingEntity = resource.asReference() },
      )
    }
  }

  /**
   * Adds [Resource] to [Group.member] if the member does not exist and if [Resource.logicalId] is
   * NOT the same as the retrieved [GroupResourceConfig.groupIdentifier] (Cannot add a [Group] as
   * member of itself.
   */
  suspend fun addMemberToGroup(
    resource: Resource,
    memberResourceType: ResourceType?,
    groupIdentifier: String?,
  ) {
    // Load the Group resource from the database to get the updated one
    val group =
      groupIdentifier?.extractLogicalIdUuid()?.let { loadResource(ResourceType.Group, it) }
        as Group? ?: return

    val reference = resource.asReference()
    val member = group.member.find { it.entity.reference.equals(reference.reference, true) }

    // Cannot add Group as member of itself; Cannot not duplicate existing members
    if (resource.logicalId == group.logicalId || member != null) return

    if (
      resource.resourceType.isIn(
        ResourceType.CareTeam,
        ResourceType.Device,
        ResourceType.Group,
        ResourceType.HealthcareService,
        ResourceType.Location,
        ResourceType.Organization,
        ResourceType.Patient,
        ResourceType.Practitioner,
        ResourceType.PractitionerRole,
        ResourceType.Specimen,
      ) && resource.resourceType == memberResourceType
    ) {
      group.addMember(Group.GroupMemberComponent().apply { entity = reference })
      defaultRepository.addOrUpdate(resource = group)
    }
  }

  /**
   * This function triggers removal of [Resource] s as per the [QuestionnaireConfig.groupResource]
   * or [QuestionnaireConfig.removeResource] config properties.
   */
  suspend fun softDeleteResources(questionnaireConfig: QuestionnaireConfig) {
    if (questionnaireConfig.groupResource != null) {
      removeGroup(
        groupId = questionnaireConfig.groupResource!!.groupIdentifier,
        removeGroup = questionnaireConfig.groupResource?.removeGroup ?: false,
        deactivateMembers = questionnaireConfig.groupResource!!.deactivateMembers,
      )
      removeGroupMember(
        memberId = questionnaireConfig.resourceIdentifier,
        removeMember = questionnaireConfig.groupResource?.removeMember ?: false,
        groupIdentifier = questionnaireConfig.groupResource!!.groupIdentifier,
        memberResourceType = questionnaireConfig.groupResource!!.memberResourceType,
      )
    }

    if (
      questionnaireConfig.removeResource == true &&
        questionnaireConfig.resourceType != null &&
        !questionnaireConfig.resourceIdentifier.isNullOrEmpty()
    ) {
      viewModelScope.launch {
        defaultRepository.delete(
          resourceType = questionnaireConfig.resourceType!!,
          resourceId = questionnaireConfig.resourceIdentifier!!,
          softDelete = true,
        )
      }
    }
  }

  private suspend fun removeGroup(
    groupId: String,
    removeGroup: Boolean,
    deactivateMembers: Boolean,
  ) {
    if (removeGroup) {
      try {
        defaultRepository.removeGroup(
          groupId = groupId,
          isDeactivateMembers = deactivateMembers,
          configComputedRuleValues = emptyMap(),
        )
      } catch (exception: Exception) {
        Timber.e(exception)
      }
    }
  }

  private suspend fun removeGroupMember(
    memberId: String?,
    groupIdentifier: String?,
    memberResourceType: ResourceType?,
    removeMember: Boolean,
  ) {
    if (removeMember && !memberId.isNullOrEmpty()) {
      try {
        defaultRepository.removeGroupMember(
          memberId = memberId,
          groupId = groupIdentifier,
          groupMemberResourceType = memberResourceType,
          configComputedRuleValues = emptyMap(),
        )
      } catch (exception: Exception) {
        Timber.e(exception)
      }
    }
  }

  private suspend fun launchContextResources(
    subjectResourceType: ResourceType?,
    subjectResourceIdentifier: String?,
    actionParameters: List<ActionParameter>,
  ): List<Resource> {
    return when {
      subjectResourceType != null && subjectResourceIdentifier != null ->
        mutableListOf<Resource>().apply {
          loadResource(subjectResourceType, subjectResourceIdentifier)?.let { add(it) }
          val actionParametersExcludingSubject =
            actionParameters.filterNot {
              it.paramType == ActionParameterType.QUESTIONNAIRE_RESPONSE_POPULATION_RESOURCE &&
                subjectResourceType == it.resourceType &&
                subjectResourceIdentifier.equals(it.value, ignoreCase = true)
            }
          addAll(retrievePopulationResources(actionParametersExcludingSubject))
        }
      else -> retrievePopulationResources(actionParameters)
    }
  }

  suspend fun populateQuestionnaire(
    questionnaire: Questionnaire,
    questionnaireConfig: QuestionnaireConfig,
    actionParameters: List<ActionParameter>,
  ): Pair<QuestionnaireResponse?, List<Resource>> =
    withContext(dispatcherProvider.default()) {
      val questionnaireSubjectType = questionnaire.subjectType.firstOrNull()?.code
      val resourceType =
        questionnaireConfig.resourceType
          ?: questionnaireSubjectType?.let { ResourceType.valueOf(it) }
      val resourceIdentifier = questionnaireConfig.resourceIdentifier

      val launchContextResources =
        launchContextResources(resourceType, resourceIdentifier, actionParameters)
      val launchContexts = launchContextResources.associateBy { it.resourceType.name.lowercase() }

      questionnaire.prepopulateWithComputedConfigValues(
        questionnaireConfig,
        actionParameters,
        {
          val rules = rulesExecutor.rulesFactory.generateRules(it)
          rulesExecutor.computeResourceDataRules(rules, null, emptyMap())
        },
        { uniqueIdAssignmentConfig, computedValues ->
          // Extract ID from a Group, should be modified in future to support other resources
          uniqueIdResource =
            defaultRepository.retrieveUniqueIdAssignmentResource(
              uniqueIdAssignmentConfig,
              computedValues,
            )

          withContext(dispatcherProvider.default()) {
            fhirPathDataExtractor.extractValue(
              base = uniqueIdResource,
              expression = uniqueIdAssignmentConfig.idFhirPathExpression,
            )
          }
        },
      )

      // Apply CQL initialExpression defaults before ResourceMapper.populate so they become QR answers.
      // Skip when reopening a saved/editable/draft response (saved answers take precedence).
      val willLoadSavedResponse =
        resourceType != null &&
          !resourceIdentifier.isNullOrEmpty() &&
          (questionnaireConfig.isEditable() ||
            questionnaireConfig.isReadOnly() ||
            questionnaireConfig.isSummary() ||
            questionnaireConfig.saveDraft)
      if (!willLoadSavedResponse) {
        evaluateCqlInitialExpressions(questionnaire, launchContextResources)
      }

      // Populate questionnaire with latest QuestionnaireResponse and initial default values
      val questionnaireResponse =
        fetchRepositoryQuestionnaireResponse(
          questionnaireConfig,
          resourceIdentifier,
          resourceType,
          questionnaire,
          defaultQuestionnaire =
            withContext(dispatcherProvider.default()) {
              ResourceMapper.populate(
                questionnaire,
                launchContexts = launchContexts,
              )
            },
        )

      questionnaireResponse.apply { item = item.removeUnAnsweredItems() }

      Pair(questionnaireResponse, launchContextResources)
    }

  private suspend fun fetchRepositoryQuestionnaireResponse(
    questionnaireConfig: QuestionnaireConfig,
    resourceIdentifier: String?,
    resourceType: ResourceType?,
    questionnaire: Questionnaire,
    defaultQuestionnaire: QuestionnaireResponse,
  ): QuestionnaireResponse {
    if (resourceType == null || resourceIdentifier.isNullOrEmpty()) return defaultQuestionnaire

    val searchQR =
      questionnaireConfig
        .takeIf { it.isEditable() || it.isReadOnly() || it.isSummary() || it.saveDraft }
        ?.let {
          defaultRepository.searchQuestionnaireResponse(
            resourceId = resourceIdentifier,
            resourceType = resourceType,
            questionnaireId = questionnaire.logicalId,
            encounterId = questionnaireConfig.encounterId,
            questionnaireResponseStatus = questionnaireConfig.questionnaireResponseStatus(),
          )
        }

    if (searchQR == null) return defaultQuestionnaire

    val exclusionLinkIdsMap: Map<String, Boolean> =
      questionnaireConfig.linkIds
        ?.asSequence()
        ?.filter { it.type == LinkIdType.PREPOPULATION_EXCLUSION }
        ?.associateBy { it.linkId }
        ?.mapValues { it.value.type == LinkIdType.PREPOPULATION_EXCLUSION } ?: emptyMap()

    return QuestionnaireResponse().apply {
      /**
       * Only set the ID value when [saveDraft] is set to true] this ensues that a new
       * [QuestionnaireResponse] is generated when the questionnaire is submitted
       */
      if (questionnaireConfig.saveDraft) {
        id = searchQR.id
      }
      status = searchQR.status

      // Exclude the configured fields from QR
      item =
        excludePreviouslyAnsweredFields(
          searchQR.item,
          defaultQuestionnaire.item,
          exclusionLinkIdsMap,
        )

      // Clearing the text prompts the SDK to re-process the content, which includes
      // HTML
      clearText()
    }
  }

  fun excludePreviouslyAnsweredFields(
    previouslyAnsweredItems: MutableList<QuestionnaireResponseItemComponent>,
    newlyPopulatedItems: List<QuestionnaireResponseItemComponent>,
    exclusionMap: Map<String, Boolean>,
  ): MutableList<QuestionnaireResponseItemComponent> {
    val prevStack =
      LinkedList<MutableList<QuestionnaireResponseItemComponent>>().apply {
        push(previouslyAnsweredItems)
      }
    val newlyStack =
      LinkedList<List<QuestionnaireResponseItemComponent>>().apply { push(newlyPopulatedItems) }

    while (prevStack.isNotEmpty()) {
      val prevAnswerItemsIterator = prevStack.pop().listIterator()
      val newItemsIterator: Iterator<QuestionnaireResponseItemComponent> =
        (if (newlyStack.isNotEmpty()) newlyStack.pop() else emptyList()).iterator()

      while (prevAnswerItemsIterator.hasNext()) {
        val item = prevAnswerItemsIterator.next()
        val newPopulatedItem = if (newItemsIterator.hasNext()) newItemsIterator.next() else null

        when {
          exclusionMap.containsKey(item.linkId) && newPopulatedItem?.linkId == item.linkId -> {
            prevAnswerItemsIterator.set(newPopulatedItem)
          }
          exclusionMap.containsKey(item.linkId) -> {
            prevAnswerItemsIterator.remove()
          }
          else -> {
            prevStack.push(item.item)
            newPopulatedItem?.let { newlyStack.push(it.item) }
          }
        }
      }
    }
    return previouslyAnsweredItems
  }

  private fun List<QuestionnaireResponseItemComponent>.removeUnAnsweredItems():
    List<QuestionnaireResponseItemComponent> {
    return this.asSequence()
      .filter { it.hasAnswer() || it.item.isNotEmpty() }
      .onEach { it.item = it.item.removeUnAnsweredItems() }
      .filter { it.hasAnswer() || it.item.isNotEmpty() }
      .toList()
  }

  /**
   * Return [Resource]s to be used in the launch context of the questionnaire. Launch context allows
   * information to be passed into questionnaire based on the context in which the questionnaire is
   * being evaluated. For example, what patient, what encounter, what user, etc. is "in context" at
   * the time the questionnaire response is being completed:
   * https://build.fhir.org/ig/HL7/sdc/StructureDefinition-sdc-questionnaire-launchContext.html
   */
  suspend fun retrievePopulationResources(actionParameters: List<ActionParameter>): List<Resource> {
    return actionParameters
      .filter {
        it.paramType == ActionParameterType.QUESTIONNAIRE_RESPONSE_POPULATION_RESOURCE &&
          it.resourceType != null &&
          it.value.isNotEmpty()
      }
      .distinctBy { "${it.resourceType?.name}${it.value}" }
      .mapNotNull { loadResource(it.resourceType!!, it.value) }
  }

  /** Load [Resource] of type [ResourceType] for the provided [resourceIdentifier] */
  suspend fun loadResource(resourceType: ResourceType, resourceIdentifier: String): Resource? =
    try {
      defaultRepository.loadResource(resourceIdentifier, resourceType)
    } catch (_: ResourceNotFoundException) {
      null
    }

  fun showSpeechToText() {
    _questionnaireFormUpdateMutableStateflow.update {
      val questionnaireResponse =
        when (it) {
          is QuestionnaireFormUpdate.ShowQuestionnaireResponse -> it.newQuestionnaireResponse
          is QuestionnaireFormUpdate.ShowSpeechToTextSubView -> it.currentQuestionnaireResponse
        }
      QuestionnaireFormUpdate.ShowSpeechToTextSubView(questionnaireResponse)
    }
  }

  fun hideSpeechToText() {
    _questionnaireFormUpdateMutableStateflow.update {
      val questionnaireResponse =
        when (it) {
          is QuestionnaireFormUpdate.ShowQuestionnaireResponse -> it.newQuestionnaireResponse
          is QuestionnaireFormUpdate.ShowSpeechToTextSubView -> it.currentQuestionnaireResponse
        }
      QuestionnaireFormUpdate.ShowQuestionnaireResponse(questionnaireResponse)
    }
  }

  fun showQuestionnaireResponse(questionnaireResponse: QuestionnaireResponse?) =
    _questionnaireFormUpdateMutableStateflow.update {
      QuestionnaireFormUpdate.ShowQuestionnaireResponse(questionnaireResponse)
    }

  fun disableQuestionnaireItem(
    questionnaireItemComponent: Questionnaire.QuestionnaireItemComponent,
  ) {
    questionnaireItemComponent.item.forEach { disableQuestionnaireItem(it) }
    questionnaireItemComponent.readOnly = true
  }

  companion object {
    const val CONTAINED_LIST_TITLE = "GeneratedResourcesList"
    const val OUTPUT_PARAMETER_KEY = "OUTPUT"
    const val DELIMITER = ","
    private val CQL_INITIAL_EXPRESSION_LANGUAGES = setOf("text/cql-identifier", "text/cql")
  }
}

sealed class QuestionnaireFormUpdate {
  data class ShowSpeechToTextSubView(val currentQuestionnaireResponse: QuestionnaireResponse?) :
    QuestionnaireFormUpdate()

  data class ShowQuestionnaireResponse(val newQuestionnaireResponse: QuestionnaireResponse?) :
    QuestionnaireFormUpdate()
}
