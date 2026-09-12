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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import ca.uhn.fhir.parser.IParser
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.SearchResult
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.datacapture.mapping.ResourceMapper
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.get
import com.google.android.fhir.knowledge.KnowledgeManager
import com.google.android.fhir.search.Search
import com.google.android.fhir.workflow.FhirOperator
import dagger.Lazy
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.model.Address
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.Basic
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CarePlan
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Consent
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Flag
import org.hl7.fhir.r4.model.Group
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.ListResource
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.MedicationRequest
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import org.junit.Assert
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.configuration.ExtractedResourceUniquePropertyExpression
import org.smartregister.fhircore.engine.configuration.GroupResourceConfig
import org.smartregister.fhircore.engine.configuration.LinkIdConfig
import org.smartregister.fhircore.engine.configuration.LinkIdType
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.UniqueIdAssignmentConfig
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.data.local.ContentCache
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.domain.model.ActionParameter
import org.smartregister.fhircore.engine.domain.model.ActionParameterType
import org.smartregister.fhircore.engine.domain.model.QuestionnaireType
import org.smartregister.fhircore.engine.domain.model.RuleConfig
import org.smartregister.fhircore.engine.rulesengine.ConfigRulesExecutor
import org.smartregister.fhircore.engine.rulesengine.RulesExecutor
import org.smartregister.fhircore.engine.task.FhirCarePlanGenerator
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.appendPractitionerInfo
import org.smartregister.fhircore.engine.util.extension.asReference
import org.smartregister.fhircore.engine.util.extension.decodeResourceFromString
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.find
import org.smartregister.fhircore.engine.util.extension.isToday
import org.smartregister.fhircore.engine.util.extension.questionnaireResponseStatus
import org.smartregister.fhircore.engine.util.extension.valueToString
import org.smartregister.fhircore.engine.util.extension.yesterday
import org.smartregister.fhircore.engine.util.fhirpath.FhirPathDataExtractor
import org.smartregister.fhircore.engine.util.validation.ResourceValidationRequestHandler
import org.smartregister.fhircore.quest.app.fakes.Faker
import org.smartregister.fhircore.quest.assertResourceEquals
import org.smartregister.fhircore.quest.robolectric.RobolectricTest
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireViewModel.Companion.CONTAINED_LIST_TITLE
import org.smartregister.fhircore.quest.util.QuestionnaireResponseUtils
import org.smartregister.model.practitioner.FhirPractitionerDetails
import org.smartregister.model.practitioner.PractitionerDetails

@HiltAndroidTest
class QuestionnaireViewModelTest : RobolectricTest() {

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @Inject lateinit var sharedPreferencesHelper: SharedPreferencesHelper

  @Inject lateinit var fhirValidatorRequestHandlerProvider: Lazy<ResourceValidationRequestHandler>

  @Inject lateinit var configService: ConfigService

  @Inject lateinit var rulesExecutor: RulesExecutor

  @Inject lateinit var fhirPathDataExtractor: FhirPathDataExtractor

  @Inject lateinit var fhirEngine: FhirEngine

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  @Inject lateinit var parser: IParser

  @Inject lateinit var knowledgeManager: KnowledgeManager

  @Inject lateinit var contentCache: ContentCache
  private lateinit var samplePatientRegisterQuestionnaire: Questionnaire
  private lateinit var questionnaireConfig: QuestionnaireConfig
  private lateinit var questionnaireViewModel: QuestionnaireViewModel
  private lateinit var defaultRepository: DefaultRepository
  private val configurationRegistry = Faker.buildTestConfigurationRegistry()
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val fhirOperator: FhirOperator = mockk()
  private val configRulesExecutor: ConfigRulesExecutor = mockk()
  private val patient =
    Faker.buildPatient().apply {
      address =
        listOf(
          Address().apply {
            city = "Mombasa"
            country = "Kenya"
          },
        )
    }
  private val fhirCarePlanGenerator: FhirCarePlanGenerator =
    mockk(relaxUnitFun = true, relaxed = true)

  @Before
  @ExperimentalCoroutinesApi
  fun setUp() {
    hiltRule.inject()
    // Write practitioner and organization to shared preferences
    sharedPreferencesHelper.write(
      SharedPreferenceKey.PRACTITIONER_ID.name,
      practitionerDetails().fhirPractitionerDetails.practitionerId.valueToString(),
    )

    sharedPreferencesHelper.write(ResourceType.Organization.name, listOf("105"))

    defaultRepository =
      spyk(
        DefaultRepository(
          fhirEngine = fhirEngine,
          dispatcherProvider = dispatcherProvider,
          sharedPreferencesHelper = sharedPreferencesHelper,
          configurationRegistry = configurationRegistry,
          configService = configService,
          configRulesExecutor = configRulesExecutor,
          fhirPathDataExtractor = fhirPathDataExtractor,
          parser = parser,
          context = context,
          contentCache = contentCache,
        ),
      )

    questionnaireConfig =
      QuestionnaireConfig(
        id = "e5155788-8831-4916-a3f5-486915ce34b211", // Same as ID in
        // sample_patient_registration.json
        title = "Patient registration",
        type = "DEFAULT",
      )

    questionnaireViewModel =
      spyk(
        QuestionnaireViewModel(
          defaultRepository = defaultRepository,
          dispatcherProvider = dispatcherProvider,
          fhirCarePlanGenerator = fhirCarePlanGenerator,
          rulesExecutor = rulesExecutor,
          transformSupportServices = mockk(),
          sharedPreferencesHelper = sharedPreferencesHelper,
          fhirValidatorRequestHandlerProvider = fhirValidatorRequestHandlerProvider,
          fhirOperator = fhirOperator,
          fhirPathDataExtractor = fhirPathDataExtractor,
          configurationRegistry = configurationRegistry,
        ),
      )

    // Sample questionnaire
    val questionnaireJson =
      context.assets.open("resources/sample_patient_registration.json").bufferedReader().use {
        it.readText()
      }

    samplePatientRegisterQuestionnaire = questionnaireJson.decodeResourceFromString()
  }

  private fun practitionerDetails(): PractitionerDetails {
    return PractitionerDetails().apply {
      fhirPractitionerDetails =
        FhirPractitionerDetails().apply {
          id = "12345"
          practitionerId = StringType("12345")
        }
    }
  }

  @Test
  fun testHandleQuestionnaireSubmissionHasValidationErrorsExtractedResourcesContainingInvalidWhenInDebug() =
    runTest {
      mockkObject(ResourceMapper)
      mockkObject(QuestionnaireResponseUtils)
      val questionnaire =
        extractionQuestionnaire().apply { extension = samplePatientRegisterQuestionnaire.extension }
      val questionnaireResponse = extractionQuestionnaireResponse()
      val actionParameters = emptyList<ActionParameter>()
      coEvery { defaultRepository.applyDbTransaction(any()) } answers
        {
          runBlocking { (firstArg() as suspend () -> Unit).invoke() }
        }
      val onSuccessfulSubmission: (List<IdType>, QuestionnaireResponse) -> Unit = spyk()
      coEvery {
        ResourceMapper.extract(
          questionnaire = questionnaire,
          questionnaireResponse = questionnaireResponse,
          structureMapExtractionContext = any(),
        )
      } returns
        Bundle().apply {
          addEntry(
            Bundle.BundleEntryComponent().apply {
              resource =
                patient.apply {
                  addLink().apply {
                    other = Reference("Group/1234")
                    type = Patient.LinkType.REFER
                  }
                }
            },
          )
        }
      coEvery { defaultRepository.addOrUpdate(any(Boolean::class), any<Resource>()) } just runs
      coEvery { defaultRepository.loadResource(any<String>(), ResourceType.Patient) } returns
        Patient()

      coEvery {
        QuestionnaireResponseUtils.validateQuestionnaireResponse(any(), any(), any())
      } returns true

      val updatedQuestionnaireConfig =
        questionnaireConfig.copy(
          linkIds =
            listOf(
              LinkIdConfig(
                resourceType = ResourceType.MedicationRequest,
                linkId = "active-med-ids",
                type = LinkIdType.REPEATED_GROUP_DELETION,
              ),
            ),
        )
      questionnaireViewModel.handleQuestionnaireSubmission(
        questionnaire = questionnaire,
        currentQuestionnaireResponse = questionnaireResponse,
        actionParameters = actionParameters,
        context = context,
        questionnaireConfig = updatedQuestionnaireConfig,
        onSuccessfulSubmission = onSuccessfulSubmission,
        onQuestionnaireResponseInvalid = {},
      )

      // Verify QuestionnaireResponse was validated
      coVerify {
        QuestionnaireResponseUtils.validateQuestionnaireResponse(
          questionnaire = questionnaire,
          questionnaireResponse = questionnaireResponse,
          context = context,
        )
      }

      // Verify perform extraction was invoked
      coVerify {
        questionnaireViewModel.performExtraction(
          extractByStructureMap = true,
          questionnaire = questionnaire,
          questionnaireResponse = questionnaireResponse,
          context = context,
        )
      }

      verify { onSuccessfulSubmission(any(), any()) }
      coVerify { questionnaireViewModel.validateWithFhirValidator(*anyVararg()) }
      coVerify {
        questionnaireViewModel.saveExtractedResources(
          bundle = any<Bundle>(),
          questionnaire = questionnaire,
          questionnaireConfig = updatedQuestionnaireConfig,
          questionnaireResponse = questionnaireResponse,
          context = context,
        )
      }
      coVerify {
        questionnaireViewModel.updateResourcesLastUpdatedProperty(
          actionParameters,
        )
      }

      coVerify {
        questionnaireViewModel.processRepeatedGroupItems(
          questionnaireResponse,
          questionnaire,
          updatedQuestionnaireConfig,
        )
      }
      coVerify { onSuccessfulSubmission(any(), questionnaireResponse) }
      unmockkObject(QuestionnaireResponseUtils)
      unmockkObject(ResourceMapper)
    }

  // TODO Write integration test for QuestionnaireActivity to compliment this unit test;
  @Test
  fun testHandleQuestionnaireSubmission() = runTest {
    mockkObject(ResourceMapper)
    mockkObject(QuestionnaireResponseUtils)
    val questionnaire =
      extractionQuestionnaire().apply {
        // Use StructureMap for extraction
        extension = samplePatientRegisterQuestionnaire.extension
      }
    val theLinkId = "someLinkId"
    val relatedEntityLocationCode = "awesome-location-uuid"
    val questionnaireResponse =
      extractionQuestionnaireResponse().apply {
        addItem(
          QuestionnaireResponse.QuestionnaireResponseItemComponent(StringType(theLinkId)).apply {
            addAnswer(
              QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                setValue(StringType(relatedEntityLocationCode))
              },
            )
          },
        )
      }
    val updatedQuestionnaireConfig =
      questionnaireConfig.copy(
        linkIds = listOf(LinkIdConfig(linkId = theLinkId, LinkIdType.LOCATION)),
      )
    val actionParameters = emptyList<ActionParameter>()
    val onSuccessfulSubmission: (List<IdType>, QuestionnaireResponse) -> Unit = spyk()

    // Throw ResourceNotFoundException existing QuestionnaireResponse
    coEvery { fhirEngine.get(ResourceType.Patient, patient.logicalId) } returns patient
    coEvery { fhirEngine.get(ResourceType.QuestionnaireResponse, any()) } throws
      ResourceNotFoundException("QuestionnaireResponse", "")
    coEvery { fhirEngine.create(resource = anyVararg()) } returns listOf(patient.logicalId)
    coEvery { fhirEngine.update(resource = anyVararg()) } just runs
    coEvery { defaultRepository.applyDbTransaction(any()) } answers
      {
        runBlocking { (firstArg() as suspend () -> Unit).invoke() }
      }

    // Mock returned bundle after extraction refer to FhirExtractionTest.kt for extraction test
    coEvery {
      ResourceMapper.extract(
        questionnaire = questionnaire,
        questionnaireResponse = questionnaireResponse,
        structureMapExtractionContext = any(),
      )
    } returns
      Bundle().apply {
        addEntry(
          Bundle.BundleEntryComponent().apply { resource = patient },
        )
      }

    coEvery {
      QuestionnaireResponseUtils.validateQuestionnaireResponse(any(), any(), any())
    } returns true

    questionnaireViewModel.handleQuestionnaireSubmission(
      questionnaire = questionnaire,
      currentQuestionnaireResponse = questionnaireResponse,
      actionParameters = actionParameters,
      context = context,
      questionnaireConfig = updatedQuestionnaireConfig,
      onSuccessfulSubmission = onSuccessfulSubmission,
      onQuestionnaireResponseInvalid = {},
    )

    // Verify QuestionnaireResponse was validated
    coVerify {
      QuestionnaireResponseUtils.validateQuestionnaireResponse(
        questionnaire,
        questionnaireResponse,
        context,
      )
    }

    // Assert that the QuestionnaireResponse metadata were set
    Assert.assertEquals(
      QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED,
      questionnaireResponse.status,
    )
    Assert.assertTrue(questionnaireResponse.authored.isToday())
    Assert.assertEquals(
      "${questionnaire.resourceType}/${questionnaire.logicalId}",
      questionnaireResponse.questionnaire,
    )
    Assert.assertNotNull(questionnaireResponse.id)
    Assert.assertNotNull(questionnaireResponse.meta.lastUpdated)

    // Verify perform extraction was invoked
    coVerify {
      questionnaireViewModel.performExtraction(
        extractByStructureMap = true,
        questionnaire = questionnaire,
        questionnaireResponse = questionnaireResponse,
        context = context,
      )
    }

    // Verify that the questionnaire response and extracted resources were saved
    val bundleSlot = slot<Bundle>()
    // https://github.com/mockk/mockk/issues/352#issuecomment-592426549
    coVerify {
      questionnaireViewModel.saveExtractedResources(
        bundle = capture(bundleSlot),
        questionnaire = questionnaire,
        questionnaireConfig = updatedQuestionnaireConfig,
        questionnaireResponse = questionnaireResponse,
        context = context,
      )
    }

    val bundle = bundleSlot.captured
    Assert.assertNotNull(bundle)
    Assert.assertTrue(bundle.entryFirstRep?.resource is Patient)
    Assert.assertEquals(patient.id, bundle.entryFirstRep?.resource?.id)
    bundle.entry.forEach {
      // Every extracted resource saved and optionally added to configured group
      val resource = it.resource
      Assert.assertNotNull(resource.id)
      Assert.assertNotNull(resource.meta.lastUpdated)

      // Assert every resource contains Related Entity Location meta tag as configured
      val relatedEntityLocationCodingSystem =
        context.getString(
          R.string.sync_strategy_related_entity_location_system,
        )

      val relatedEntityLocationMetaTag =
        resource.meta.tag.findLast { coding ->
          coding.system == relatedEntityLocationCodingSystem &&
            coding.code == relatedEntityLocationCode
        }
      Assert.assertNotNull(relatedEntityLocationMetaTag)

      coVerify { defaultRepository.addOrUpdate(addMandatoryTags = true, resource = resource) }
    }
    // QuestionnaireResponse should have, subject and contained properties set then it's saved
    Assert.assertEquals("Patient/" + patient.logicalId, questionnaireResponse.subject.reference)
    coVerify {
      defaultRepository.addOrUpdate(addMandatoryTags = true, resource = questionnaireResponse)
    }
    Assert.assertEquals(1, questionnaireResponse.contained.size)
    Assert.assertTrue(questionnaireResponse.contained.firstOrNull() is ListResource)
    val listResource = questionnaireResponse.contained.firstOrNull() as ListResource
    Assert.assertEquals(ListResource.ListStatus.CURRENT, listResource.status)
    Assert.assertEquals(ListResource.ListMode.WORKING, listResource.mode)
    Assert.assertEquals(CONTAINED_LIST_TITLE, listResource.title)
    Assert.assertTrue(listResource.date.isToday())

    val subjectSlot = slot<Resource>()
    val idsTypesSlot = slot<List<IdType>>()

    // Mock result for subject (Patient) that is reloaded via loadResource function
    coEvery {
      questionnaireViewModel.loadResource(
        ResourceType.Patient,
        patient.logicalId,
      )
    } returns patient

    // Verify other function calls in order of execution after saving resources
    coVerifyOrder {
      questionnaireViewModel.updateResourcesLastUpdatedProperty(actionParameters)

      questionnaireViewModel.generateCarePlan(
        subject = capture(subjectSlot),
        bundle = capture(bundleSlot),
        questionnaireConfig = updatedQuestionnaireConfig,
      )

      questionnaireViewModel.executeCql(
        subject = capture(subjectSlot),
        bundle = capture(bundleSlot),
        questionnaire = questionnaire,
        questionnaireConfig = updatedQuestionnaireConfig,
      )

      fhirCarePlanGenerator.conditionallyUpdateResourceStatus(
        questionnaireConfig = updatedQuestionnaireConfig,
        subject = capture(subjectSlot),
        bundle = capture(bundleSlot),
      )

      questionnaireViewModel.softDeleteResources(updatedQuestionnaireConfig)

      onSuccessfulSubmission(capture(idsTypesSlot), questionnaireResponse)
    }

    // Captured bundle slot should contain QuestionnaireResponse
    Assert.assertTrue(
      bundleSlot.captured.entry.any {
        it.resource.resourceType == ResourceType.QuestionnaireResponse
      },
    )

    // ID of extracted resources passed to the onSuccessfulSubmission callback
    Assert.assertEquals(idsTypesSlot.captured.firstOrNull()?.idPart, patient.logicalId)

    unmockkObject(QuestionnaireResponseUtils)
    unmockkObject(ResourceMapper)
  }

  @Test
  fun testPerformExtractionWithStructureMap() = runTest {
    mockkObject(ResourceMapper)
    val questionnaire = extractionQuestionnaire()
    val theQuestionnaireResponse = extractionQuestionnaireResponse()

    coEvery {
      ResourceMapper.extract(
        questionnaire = questionnaire,
        questionnaireResponse = theQuestionnaireResponse,
        structureMapExtractionContext = any(),
      )
    } returns
      Bundle().apply {
        addEntry(
          Bundle.BundleEntryComponent().apply { resource = patient },
        )
      }
    val bundle =
      questionnaireViewModel.performExtraction(
        extractByStructureMap = true,
        questionnaire = questionnaire,
        questionnaireResponse = theQuestionnaireResponse,
        context = context,
      )
    Assert.assertNotNull(bundle)
    Assert.assertTrue(bundle.entryFirstRep?.resource is Patient)
    Assert.assertEquals(patient.id, bundle.entryFirstRep?.resource?.id)
    unmockkObject(ResourceMapper)
  }

  @Test
  fun testPerformExtractionWithDefinitionBasedExtraction() = runTest {
    mockkObject(ResourceMapper)
    val questionnaire = extractionQuestionnaire()
    val theQuestionnaireResponse = extractionQuestionnaireResponse()

    coEvery { ResourceMapper.extract(questionnaire, theQuestionnaireResponse) } returns
      Bundle().apply {
        addEntry(
          Bundle.BundleEntryComponent().apply { resource = patient },
        )
      }
    val bundle =
      questionnaireViewModel.performExtraction(
        extractByStructureMap = false,
        questionnaire = questionnaire,
        questionnaireResponse = theQuestionnaireResponse,
        context = context,
      )
    Assert.assertNotNull(bundle)
    Assert.assertTrue(bundle.entryFirstRep?.resource is Patient)
    Assert.assertEquals(patient.id, bundle.entryFirstRep?.resource?.id)
    unmockkObject(ResourceMapper)
  }

  private fun extractionQuestionnaireResponse() =
    QuestionnaireResponse().apply {
      addItem(
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
          linkId = "patient-name"
          addAnswer(
            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
              .setValue(StringType("Nelson Mandela")),
          )
        },
      )
      addItem(
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
          linkId = "patient-address"
          addAnswer(
            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
              .setValue(StringType("Mombasa")),
          )
        },
      )
      addItem(
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
          linkId = "country-of-residence"
          addAnswer(
            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
              .setValue(StringType("Kenya")),
          )
        },
      )
    }

  private fun extractionQuestionnaire() =
    Questionnaire().apply {
      id = questionnaireConfig.id
      name = questionnaireConfig.title
      addSubjectType("Patient")
      addItem(
        QuestionnaireItemComponent().apply {
          linkId = "patient-name"
          type = Questionnaire.QuestionnaireItemType.STRING
        },
      )
      addItem(
        QuestionnaireItemComponent().apply {
          linkId = "patient-address"
          type = Questionnaire.QuestionnaireItemType.STRING
        },
      )
      addItem(
        QuestionnaireItemComponent().apply {
          linkId = "country-of-residence"
          type = Questionnaire.QuestionnaireItemType.STRING
        },
      )
    }

  @Test
  fun testRetrieveQuestionnaireShouldReturnValidQuestionnaire() = runTest {
    coEvery { fhirEngine.get(ResourceType.Questionnaire, questionnaireConfig.id) } returns
      samplePatientRegisterQuestionnaire

    val questionnaire =
      questionnaireViewModel.retrieveQuestionnaire(
        questionnaireConfig = questionnaireConfig,
      )

    Assert.assertNotNull(questionnaire)
    Assert.assertEquals(questionnaireConfig.id, questionnaire?.id?.extractLogicalIdUuid())
  }

  @Test
  fun testRetrieveQuestionnaireShouldReturnValidQuestionnaireFromCache() = runTest {
    coEvery { fhirEngine.get(ResourceType.Questionnaire, questionnaireConfig.id) } returns
      samplePatientRegisterQuestionnaire

    coEvery { defaultRepository.loadResource<Questionnaire>(questionnaireConfig.id) } returns
      samplePatientRegisterQuestionnaire

    contentCache.saveResource(samplePatientRegisterQuestionnaire)
    val questionnaire =
      questionnaireViewModel.retrieveQuestionnaire(
        questionnaireConfig = questionnaireConfig,
      )

    Assert.assertEquals(
      samplePatientRegisterQuestionnaire.idPart,
      contentCache.getResource(ResourceType.Questionnaire, questionnaireConfig.id)?.idPart,
    )
    Assert.assertNotNull(questionnaire)
    Assert.assertEquals(questionnaireConfig.id, questionnaire?.id?.extractLogicalIdUuid())
  }

  @Test
  fun testRetrieveQuestionnaireShouldReturnValidQuestionnaireFromDatabase() = runTest {
    coEvery { fhirEngine.get(ResourceType.Questionnaire, questionnaireConfig.id) } returns
      samplePatientRegisterQuestionnaire

    coEvery { defaultRepository.loadResource<Questionnaire>(questionnaireConfig.id) } returns
      samplePatientRegisterQuestionnaire

    val questionnaire =
      questionnaireViewModel.retrieveQuestionnaire(
        questionnaireConfig = questionnaireConfig,
      )

    Assert.assertNotNull(questionnaire)
    Assert.assertEquals(questionnaireConfig.id, questionnaire?.id?.extractLogicalIdUuid())

    coVerify(exactly = 1) { defaultRepository.loadResource<Questionnaire>(questionnaireConfig.id) }
  }

  @Test
  fun testPopulateQuestionnaireShouldPrePopulatedQuestionnaireWithComputedValues() = runTest {
    val questionnaireViewModelInstance =
      QuestionnaireViewModel(
        defaultRepository = defaultRepository,
        dispatcherProvider = dispatcherProvider,
        fhirCarePlanGenerator = fhirCarePlanGenerator,
        rulesExecutor = rulesExecutor,
        transformSupportServices = mockk(),
        sharedPreferencesHelper = sharedPreferencesHelper,
        fhirOperator = fhirOperator,
        fhirValidatorRequestHandlerProvider = fhirValidatorRequestHandlerProvider,
        fhirPathDataExtractor = fhirPathDataExtractor,
        configurationRegistry = configurationRegistry,
      )
    val patientAgeLinkId = "patient-age"
    val newQuestionnaireId = "new-${questionnaireConfig.id}"
    val newQuestionnaireConfig =
      questionnaireConfig.copy(
        id = newQuestionnaireId,
        resourceIdentifier = patient.id,
        resourceType = patient.resourceType,
        barcodeLinkId = "patient-barcode",
        configRules =
          listOf(
            RuleConfig(
              name = "rule1",
              actions = listOf("data.put('rule1', 'Sample Rule')"),
            ),
          ),
        extraParams =
          listOf(
            ActionParameter(
              key = "rule1",
              value = "@{rule1}",
              paramType = ActionParameterType.PARAMDATA,
            ),
          ),
      )
    coEvery { fhirEngine.get(ResourceType.Questionnaire, newQuestionnaireConfig.id) } returns
      samplePatientRegisterQuestionnaire.apply {
        id = newQuestionnaireId
        addItem(
          QuestionnaireItemComponent().apply {
            linkId = patientAgeLinkId
            type = Questionnaire.QuestionnaireItemType.INTEGER
            readOnly = true
          },
        )
      }
    val actionParameter =
      listOf(
        ActionParameter(
          paramType = ActionParameterType.PREPOPULATE,
          linkId = patientAgeLinkId,
          dataType = Enumerations.DataType.INTEGER,
          key = patientAgeLinkId,
          value = "20",
        ),
      )
    val questionnaire = questionnaireViewModelInstance.retrieveQuestionnaire(newQuestionnaireConfig)
    Assert.assertNotNull(questionnaire)
    questionnaireViewModelInstance.populateQuestionnaire(
      questionnaire!!,
      newQuestionnaireConfig,
      actionParameter,
    )

    // Questionnaire.item pre-populated
    val questionnairePatientAgeItem = questionnaire.find(patientAgeLinkId)
    val itemValue: Type? = questionnairePatientAgeItem?.initial?.firstOrNull()?.value
    Assert.assertTrue(itemValue is IntegerType)
    Assert.assertEquals(20, itemValue?.primitiveValue()?.toInt())

    // Barcode linkId updated
    val questionnaireBarcodeItem =
      newQuestionnaireConfig.barcodeLinkId?.let { questionnaire.find(it) }
    val barCodeItemValue: Type? = questionnaireBarcodeItem?.initial?.firstOrNull()?.value
    Assert.assertFalse(barCodeItemValue is StringType)
    Assert.assertNull(
      newQuestionnaireConfig.resourceIdentifier,
      barCodeItemValue?.primitiveValue(),
    )
  }

  @Test
  fun testSaveDraftQuestionnaireShouldUpdateStatusToInProgress() = runTest {
    val questionnaireResponse =
      QuestionnaireResponse().apply {
        addItem(
          QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            addAnswer(
              QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                .setValue(StringType("Sky is the limit")),
            )
          },
        )
      }
    questionnaireViewModel.saveDraftQuestionnaire(
      questionnaireResponse,
      QuestionnaireConfig("qr-id-1"),
    )
    Assert.assertEquals(
      QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS,
      questionnaireResponse.status,
    )
    coVerify { defaultRepository.addOrUpdate(resource = questionnaireResponse) }
  }

  @Test
  fun testSaveDraftQuestionnaireShouldUpdateSubjectAndQuestionnaireValues() = runTest {
    val questionnaireResponse =
      QuestionnaireResponse().apply {
        addItem(
          QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            addAnswer(
              QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                .setValue(StringType("Sky is the limit")),
            )
          },
        )
      }
    questionnaireViewModel.saveDraftQuestionnaire(
      questionnaireResponse,
      QuestionnaireConfig(
        "dc-household-registration",
        resourceIdentifier = "group-id-1",
        resourceType = ResourceType.Group,
      ),
    )
    Assert.assertEquals(
      "Questionnaire/dc-household-registration",
      questionnaireResponse.questionnaire,
    )
    Assert.assertEquals(
      "Group/group-id-1",
      questionnaireResponse.subject.reference,
    )
    coVerify { defaultRepository.addOrUpdate(resource = questionnaireResponse) }
  }

  @Test
  @Ignore("Re-check this test, it takes forever to run")
  fun testSaveDraftQuestionnaireCallsAddOrUpdateForPaginatedForms() = runTest {
    val pageItem =
      QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
        addItem(
          QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            addAnswer(
              QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                .setValue(StringType("Sky is the limit")),
            )
          },
        )
      }
    val questionnaireResponse =
      QuestionnaireResponse().apply {
        addItem(
          pageItem,
        )
      }
    questionnaireViewModel.saveDraftQuestionnaire(
      questionnaireResponse,
      QuestionnaireConfig(
        "dc-household-registration",
        resourceIdentifier = "group-id-1",
        resourceType = ResourceType.Group,
      ),
    )

    coVerify { defaultRepository.addOrUpdate(resource = questionnaireResponse) }
  }

  @Test
  fun testUpdateResourcesLastUpdatedProperty() = runTest {
    val yesterday = yesterday()
    val thisPatient = Faker.buildPatient(id = "someId").apply { meta.lastUpdated = yesterday }
    val actionParameters =
      listOf(
        ActionParameter(
          paramType = ActionParameterType.UPDATE_DATE_ON_EDIT,
          resourceType = ResourceType.Patient,
          value = thisPatient.id,
          key = "patientId",
        ),
      )

    coEvery { fhirEngine.update(any()) } just runs
    coEvery { fhirEngine.create(thisPatient) } returns listOf(thisPatient.logicalId)
    coEvery { fhirEngine.get(ResourceType.Patient, thisPatient.id) } returns thisPatient

    questionnaireViewModel.updateResourcesLastUpdatedProperty(actionParameters)
    Assert.assertTrue(thisPatient.meta.lastUpdated.after(yesterday))

    val otherActionParameters =
      listOf(
        ActionParameter(
          paramType = ActionParameterType.UPDATE_DATE_ON_EDIT,
          value = "Patient/${thisPatient.id}",
          key = "patientId",
        ),
      )
    questionnaireViewModel.updateResourcesLastUpdatedProperty(otherActionParameters)
    Assert.assertTrue(thisPatient.meta.lastUpdated.after(yesterday))
  }

  @Test
  fun testValidateQuestionnaireResponse() = runTest {
    val questionnaire =
      Questionnaire().apply {
        addItem(
          QuestionnaireItemComponent().apply {
            linkId = "linkId"
            type = Questionnaire.QuestionnaireItemType.INTEGER
            required = true
          },
        )
      }

    val questionnaireResponse =
      QuestionnaireResponse().apply {
        addItem(
          QuestionnaireResponse.QuestionnaireResponseItemComponent().apply { linkId = "linkId" },
        )
      }

    // No answer provided
    Assert.assertFalse(
      QuestionnaireResponseUtils.validateQuestionnaireResponse(
        questionnaire,
        questionnaireResponse,
        context,
      ),
    )

    // With an answer provided
    Assert.assertTrue(
      QuestionnaireResponseUtils.validateQuestionnaireResponse(
        questionnaire,
        questionnaireResponse.apply {
          itemFirstRep.answer =
            listOf(
              QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                .setValue(StringType("Answer")),
            )
        },
        context,
      ),
    )
  }

  @Test
  fun testValidateQuestionnaireResponseWithRepeatedGroup() = runTest {
    val questionnaireString =
      """
      {
        "resourceType": "Questionnaire",
        "item": [
              {
                "linkId": "systolic-bp",
                "type": "integer",
                "required": true
              },
              {
                "linkId": "blood-pressure-repeating-group",
                "type": "group",
                "required": false,
                "repeats": true,
                "item": [
                  {
                    "linkId": "systolic-bp",
                    "type": "integer",
                    "required": true
                  }
                ]
              }
            ]
      }
        """
        .trimIndent()
    val questionnaireResponseString =
      """
      {
        "resourceType": "QuestionnaireResponse",
        "item": [
              {
                "linkId": "systolic-bp",
                "answer": [
                  {
                    "valueInteger": 123
                  }
                ]
              },
              {
                "linkId": "blood-pressure-repeating-group",
                "item": [
                  {
                    "linkId": "systolic-bp",
                    "answer": [
                      {
                        "valueInteger": 124
                      }
                    ]
                  }
                ]
              },
              {
                "linkId": "blood-pressure-repeating-group",
                "item": [
                  {
                    "linkId": "systolic-bp",
                    "answer": [
                      {
                        "valueInteger": 125
                      }
                    ]
                  }
                ]
              },
              {
                "linkId": "blood-pressure-repeating-group",
                "item": [
                  {
                    "linkId": "systolic-bp",
                    "answer": [
                      {
                        "valueInteger": 126
                      }
                    ]
                  }
                ]
              }
            ]
      }
        """
        .trimIndent()
    val questionnaire = parser.parseResource(questionnaireString) as Questionnaire
    val questionnaireResponse =
      parser.parseResource(questionnaireResponseString) as QuestionnaireResponse
    val result =
      QuestionnaireResponseUtils.validateQuestionnaireResponse(
        questionnaire,
        questionnaireResponse,
        context,
      )
    Assert.assertTrue(result)
  }

  @Test
  fun testValidateQuestionnaireResponseWithNestedRepeatedGroupShouldNotUpdateTheOriginalQuestionnaireResponse() =
    runTest {
      val questionnaireString =
        """
      {
        "resourceType": "Questionnaire",
        "item": [
          {
            "extension": [
              {
                "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-itemControl",
                "valueCodeableConcept": {
                  "coding": [
                    {
                      "system": "http://hl7.org/fhir/questionnaire-item-control",
                      "code": "page",
                      "display": "Page 1"
                    }
                  ],
                  "text": "Page 1"
                }
              }
            ],
            "linkId": "page-1",
            "type": "group",
            "item": [
              {
                "linkId": "systolic-bp",
                "type": "integer",
                "required": true
              },
              {
                "linkId": "blood-pressure-repeating-group",
                "type": "group",
                "required": false,
                "repeats": true,
                "item": [
                  {
                    "linkId": "systolic-bp",
                    "type": "integer",
                    "required": true
                  }
                ]
              }
            ]
          }
        ]
      }
        """
          .trimIndent()
      val questionnaireResponseString =
        """
      {
        "resourceType": "QuestionnaireResponse",
        "item": [
          {
            "linkId": "page-1",
            "item": [
              {
                "linkId": "systolic-bp",
                "answer": [
                  {
                    "valueInteger": 123
                  }
                ]
              },
              {
                "linkId": "blood-pressure-repeating-group",
                "item": [
                  {
                    "linkId": "systolic-bp",
                    "answer": [
                      {
                        "valueInteger": 124
                      }
                    ]
                  }
                ]
              },
              {
                "linkId": "blood-pressure-repeating-group",
                "item": [
                  {
                    "linkId": "systolic-bp",
                    "answer": [
                      {
                        "valueInteger": 125
                      }
                    ]
                  }
                ]
              },
              {
                "linkId": "blood-pressure-repeating-group",
                "item": [
                  {
                    "linkId": "systolic-bp",
                    "answer": [
                      {
                        "valueInteger": 126
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
        """
          .trimIndent()
      val questionnaire = parser.parseResource(questionnaireString) as Questionnaire
      val actualQuestionnaireResponse =
        parser.parseResource(questionnaireResponseString) as QuestionnaireResponse
      val result =
        QuestionnaireResponseUtils.validateQuestionnaireResponse(
          questionnaire,
          actualQuestionnaireResponse,
          context,
        )
      val expectedQuestionnaireResponse =
        parser.parseResource(questionnaireResponseString) as QuestionnaireResponse
      Assert.assertTrue(result)
      assertResourceEquals(expectedQuestionnaireResponse, actualQuestionnaireResponse)
    }

  @Test
  fun testExecuteCqlShouldInvokeRunCqlLibrary() = runTest {
    val bundle =
      Bundle().apply { addEntry(Bundle.BundleEntryComponent().apply { resource = patient }) }

    val questionnaire =
      samplePatientRegisterQuestionnaire.copy().apply {
        addExtension(
          Extension().apply {
            url = "https://sample.cqf-library.url"
            setValue(StringType("http://smartreg.org/Library/123"))
          },
        )
      }

    coEvery { fhirOperator.evaluateLibrary(any(), any(), any(), any()) } returns Parameters()

    val cqlLibrary =
      Library().apply {
        id = "123"
        url = "http://smartreg.org/Library/123"
        name = "123"
        version = "1.0.0"
        status = Enumerations.PublicationStatus.ACTIVE
        addContent(
          Attachment().apply {
            contentType = "text/cql"
            data = "someCQL".toByteArray()
          },
        )
      }

    knowledgeManager.index(
      File.createTempFile(cqlLibrary.name, ".json").apply {
        this.writeText(cqlLibrary.encodeResourceToString())
      },
    )

    defaultRepository.fhirEngine.create(cqlLibrary)

    // Load the CQL input resources from the questionnaireConfig
    val questionnaireConfigCqlInputResources =
      questionnaireConfig.copy(cqlInputResources = listOf("basic-resource-id"))

    questionnaireViewModel.executeCql(
      patient,
      bundle,
      questionnaire,
      questionnaireConfigCqlInputResources,
    )

    coVerify {
      fhirOperator.evaluateLibrary(
        "http://smartreg.org/Library/123",
        patient.asReference().reference,
        null,
        bundle,
        null,
      )
    }
  }

  @Test
  fun testGenerateCarePlan() = runTest {
    val bundle =
      Bundle().apply { addEntry(Bundle.BundleEntryComponent().apply { resource = patient }) }
    coEvery {
      fhirCarePlanGenerator.generateOrUpdateCarePlan(any<String>(), any(), any(), any())
    } returns CarePlan()

    val questionnaireConfig = questionnaireConfig.copy(planDefinitions = listOf("planDefId"))
    questionnaireViewModel.generateCarePlan(patient, bundle, questionnaireConfig)
    coVerify {
      fhirCarePlanGenerator.generateOrUpdateCarePlan(
        planDefinitionId = "planDefId",
        subject = patient,
        data = bundle,
      )
    }
  }

  @Test
  fun testAddResourceToConfiguredGroup() = runTest {
    val group =
      Group().apply {
        id = "testGroupId"
        active = true
      }

    questionnaireConfig =
      questionnaireConfig.copy(
        groupResource =
          GroupResourceConfig(
            groupIdentifier = group.logicalId,
            memberResourceType = ResourceType.Patient,
          ),
      )

    coEvery { fhirEngine.get(ResourceType.Group, group.logicalId) } returns group
    coEvery { fhirEngine.update(any()) } just runs

    // Attempting to add Group as member of itself should fail
    questionnaireViewModel.addMemberToGroup(
      resource = group,
      memberResourceType = questionnaireConfig.groupResource?.memberResourceType,
      groupIdentifier = group.logicalId,
    )
    coVerify(exactly = 0) { defaultRepository.addOrUpdate(resource = group) }

    // Should add member to existing group
    questionnaireViewModel.addMemberToGroup(
      resource = patient,
      memberResourceType = questionnaireConfig.groupResource?.memberResourceType,
      groupIdentifier = group.logicalId,
    )

    Assert.assertFalse(group.member.isNullOrEmpty())
    coVerify { defaultRepository.addOrUpdate(resource = group) }

    // Attempting to add existing member to a Group should fail
    val anotherGroup =
      Group()
        .apply {
          id = "anotherGroup"
          active = true
        }
        .apply { addMember(Group.GroupMemberComponent(patient.asReference())) }
    coEvery { fhirEngine.get(ResourceType.Group, anotherGroup.logicalId) } returns anotherGroup
    questionnaireViewModel.addMemberToGroup(
      resource = patient,
      memberResourceType = questionnaireConfig.groupResource?.memberResourceType,
      groupIdentifier = group.logicalId,
    )
    coVerify(exactly = 0) { defaultRepository.addOrUpdate(resource = anotherGroup) }

    questionnaireViewModel.addMemberToGroup(
      resource = Location().apply { id = "some-loc-id" },
      memberResourceType = questionnaireConfig.groupResource?.memberResourceType,
      groupIdentifier = group.logicalId,
    )
    coVerify(exactly = 0) { defaultRepository.addOrUpdate(resource = anotherGroup) }
  }

  @Test
  fun testSoftDeleteShouldTriggerDefaultRepositoryRemoveGroupFunction() = runTest {
    val theQuestionnaireConfig =
      QuestionnaireConfig(
        id = samplePatientRegisterQuestionnaire.id,
        groupResource =
          GroupResourceConfig(
            groupIdentifier = "sampleGroupId",
            removeGroup = true,
            memberResourceType = ResourceType.Patient,
          ),
      )

    questionnaireViewModel.softDeleteResources(theQuestionnaireConfig)

    coVerify {
      defaultRepository.removeGroup(
        groupId = theQuestionnaireConfig.groupResource?.groupIdentifier!!,
        isDeactivateMembers = true,
        configComputedRuleValues = emptyMap(),
      )
    }
  }

  @Test
  fun testSoftDeleteShouldTriggerDefaultRepositoryRemoveGroupMemberFunction() = runTest {
    val theQuestionnaireConfig =
      QuestionnaireConfig(
        id = samplePatientRegisterQuestionnaire.id,
        resourceIdentifier = patient.logicalId,
        groupResource =
          GroupResourceConfig(
            groupIdentifier = "sampleGroupId",
            removeGroup = true,
            memberResourceType = ResourceType.Patient,
            removeMember = true,
          ),
      )

    questionnaireViewModel.softDeleteResources(theQuestionnaireConfig)

    coVerify {
      defaultRepository.removeGroupMember(
        memberId = patient.logicalId,
        groupId = theQuestionnaireConfig.groupResource?.groupIdentifier!!,
        groupMemberResourceType = ResourceType.Patient,
        configComputedRuleValues = emptyMap(),
      )
    }
  }

  @Test
  @Ignore("This test is flaky investigate and fix")
  fun testSoftDeleteShouldTriggerDefaultRepositoryUpdateResourceFunction() = runTest {
    val patient = Faker.buildPatient()
    val theQuestionnaireConfig =
      QuestionnaireConfig(
        id = "the-questionnaire-id",
        resourceIdentifier = patient.id,
        resourceType = ResourceType.Patient,
        removeResource = true,
      )
    coEvery { fhirEngine.get(ResourceType.Patient, patient.id) } returns patient
    questionnaireViewModel.softDeleteResources(theQuestionnaireConfig)

    // Soft delete sets Patient.active to false
    Assert.assertFalse(patient.active)
    coVerify { defaultRepository.addOrUpdate(true, patient) }
  }

  @Test
  fun testRetrievePopulationResourcesReturnsListOfResourcesOrEmptyList() = runTest {
    val specimenId = "specimenId"
    val actionParameters =
      listOf(
        ActionParameter(
          paramType = ActionParameterType.QUESTIONNAIRE_RESPONSE_POPULATION_RESOURCE,
          resourceType = ResourceType.Patient,
          value = patient.id,
          key = "patientId",
        ),
        ActionParameter(
          paramType = ActionParameterType.QUESTIONNAIRE_RESPONSE_POPULATION_RESOURCE,
          resourceType = ResourceType.Specimen,
          value = specimenId,
          key = specimenId,
        ),
      )

    // No resources found, empty list returned
    coEvery { fhirEngine.get(ResourceType.Patient, patient.id) } throws
      (ResourceNotFoundException("Patient", patient.id))
    coEvery { fhirEngine.get(ResourceType.Specimen, specimenId) } throws
      (ResourceNotFoundException("Specimen", specimenId))
    Assert.assertTrue(
      questionnaireViewModel.retrievePopulationResources(actionParameters).isEmpty(),
    )

    // Provide only Patient resource
    coEvery { fhirEngine.get(ResourceType.Patient, patient.id) } returns patient
    coEvery { fhirEngine.get(ResourceType.Specimen, specimenId) } throws
      (ResourceNotFoundException("Specimen", specimenId))
    val resources = questionnaireViewModel.retrievePopulationResources(actionParameters)
    Assert.assertEquals(1, resources.size)
    Assert.assertEquals(patient.id, resources.firstOrNull()?.id)
  }

  @Test
  fun testLoadResourceShouldReturnResourceOrNullIfNotFound() = runTest {
    coEvery { fhirEngine.get(ResourceType.Patient, patient.id) } throws
      (ResourceNotFoundException("Patient", patient.id))
    Assert.assertNull(questionnaireViewModel.loadResource(ResourceType.Patient, patient.id))

    coEvery { fhirEngine.get(ResourceType.Patient, patient.id) } returns patient
    Assert.assertNotNull(questionnaireViewModel.loadResource(ResourceType.Patient, patient.id))
  }

  @Test
  fun testAddPractitionerInfoAppendedCorrectlyOnEncounterResource() {
    val encounter = Encounter().apply { this.id = "123456" }
    encounter.appendPractitionerInfo("12345")
    Assert.assertEquals(
      "Practitioner/12345",
      encounter.participant.first().individual.reference,
    )
  }

  @Test
  fun testAddPractitionerInfoAppendedCorrectlyOnObservationResource() {
    val observation = Observation().apply { this.id = "123456" }
    observation.appendPractitionerInfo("12345")
    Assert.assertEquals("Practitioner/12345", observation.performer.first().reference)
  }

  @Test
  fun testAddPractitionerInfoAppendedCorrectlyOnQuestionnaireResponse() {
    val questionnaireResponse = QuestionnaireResponse().apply { this.id = "123456" }
    questionnaireResponse.appendPractitionerInfo("12345")
    Assert.assertEquals("Practitioner/12345", questionnaireResponse.author.reference)
  }

  @Test
  fun testAddPractitionerInfoAppendedCorrectlyOnPatientResource() {
    val patient = Patient().apply { this.id = "123456" }
    patient.appendPractitionerInfo("12345")
    Assert.assertEquals("Practitioner/12345", patient.generalPractitioner.first().reference)
  }

  @Test
  fun testAddPractitionerInfoAppendedCorrectlyOnFlag() {
    val flag = Flag().apply { this.id = "123456" }
    flag.appendPractitionerInfo("12345")
    Assert.assertEquals("Practitioner/12345", flag.author.reference)
  }

  @Test
  fun testAddPractitionerInfoAppendedCorrectlyOnConsentResource() {
    val consent = Consent().apply { this.id = "123456" }
    consent.appendPractitionerInfo("12345")
    Assert.assertEquals("Practitioner/12345", consent.performer.first().reference)
  }

  @Test
  fun testSaveExtractedResourcesForEditedQuestionnaire() = runTest {
    val questionnaire = extractionQuestionnaire()
    val questionnaireResponse =
      extractionQuestionnaireResponse().apply { subject = patient.asReference() }

    val questionnaireConfig =
      questionnaireConfig.copy(
        resourceIdentifier = patient.logicalId,
        saveQuestionnaireResponse = false,
        type = "EDIT",
        extractedResourceUniquePropertyExpressions =
          listOf(
            ExtractedResourceUniquePropertyExpression(
              ResourceType.Observation,
              "Observation.code.where(coding.code='obs1').coding.code",
            ),
          ),
      )
    val previousObs =
      Observation().apply {
        id = "previousObs1"
        code = (CodeableConcept(Coding("http://obsys", "obs1", "Obs 1")))
      }
    val newObservation =
      Observation().apply {
        id = UUID.randomUUID().toString()
        code = (CodeableConcept(Coding("http://obsys", "obs1", "Obs 1")))
      }

    // The last extraction generated an Obs and referenced it in the QuestionnaireResponse.contained
    val previousQuestionnaireResponse =
      extractionQuestionnaireResponse().apply {
        val extractionDate = Date()
        subject = patient.asReference()
        val listResource =
          ListResource().apply {
            id = UUID.randomUUID().toString()
            status = ListResource.ListStatus.CURRENT
            mode = ListResource.ListMode.WORKING
            title = CONTAINED_LIST_TITLE
            date = extractionDate
          }
        val listEntryComponent =
          ListResource.ListEntryComponent().apply {
            deleted = false
            date = extractionDate
            item = previousObs.asReference()
          }
        listResource.addEntry(listEntryComponent)
        addContained(listResource)
        status = QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
      }

    coEvery {
      defaultRepository.searchQuestionnaireResponse(
        resourceId = patient.logicalId,
        resourceType = ResourceType.Patient,
        questionnaireId = questionnaireConfig.id,
        encounterId = null,
        questionnaireResponseStatus = questionnaireConfig.questionnaireResponseStatus(),
      )
    } returns previousQuestionnaireResponse

    val extractedBundle =
      Bundle().apply {
        addEntry(Bundle.BundleEntryComponent().apply { resource = newObservation })
        addEntry(Bundle.BundleEntryComponent().apply { resource = patient })
      }

    coEvery { fhirEngine.get(ResourceType.Patient, patient.logicalId) } returns patient
    coEvery {
      fhirEngine.get(
        ResourceType.Observation,
        previousObs.logicalId,
      )
    } returns previousObs
    coEvery { fhirEngine.get(ResourceType.Observation, newObservation.logicalId) } returns
      newObservation
    coEvery { fhirEngine.update(resource = anyVararg()) } just runs

    questionnaireViewModel.saveExtractedResources(
      bundle = extractedBundle,
      questionnaire = questionnaire,
      questionnaireConfig = questionnaireConfig,
      questionnaireResponse = questionnaireResponse,
      context = context,
    )

    // The Observation ID for the extracted Obs should be the same as previous Obs Id
    Assert.assertTrue(questionnaireResponse.contained.firstOrNull() is ListResource)
    val listResource = questionnaireResponse.contained.firstOrNull() as ListResource
    val observationReference =
      listResource.entry
        .find { it.item.reference.equals(previousObs.asReference().reference, true) }
        ?.item
        ?.reference

    Assert.assertNotNull(observationReference)
    Assert.assertEquals(
      previousObs.logicalId.asReference(ResourceType.Observation).reference,
      observationReference,
    )

    // Save QuestionnaireResponse never called because the config is set to false
    coVerify(exactly = 0) {
      defaultRepository.addOrUpdate(addMandatoryTags = true, resource = questionnaireResponse)
    }
  }

  @Test
  fun testLoadCqlInputResourcesFromQuestionnaireConfig() = runTest {
    val bundle = Bundle()

    // Define the expected CQL input resources
    val expectedCqlInputResources = listOf("basic-resource-id")

    val questionnaireConfigCqlInputResources =
      questionnaireConfig.copy(cqlInputResources = listOf("basic-resource-id"))

    // Create a sample questionnaire with a CQL library extension
    val questionnaire =
      samplePatientRegisterQuestionnaire.copy().apply {
        addExtension(
          Extension().apply {
            url = "https://sample.cqf-library.url"
            setValue(StringType("http://smartreg.org/Library/123"))
          },
        )
      }

    // Mock the retrieval of a Basic resource with the specified ID
    val resource1 = Faker.buildBasicResource("basic-resource-id")
    coEvery { fhirEngine.get<Basic>(any()) } answers { resource1 }
    coEvery { fhirOperator.evaluateLibrary(any(), any(), any(), any()) } returns Parameters()

    // Load the CQL input resources from the questionnaireConfig
    val loadedCqlInputResources = questionnaireConfigCqlInputResources.cqlInputResources

    // Verify that the loadedCqlInputResources match the expected list
    Assert.assertEquals(expectedCqlInputResources, loadedCqlInputResources)

    // Execute CQL by invoking the questionnaireViewModel.executeCql method
    questionnaireViewModel.executeCql(
      patient,
      bundle,
      questionnaire,
      questionnaireConfigCqlInputResources,
    )

    // Verify that the bundle contains the expected Basic resource with ID "basic-resource-id"
    Assert.assertTrue(
      bundle.entry.any { it.resource is Basic && it.resource.id == "basic-resource-id" },
    )
  }

  @Test
  fun testSaveExtractedResourcesAddsRelatedEntityLocationMetaTagToExtractedResource() =
    runBlocking {
      val bundleSlot = slot<Bundle>()
      val bundle = bundleSlot.captured
      val linkId = "linkId"
      val metaTagId = "UUId123"
      val questionnaire = extractionQuestionnaire()
      val questionnaireConfig =
        questionnaireConfig.copy(
          resourceIdentifier = "resourceId",
          resourceType = ResourceType.Location,
          saveQuestionnaireResponse = false,
          type = "EDIT",
          linkIds = listOf(LinkIdConfig(linkId = linkId, LinkIdType.LOCATION)),
          extractedResourceUniquePropertyExpressions =
            listOf(
              ExtractedResourceUniquePropertyExpression(
                ResourceType.Location,
                "Observation.code.where(coding.code='obs1').coding.code",
              ),
            ),
        )
      val previousObs =
        Observation().apply {
          id = "previousObs1"
          code = (CodeableConcept(Coding("http://obsys", "obs1", "Obs 1")))
        }

      val questionnaireResponse =
        extractionQuestionnaireResponse().apply {
          val extractionDate = Date()
          subject = patient.asReference()
          val listResource =
            ListResource().apply {
              id = metaTagId
              status = ListResource.ListStatus.CURRENT
              mode = ListResource.ListMode.WORKING
              title = CONTAINED_LIST_TITLE
              date = extractionDate
            }
          val listEntryComponent =
            ListResource.ListEntryComponent().apply {
              deleted = false
              date = extractionDate
              item = previousObs.asReference()
            }
          listResource.addEntry(listEntryComponent)
          addContained(listResource)
        }

      questionnaireViewModel.saveExtractedResources(
        bundle = bundle,
        questionnaire = questionnaire,
        questionnaireConfig = questionnaireConfig,
        questionnaireResponse = questionnaireResponse,
        context = context,
      )

      // Extract tags manually
      val listResource = questionnaireResponse.contained.firstOrNull() as ListResource
      val resource = listResource.entry.firstOrNull()?.item?.resource

      // Assert
      Assert.assertNotNull(listResource)
      Assert.assertNotNull(resource)

      // Check if the meta tag id exists
      var metaTagIdExists = false
      resource?.meta?.tag?.forEach { tag ->
        if (tag.code == metaTagId) {
          metaTagIdExists = true
          return@forEach
        }
      }
      Assert.assertTrue(metaTagIdExists)

      // Check if the related entity location meta tag exists
      var relatedEntityLocationMetaTagExists = false
      resource?.meta?.tag?.forEach { tag ->
        if (tag.system == "https://smartregister.org/related-entity-location-tag-id") {
          relatedEntityLocationMetaTagExists = true
          return@forEach
        }
      }
      Assert.assertTrue(relatedEntityLocationMetaTagExists)

      // Assert that the listResource id matches the linkId
      assertEquals(linkId, listResource.id)
    }

  @Test
  fun testRetireUsedQuestionnaireUniqueIdShouldUpdateGroupResourceWhenIDIsUsed() = runTest {
    val linkId = "phn"
    val uniqueIdAssignmentConfig =
      UniqueIdAssignmentConfig(
        linkId = linkId,
        idFhirPathExpression = "",
        resource = ResourceType.Group,
      )
    val questionnaireConfig =
      QuestionnaireConfig(
        id = "sample_config_123",
        uniqueIdAssignment = uniqueIdAssignmentConfig,
      )
    val group =
      Group().apply {
        id = "grp1"
        addCharacteristic(
          Group.GroupCharacteristicComponent(
            CodeableConcept(Coding()).setText("phn"),
            CodeableConcept(Coding()).setText("1234"),
            BooleanType(false),
          ),
        )
        addCharacteristic(
          Group.GroupCharacteristicComponent(
            CodeableConcept(Coding()).setText("phn"),
            CodeableConcept(Coding()).setText("12345"),
            BooleanType(false),
          ),
        )
      }

    val questionnaireResponse =
      extractionQuestionnaireResponse().apply {
        addItem(
          QuestionnaireResponse.QuestionnaireResponseItemComponent(StringType(linkId)).apply {
            addAnswer(
              QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                setValue(StringType("1234"))
              },
            )
          },
        )
      }
    questionnaireViewModel.uniqueIdResource = group
    coEvery { defaultRepository.addOrUpdate(resource = group) } just runs
    questionnaireViewModel.retireUsedQuestionnaireUniqueId(
      questionnaireConfig,
      questionnaireResponse,
    )

    coVerify(exactly = 1) { defaultRepository.addOrUpdate(resource = group) }
    Assert.assertTrue(group.characteristic.first().exclude)
    Assert.assertFalse(group.characteristic.last().exclude)
    Assert.assertTrue(group.active)
  }

  @Test
  fun testRetireUsedQuestionnaireUniqueIdShouldDeactivateGroupResourceWhenAllIDsAreUsed() =
    runTest {
      val linkId = "phn"
      val uniqueIdAssignmentConfig =
        UniqueIdAssignmentConfig(
          linkId = linkId,
          idFhirPathExpression = "",
          resource = ResourceType.Group,
        )
      val questionnaireConfig =
        QuestionnaireConfig(
          id = "sample_config_123",
          uniqueIdAssignment = uniqueIdAssignmentConfig,
        )
      val group =
        Group().apply {
          id = "grp2"
          addCharacteristic(
            Group.GroupCharacteristicComponent(
              CodeableConcept(Coding()).setText("phn"),
              CodeableConcept(Coding()).setText("1234"),
              BooleanType(true),
            ),
          )
          addCharacteristic(
            Group.GroupCharacteristicComponent(
              CodeableConcept(Coding()).setText("phn"),
              CodeableConcept(Coding()).setText("1235"),
              BooleanType(false),
            ),
          )
          this.quantity = 1
        }

      val questionnaireResponse =
        extractionQuestionnaireResponse().apply {
          addItem(
            QuestionnaireResponse.QuestionnaireResponseItemComponent(StringType(linkId)).apply {
              addAnswer(
                QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                  setValue(StringType("1235"))
                },
              )
            },
          )
        }

      questionnaireViewModel.uniqueIdResource = group
      coEvery { defaultRepository.addOrUpdate(resource = group) } just runs
      questionnaireViewModel.retireUsedQuestionnaireUniqueId(
        questionnaireConfig,
        questionnaireResponse,
      )

      coVerify(exactly = 1) { defaultRepository.addOrUpdate(resource = group) }
      Assert.assertTrue(group.characteristic.first().exclude)
      Assert.assertTrue(group.characteristic.last().exclude)
      Assert.assertFalse(group.active)
    }

  @Test
  fun testGroupResourceShouldNotBeUpdatedWhenCustomUniqueIDsAreUsed() = runTest {
    val linkId = "phn"
    val uniqueIdAssignmentConfig =
      UniqueIdAssignmentConfig(
        linkId = linkId,
        idFhirPathExpression = "",
        resource = ResourceType.Group,
      )
    val questionnaireConfig =
      QuestionnaireConfig(
        id = "sample_config_123",
        uniqueIdAssignment = uniqueIdAssignmentConfig,
      )
    val group =
      Group().apply {
        id = "grp2"
        addCharacteristic(
          Group.GroupCharacteristicComponent(
            CodeableConcept(Coding()).setText("phn"),
            CodeableConcept(Coding()).setText("1234"),
            BooleanType(true),
          ),
        )
        addCharacteristic(
          Group.GroupCharacteristicComponent(
            CodeableConcept(Coding()).setText("phn"),
            CodeableConcept(Coding()).setText("1235"),
            BooleanType(false),
          ),
        )
        this.quantity = 1
      }

    val questionnaireResponse =
      extractionQuestionnaireResponse().apply {
        addItem(
          QuestionnaireResponse.QuestionnaireResponseItemComponent(StringType(linkId)).apply {
            addAnswer(
              QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                setValue(StringType("CUSTID123456"))
              },
            )
          },
        )
      }

    questionnaireViewModel.uniqueIdResource = group
    questionnaireViewModel.retireUsedQuestionnaireUniqueId(
      questionnaireConfig,
      questionnaireResponse,
    )

    coVerify(exactly = 0) { defaultRepository.addOrUpdate(resource = group) }
    Assert.assertTrue(group.characteristic.first().exclude)
    Assert.assertFalse(group.characteristic.last().exclude)
    Assert.assertFalse(group.active)
  }

  @Test
  fun testThatPopulateQuestionnaireSetsQuestionnaireResponseWithInitialDefaultValueForQuestionnaireInitialExpression() =
    runTest {
      val questionnaireViewModelInstance =
        QuestionnaireViewModel(
          defaultRepository = defaultRepository,
          dispatcherProvider = dispatcherProvider,
          fhirCarePlanGenerator = fhirCarePlanGenerator,
          rulesExecutor = rulesExecutor,
          transformSupportServices = mockk(),
          sharedPreferencesHelper = sharedPreferencesHelper,
          fhirOperator = fhirOperator,
          fhirValidatorRequestHandlerProvider = fhirValidatorRequestHandlerProvider,
          fhirPathDataExtractor = fhirPathDataExtractor,
          configurationRegistry = configurationRegistry,
        )
      val questionnaireWithDefaultDate =
        Questionnaire().apply {
          id = questionnaireConfig.id
          addItem(
            QuestionnaireItemComponent().apply {
              linkId = "defaultedDate"
              type = Questionnaire.QuestionnaireItemType.DATE
              addExtension(
                Extension(
                  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression",
                  Expression().apply {
                    language = "text/fhirpath"
                    expression = "today()"
                  },
                ),
              )
            },
          )
        }
      coEvery { fhirEngine.get(ResourceType.Questionnaire, questionnaireConfig.id) } returns
        questionnaireWithDefaultDate

      val (questionnaireResponse, _) =
        questionnaireViewModelInstance.populateQuestionnaire(
          questionnaireWithDefaultDate,
          questionnaireConfig,
          emptyList(),
        )

      Assert.assertTrue(
        (questionnaireResponse!!.item.single { it.linkId == "defaultedDate" }.answerFirstRep.value
            as DateType)
          .isToday,
      )
    }

  @Test
  fun testThatPopulateQuestionnaireSetsInitialDefaultValueButExcludesValueFromPreviousResponse() =
    runTest(timeout = 90.seconds) {
      val thisQuestionnaireConfig =
        questionnaireConfig.copy(
          resourceType = ResourceType.Patient,
          resourceIdentifier = patient.logicalId,
          type = QuestionnaireType.EDIT.name,
          linkIds =
            listOf(
              LinkIdConfig("dateToday", LinkIdType.PREPOPULATION_EXCLUSION),
            ),
        )
      val questionnaireViewModelInstance =
        QuestionnaireViewModel(
          defaultRepository = defaultRepository,
          dispatcherProvider = dispatcherProvider,
          fhirCarePlanGenerator = fhirCarePlanGenerator,
          rulesExecutor = rulesExecutor,
          transformSupportServices = mockk(),
          sharedPreferencesHelper = sharedPreferencesHelper,
          fhirOperator = fhirOperator,
          fhirValidatorRequestHandlerProvider = fhirValidatorRequestHandlerProvider,
          fhirPathDataExtractor = fhirPathDataExtractor,
          configurationRegistry = configurationRegistry,
        )
      val questionnaireWithDefaultDate =
        Questionnaire().apply {
          id = thisQuestionnaireConfig.id
          addItem(
            QuestionnaireItemComponent().apply {
              linkId = "dateToday"
              type = Questionnaire.QuestionnaireItemType.DATE
              addExtension(
                Extension(
                  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression",
                  Expression().apply {
                    language = "text/fhirpath"
                    expression = "today()"
                  },
                ),
              )
            },
          )
        }

      val questionnaireResponse =
        QuestionnaireResponse().apply {
          addItem(
            QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
              linkId = "dateToday"
              addAnswer(
                QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                  value = DateType(Date())
                },
              )
            },
          )
          setQuestionnaire(
            thisQuestionnaireConfig.id.asReference(ResourceType.Questionnaire).reference,
          )
        }

      coEvery {
        fhirEngine.get(
          thisQuestionnaireConfig.resourceType!!,
          thisQuestionnaireConfig.resourceIdentifier!!,
        )
      } returns patient

      coEvery { fhirEngine.search<QuestionnaireResponse>(any<Search>()) } returns
        listOf(
          SearchResult(questionnaireResponse, included = null, revIncluded = null),
        )

      val (result, _) =
        questionnaireViewModelInstance.populateQuestionnaire(
          questionnaire = questionnaireWithDefaultDate,
          questionnaireConfig = thisQuestionnaireConfig,
          actionParameters = emptyList(),
        )

      Assert.assertNotNull(result?.item)
      Assert.assertTrue(result!!.item.isNotEmpty())
      Assert.assertTrue((result.item.single().answerFirstRep.value as DateType).isToday)
    }

  @Test
  fun testPopulateQuestionnaireEvaluatesCqlBasedInitialExpression() = runTest {
    val questionnaireViewModelInstance =
      QuestionnaireViewModel(
        defaultRepository = defaultRepository,
        dispatcherProvider = dispatcherProvider,
        fhirCarePlanGenerator = fhirCarePlanGenerator,
        rulesExecutor = rulesExecutor,
        transformSupportServices = mockk(),
        sharedPreferencesHelper = sharedPreferencesHelper,
        fhirOperator = fhirOperator,
        fhirValidatorRequestHandlerProvider = fhirValidatorRequestHandlerProvider,
        fhirPathDataExtractor = fhirPathDataExtractor,
        configurationRegistry = configurationRegistry,
      )
    val cqlIdentifier = "hasChronicCondition"
    val questionnaireWithCqlInitExpr =
      Questionnaire().apply {
        id = questionnaireConfig.id
        addExtension(
          Extension(
            "http://hl7.org/fhir/StructureDefinition/cqf-library",
            StringType("http://example.org/Library/test-cql-lib|1.0.0"),
          ),
        )
        addItem(
          QuestionnaireItemComponent().apply {
            linkId = "chronicCondition"
            type = Questionnaire.QuestionnaireItemType.BOOLEAN
            addExtension(
              Extension(
                "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression",
                Expression().apply {
                  language = "text/cql-identifier"
                  expression = cqlIdentifier
                },
              ),
            )
          },
        )
      }

    // New questionnaire (not edit/draft) so CQL initialExpression population runs.
    val thisConfig =
      questionnaireConfig.copy(
        resourceType = ResourceType.Patient,
        resourceIdentifier = "patient-1",
        type = QuestionnaireType.DEFAULT.name,
      )

    coEvery { fhirEngine.get(ResourceType.Questionnaire, thisConfig.id) } returns
      questionnaireWithCqlInitExpr
    coEvery { defaultRepository.loadResource("patient-1", ResourceType.Patient) } returns patient

    val cqlResultParams =
      Parameters().apply {
        addParameter(
          Parameters.ParametersParameterComponent().apply {
            name = cqlIdentifier
            value = BooleanType(true)
          },
        )
      }
    coEvery { fhirOperator.evaluateLibrary(any(), any(), any(), any(), any()) } returns
      cqlResultParams

    questionnaireViewModelInstance.populateQuestionnaire(
      questionnaireWithCqlInitExpr,
      thisConfig,
      emptyList(),
    )

    val initial =
      questionnaireWithCqlInitExpr.item
        .first { it.linkId == "chronicCondition" }
        .initial
        .firstOrNull()
        ?.value
    Assert.assertTrue(initial is BooleanType && (initial as BooleanType).booleanValue())
    coVerify {
      fhirOperator.evaluateLibrary(
        any(),
        any(),
        any(),
        any(),
        any(),
      )
    }
  }

  @Test
  fun testThatPopulateQuestionnaireReturnsQuestionnaireResponseWithUnAnsweredRemoved() = runTest {
    val questionnaireViewModelInstance =
      QuestionnaireViewModel(
        defaultRepository = defaultRepository,
        dispatcherProvider = dispatcherProvider,
        fhirCarePlanGenerator = fhirCarePlanGenerator,
        rulesExecutor = rulesExecutor,
        transformSupportServices = mockk(),
        sharedPreferencesHelper = sharedPreferencesHelper,
        fhirOperator = fhirOperator,
        fhirValidatorRequestHandlerProvider = fhirValidatorRequestHandlerProvider,
        fhirPathDataExtractor = fhirPathDataExtractor,
        configurationRegistry = configurationRegistry,
      )
    val questionnaireConfig1 =
      questionnaireConfig.copy(
        resourceType = ResourceType.Patient,
        resourceIdentifier = patient.logicalId,
        type = QuestionnaireType.EDIT.name,
      )

    val questionnaireWithInitialValue =
      Questionnaire().apply {
        id = questionnaireConfig1.id
        addItem(
          QuestionnaireItemComponent().apply {
            linkId = "group-1"
            type = Questionnaire.QuestionnaireItemType.GROUP
            addItem(
              QuestionnaireItemComponent().apply {
                linkId = "linkid-1"
                type = Questionnaire.QuestionnaireItemType.STRING
                addInitial(Questionnaire.QuestionnaireItemInitialComponent(StringType("---")))
              },
            )

            addItem(
              QuestionnaireItemComponent().apply {
                linkId = "linkid-1.1"
                type = Questionnaire.QuestionnaireItemType.STRING
                addInitial(Questionnaire.QuestionnaireItemInitialComponent(StringType("---")))
              },
            )
          },
        )

        addItem(
          QuestionnaireItemComponent().apply {
            linkId = "linkid-2"
            type = Questionnaire.QuestionnaireItemType.STRING
          },
        )
      }
    val questionnaireResponse =
      QuestionnaireResponse().apply {
        addItem(
          QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = "group-1"
            addItem(
              QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                linkId = "linkid-1"
              },
            )
            addItem(
              QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                linkId = "linkid-1.1"
                addAnswer(
                  QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                    value = StringType("World")
                  },
                )
              },
            )
          },
        )

        addItem(
          QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = "linkid-2"
            addAnswer(
              QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                value = StringType("45678")
              },
            )
          },
        )
      }
    coEvery {
      fhirEngine.get(questionnaireConfig1.resourceType!!, questionnaireConfig1.resourceIdentifier!!)
    } returns patient

    coEvery { fhirEngine.search<QuestionnaireResponse>(any<Search>()) } returns
      listOf(
        SearchResult(questionnaireResponse, included = null, revIncluded = null),
      )

    Assert.assertNotNull(questionnaireResponse.find("linkid-1"))
    val result =
      questionnaireViewModelInstance.populateQuestionnaire(
        questionnaireWithInitialValue,
        questionnaireConfig1,
        emptyList(),
      )
    Assert.assertNotNull(result.first)
    Assert.assertTrue(result.first!!.find("linkid-1") == null)
  }

  @Test
  fun testThatPopulateQuestionnaireReturnsQuestionnaireResponseWithIdValueWhenSaveDraftIsTrue() =
    runTest {
      val questionnaireViewModelInstance =
        QuestionnaireViewModel(
          defaultRepository = defaultRepository,
          dispatcherProvider = dispatcherProvider,
          fhirCarePlanGenerator = fhirCarePlanGenerator,
          rulesExecutor = rulesExecutor,
          transformSupportServices = mockk(),
          sharedPreferencesHelper = sharedPreferencesHelper,
          fhirOperator = fhirOperator,
          fhirValidatorRequestHandlerProvider = fhirValidatorRequestHandlerProvider,
          fhirPathDataExtractor = fhirPathDataExtractor,
          configurationRegistry = configurationRegistry,
        )
      val questionnaireConfig1 =
        questionnaireConfig.copy(
          resourceType = ResourceType.Patient,
          resourceIdentifier = patient.logicalId,
          type = QuestionnaireType.EDIT.name,
          saveDraft = true,
        )

      val questionnaireWithInitialValue =
        Questionnaire().apply {
          id = questionnaireConfig1.id
          addItem(
            QuestionnaireItemComponent().apply {
              linkId = "group-1"
              type = Questionnaire.QuestionnaireItemType.GROUP
              addItem(
                QuestionnaireItemComponent().apply {
                  linkId = "linkid-1"
                  type = Questionnaire.QuestionnaireItemType.STRING
                  addInitial(Questionnaire.QuestionnaireItemInitialComponent(StringType("---")))
                },
              )
            },
          )

          addItem(
            QuestionnaireItemComponent().apply {
              linkId = "linkid-2"
              type = Questionnaire.QuestionnaireItemType.STRING
            },
          )
        }
      val qrId = "qr-id-1"
      val questionnaireResponse =
        QuestionnaireResponse().apply {
          id = qrId
          addItem(
            QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
              linkId = "group-1"
              addItem(
                QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                  linkId = "linkid-1"
                },
              )
            },
          )
        }
      coEvery {
        fhirEngine.get(
          questionnaireConfig1.resourceType!!,
          questionnaireConfig1.resourceIdentifier!!,
        )
      } returns patient

      coEvery { fhirEngine.search<QuestionnaireResponse>(any<Search>()) } returns
        listOf(
          SearchResult(questionnaireResponse, included = null, revIncluded = null),
        )

      Assert.assertNotNull(questionnaireResponse.find("linkid-1"))
      val result =
        questionnaireViewModelInstance.populateQuestionnaire(
          questionnaireWithInitialValue,
          questionnaireConfig1,
          emptyList(),
        )
      Assert.assertNotNull(result.first)
      Assert.assertEquals(qrId, result.first!!.id)
    }

  @Test
  fun testThatPopulateQuestionnaireReturnsQuestionnaireResponseWithoutIdValueWhenSaveDraftIsFalse() =
    runTest {
      val questionnaireViewModelInstance =
        QuestionnaireViewModel(
          defaultRepository = defaultRepository,
          dispatcherProvider = dispatcherProvider,
          fhirCarePlanGenerator = fhirCarePlanGenerator,
          rulesExecutor = rulesExecutor,
          transformSupportServices = mockk(),
          sharedPreferencesHelper = sharedPreferencesHelper,
          fhirOperator = fhirOperator,
          fhirValidatorRequestHandlerProvider = fhirValidatorRequestHandlerProvider,
          fhirPathDataExtractor = fhirPathDataExtractor,
          configurationRegistry = configurationRegistry,
        )
      val questionnaireConfig1 =
        questionnaireConfig.copy(
          resourceType = ResourceType.Patient,
          resourceIdentifier = patient.logicalId,
          type = QuestionnaireType.EDIT.name,
          saveDraft = false,
        )

      val questionnaireWithInitialValue =
        Questionnaire().apply {
          id = questionnaireConfig1.id
          addItem(
            QuestionnaireItemComponent().apply {
              linkId = "group-1"
              type = Questionnaire.QuestionnaireItemType.GROUP
              addItem(
                QuestionnaireItemComponent().apply {
                  linkId = "linkid-1"
                  type = Questionnaire.QuestionnaireItemType.STRING
                  addInitial(Questionnaire.QuestionnaireItemInitialComponent(StringType("---")))
                },
              )
            },
          )

          addItem(
            QuestionnaireItemComponent().apply {
              linkId = "linkid-2"
              type = Questionnaire.QuestionnaireItemType.STRING
            },
          )
        }
      val qrId = "qr-id-1"
      val questionnaireResponse =
        QuestionnaireResponse().apply {
          id = qrId
          addItem(
            QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
              linkId = "group-1"
              addItem(
                QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                  linkId = "linkid-1"
                },
              )
            },
          )
        }
      coEvery {
        fhirEngine.get(
          questionnaireConfig1.resourceType!!,
          questionnaireConfig1.resourceIdentifier!!,
        )
      } returns patient

      coEvery { fhirEngine.search<QuestionnaireResponse>(any<Search>()) } returns
        listOf(
          SearchResult(questionnaireResponse, included = null, revIncluded = null),
        )

      Assert.assertNotNull(questionnaireResponse.find("linkid-1"))
      val result =
        questionnaireViewModelInstance.populateQuestionnaire(
          questionnaireWithInitialValue,
          questionnaireConfig1,
          emptyList(),
        )
      Assert.assertNotNull(result.first)
      Assert.assertNull(result.first?.id)
    }

  @Test
  fun testProcessRepeatGroupItems() {
    runTest {
      val questionnaireResponse =
        QuestionnaireResponse().apply {
          contained =
            listOf(
              ListResource().apply {
                id = "list-id"
                entry =
                  listOf(
                    ListResource.ListEntryComponent().apply {
                      item = Reference("MedicationRequest/med-id-1")
                    },
                  )
              },
            )
          item =
            listOf(
              QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                linkId = "active-medication-ids"
                answer =
                  listOf(
                    QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                      value = StringType("med-id-1,med-id-2")
                    },
                  )
              },
            )
        }

      val questionnaire =
        Questionnaire().apply {
          id = "questionnaire-id-1"
          item =
            listOf(
              QuestionnaireItemComponent().apply {
                linkId = "active-medication-ids"
                initial =
                  listOf(
                    Questionnaire.QuestionnaireItemInitialComponent(
                      StringType("med-id-1,med-id-2"),
                    ),
                  )
              },
            )
        }

      coEvery { defaultRepository.addOrUpdate(any(Boolean::class), any<Resource>()) } just runs

      val medRequestSlot = slot<MedicationRequest>()
      val stringSlot = slot<String>()
      val linkIdConfig =
        LinkIdConfig(
          resourceType = ResourceType.MedicationRequest,
          linkId = "active-medication-ids",
          type = LinkIdType.REPEATED_GROUP_DELETION,
        )
      val questionnaireConfig =
        QuestionnaireConfig(
          id = "sample-config-id",
          linkIds = listOf(linkIdConfig),
        )

      questionnaireViewModel.processRepeatedGroupItems(
        questionnaireResponse = questionnaireResponse,
        questionnaire = questionnaire,
        questionnaireConfig = questionnaireConfig,
      )

      coVerify { defaultRepository.addOrUpdate(true, capture(medRequestSlot)) }
      assertEquals("med-id-2", stringSlot.captured)
      assertEquals("STOPPED", medRequestSlot.captured.status.name)
    }
  }

  @Test
  fun testProcessRepeatGroupItemsWhenInitialResourceIdsStringIsBlank() {
    runTest {
      val questionnaireResponse =
        QuestionnaireResponse().apply {
          contained =
            listOf(
              ListResource().apply {
                id = "list-id"
                entry =
                  listOf(
                    ListResource.ListEntryComponent().apply {
                      item = Reference("MedicationRequest/med-id-1")
                    },
                  )
              },
            )
        }

      val questionnaire =
        Questionnaire().apply {
          id = "questionnaire-id-1"
          item =
            listOf(
              QuestionnaireItemComponent().apply {
                linkId = "active-medication-ids"
                initial =
                  listOf(
                    Questionnaire.QuestionnaireItemInitialComponent(
                      StringType(""),
                    ),
                  )
              },
            )
        }

      val linkIdConfig =
        LinkIdConfig(
          resourceType = ResourceType.MedicationRequest,
          linkId = "active-medication-ids",
          type = LinkIdType.REPEATED_GROUP_DELETION,
        )
      val questionnaireConfig =
        QuestionnaireConfig(
          id = "sample-config-id",
          linkIds = listOf(linkIdConfig),
        )

      questionnaireViewModel.processRepeatedGroupItems(
        questionnaireResponse = questionnaireResponse,
        questionnaire = questionnaire,
        questionnaireConfig = questionnaireConfig,
      )

      coVerify(exactly = 0) { defaultRepository.addOrUpdate(any(), any()) }
    }
  }

  @Test
  fun testExcludeNestedItemFromQuestionnairePrepopulation() {
    val item1 = QuestionnaireResponse.QuestionnaireResponseItemComponent().apply { linkId = "1" }
    val item2 = QuestionnaireResponse.QuestionnaireResponseItemComponent().apply { linkId = "2" }
    val item3 =
      QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
        linkId = "3"
        item =
          mutableListOf(
            QuestionnaireResponse.QuestionnaireResponseItemComponent().apply { linkId = "3.1" },
            QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
              linkId = "3.2"
              item =
                mutableListOf(
                  QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                    linkId = "3.2.1"
                  },
                  QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                    linkId = "3.2.2"
                  },
                )
            },
          )
      }

    val items = mutableListOf(item1, item2, item3)
    val exclusionMap = mapOf("2" to true, "3.1" to true, "3.2.2" to true)
    val filteredItems =
      questionnaireViewModel.excludePreviouslyAnsweredFields(items, emptyList(), exclusionMap)
    Assert.assertEquals(2, filteredItems.size)
    Assert.assertEquals("1", filteredItems.first().linkId)
    val itemThree = filteredItems.last()
    Assert.assertEquals("3", itemThree.linkId)
    Assert.assertEquals(1, itemThree.item.size)
    val itemThreePointTwo = itemThree.item.first()
    Assert.assertEquals("3.2", itemThreePointTwo.linkId)
    Assert.assertEquals(1, itemThreePointTwo.item.size)
    val itemThreePointTwoOne = itemThreePointTwo.item.first()
    Assert.assertEquals("3.2.1", itemThreePointTwoOne.linkId)
  }
}
