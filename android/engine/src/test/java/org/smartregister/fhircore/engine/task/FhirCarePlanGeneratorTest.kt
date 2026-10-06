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
import androidx.test.core.app.ApplicationProvider
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.rest.gclient.ReferenceClientParam
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.SearchResult
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.get
import com.google.android.fhir.knowledge.KnowledgeManager
import com.google.android.fhir.search.Search
import com.google.android.fhir.workflow.FhirOperator
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlin.reflect.KSuspendFunction1
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cqframework.cql.cql2elm.CqlTranslator
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.BaseDateTimeType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.CarePlan
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.Group
import org.hl7.fhir.r4.model.Immunization
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.MetadataResource
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.PlanDefinition
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.StructureMap
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskOutputComponent
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.hl7.fhir.r4.utils.FHIRPathEngine
import org.hl7.fhir.r4.utils.StructureMapUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.smartregister.fhircore.engine.app.fakes.Faker
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.configuration.event.EventTriggerCondition
import org.smartregister.fhircore.engine.configuration.event.EventWorkflow
import org.smartregister.fhircore.engine.data.local.ContentCache
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.domain.model.ResourceConfig
import org.smartregister.fhircore.engine.robolectric.RobolectricTest
import org.smartregister.fhircore.engine.rule.CoroutineTestRule
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.REFERENCE
import org.smartregister.fhircore.engine.util.extension.SDF_YYYY_MM_DD
import org.smartregister.fhircore.engine.util.extension.asReference
import org.smartregister.fhircore.engine.util.extension.batchedSearch
import org.smartregister.fhircore.engine.util.extension.decodeResourceFromString
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.extension.extractId
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.find
import org.smartregister.fhircore.engine.util.extension.formatDate
import org.smartregister.fhircore.engine.util.extension.getCustomJsonParser
import org.smartregister.fhircore.engine.util.extension.makeItReadable
import org.smartregister.fhircore.engine.util.extension.plusDays
import org.smartregister.fhircore.engine.util.extension.plusMonths
import org.smartregister.fhircore.engine.util.extension.plusYears
import org.smartregister.fhircore.engine.util.extension.referenceValue
import org.smartregister.fhircore.engine.util.extension.updateDependentTaskDueDate
import org.smartregister.fhircore.engine.util.extension.valueToString
import org.smartregister.fhircore.engine.util.fhirpath.FhirPathDataExtractor
import org.smartregister.fhircore.engine.util.helper.TransformSupportServices

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
class FhirCarePlanGeneratorTest : RobolectricTest() {

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val coroutineTestRule = CoroutineTestRule()

  @Inject lateinit var transformSupportServices: TransformSupportServices

  @Inject lateinit var fhirPathEngine: FHIRPathEngine

  @Inject lateinit var fhirEngine: FhirEngine

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  @Inject lateinit var configService: ConfigService

  @Inject lateinit var sharedPreferencesHelper: SharedPreferencesHelper

  @Inject lateinit var fhirPathDataExtractor: FhirPathDataExtractor

  @Inject lateinit var configurationRegistry: ConfigurationRegistry

  @Inject lateinit var contentCache: ContentCache

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val knowledgeManager = KnowledgeManager.create(context)
  private val fhirContext: FhirContext = FhirContext.forCached(FhirVersionEnum.R4)

  private lateinit var defaultRepository: DefaultRepository
  private lateinit var fhirResourceUtil: FhirResourceUtil
  private lateinit var fhirCarePlanGenerator: FhirCarePlanGenerator
  private lateinit var structureMapUtilities: StructureMapUtilities
  private lateinit var immunizationResource: Immunization
  private lateinit var encounter: Encounter
  private lateinit var opv0: Task
  private lateinit var opv1: Task

  private val iParser: IParser = fhirContext.newJsonParser()
  private val jsonParser = fhirContext.getCustomJsonParser()
  private val xmlParser = fhirContext.newXmlParser()

  @Before
  fun setup() {
    hiltRule.inject()
    structureMapUtilities = StructureMapUtilities(transformSupportServices.simpleWorkerContext)
    defaultRepository =
      spyk(
        DefaultRepository(
          fhirEngine = fhirEngine,
          dispatcherProvider = dispatcherProvider,
          sharedPreferencesHelper = sharedPreferencesHelper,
          configurationRegistry = mockk(),
          configService = configService,
          configRulesExecutor = mockk(),
          fhirPathDataExtractor = fhirPathDataExtractor,
          parser = iParser,
          context = context,
          contentCache = contentCache,
        ),
      )

    coEvery { defaultRepository.create(anyBoolean(), any()) } returns listOf()

    fhirResourceUtil =
      spyk(
        FhirResourceUtil(
          appContext = ApplicationProvider.getApplicationContext(),
          defaultRepository = defaultRepository,
          configurationRegistry = configurationRegistry,
        ),
      )

    val workflowCarePlanGenerator =
      WorkflowCarePlanGenerator(
        knowledgeManager = knowledgeManager,
        defaultRepository = defaultRepository,
        fhirPathEngine = fhirPathEngine,
        context = context,
        fhirOperator =
          FhirOperator.Builder(context)
            .fhirEngine(fhirEngine)
            .fhirContext(fhirContext)
            .knowledgeManager(knowledgeManager)
            .build(),
      )

    fhirCarePlanGenerator =
      FhirCarePlanGenerator(
        fhirEngine = fhirEngine,
        fhirPathEngine = fhirPathEngine,
        transformSupportServices = transformSupportServices,
        defaultRepository = defaultRepository,
        fhirResourceUtil = fhirResourceUtil,
        workflowCarePlanGenerator = workflowCarePlanGenerator,
        context = context,
      )

    immunizationResource =
      iParser.parseResource(
        Immunization::class.java,
        """
              {
                "resourceType": "Immunization",
                "id": "41921cfe-5074-4eec-925f-2bc581237660",
                "identifier": {
                  "use": "official",
                  "value": "6a637a79-df7b-4cc9-93b2-f73f965c31ab"
                },
                "status": "completed",
                "patient": {
                  "reference": "Patient/3e3d698a-4edb-48f9-9330-2f1adc0635d1"
                },
                "encounter": {
                  "reference": "Encounter/14e2ae52-32fc-4507-8736-1177cdaafe90"
                },
                "occurrenceString": "2021-10-23T00:00:00.00Z"
              }
                """
          .trimIndent(),
      )

    encounter =
      iParser.parseResource(
        Encounter::class.java,
        """
              {
                "resourceType": "Encounter",
                "id": "14e2ae52-32fc-4507-8736-1177cdaafe90",
                "identifier": {
                  "use": "official",
                  "value": "4b62fff3-6010-4674-84a2-71f2bbdbf2e5"
                },
                "status": "finished",
                "type": [
                  {
                    "coding": [
                      {
                        "system": "http://snomed.info/sct",
                        "code": "33879002",
                        "display": "Administration of vaccine to produce active immunity (procedure)"
                      }
                    ]
                  }
                ],
                "subject": {
                  "reference": "Patient/3e3d698a-4edb-48f9-9330-2f1adc0635d1"
                },
                "period": {
                  "end": "2021-10-01T00:00:00+00:00"
                },
                "partOf": {
                  "reference": "Encounter/15e2ae52-32fc-4507-8736-1177cdaafe90"
                }
              }
                """
          .trimIndent(),
      )

    opv0 =
      iParser
        .parseResource(
          Task::class.java,
          """
                  {
                    "resourceType": "Task",
                    "id": "648f786a-f716-4668-aee9-66600a8bb8c9",
                    "meta": {
                      "lastUpdated": "2023-06-23T09:36:04.849+00:00",
                      "tag": [
                        {
                          "system": "https://smartregister.org/app-version",
                          "code": "0.2.2-ecbis",
                          "display": "Application Version"
                        },
                        {
                          "system": "https://smartregister.org/care-team-tag-id",
                          "code": "eb47e6cf-6631-4d22-85db-423c109f9717",
                          "display": "Practitioner CareTeam"
                        },
                        {
                          "system": "https://smartregister.org/location-tag-id",
                          "code": "52a6d6e5-e0cd-4239-9950-7c094296128c",
                          "display": "Practitioner Location"
                        },
                        {
                          "system": "https://smartregister.org/organisation-tag-id",
                          "code": "b284e210-930d-465b-9cf6-4be64b31fd4e",
                          "display": "Practitioner Organization"
                        },
                        {
                          "system": "https://smartregister.org/practitioner-tag-id",
                          "code": "b3c19bc5-838c-4b53-a681-3995765e276f",
                          "display": "Practitioner"
                        }
                      ]
                    },
                    "identifier": [
                      {
                        "use": "official",
                        "value": "a136ce5b-902f-4e56-9b57-71958eab1c8b"
                      }
                    ],
                    "basedOn": [
                      {
                        "reference": "CarePlan/6b160e75-7543-44de-8f0c-e0178d696c28"
                      }
                    ],
                    "groupIdentifier": {
                      "use": "secondary",
                      "value": "0_d"
                    },
                    "status": "completed",
                    "intent": "plan",
                    "priority": "routine",
                    "code": {
                      "coding": [
                        {
                          "system": "http://snomed.info/sct",
                          "code": "33879002",
                          "display": "Administration of vaccine to produce active immunity (procedure)"
                        }
                      ]
                    },
                    "description": "OPV 0",
                    "for": {
                      "reference": "Patient/2305efe1-6e36-44a9-bd6e-dec746fa553c"
                    },
                    "executionPeriod": {
                      "start": "2021-06-22T00:00:00.00Z",
                      "end": "2021-07-06T00:00:00.00Z"
                    },
                    "authoredOn": "2023-06-23T09:34:48+00:00",
                    "lastModified": "2023-06-23T09:36:04+00:00",
                    "requester": {
                      "reference": "Practitioner/b3c19bc5-838c-4b53-a681-3995765e276f"
                    },
                    "owner": {
                      "reference": "Practitioner/b3c19bc5-838c-4b53-a681-3995765e276f"
                    },
                    "reasonCode": {
                      "coding": [
                        {
                          "system": "http://snomed.info/sct",
                          "code": "111164008",
                          "display": "Poliovirus vaccine"
                        }
                      ],
                      "text": "OPV"
                    },
                    "reasonReference": {
                      "reference": "Questionnaire/9b1aa23b-577c-4fb2-84e3-591e6facaf82"
                    },
                    "restriction": {
                      "period": {
                        "start": "2021-06-22T00:00:00.00Z",
                        "end": "2026-06-21T00:00:00.00Z"
                      }
                    },
                    "output": [
                      {
                        "type": {
                          "coding": [
                            {
                              "system": "http://snomed.info/sct",
                              "code": "41000179103",
                              "display": "Immunization record (record artifact)"
                            }
                          ]
                        },
                        "valueReference": {
                          "reference": "Encounter/b2cf5d28-5a3a-4217-bf57-8da5b97ae126"
                        }
                      },
                      {
                        "type": {
                          "coding": [
                            {
                              "system": "http://snomed.info/sct",
                              "code": "41000179103",
                              "display": "Immunization record (record artifact)"
                            }
                          ]
                        },
                        "valueReference": {
                          "reference": "Encounter/fb6abe21-a771-45e5-8abb-3fdd2626c3c3"
                        }
                      },
                      {
                        "type": {
                          "coding": [
                            {
                              "system": "http://snomed.info/sct",
                              "code": "41000179103",
                              "display": "Immunization record (record artifact)"
                            }
                          ]
                        },
                        "valueReference": {
                          "reference": "Immunization/4922b562-147e-4097-867c-44b905ce7ac4"
                        }
                      }
                    ]
                  }
                """
            .trimIndent(),
        )
        .apply {
          output =
            listOf(
              TaskOutputComponent(
                CodeableConcept(),
                Reference("Immunization/41921cfe-5074-4eec-925f-2bc581237660"),
              ),
            )
        }
    opv1 =
      iParser.parseResource(
        Task::class.java,
        """
              {
                "resourceType": "Task",
                "id": "650203d2-f327-4eb4-a9fd-741e0ce29c3f",
                "identifier": [
                  {
                    "use": "official",
                    "value": "ad17cda3-0ac8-43c5-8d9a-9f3adee45e2b"
                  }
                ],
                "basedOn": [
                  {
                    "reference": "CarePlan/28d7542c-ba08-4f16-b6a2-19e8b5d4c229"
                  }
                ],
                "status": "requested",
                "intent": "plan",
                "priority": "routine",
                "partOf": [ "Task/648f786a-f716-4668-aee9-66600a8bb8c9"],
                "code": {
                  "coding": [
                    {
                      "system": "http://snomed.info/sct",
                      "code": "33879002",
                      "display": "Administration of vaccine to produce active immunity (procedure)"
                    }
                  ]
                },
                "description": "OPV 0 at 0 d vaccine",
                "for": {
                  "reference": "Patient/3e3d698a-4edb-48f9-9330-2f1adc0635d1"
                },
                "executionPeriod": {
                  "start": "2021-10-10T00:00:00+00:00",
                  "end": "2021-10-15T00:00:00+00:00"
                },
                "authoredOn": "2023-03-28T10:46:59+00:00",
                "requester": {
                  "reference": "Practitioner/3812"
                },
                "owner": {
                  "reference": "Practitioner/3812"
                },
                "reasonCode": {
                  "coding": [
                    {
                      "system": "http://snomed.info/sct",
                      "code": "111164008",
                      "display": "Poliovirus vaccine"
                    }
                  ],
                  "text": "OPV"
                },
                "reasonReference": {
                  "reference": "Questionnaire/9b1aa23b-577c-4fb2-84e3-591e6facaf82"
                },
                "output": [
                  {
                    "type": {
                      "coding": [
                        {
                          "system": "http://snomed.info/sct",
                          "code": "41000179103",
                          "display": "Immunization record (record artifact)"
                        }
                      ]
                    },
                    "value": {
                      "reference": "Encounter/14e2ae52-32fc-4507-8736-1177cdaafe90"
                    }
                  }
                ]
              }
                """
          .trimIndent(),
      )
  }

  @Test
  @ExperimentalCoroutinesApi
  fun testGenerateCarePlanForPatientNoBundle() = runTest {
    val planDefinition = PlanDefinition().apply { id = "plan-1" }
    val patient = Patient()
    val carePlan =
      fhirCarePlanGenerator.generateOrUpdateCarePlan(
        planDefinitionId = planDefinition.id,
        subject = patient,
        generateCarePlanWithWorkflowApi = true,
      )
    assertNull(carePlan)
  }

  @Ignore("Throws stack overflow error")
  @Test
  @ExperimentalCoroutinesApi
  fun testGenerateCarePlanForPatient() = runTest {
    val planDefinition =
      "plans/child-routine-visit/plandefinition.json"
        .readFile()
        .decodeResourceFromString<PlanDefinition>()

    val patient =
      "plans/child-routine-visit/sample/patient.json".readFile().decodeResourceFromString<Patient>()

    val structureMapScript = "plans/child-routine-visit/structure-map.txt".readFile()
    val structureMap =
      structureMapUtilities.parse(structureMapScript, "ChildRoutineCarePlan").also {
        // TODO: IMP - The parser does not recognize the time unit i.e. months and prints as ''
        //  so use only months and that would have the unit replaced with 'months'
        println(it.encodeResourceToString().replace("''", "'month'"))
      }

    val resourcesSlot = mutableListOf<Resource>()
    val booleanSlot = slot<Boolean>()
    coEvery { defaultRepository.addOrUpdate(capture(booleanSlot), capture(resourcesSlot)) } just
      runs
    coEvery { fhirEngine.get<StructureMap>("131373") } returns structureMap
    coEvery { fhirEngine.search<CarePlan>(Search(ResourceType.CarePlan)) } returns listOf()

    fhirCarePlanGenerator
      .generateOrUpdateCarePlan(
        planDefinition,
        patient,
        Bundle().addEntry(Bundle.BundleEntryComponent().apply { resource = patient }),
      )!!
      .also { println(it.encodeResourceToString()) }
      .also { carePlan ->
        assertNotNull(UUID.fromString(carePlan.id))
        assertEquals(CarePlan.CarePlanStatus.ACTIVE, carePlan.status)
        assertEquals(CarePlan.CarePlanIntent.PLAN, carePlan.intent)
        assertEquals("Child Routine visit Plan", carePlan.title)
        assertEquals(
          "This defines the schedule of care for patients under 5 years old",
          carePlan.description,
        )
        assertEquals(patient.logicalId, carePlan.subject.extractId())
        assertEquals(
          DateTimeType.now().value.makeItReadable(),
          carePlan.created.makeItReadable(),
        )
        assertEquals(
          patient.generalPractitionerFirstRep.extractId(),
          carePlan.author.extractId(),
        )
        assertEquals(
          DateTimeType.now().value.makeItReadable(),
          carePlan.period.start.makeItReadable(),
        )
        assertEquals(
          patient.birthDate.plusYears(5).makeItReadable(),
          carePlan.period.end.makeItReadable(),
        )
        // 60 - 2  = 58 TODO Fix issue with number of tasks updating relative to today's date
        assertTrue(carePlan.activityFirstRep.outcomeReference.isNotEmpty())

        resourcesSlot
          .filter { res -> res.resourceType == ResourceType.Task }
          .map { it as Task }
          .also { list -> assertTrue(list.isNotEmpty()) }
          .all { task ->
            // TODO
            task.status == TaskStatus.REQUESTED &&
              LocalDate.parse(task.executionPeriod.end.asYyyyMmDd()).let { localDate ->
                localDate.dayOfMonth == localDate.lengthOfMonth()
              }
          }

        val task1 = resourcesSlot[1] as Task
        assertEquals(TaskStatus.REQUESTED, task1.status)
        // TODO Fix issue with task start date updating relative to today's date
        assertTrue(task1.executionPeriod.start.makeItReadable().isNotEmpty())
        // Assert.assertEquals("01-Apr-2022", task1.executionPeriod.start.makeItReadable())
        // Assert.assertEquals("30-Apr-2022", task1.executionPeriod.end.makeItReadable())
      }
  }

  @Test
  @ExperimentalCoroutinesApi
  fun testGenerateCarePlanForGroup() = runTest {
    val planDefinition =
      "plans/household-routine-visit/plandefinition.json"
        .readFile()
        .decodeResourceFromString<PlanDefinition>()

    val group =
      "plans/household-routine-visit/sample/group.json".readFile().decodeResourceFromString<Group>()

    val structureMapScript = "plans/household-routine-visit/structure-map.txt".readFile()
    val structureMap =
      structureMapUtilities.parse(structureMapScript, "HHRoutineCarePlan").also {
        // TODO: IMP - The parser does not recognize the time unit i.e. months and prints as ''
        //  so use only months and that would have the unit replaced with 'months'
        println(it.encodeResourceToString().replace("''", "'month'"))
      }

    val resourcesSlot = mutableListOf<Resource>()
    val booleanSlot = slot<Boolean>()
    coEvery { defaultRepository.addOrUpdate(capture(booleanSlot), capture(resourcesSlot)) } just
      runs
    coEvery { fhirEngine.get<StructureMap>("hh") } returns structureMap
    coEvery { fhirEngine.search<CarePlan>(Search(ResourceType.CarePlan)) } returns listOf()

    fhirCarePlanGenerator
      .generateOrUpdateCarePlan(
        planDefinition,
        group,
        Bundle()
          .addEntry(
            Bundle.BundleEntryComponent().apply {
              resource = Encounter().apply { status = Encounter.EncounterStatus.FINISHED }
            },
          ),
      )!!
      .also { println(it.encodeResourceToString()) }
      .also { carePlan ->
        assertNotNull(UUID.fromString(carePlan.id))
        assertEquals(CarePlan.CarePlanStatus.ACTIVE, carePlan.status)
        assertEquals(CarePlan.CarePlanIntent.PLAN, carePlan.intent)
        assertEquals("HH Routine visit Plan", carePlan.title)
        assertEquals("sample plan", carePlan.description)
        assertEquals(group.logicalId, carePlan.subject.extractId())
        assertEquals(
          DateTimeType.now().value.makeItReadable(),
          carePlan.created.makeItReadable(),
        )
        assertNotNull(carePlan.period.start)
        assertTrue(carePlan.activityFirstRep.outcomeReference.isNotEmpty())

        resourcesSlot
          .filter { res -> res.resourceType == ResourceType.Task }
          .map { it as Task }
          .also { list -> assertTrue(list.isNotEmpty()) }
          .all { task ->
            task.status == TaskStatus.REQUESTED &&
              LocalDate.parse(task.executionPeriod.end.asYyyyMmDd()).let { localDate ->
                localDate.dayOfMonth == localDate.lengthOfMonth()
              }
          }

        val task1 = resourcesSlot[1] as Task
        assertEquals(TaskStatus.REQUESTED, task1.status)
      }
  }

  @Ignore("Throws stack overflow error")
  @Test
  @ExperimentalCoroutinesApi
  fun testGenerateCarePlanForHouseHold() = runTest {
    val planDefinition =
      "plans/household-wash-check-routine-visit/plandefinition.json"
        .readFile()
        .decodeResourceFromString<PlanDefinition>()

    val group =
      "plans/household-wash-check-routine-visit/sample/group.json"
        .readFile()
        .decodeResourceFromString<Group>()

    val structureMapScript = "plans/household-wash-check-routine-visit/structure-map.txt".readFile()
    val structureMap =
      structureMapUtilities.parse(structureMapScript, "HHRoutineCarePlan").also {
        // The parser does not recognize the time unit i.e. months and prints as '',
        // so use only months and that would have the unit replaced with 'months'
        it.encodeResourceToString().replace("''", "'month'")
      }

    val resourcesSlot = mutableListOf<Resource>()
    val booleanSlot = slot<Boolean>()
    coEvery { defaultRepository.addOrUpdate(capture(booleanSlot), capture(resourcesSlot)) } just
      runs
    coEvery { fhirEngine.get<StructureMap>("hh") } returns structureMap
    coEvery { fhirEngine.search<CarePlan>(Search(ResourceType.CarePlan)) } returns listOf()

    fhirCarePlanGenerator
      .generateOrUpdateCarePlan(
        planDefinition,
        group,
        Bundle()
          .addEntry(
            Bundle.BundleEntryComponent().apply {
              resource = Encounter().apply { status = Encounter.EncounterStatus.FINISHED }
            },
          ),
      )!!
      .also { println(it.encodeResourceToString()) }
      .also { carePlan ->
        assertNotNull(UUID.fromString(carePlan.id))
        assertEquals(CarePlan.CarePlanStatus.ACTIVE, carePlan.status)
        assertEquals(CarePlan.CarePlanIntent.PLAN, carePlan.intent)
        assertEquals("Household Routine WASH Check Plan", carePlan.title)
        assertEquals(
          "This defines the schedule of service for WASH Check on households",
          carePlan.description,
        )
        assertEquals(group.logicalId, carePlan.subject.extractId())
        assertEquals(
          DateTimeType.now().value.makeItReadable(),
          carePlan.created.makeItReadable(),
        )
        assertNotNull(carePlan.period.start)
        assertTrue(carePlan.activityFirstRep.outcomeReference.isNotEmpty())

        resourcesSlot
          .filter { res -> res.resourceType == ResourceType.Task }
          .map { it as Task }
          .also { list -> assertTrue(list.isNotEmpty() && list.size > 59 && list.size < 62) }
          .all { task ->
            task.status == TaskStatus.REQUESTED &&
              LocalDate.parse(task.executionPeriod.end.asYyyyMmDd()).let { localDate ->
                localDate.dayOfMonth == localDate.lengthOfMonth()
              }
          }

        val task1 = resourcesSlot[1] as Task
        assertEquals(TaskStatus.REQUESTED, task1.status)
      }
  }

  @Test
  @ExperimentalCoroutinesApi
  fun testGenerateCarePlanForSickChildOver2m() = runTest {
    val planDefinitionResources =
      loadPlanDefinitionResources("sick-child-visit", listOf("register-over2m"))
    val planDefinition = planDefinitionResources.planDefinition
    val patient = planDefinitionResources.patient.apply { this.birthDate = Date().plusMonths(-3) }
    val questionnaireResponses = planDefinitionResources.questionnaireResponses
    val resourcesSlot = planDefinitionResources.resourcesSlot

    fhirCarePlanGenerator
      .generateOrUpdateCarePlan(
        planDefinition,
        patient,
        Bundle()
          .addEntry(
            Bundle.BundleEntryComponent().apply { resource = questionnaireResponses.first() },
          ),
      )!!
      .also { println(it.encodeResourceToString()) }
      .also { carePlan ->
        assertCarePlan(carePlan, planDefinition, patient, Date(), Date().plusDays(7), 3)

        resourcesSlot
          .filter { res -> res.resourceType == ResourceType.Task }
          .map { it as Task }
          .also { assertEquals(4, it.size) } // 4 tasks generated, 3 followup 1 referral
          .also {
            assertTrue(it.all { task -> task.status == TaskStatus.READY })
            assertTrue(
              it.all { task -> task.`for`.reference == patient.asReference().reference },
            )
          }
          .also {
            it.last().let { task ->
              assertTrue(task.reasonReference.reference == "Questionnaire/132049")
              assertTrue(
                task.executionPeriod.end.asYyyyMmDd() == Date().plusMonths(1).asYyyyMmDd(),
              )
            }
          }
          .take(3)
          .run {
            assertTrue(this.all { it.reasonReference.reference == "Questionnaire/131898" })
            assertTrue(
              this.all { task ->
                task.executionPeriod.end.asYyyyMmDd() == Date().plusDays(7).asYyyyMmDd()
              },
            )
            assertTrue(
              this.all { it.basedOn.first().reference == carePlan.asReference().reference },
            )
            assertTrue(
              this.elementAt(0).executionPeriod.start.asYyyyMmDd() ==
                Date().plusDays(1).asYyyyMmDd(),
            )
            assertTrue(
              this.elementAt(1).executionPeriod.start.asYyyyMmDd() ==
                Date().plusDays(2).asYyyyMmDd(),
            )
            assertTrue(
              this.elementAt(2).executionPeriod.start.asYyyyMmDd() ==
                Date().plusDays(3).asYyyyMmDd(),
            )
          }
      }
  }

  @Test
  @ExperimentalCoroutinesApi
  fun testGenerateCarePlanForSickChildUnder2m() = runTest {
    val planDefinitionResources =
      loadPlanDefinitionResources("sick-child-visit", listOf("register-under2m"))
    val planDefinition = planDefinitionResources.planDefinition
    val patient = planDefinitionResources.patient.apply { this.birthDate = Date().plusMonths(-1) }
    val questionnaireResponses = planDefinitionResources.questionnaireResponses
    val resourcesSlot = planDefinitionResources.resourcesSlot

    installToIgManager(planDefinition)

    coEvery { fhirEngine.search<CarePlan>(Search(ResourceType.Library)) } returns listOf()
    coEvery { fhirEngine.search<PlanDefinition>(Search(ResourceType.PlanDefinition)) } returns
      listOf(
        SearchResult(
          resource = planDefinition,
          included = null,
          revIncluded = null,
        ),
      )

    fhirCarePlanGenerator
      .generateOrUpdateCarePlan(
        planDefinition = planDefinition,
        subject = patient,
        data =
          Bundle()
            .addEntry(
              Bundle.BundleEntryComponent().apply { resource = questionnaireResponses.first() },
            ),
        generateCarePlanWithWorkflowApi = false,
      )
      .also { carePlan ->
        assertNull(carePlan)

        resourcesSlot.forEach { println(it.encodeResourceToString()) }

        resourcesSlot.filterIsInstance<CarePlan>().let { assertTrue(it.isNotEmpty()) }

        resourcesSlot
          .filterIsInstance<Task>()
          .also { assertEquals(1, it.size) }
          .first()
          .let {
            assertTrue(it.status == TaskStatus.READY)
            assertTrue(it.basedOn.first().reference == planDefinition.asReference().reference)
            assertTrue(it.`for`.reference == patient.asReference().reference)
            assertTrue(it.executionPeriod.start.asYyyyMmDd() == Date().asYyyyMmDd())
          }
      }
  }

  @Test
  @ExperimentalCoroutinesApi
  fun testCompleteCarePlanForSickChildFollowup() = runTest {
    val plandefinition =
      "plans/sick-child-visit/plandefinition.json"
        .readFile()
        .decodeResourceFromString<PlanDefinition>()

    val patient =
      "plans/sick-child-visit/sample/patient.json".readFile().decodeResourceFromString<Patient>()
    val questionnaireResponse =
      "plans/sick-child-visit/sample/questionnaire-response-followup.json"
        .readFile()
        .decodeResourceFromString<QuestionnaireResponse>()

    val structureMapReferral =
      structureMapUtilities
        .parse("plans/structure-map-referral.txt".readFile(), "ReferralTask")
        .also { println(it.encodeResourceToString()) }

    val createdTasksSlot = mutableListOf<Resource>()
    val updatedTasksSlot = mutableListOf<Resource>()
    val booleanSlot = slot<Boolean>()
    coEvery {
      defaultRepository.addOrUpdate(
        capture(booleanSlot),
        capture(createdTasksSlot),
      )
    } just runs
    coEvery { defaultRepository.addOrUpdate(any(), capture(updatedTasksSlot)) } just runs
    coEvery { fhirEngine.update(any()) } just runs
    coEvery { fhirEngine.get<StructureMap>("528a8603-2e43-4a2e-a33d-1ec2563ffd3e") } returns
      structureMapReferral
        .encodeResourceToString()
        .decodeResourceFromString() // Ensure 'months' Duration code is correctly escaped
    coEvery { fhirEngine.search<CarePlan>(Search(ResourceType.CarePlan)) } returns
      listOf(
        SearchResult(
          resource =
            CarePlan().apply {
              instantiatesCanonical = listOf(CanonicalType(plandefinition.asReference().reference))
              addActivity().apply {
                this.addOutcomeReference().apply { this.reference = "Task/1111" }
              }
            },
          null,
          null,
        ),
      )
    coEvery { fhirEngine.get<Task>(any()) } returns
      Task().apply {
        id = "1111"
        status = TaskStatus.READY
      }

    fhirCarePlanGenerator
      .generateOrUpdateCarePlan(
        planDefinition = plandefinition,
        subject = patient,
        data =
          Bundle()
            .addEntry(
              Bundle.BundleEntryComponent().apply { resource = questionnaireResponse },
            ),
      )
      ?.also { carePlan: CarePlan ->
        assertEquals(CarePlan.CarePlanStatus.COMPLETED, carePlan.status)

        createdTasksSlot.forEach { resource -> println(resource.encodeResourceToString()) }

        createdTasksSlot
          .also { list -> assertTrue(list.size == 2) }
          .filter { resource -> resource.resourceType == ResourceType.Task }
          .map { resource -> resource as Task }
          .also { tasks ->
            tasks.first().let { task ->
              assertTrue(task.status == TaskStatus.READY)
              assertTrue(
                task.basedOn.first().reference == plandefinition.asReference().reference,
              )
              assertTrue(task.`for`.reference == patient.asReference().reference)
              assertTrue(task.executionPeriod.start.asYyyyMmDd() == Date().asYyyyMmDd())
            }
          }
      }
  }

  @Test
  @ExperimentalCoroutinesApi
  fun testUpdateCarePlanForSickChildReferral() = runTest {
    val plandefinition =
      "plans/sick-child-visit/plandefinition.json"
        .readFile()
        .decodeResourceFromString<PlanDefinition>()

    val patient =
      "plans/sick-child-visit/sample/patient.json"
        .readFile()
        .decodeResourceFromString<Patient>()
        .apply { this.birthDate = Date().plusMonths(-1).plusDays(-15) }

    val questionnaireResponse =
      "plans/sick-child-visit/sample/questionnaire-response-register-under2m.json"
        .readFile()
        .decodeResourceFromString<QuestionnaireResponse>()

    val structureMapScript = "plans/structure-map-referral.txt".readFile()
    val structureMap =
      structureMapUtilities.parse(structureMapScript, "ReferralTask").also {
        println(it.encodeResourceToString())
      }

    val resourcesSlot = mutableListOf<Resource>()
    val booleanSlot = slot<Boolean>()
    coEvery { defaultRepository.addOrUpdate(capture(booleanSlot), capture(resourcesSlot)) } just
      runs
    coEvery { fhirEngine.get<StructureMap>("528a8603-2e43-4a2e-a33d-1ec2563ffd3e") } returns
      structureMap
        .encodeResourceToString()
        .decodeResourceFromString() // Ensure 'months' Duration code is correctly escaped

    coEvery { fhirEngine.search<CarePlan>(Search(ResourceType.CarePlan)) } returns listOf()

    fhirCarePlanGenerator
      .generateOrUpdateCarePlan(
        plandefinition,
        patient,
        Bundle()
          .addEntry(
            Bundle.BundleEntryComponent().apply { resource = questionnaireResponse },
          ),
      )
      .also { _ ->
        resourcesSlot.forEach { println(it.encodeResourceToString()) }

        resourcesSlot
          .filter { it.resourceType == ResourceType.Task }
          .map { it as Task }
          .also { list -> assertTrue(list.size == 1) }
          .also { list ->
            list.first().let {
              assertTrue(it.status == TaskStatus.READY)
              assertTrue(it.basedOn.first().reference == plandefinition.asReference().reference)
              assertTrue(it.`for`.reference == patient.asReference().reference)
              assertTrue(it.executionPeriod.start.asYyyyMmDd() == Date().asYyyyMmDd())
            }
          }
      }
  }

  @Test
  @ExperimentalCoroutinesApi
  fun `generateOrUpdateCarePlan should generate full careplan for 8 visits when lmp is today`() =
    runTest {
      val planDefinitionResources = loadPlanDefinitionResources("anc-visit", listOf("register"))
      val planDefinition = planDefinitionResources.planDefinition
      val patient = planDefinitionResources.patient
      val questionnaireResponses = planDefinitionResources.questionnaireResponses
      val resourcesSlot = planDefinitionResources.resourcesSlot

      // start of plan is lmp date | 8 tasks to be generated for each month ahead i.e. lmp + 9m
      val lmp = Date()

      questionnaireResponses
        .first()
        .find("245679f2-6172-456e-8ff3-425f5cea3243")!!
        .answer
        .first()
        .value = DateType(lmp)

      fhirCarePlanGenerator
        .generateOrUpdateCarePlan(
          planDefinition,
          patient,
          Bundle()
            .addEntry(
              Bundle.BundleEntryComponent().apply { resource = questionnaireResponses.first() },
            ),
        )!!
        .also { println(it.encodeResourceToString()) }
        .also { carePlan ->
          assertCarePlan(
            carePlan,
            planDefinition,
            patient,
            lmp,
            lmp.plusMonths(9),
            8,
          ) // 8 visits for each month of ANC

          repeat(resourcesSlot.size) { println(carePlan.encodeResourceToString()) }

          assertTrue(resourcesSlot.first() is CarePlan)

          resourcesSlot
            .filter { res -> res.resourceType == ResourceType.Task }
            .map { it as Task }
            .also { assertEquals(9, it.size) } // 8 for visit, 1 for referral
            .also {
              assertTrue(it.all { task -> task.status == TaskStatus.READY })
              assertTrue(
                it.all { task -> task.`for`.reference == patient.asReference().reference },
              )
            }
            // last task is referral
            .also {
              it.last().let { task ->
                assertTrue(task.reasonReference.reference == "Questionnaire/132049")
                assertTrue(
                  task.executionPeriod.end.asYyyyMmDd() == Date().plusMonths(1).asYyyyMmDd(),
                )
              }
            }
            // first 8 tasks are anc visit for each month start
            .take(8)
            .run {
              assertTrue(this.all { it.reasonReference.reference == "Questionnaire/132155" })
              assertTrue(
                this.all { it.basedOn.first().reference == carePlan.asReference().reference },
              )

              // first visit is lmp plus 1 month and subsequent visit are every month after
              // that until
              // delivery
              var pregnancyStart: BaseDateTimeType = DateTimeType(lmp.clone() as Date)
              this.forEach { task ->
                fhirPathEngine
                  .evaluate(pregnancyStart, "\$this + 1 'month'")
                  .firstOrNull()
                  ?.dateTimeValue()
                  ?.let { result -> pregnancyStart = result }
                assertEquals(
                  pregnancyStart.valueToString(),
                  DateTimeType(task.executionPeriod.start).valueToString(),
                )
              }
            }
        }
    }

  @Test
  @ExperimentalCoroutinesApi
  fun `generateOrUpdateCarePlan should generate careplan for 5 visits when lmp has passed 3 months`() =
    runTest(timeout = 120.seconds) {
      val monthToDateMap = mutableMapOf<Int, Map<Int, Int>>()

      for (i in 1..12) {
        monthToDateMap[i] =
          mapOf(
            1 to 2023,
            15 to 2023,
            when (i) {
              4,
              6,
              9,
              11, -> 30 to 2023
              2 -> 28 to 2023
              else -> 31 to 2023
            },
          )
      }

      // Add leap year
      monthToDateMap[2] = monthToDateMap[2]!!.plus(29 to 2020)

      monthToDateMap.forEach { entry ->
        entry.value.forEach { innerEntry ->
          val dateToday: Date =
            Date.from(
              LocalDate.of(innerEntry.value, entry.key, innerEntry.key)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant(),
            )

          val planDefinitionResources = loadPlanDefinitionResources("anc-visit", listOf("register"))
          val planDefinition = planDefinitionResources.planDefinition
          val patient = planDefinitionResources.patient
          val questionnaireResponses = planDefinitionResources.questionnaireResponses
          val resourcesSlot = planDefinitionResources.resourcesSlot

          // start of plan is lmp date | 8 tasks to be generated for each month ahead i.e. lmp +
          // 9m
          // anc registered late so skip the tasks which passed due date

          val lmp = DateType(Date()).apply { add(Calendar.MONTH, -4) }

          questionnaireResponses
            .first()
            .find("245679f2-6172-456e-8ff3-425f5cea3243")!!
            .answer
            .first()
            .value = lmp

          fhirCarePlanGenerator
            .generateOrUpdateCarePlan(
              planDefinition,
              patient,
              Bundle()
                .addEntry(
                  Bundle.BundleEntryComponent().apply { resource = questionnaireResponses.first() },
                ),
            )!!
            .apply { created = dateToday }
            .also { println(it.encodeResourceToString()) }
            .also { resourcesSlot.forEach { println(it.encodeResourceToString()) } }
            .also { carePlan ->
              assertCarePlan(
                carePlan,
                planDefinition,
                patient,
                lmp.value,
                fhirCarePlanGenerator
                  .evaluateToDate(DateTimeType(lmp.value), "\$this + 9 'month'")!!
                  .value,
                5,
                dateToday,
              ) // 5 visits for each month of ANC

              resourcesSlot
                .filter { res -> res.resourceType == ResourceType.Task }
                .map { it as Task }
                .also { assertEquals(6, it.size) } // 5 for visit, 1 for referral
                .also {
                  assertTrue(it.all { task -> task.status == TaskStatus.READY })
                  assertTrue(
                    it.all { task -> task.`for`.reference == patient.asReference().reference },
                  )
                }
                // first 5 tasks are anc visit for each month of pregnancy
                .take(5)
                .run {
                  assertTrue(
                    this.all { it.reasonReference.reference == "Questionnaire/132155" },
                  )
                  assertTrue(
                    this.all { it.basedOn.first().reference == carePlan.asReference().reference },
                  )

                  // first visit is lmp plus 1 month and subsequent visit are every month
                  // after
                  // that until
                  // delivery
                  // skip tasks for past 3 months of late registration

                  val ancStart =
                    fhirCarePlanGenerator
                      .evaluateToDate(
                        DateTimeType(lmp.value),
                        "\$this + 3 'month'",
                      )!!
                      .value

                  val expectedAssertionDates: MutableList<Date> =
                    mutableListOf(
                      fhirCarePlanGenerator
                        .evaluateToDate(
                          DateTimeType(ancStart),
                          "\$this + 1 'month'",
                        )!!
                        .value,
                    )

                  for (index in 1 until this.size) {
                    expectedAssertionDates.add(
                      fhirCarePlanGenerator
                        .evaluateToDate(
                          DateTimeType(expectedAssertionDates.last()),
                          "\$this + 1 'month'",
                        )!!
                        .value,
                    )
                  }

                  this.forEachIndexed { index, task ->
                    assertEquals(
                      expectedAssertionDates[index].asYyyyMmDd(),
                      task.executionPeriod.start.asYyyyMmDd(),
                    )
                  }
                }
            }
        }
      }
    }

  @Test
  @ExperimentalCoroutinesApi
  fun `generateOrUpdateCarePlan should generate careplan for next visit when ondemand task is required`() =
    runTest {
      val planDefinitionResources =
        loadPlanDefinitionResources("anc-visit-ondemand", listOf("register"))
      val planDefinition = planDefinitionResources.planDefinition
      val patient = planDefinitionResources.patient
      val questionnaireResponses = planDefinitionResources.questionnaireResponses
      val resourcesSlot = planDefinitionResources.resourcesSlot

      // start of plan is lmp date | 8 tasks to be generated for each month ahead i.e. lmp + 9m
      // anc registered late so skip the tasks which passed due date
      val lmp = Date().plusMonths(-4)

      questionnaireResponses
        .first()
        .find("245679f2-6172-456e-8ff3-425f5cea3243")!!
        .answer
        .first()
        .value = DateType(lmp)

      fhirCarePlanGenerator
        .generateOrUpdateCarePlan(
          planDefinition,
          patient,
          Bundle()
            .addEntry(
              Bundle.BundleEntryComponent().apply { resource = questionnaireResponses.first() },
            ),
        )!!
        .also { println(it.encodeResourceToString()) }
        .also { carePlan ->
          assertCarePlan(
            carePlan,
            planDefinition,
            patient,
            lmp,
            lmp.plusMonths(9),
            1,
          ) // 1 visits for next month of ANC

          resourcesSlot.forEach { println(it.encodeResourceToString()) }
          assertEquals(1, carePlan.activityFirstRep.outcomeReference.size)

          resourcesSlot
            .filter { res -> res.resourceType == ResourceType.Task }
            .map { it as Task }
            .also { assertEquals(2, it.size) } // 1 for visit, 1 for referral
            .also { tasks ->
              assertTrue(tasks.all { it.status == TaskStatus.READY })
              assertTrue(tasks.all { it.`for`.reference == patient.asReference().reference })
            }
            // first 5 tasks are anc visit for each month of pregnancy
            .take(1)
            .run {
              assertTrue(this.all { it.reasonReference.reference == "Questionnaire/132155" })
              assertTrue(
                this.all { it.basedOn.first().reference == carePlan.asReference().reference },
              )

              this.forEachIndexed { _, task ->
                assertEquals(
                  Date().plusMonths(1).asYyyyMmDd(),
                  task.executionPeriod.start.asYyyyMmDd(),
                )
              }
            }
        }
    }

  @Test
  fun `Generate CarePlan should generate child immunization schedule`() =
    runBlockingOnWorkerThread {
      val planDefinitionResources =
        loadPlanDefinitionResources("child-immunization-schedule", listOf("register-temp"))
      val planDefinition = planDefinitionResources.planDefinition
      val patient = planDefinitionResources.patient
      val questionnaireResponses = planDefinitionResources.questionnaireResponses
      val resourcesSlot = planDefinitionResources.resourcesSlot
      val vaccines = makeVaccinesMapForPatient(patient)

      installToIgManager(planDefinition)
      installToIgManager(planDefinitionResources.structureMap)
      importToFhirEngine(patient)
      questionnaireResponses.forEach { importToFhirEngine(it) }

      fhirCarePlanGenerator
        .generateOrUpdateCarePlan(
          planDefinition = planDefinition,
          subject = patient,
          data =
            Bundle()
              .addEntry(Bundle.BundleEntryComponent().apply { resource = patient })
              .addEntry(
                Bundle.BundleEntryComponent().apply { resource = questionnaireResponses.first() },
              ),
        )!!
        .also { println(it.encodeResourceToString()) }
        .also { carePlan ->
          assertCarePlan(
            carePlan,
            planDefinition,
            patient,
            patient.birthDate,
            patient.birthDate.plusDays(4017),
            20,
          )
          resourcesSlot
            .filter { res -> res.resourceType == ResourceType.Task }
            .map { it as Task }
            .also { tasks ->
              assertTrue(tasks.all { it.status == TaskStatus.REQUESTED })
              assertTrue(
                tasks.all {
                  it.reasonReference.reference ==
                    "Questionnaire/9b1aa23b-577c-4fb2-84e3-591e6facaf82"
                },
              )
              assertTrue(
                tasks.all {
                  it.code.codingFirstRep.display ==
                    "Administration of vaccine to produce active immunity (procedure)" &&
                    it.code.codingFirstRep.code == "33879002"
                },
              )
              assertTrue(
                tasks.all {
                  it.description.contains(
                    it.reasonCode.text,
                    true,
                  )
                },
              )
              assertTrue(
                tasks.all {
                  it.`for`.reference == questionnaireResponses.first().subject.reference
                },
              )
              assertTrue(
                tasks.all { it.basedOnFirstRep.reference == carePlan.asReference().reference },
              )
              vaccines.forEach { vaccine ->
                val task = tasks.find { it.description.startsWith(vaccine.key) }!!

                println(task.encodeResourceToString())

                assertEquals(
                  "${vaccine.key}'s vaccine start period",
                  task.executionPeriod.start.asYyyyMmDd(),
                  vaccine.value.asYyyyMmDd(),
                )
              }
            }
        }
      Unit
    }

  @Test
  @ExperimentalCoroutinesApi
  fun `Generate CarePlan should generate covid immunization schedule`() = runTest {
    val planDefinitionResources =
      loadPlanDefinitionResources("covid-19-immunization", listOf("covid"))
    val planDefinition = planDefinitionResources.planDefinition
    val patient = planDefinitionResources.patient
    val questionnaireResponses = planDefinitionResources.questionnaireResponses
    val resourcesSlot = planDefinitionResources.resourcesSlot
    val referenceDate =
      questionnaireResponses.first().find("vaccine_date")!!.answerFirstRep.valueDateType.value
    val vaccines =
      mapOf(
        "AstraZeneca 2" to referenceDate.plusDays(70),
        "AstraZeneca Booster" to referenceDate.plusDays(180),
      )

    fhirCarePlanGenerator
      .generateOrUpdateCarePlan(
        planDefinition,
        patient,
        Bundle()
          .addEntry(Bundle.BundleEntryComponent().apply { resource = patient })
          .addEntry(
            Bundle.BundleEntryComponent().apply { resource = questionnaireResponses.first() },
          ),
      )!!
      .also { carePlan ->
        assertCarePlan(carePlan, planDefinition, patient, referenceDate, null, 2)

        resourcesSlot
          .filter { res -> res.resourceType == ResourceType.Task }
          .map { it as Task }
          .also { tasks ->
            assertTrue(tasks.all { it.status == TaskStatus.REQUESTED })
            assertTrue(
              tasks.all {
                it.reasonReference.reference == "Questionnaire/e8572c86-065d-11ee-be56-0242ac120002"
              },
            )
            assertTrue(
              tasks.all {
                it.code.codingFirstRep.display == "SARS-CoV-2 vaccination" &&
                  it.code.codingFirstRep.code == "840534001"
              },
            )
            assertTrue(tasks.all { it.description.contains(it.reasonCode.text, true) })
            assertTrue(
              tasks.all { it.`for`.reference == questionnaireResponses.first().subject.reference },
            )
            assertTrue(
              tasks.all { it.basedOnFirstRep.reference == carePlan.asReference().reference },
            )
            vaccines.forEach { vaccine ->
              val task = tasks.find { it.description.startsWith(vaccine.key) }!!
              assertTrue(task.executionPeriod.start.asYyyyMmDd() == vaccine.value.asYyyyMmDd())
            }
          }
      }
  }

  @Test
  @ExperimentalCoroutinesApi
  fun `test generateOrUpdateCarePlan returns success even when evaluatedValue is null`() =
    runBlockingOnWorkerThread {
      val planDefinitionResources =
        loadPlanDefinitionResources("child-immunization-schedule", listOf("register-temp"))
      val questionnaireResponses = planDefinitionResources.questionnaireResponses
      val planDefinition = planDefinitionResources.planDefinition
      val patient = planDefinitionResources.patient
      val data =
        Bundle()
          .addEntry(Bundle.BundleEntryComponent().apply { resource = patient })
          .addEntry(
            Bundle.BundleEntryComponent().apply { resource = questionnaireResponses.first() },
          )

      val dynamicValue = planDefinition.action.first().dynamicValue
      val expressionValue = dynamicValue.find { it.expression.expression == "%rootResource.title" }

      installToIgManager(planDefinitionResources.planDefinition)

      // Update the value of the expression
      expressionValue?.let { it.expression = Expression().apply { expression = "dummyExpression" } }

      // call the method under test and get the result
      val result =
        fhirCarePlanGenerator.generateOrUpdateCarePlan(
          planDefinition,
          patient,
          data,
          generateCarePlanWithWorkflowApi = true,
        )

      // assert that the result is not null
      assertNotNull(result)
    }

  @Test
  @ExperimentalCoroutinesApi
  fun `Generate CarePlan should generate child immunization schedule with correct groupIdentifier value`() =
    runTest {
      val planDefinitionResources =
        loadPlanDefinitionResources("child-immunization-schedule", listOf("register-temp"))
      val planDefinition = planDefinitionResources.planDefinition
      val patient = planDefinitionResources.patient
      val questionnaireResponses = planDefinitionResources.questionnaireResponses
      val resourcesSlot = planDefinitionResources.resourcesSlot
      val vaccines = makeVaccinesMapForPatient(patient)

      fhirCarePlanGenerator
        .generateOrUpdateCarePlan(
          planDefinition,
          patient,
          Bundle()
            .addEntry(Bundle.BundleEntryComponent().apply { resource = patient })
            .addEntry(
              Bundle.BundleEntryComponent().apply { resource = questionnaireResponses.first() },
            ),
        )!!
        .also { carePlan ->
          assertCarePlan(
            carePlan,
            planDefinition,
            patient,
            patient.birthDate,
            patient.birthDate.plusDays(4017),
            20,
          )
          resourcesSlot
            .filter { res -> res.resourceType == ResourceType.Task }
            .map { it as Task }
            .also { tasks ->
              assertTrue(tasks.all { it.status == TaskStatus.REQUESTED })
              assertTrue(
                tasks.all {
                  it.reasonReference.reference ==
                    "Questionnaire/9b1aa23b-577c-4fb2-84e3-591e6facaf82"
                },
              )
              assertTrue(
                tasks.all {
                  it.code.codingFirstRep.display ==
                    "Administration of vaccine to produce active immunity (procedure)" &&
                    it.code.codingFirstRep.code == "33879002"
                },
              )
              assertTrue(
                tasks.all {
                  it.description.contains(
                    it.reasonCode.text,
                    true,
                  )
                },
              )
              assertTrue(
                tasks.all {
                  it.`for`.reference == questionnaireResponses.first().subject.reference
                },
              )
              assertTrue(
                tasks.all { it.basedOnFirstRep.reference == carePlan.asReference().reference },
              )
              vaccines.forEach { vaccine ->
                val task = tasks.find { it.description.startsWith(vaccine.key) }!!
                when (vaccine.key) {
                  "BCG" -> assertEquals(task.groupIdentifier.value, "0_d")
                  "OPV 0" -> assertEquals(task.groupIdentifier.value, "0_d")
                  "PENTA 1" -> assertEquals(task.groupIdentifier.value, "6_wk")
                  "OPV 1" -> assertEquals(task.groupIdentifier.value, "6_wk")
                  "PCV 1" -> assertEquals(task.groupIdentifier.value, "6_wk")
                  "ROTA 1" -> assertEquals(task.groupIdentifier.value, "6_wk")
                  "PENTA 2" -> assertEquals(task.groupIdentifier.value, "10_wk")
                  "OPV 2" -> assertEquals(task.groupIdentifier.value, "10_wk")
                  "PCV 2" -> assertEquals(task.groupIdentifier.value, "10_wk")
                  "ROTA 2" -> assertEquals(task.groupIdentifier.value, "10_wk")
                  "PENTA 3" -> assertEquals(task.groupIdentifier.value, "14_wk")
                  "OPV 3" -> assertEquals(task.groupIdentifier.value, "14_wk")
                  "PCV 3" -> assertEquals(task.groupIdentifier.value, "14_wk")
                  "IPV" -> assertEquals(task.groupIdentifier.value, "14_wk")
                  "MEASLES 1" -> assertEquals(task.groupIdentifier.value, "9_mo")
                  "MEASLES 2" -> assertEquals(task.groupIdentifier.value, "15_mo")
                  "YELLOW FEVER" ->
                    assertEquals(
                      task.groupIdentifier.value,
                      "9_mo",
                    )
                  "TYPHOID" -> assertEquals(task.groupIdentifier.value, "9_mo")
                  "HPV 1" -> assertEquals(task.groupIdentifier.value, "108_mo")
                  "HPV 2" -> assertEquals(task.groupIdentifier.value, "114_mo")
                }
              }
            }
        }
    }

  @Test
  @ExperimentalCoroutinesApi
  fun `Generate CarePlan should generate child immunization schedule with pre-req def and expiry timing`() =
    runTest {
      val planDefinitionResources =
        loadPlanDefinitionResources("child-immunization-schedule", listOf("register-temp"))
      val planDefinition = planDefinitionResources.planDefinition
      val patient = planDefinitionResources.patient
      val questionnaireResponses = planDefinitionResources.questionnaireResponses
      val resourcesSlot = planDefinitionResources.resourcesSlot
      fhirCarePlanGenerator
        .generateOrUpdateCarePlan(
          planDefinition,
          patient,
          Bundle()
            .addEntry(Bundle.BundleEntryComponent().apply { resource = patient })
            .addEntry(
              Bundle.BundleEntryComponent().apply { resource = questionnaireResponses.first() },
            ),
        )!!
        .also { println(it.encodeResourceToString()) }
        .also { carePlan ->
          assertCarePlan(
            carePlan,
            planDefinition,
            patient,
            patient.birthDate,
            patient.birthDate.plusDays(4017),
            20,
          )

          resourcesSlot
            .filter { res -> res.resourceType == ResourceType.Task }
            .map {
              println(it.encodeResourceToString())
              it as Task
            }
            .also { tasks ->
              assertTrue(tasks.all { it.input.firstOrNull()?.value.valueToString() == "28" })
              assertTrue(
                tasks.all {
                  it.input.firstOrNull()?.type?.coding!![0].display == "Dependent (qualifier value)"
                },
              )
              assertTrue(
                tasks.all { it.input.firstOrNull()?.type?.coding!![0].code == "371154000" },
              )

              /*assertTrue(
                tasks.all {
                  it.restriction.period.start.asYyyyMmDd() == patient.birthDate.asYyyyMmDd()
                }
              )*/
              val opv2 = tasks.first { it.description.contains("OPV 2") }
              val opv1 = tasks.first { it.description.contains("OPV 1") }
              val pcv3 = tasks.first { it.description.contains("PCV 3") }
              val pcv2 = tasks.first { it.description.contains("PCV 2") }
              val bcg = tasks.first { it.description.contains("BCG") }

              assertEquals(
                opv2.partOf.first().reference.toString(),
                opv1.referenceValue(),
              )
              assertEquals(
                pcv3.partOf.first().reference.toString(),
                pcv2.referenceValue(),
              )
              assertTrue(bcg.partOf.isEmpty())
              val c = Calendar.getInstance()
              c.time = opv1.restriction?.period?.start!!
              c.add(Calendar.YEAR, 5)
              c.add(Calendar.DATE, -1)
              assertEquals(opv1.restriction?.period?.end, c.time)
            }
        }
    }

  @Test
  @ExperimentalCoroutinesApi
  fun `Generate CarePlan should generate disease followup schedule`() = runTest {
    val planDefinition =
      "plans/disease-followup/plan-definition.json"
        .readFile()
        .decodeResourceFromString<PlanDefinition>()

    val patient =
      "plans/disease-followup/patient.json".readFile().decodeResourceFromString<Patient>()
    val diseaseFollowUpQuestionnaireResponseString =
      "plans/disease-followup/questionnaire-response.json"
        .readFile()
        .decodeResourceFromString<QuestionnaireResponse>()

    val structureMapScript = "plans/disease-followup/structure-map.txt".readFile()
    val structureMap = structureMapUtilities.parse(structureMapScript, "eCBIS Child Immunization")

    val resourcesSlot = mutableListOf<Resource>()
    val booleanSlot = slot<Boolean>()
    coEvery { defaultRepository.addOrUpdate(capture(booleanSlot), capture(resourcesSlot)) } just
      runs
    coEvery { fhirEngine.get<StructureMap>("63752b18-9f0e-48a7-9a21-d3714be6309a") } returns
      structureMap
    coEvery { fhirEngine.search<CarePlan>(Search(ResourceType.CarePlan)) } returns emptyList()
    fhirCarePlanGenerator
      .generateOrUpdateCarePlan(
        planDefinition,
        patient,
        Bundle()
          .addEntry(Bundle.BundleEntryComponent().apply { resource = patient })
          .addEntry(
            Bundle.BundleEntryComponent().apply {
              resource = diseaseFollowUpQuestionnaireResponseString
            },
          ),
      )!!
      .also { println(it.encodeResourceToString()) }
      .also { carePlan ->
        assertNotNull(UUID.fromString(carePlan.id))
        assertEquals(CarePlan.CarePlanStatus.ACTIVE, carePlan.status)
        assertEquals(CarePlan.CarePlanIntent.PLAN, carePlan.intent)

        assertEquals("Disease Follow Up", carePlan.title)
        assertEquals(
          "This is a follow up for patient's marked with the following diseases HIV, TB, Mental Health & CM-NTD",
          carePlan.description,
        )
        assertEquals(patient.logicalId, carePlan.subject.extractId())
        assertEquals(
          DateTimeType.now().value.makeItReadable(),
          carePlan.created.makeItReadable(),
        )
        assertEquals(
          patient.generalPractitionerFirstRep.extractId(),
          carePlan.author.extractId(),
        )
        assertTrue(carePlan.activityFirstRep.outcomeReference.isNotEmpty())
        coEvery {
          defaultRepository.addOrUpdate(
            capture(booleanSlot),
            capture(resourcesSlot),
          )
        }
        resourcesSlot
          .filter { res -> res.resourceType == ResourceType.Task }
          .map { it as Task }
          .also { list -> assertTrue(list.isNotEmpty()) }
          .also { println(it.last().encodeResourceToString()) }
          .all { task ->
            task.status == TaskStatus.INPROGRESS &&
              LocalDate.parse(task.executionPeriod.end.asYyyyMmDd()).let { localDate ->
                localDate.dayOfMonth == localDate.lengthOfMonth()
              }
          }
      }
  }

  @Test
  @ExperimentalCoroutinesApi
  fun `transitionTaskTo should update task status`() = runTest {
    coEvery { fhirEngine.get(ResourceType.Task, "12345") } returns Task().apply { id = "12345" }
    coEvery { defaultRepository.addOrUpdate(any(), any()) } just Runs
    coEvery { fhirEngine.search<Task>(any()) } returns emptyList()

    fhirCarePlanGenerator.updateTaskDetailsByResourceId("12345", TaskStatus.COMPLETED)

    val task = slot<Task>()
    coVerify { defaultRepository.addOrUpdate(any(), capture(task)) }

    assertEquals(TaskStatus.COMPLETED, task.captured.status)
  }

  @Test
  fun testConditionallyUpdateCarePlanStatusTerminatesWhenNoCarePlanIsFound() {
    val planDefinitions = listOf("plandef-1")
    val eventWorkflow =
      EventWorkflow(
        triggerConditions =
          listOf(
            EventTriggerCondition(
              eventResourceId = "carePlan1",
              conditionalFhirPathExpressions = listOf("Patient.active"),
            ),
          ),
      )
    val questionnaireConfig =
      QuestionnaireConfig(
        id = "id-1",
        planDefinitions = planDefinitions,
        eventWorkflows = listOf(eventWorkflow),
      )
    val patient =
      Patient().apply {
        id = "patient-1"
        active = true
      }
    val bundle = Bundle().apply { addEntry().resource = patient }
    coEvery {
      fhirEngine.batchedSearch<CarePlan> {
        filter(
          CarePlan.INSTANTIATES_CANONICAL,
          { value = "${PlanDefinition().fhirType()}/plandef-1" },
        )
        filter(CarePlan.SUBJECT, { value = patient.referenceValue() })
      }
    } returns listOf()

    runBlocking {
      fhirCarePlanGenerator.conditionallyUpdateResourceStatus(
        questionnaireConfig = questionnaireConfig,
        subject = patient,
        bundle = bundle,
      )
    }

    coVerify(exactly = 0) { fhirEngine.get(any(), any()) }
    coVerify(exactly = 0) { fhirEngine.update(any()) }
  }

  @Test
  fun testConditionallyUpdateCarePlanStatusDoesNotUpdateCarePlanWhenFhirPathResourceExpressionFails() {
    val planDefinitions = listOf("plandef-1")
    val eventWorkflow =
      EventWorkflow(
        triggerConditions =
          listOf(
            EventTriggerCondition(
              eventResourceId = "carePlan1",
              conditionalFhirPathExpressions = listOf("Patient.active"),
            ),
          ),
      )
    val questionnaireConfig =
      QuestionnaireConfig(
        id = "id-1",
        planDefinitions = planDefinitions,
        eventWorkflows = listOf(eventWorkflow),
      )
    val patient =
      Patient().apply {
        id = "patient-1"
        active = false
      }
    val carePlan =
      CarePlan().apply {
        id = "careplan-1"
        status = CarePlan.CarePlanStatus.ACTIVE
      }
    val bundle = Bundle().apply { addEntry().resource = patient }
    coEvery {
      fhirEngine.batchedSearch<CarePlan> {
        filter(
          CarePlan.INSTANTIATES_CANONICAL,
          { value = "${PlanDefinition().fhirType()}/plandef-1" },
        )
        filter(CarePlan.SUBJECT, { value = patient.referenceValue() })
      }
    } returns listOf(SearchResult(resource = carePlan, null, null))
    coEvery { fhirEngine.update(any()) } just runs

    runBlocking {
      fhirCarePlanGenerator.conditionallyUpdateResourceStatus(
        questionnaireConfig = questionnaireConfig,
        subject = patient,
        bundle = bundle,
      )
    }

    coVerify(exactly = 0) { fhirEngine.update(any()) }
  }

  @Test
  fun testConditionallyUpdateCarePlanStatusCallsDefaultRepositoryUpdateResourcesRecursively() {
    val carePlanId = "carePlan1"
    val patientId = "patient-1"
    val planDefinitions = listOf("plandef-1")
    val eventWorkflow =
      EventWorkflow(
        triggerConditions =
          listOf(
            EventTriggerCondition(
              eventResourceId = "carePlan1",
              conditionalFhirPathExpressions = listOf("Patient.active"),
            ),
          ),
        eventResources =
          listOf(
            ResourceConfig(
              resource = ResourceType.CarePlan,
              planDefinitions = planDefinitions,
              id = carePlanId,
            ),
          ),
      )
    val questionnaireConfig =
      QuestionnaireConfig(
        id = "id-1",
        planDefinitions = planDefinitions,
        eventWorkflows = listOf(eventWorkflow),
      )
    val patient =
      Patient().apply {
        id = patientId
        active = true
      }
    val bundle = Bundle().apply { addEntry().resource = patient }
    coEvery { defaultRepository.updateResourcesRecursively(any(), any(), any()) } just runs

    runBlocking {
      fhirCarePlanGenerator.conditionallyUpdateResourceStatus(
        questionnaireConfig = questionnaireConfig,
        subject = patient,
        bundle = bundle,
      )
    }

    val eventResourceConfigSlot = slot<ResourceConfig>()
    val resourceSLot = slot<Resource>()
    val eventWorkflowSlot = slot<EventWorkflow>()

    coVerify {
      defaultRepository.updateResourcesRecursively(
        capture(eventResourceConfigSlot),
        capture(resourceSLot),
        capture(eventWorkflowSlot),
      )
    }
    assertEquals(carePlanId, eventResourceConfigSlot.captured.id)
    assertEquals(patientId, resourceSLot.captured.id)
  }

  @Test
  fun `updateDependentTaskDueDate should have no dependent tasks`() {
    coEvery { fhirEngine.search<Task>(any()) } returns emptyList()
    opv0.apply {
      status = TaskStatus.REQUESTED
      partOf = emptyList()
    }

    val updatedTask = runBlocking { opv0.updateDependentTaskDueDate(defaultRepository) }

    coVerify { fhirEngine.search<Task>(any()) }
    assertEquals("650203d2-f327-4eb4-a9fd-741e0ce29c3f", opv1.logicalId)
    assertEquals(opv0, updatedTask)
  }

  @Test
  fun `updateDependentTaskDueDate should update dependent task without output`() {
    coEvery { fhirEngine.search<Task>(any()) } returns emptyList()
    coEvery {
      fhirEngine.get(
        ResourceType.Task,
        "650203d2-f327-4eb4-a9fd-741e0ce29c3f",
      )
    } returns
      opv0.apply {
        status = TaskStatus.READY
        partOf = listOf(Reference("Task/650203d2-f327-4eb4-a9fd-741e0ce29c3f"))
      }
    opv1.apply {
      output = listOf()
      executionPeriod =
        Period().apply {
          start = Date()
          end = Date()
        }
    }
    coEvery { defaultRepository.loadResource(Reference(opv0.partOf.first().reference)) } returns
      opv1
    coEvery { defaultRepository.loadResource(Reference(opv0.partOf.first().reference)) } returns
      opv1

    val updatedTask = runBlocking { opv0.updateDependentTaskDueDate(defaultRepository) }

    coVerify { fhirEngine.search<Task>(any()) }
    assertEquals(opv0, updatedTask)
  }

  @Test
  fun `updateDependentTaskDueDate should run with dependent task, with output but no execution period start date`() {
    coEvery {
      fhirEngine.get(
        ResourceType.Task,
        "650203d2-f327-4eb4-a9fd-741e0ce29c3f",
      )
    } returns
      opv0.apply {
        status = TaskStatus.INPROGRESS
        output = listOf()
      }
    coEvery { fhirEngine.search<Task>(any()) } returns listOf()

    val updatedTask = runBlocking { opv0.updateDependentTaskDueDate(defaultRepository) }

    coVerify { fhirEngine.search<Task>(any()) }
    assertEquals(opv0, updatedTask)
  }

  @Test
  fun `updateDependentTaskDueDate with dependent task with output, execution period start date, and encounter part of reference that is null`() {
    coEvery { fhirEngine.search<Task>(any()) } returns emptyList()
    coEvery {
      fhirEngine.get(
        ResourceType.Task,
        "650203d2-f327-4eb4-a9fd-741e0ce29c3f",
      )
    } returns
      opv1.apply {
        status = TaskStatus.INPROGRESS
        output =
          listOf(
            TaskOutputComponent(
              CodeableConcept(),
              StringType(
                "{\n" +
                  "          \"reference\": \"Encounter/14e2ae52-32fc-4507-8736-1177cdaafe90\"\n" +
                  "        }",
              ),
            ),
          )
      }
    coEvery {
      defaultRepository.loadResource(
        Reference(
          Json.decodeFromString<JsonObject>(opv1.output.first().value.toString())[REFERENCE]
            ?.jsonPrimitive
            ?.content,
        ),
      )
    } returns encounter.apply { partOf = null }
    coEvery { defaultRepository.loadResource(Reference(ArgumentMatchers.anyString())) } returns
      Immunization()
    val updatedTask = runBlocking { opv0.updateDependentTaskDueDate(defaultRepository) }
    assertEquals(opv0, updatedTask)
  }

  @Test
  fun `updateDependentTaskDueDate with dependent task with output, execution period start date, and encounter part of reference that is not an Immunization`() {
    coEvery { fhirEngine.search<Task>(any()) } returns emptyList()
    coEvery {
      fhirEngine.get(
        ResourceType.Task,
        "650203d2-f327-4eb4-a9fd-741e0ce29c3f",
      )
    } returns
      opv1.apply {
        status = TaskStatus.INPROGRESS
        output =
          listOf(
            TaskOutputComponent(
              CodeableConcept(),
              StringType(
                "{\n" +
                  "          \"reference\": \"Encounter/14e2ae52-32fc-4507-8736-1177cdaafe90\"\n" +
                  "        }",
              ),
            ),
          )
        input = listOf(Task.ParameterComponent(CodeableConcept(), StringType("9")))
      }
    coEvery {
      fhirEngine.get(ResourceType.Encounter, "14e2ae52-32fc-4507-8736-1177cdaafe90")
    } returns encounter
    coEvery {
      fhirEngine.get(ResourceType.Immunization, "15e2ae52-32fc-4507-8736-1177cdaafe90")
    } returns Task()
    coEvery {
      defaultRepository.loadResource(
        Reference(
          Json.decodeFromString<JsonObject>(opv1.output.first().value.toString())[REFERENCE]
            ?.jsonPrimitive
            ?.content,
        ),
      )
    } returns encounter
    coEvery { defaultRepository.loadResource(Reference(encounter.partOf.reference)) } returns
      immunizationResource

    val updatedTask = runBlocking { opv0.updateDependentTaskDueDate(defaultRepository) }
    assertEquals(opv0, updatedTask)
  }

  @Test
  fun `updateDependentTaskDueDate with Task input value equal or greater than difference between administration date and depedentTask executionPeriod start`() {
    coEvery {
      fhirEngine.get(
        ResourceType.Task,
        "650203d2-f327-4eb4-a9fd-741e0ce29c3f",
      )
    } returns
      opv1.apply {
        status = TaskStatus.INPROGRESS
        output =
          listOf(
            TaskOutputComponent(
              CodeableConcept(),
              Reference("Encounter/14e2ae52-32fc-4507-8736-1177cdaafe90"),
            ),
          )
        input = listOf(Task.ParameterComponent(CodeableConcept(), StringType("9")))
      }
    coEvery {
      fhirEngine.batchedSearch<Task> {
        filter(
          referenceParameter = ReferenceClientParam("part-of"),
          { value = opv1.id.extractLogicalIdUuid() },
        )
      }
    } returns
      listOf(
        SearchResult(
          resource =
            opv1.apply { partOf = listOf(Reference("Task/650203d2-f327-4eb4-a9fd-741e0ce29c3f")) },
          null,
          null,
        ),
      )
    coEvery {
      fhirEngine.batchedSearch<Immunization> {
        filter(
          referenceParameter = ReferenceClientParam("part-of"),
          { value = immunizationResource.id.extractLogicalIdUuid() },
        )
      }
    } returns listOf(SearchResult(resource = immunizationResource, null, null))
    coEvery {
      fhirEngine.batchedSearch<Encounter> {
        filter(
          referenceParameter = ReferenceClientParam("part-of"),
          { value = encounter.id.extractLogicalIdUuid() },
        )
      }
    } returns listOf(SearchResult(resource = encounter, null, null))

    val updatedTask = runBlocking { opv0.updateDependentTaskDueDate(defaultRepository) }
    assertEquals(opv0, updatedTask)
  }

  @Test
  fun `updateDependentTaskDueDate sets executionPeriod start correctly`() =
    runTest(timeout = 60.seconds) {
      // Prepare database for testing
      fhirEngine.create(
        opv0,
        opv1.apply {
          addPartOf(opv0.asReference())
          status = TaskStatus.REQUESTED
          input = listOf(Task.ParameterComponent(CodeableConcept(), StringType("28")))
        },
        encounter,
        immunizationResource,
      )

      val dependentTaskSlot = slot<Task>()
      coEvery {
        defaultRepository.addOrUpdate(addMandatoryTags = true, capture(dependentTaskSlot))
      } just runs
      opv0.updateDependentTaskDueDate(defaultRepository)
      println("AA - ${dependentTaskSlot.captured.encodeResourceToString()}")
      assertEquals(
        Date.from(Instant.parse("2021-11-20T00:00:00Z")),
        dependentTaskSlot.captured.executionPeriod.start,
      )
      // TODO update list to add tests for other scenarios
    }

  @Test
  fun testEvaluateToBooleanReturnsTrueWhenAllConditionsAreMetIfMatchAllIsSetToTrue() {
    val conditionalFhirPathExpression = listOf("Patient.active", "Patient.id = 'patient-1'")
    val patient = Faker.buildPatient()
    patient.apply {
      id = "patient-1"
      active = true
    }
    val bundle = Bundle().apply { addEntry().resource = patient }

    val conditionsMet =
      fhirCarePlanGenerator.evaluateToBoolean(
        subject = patient,
        bundle = bundle,
        triggerConditions = conditionalFhirPathExpression,
        matchAll = true,
      )

    assertTrue(conditionsMet)
  }

  @Test
  fun testEvaluateToBooleanReturnsFalseWhenSomeConditionsAreNotMetIfMatchAllIsSetToTrue() {
    val conditionalFhirPathExpression =
      listOf("Patient.active", "Patient.id = 'another-patient-id'")
    val patient = Faker.buildPatient()
    patient.apply {
      id = "patient-1"
      active = true
    }
    val bundle = Bundle().apply { addEntry().resource = patient }

    val conditionsMet =
      fhirCarePlanGenerator.evaluateToBoolean(
        subject = patient,
        bundle = bundle,
        triggerConditions = conditionalFhirPathExpression,
        matchAll = true,
      )

    assertFalse(conditionsMet)
  }

  @Test
  fun testEvaluateToBooleanReturnsTrueWhenSomeConditionsAreNotMetIfMatchAllIsSetToFalse() {
    val conditionalFhirPathExpression =
      listOf("Patient.active", "Patient.id = 'another-patient-id'")
    val patient = Faker.buildPatient()
    patient.apply {
      id = "patient-1"
      active = true
    }
    val bundle = Bundle().apply { addEntry().resource = patient }

    val conditionsMet =
      fhirCarePlanGenerator.evaluateToBoolean(
        subject = patient,
        bundle = bundle,
        triggerConditions = conditionalFhirPathExpression,
        matchAll = false,
      )

    assertTrue(conditionsMet)
  }

  @Test
  fun testEvaluateToBooleanReturnsFalseWhenNoneOfTheConditionsAreNotMetIfMatchAllIsSetToFalse() {
    val conditionalFhirPathExpression =
      listOf("Patient.active = 'false'", "Patient.id = 'another-patient-id'")
    val patient = Faker.buildPatient()
    patient.apply {
      id = "patient-1"
      active = true
    }
    val bundle = Bundle().apply { addEntry().resource = patient }

    val conditionsMet =
      fhirCarePlanGenerator.evaluateToBoolean(
        subject = patient,
        bundle = bundle,
        triggerConditions = conditionalFhirPathExpression,
        matchAll = false,
      )

    assertFalse(conditionsMet)
  }

  @Test
  @ExperimentalCoroutinesApi
  fun `generateOrUpdateCarePlan should generate a sample careplan using apply`(): Unit =
    runBlocking(Dispatchers.IO) {
      val planDefinition =
        "plans/sample-request/sample_request_plan_definition.json"
          .readFile()
          .decodeResourceFromString<PlanDefinition>()
      val patient =
        "plans/sample-request/sample_request_patient.json"
          .readFile()
          .decodeResourceFromString<Patient>()
      val library =
        "plans/sample-request/sample_request_example-1.0.0.cql.fhir.json"
          .readFile()
          .decodeResourceFromString<Library>()

      installToIgManager(planDefinition)
      installToIgManager(library)
      importToFhirEngine(patient)

      val resourceSlot = slot<Resource>()
      coEvery { defaultRepository.addOrUpdate(any(), capture(resourceSlot)) } answers
        {
          runBlocking(Dispatchers.IO) { fhirEngine.create(resourceSlot.captured) }
        }
      val carePlan =
        fhirCarePlanGenerator.generateOrUpdateCarePlan(
          planDefinition = planDefinition,
          subject = patient,
          generateCarePlanWithWorkflowApi = true,
        )!!

      assertNotNull(carePlan)
      assertNotNull(UUID.fromString(carePlan.id))
      assertEquals(planDefinition.title, carePlan.title)
      assertEquals(planDefinition.description, carePlan.description)
    }

  @Test
  fun generateMeaslesCarePlan(): Unit = runBlockingOnWorkerThread {
    loadFile(
      "/plans/measles-immunizations/Library-FHIRCommon.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-FHIRHelpers.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-IMMZCommon.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-IMMZCommonIzDataElements.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-IMMZConcepts.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-IMMZConfig.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-IMMZD2DTMeaslesLogic.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-IMMZIndicatorCommon.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-IMMZINDMeasles.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-IMMZVaccineLibrary.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/ActivityDefinition-IMMZD2DTMeaslesMR.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/PlanDefinition-IMMZD2DTMeasles.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-WHOCommon.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/Library-WHOConcepts.json",
      ::installToIgManager,
    )
    loadFile(
      "/plans/measles-immunizations/ValueSet-HIVstatus-values.json",
      ::installToIgManager,
    )

    loadFile(
      "/plans/measles-immunizations/IMMZ-Patient-NoVaxeninfant-f.json",
      ::importToFhirEngine,
    )
    loadFile(
      "/plans/measles-immunizations/birthweightnormal-NoVaxeninfant-f.json",
      ::importToFhirEngine,
    )

    /*val fhirOperator =
      FhirOperator.Builder(context)
        .fhirEngine(fhirEngine)
        .fhirContext(fhirContext)
        .knowledgeManager(knowledgeManager)
        .build()

    val carePlan =
      fhirOperator.generateCarePlan(
        planDefinition =
          CanonicalType(
            "http://smart.who.int/smart-immunizations-measles/PlanDefinition/IMMZD2DTMeasles",
          ),
        subject = "Patient/IMMZ-Patient-NoVaxeninfant-f",
      )*/

    val planDefinition =
      "/plans/measles-immunizations/PlanDefinition-IMMZD2DTMeasles.json"
        .readFile()
        .decodeResourceFromString<PlanDefinition>()

    val patient =
      "/plans/measles-immunizations/IMMZ-Patient-NoVaxeninfant-f.json"
        .readFile()
        .decodeResourceFromString<Patient>()

    val resourcesSlot = mutableListOf<Resource>()
    val booleanSlot = slot<Boolean>()
    coEvery { defaultRepository.create(capture(booleanSlot), capture(resourcesSlot)) } returns
      emptyList()

    fhirCarePlanGenerator
      .generateOrUpdateCarePlan(
        planDefinition = planDefinition,
        subject = patient,
        generateCarePlanWithWorkflowApi = true,
      )
      .also { careplan ->
        assertTrue(resourcesSlot.any { it.resourceType == ResourceType.MedicationRequest })
        assertTrue(resourcesSlot.size > 0)
        println(jsonParser.encodeResourceToString(careplan))
        assertNotNull(careplan)
      }

    // println(jsonParser.encodeResourceToString(carePlan))

    // assertNotNull(carePlan)
  }

  private suspend fun loadFile(path: String, importFunction: KSuspendFunction1<Resource, Unit>) {
    val resource =
      if (path.endsWith(suffix = ".xml")) {
        xmlParser.parseResource(open(path)) as Resource
      } else if (path.endsWith(".json")) {
        jsonParser.parseResource(open(path)) as Resource
      } else if (path.endsWith(".cql")) {
        toFhirLibrary(open(path))
      } else {
        throw IllegalArgumentException("Only xml and json and cql files are supported")
      }
    loadResource(resource, importFunction)
  }

  private suspend fun importToFhirEngine(resource: Resource) {
    val ids = fhirEngine.create(resource)
    resource.id = ids.first()
  }

  private suspend fun installToIgManager(resource: Resource) {
    knowledgeManager.index(writeToFile(resource))
  }

  private suspend fun loadResource(
    resource: Resource,
    importFunction: KSuspendFunction1<Resource, Unit>,
  ) {
    when (resource.resourceType) {
      ResourceType.Bundle -> loadBundle(resource as Bundle, importFunction)
      else -> importFunction(resource)
    }
  }

  private fun open(path: String) = javaClass.getResourceAsStream(path)!!

  private suspend fun loadBundle(
    bundle: Bundle,
    importFunction: KSuspendFunction1<Resource, Unit>,
  ) {
    for (entry in bundle.entry) {
      val resource = entry.resource
      loadResource(resource, importFunction)
    }
  }

  private fun writeToFile(resource: Resource): File {
    val fileName =
      if (resource is MetadataResource && resource.name != null) {
        resource.name
      } else {
        resource.idElement.idPart
      }
    return File(context.filesDir, fileName).apply {
      writeText(jsonParser.encodeResourceToString(resource))
    }
  }

  private fun toFhirLibrary(cql: InputStream): Library {
    val cqlText = cql.bufferedReader().use { bufferReader -> bufferReader.readText() }

    val translator =
      CqlTranslator.fromText(
        cqlText,
        LibraryManager(ModelManager()).apply {
          librarySourceLoader.registerProvider(FhirLibrarySourceProvider())
        },
      )

    val identifier = translator.translatedLibrary.library.identifier

    return Library().apply {
      id = "${identifier.id}-${identifier.version}"
      name = identifier.id
      version = identifier.version
      status = Enumerations.PublicationStatus.ACTIVE
      url = "http://localhost/Library/${identifier.id}|${identifier.version}"
      addContent(
        Attachment().apply {
          contentType = "text/cql"
          data = cqlText.toByteArray()
        },
      )
    }
  }

  internal fun <T> runBlockingOnWorkerThread(block: suspend (CoroutineScope) -> T) =
    runBlocking(Dispatchers.IO) { block(this) }

  data class PlanDefinitionResources(
    val planDefinition: PlanDefinition,
    val patient: Patient,
    val questionnaireResponses: List<QuestionnaireResponse>,
    val structureMap: StructureMap,
    val structureMapReferral: StructureMap,
    val resourcesSlot: MutableList<Resource>,
  )

  private fun loadPlanDefinitionResources(
    planName: String,
    questionnaireResponseTags: List<String> = emptyList(),
  ): PlanDefinitionResources {
    val planDefinition =
      "plans/$planName/plandefinition.json".readFile().decodeResourceFromString<PlanDefinition>()

    val patient =
      "plans/$planName/sample/patient.json".readFile().decodeResourceFromString<Patient>()

    val questionnaireResponses =
      questionnaireResponseTags.map {
        "plans/$planName/sample/questionnaire-response-$it.json"
          .readFile()
          .decodeResourceFromString<QuestionnaireResponse>()
      }

    val structureMapRegister =
      structureMapUtilities
        .parse(
          "plans/$planName/structure-map-register.txt".readFile(),
          "${planName.uppercase().replace("-", "").replace(" ", "")}CarePlan",
        )
        .also { println(it.encodeResourceToString()) }

    val structureMapReferral =
      structureMapUtilities
        .parse("plans/structure-map-referral.txt".readFile(), "ReferralTask")
        .also { println(it.encodeResourceToString()) }

    val resourcesSlot = mutableListOf<Resource>()
    val booleanSlot = slot<Boolean>()
    coEvery { defaultRepository.addOrUpdate(capture(booleanSlot), capture(resourcesSlot)) } just
      runs
    coEvery { fhirEngine.search<CarePlan>(Search(ResourceType.CarePlan)) } returns listOf()
    coEvery { fhirEngine.get<StructureMap>(structureMapRegister.logicalId) } returns
      structureMapRegister
        .encodeResourceToString()
        .decodeResourceFromString() // Ensure 'months' Duration code is correctly escaped
    coEvery { fhirEngine.get<StructureMap>("528a8603-2e43-4a2e-a33d-1ec2563ffd3e") } returns
      structureMapReferral
        .encodeResourceToString()
        .decodeResourceFromString() // Ensure 'months' Duration code is correctly escaped

    return PlanDefinitionResources(
      planDefinition,
      patient,
      questionnaireResponses,
      structureMapRegister,
      structureMapReferral,
      resourcesSlot,
    )
  }

  private fun assertCarePlan(
    carePlan: CarePlan,
    planDefinition: PlanDefinition,
    patient: Patient,
    referenceDate: Date,
    endDate: Date?,
    visitTasks: Int,
  ) =
    assertCarePlan(
      carePlan,
      planDefinition,
      patient,
      referenceDate,
      endDate,
      visitTasks,
      Date(),
    )

  private fun assertCarePlan(
    carePlan: CarePlan,
    planDefinition: PlanDefinition,
    patient: Patient,
    referenceDate: Date,
    endDate: Date?,
    visitTasks: Int,
    dateToday: Date,
  ) {
    assertNotNull(carePlan.id)
    assertNotNull(UUID.fromString(carePlan.id))
    assertEquals(CarePlan.CarePlanStatus.ACTIVE, carePlan.status)
    assertEquals(CarePlan.CarePlanIntent.PLAN, carePlan.intent)
    assertEquals(planDefinition.title, carePlan.title)
    assertEquals(planDefinition.description, carePlan.description)
    assertEquals(patient.logicalId, carePlan.subject.extractId())
    assertEquals(
      DateTimeType(dateToday).value.makeItReadable(),
      carePlan.created.makeItReadable(),
    )
    assertEquals(patient.generalPractitionerFirstRep.extractId(), carePlan.author.extractId())

    assertEquals(referenceDate.makeItReadable(), carePlan.period.start.makeItReadable())
    assertEquals(endDate.makeItReadable(), carePlan.period.end.makeItReadable())

    assertTrue(carePlan.activityFirstRep.outcomeReference.isNotEmpty())
    assertEquals(visitTasks, carePlan.activityFirstRep.outcomeReference.size)
  }

  private fun makeVaccinesMapForPatient(patient: Patient) =
    mapOf<String, Date>(
      "BCG" to patient.birthDate,
      "OPV 0" to patient.birthDate,
      "PENTA 1" to patient.birthDate.plusDays(42),
      "OPV 1" to patient.birthDate.plusDays(42),
      "PCV 1" to patient.birthDate.plusDays(42),
      "ROTA 1" to patient.birthDate.plusDays(42),
      "PENTA 2" to patient.birthDate.plusDays(70),
      "OPV 2" to patient.birthDate.plusDays(70),
      "PCV 2" to patient.birthDate.plusDays(70),
      "ROTA 2" to patient.birthDate.plusDays(70),
      "PENTA 3" to patient.birthDate.plusDays(98),
      "OPV 3" to patient.birthDate.plusDays(98),
      "PCV 3" to patient.birthDate.plusDays(98),
      "IPV" to patient.birthDate.plusDays(98),
      "MEASLES 1" to patient.birthDate.plusMonths(9),
      "MEASLES 2" to patient.birthDate.plusMonths(15),
      "YELLOW FEVER" to patient.birthDate.plusMonths(9),
      "TYPHOID" to patient.birthDate.plusMonths(9),
      "HPV 1" to patient.birthDate.plusMonths(108),
      "HPV 2" to patient.birthDate.plusMonths(114),
    )

  @Test
  fun cleanPlanDefinitionCanonical() {
    val carePlan = CarePlan().apply { instantiatesCanonical = listOf(CanonicalType("123456")) }
    fhirCarePlanGenerator.invokeCleanPlanDefinitionCanonical(carePlan)
    assertEquals("PlanDefinition/123456", carePlan.instantiatesCanonical.first().value)
  }

  private fun vaccineTask(
    id: String,
    description: String,
    status: TaskStatus,
    start: String,
    partOf: String? = null,
  ) =
    Task().apply {
      this.id = id
      this.description = description
      this.status = status
      this.code = CodeableConcept().addCoding(Coding("http://snomed.info/sct", "33879002", null))
      this.executionPeriod = Period().apply { this.start = DateTimeType(start).value }
      this.identifier = listOf(org.hl7.fhir.r4.model.Identifier().apply { value = "ident-$id" })
      partOf?.let { this.partOf = listOf(Reference("Task/$it")) }
    }

  private fun carePlanWithActivity(vararg taskIds: String) =
    CarePlan().apply {
      id = "careplan-1"
      addActivity().apply { taskIds.forEach { addOutcomeReference(Reference("Task/$it")) } }
    }

  @Test
  fun testReconcileWithExistingTasksUpdatesPendingTasksInPlace() {
    val existing =
      listOf(
        vaccineTask("old-bcg", "BCG ", TaskStatus.READY, "2026-01-01"),
        vaccineTask("old-penta1", "Penta 1", TaskStatus.REQUESTED, "2026-02-12"),
        vaccineTask("old-penta2", "Penta 2", TaskStatus.CANCELLED, "2026-03-12", "old-penta1"),
      )
    val carePlan = carePlanWithActivity("old-bcg", "old-penta1", "old-penta2")
    val existingActivityCount = carePlan.activity.size

    // Plan re-applied after the date of birth was corrected
    carePlan.addContained(vaccineTask("new-bcg", "BCG", TaskStatus.READY, "2026-01-10"))
    carePlan.addContained(vaccineTask("new-penta1", "Penta 1", TaskStatus.REQUESTED, "2026-02-21"))
    carePlan.addContained(
      vaccineTask("new-penta2", "Penta 2", TaskStatus.REQUESTED, "2026-03-21", "new-penta1"),
    )
    carePlan.activity.first().apply {
      listOf("new-bcg", "new-penta1", "new-penta2").forEach {
        addOutcomeReference(Reference("Task/$it"))
      }
    }

    fhirCarePlanGenerator.reconcileWithExistingTasks(carePlan, existing, existingActivityCount)

    val tasks = carePlan.contained.filterIsInstance<Task>().associateBy { it.logicalId }
    assertEquals(setOf("old-bcg", "old-penta1", "old-penta2"), tasks.keys)
    // New dates and status are kept, ids and identifiers come from the existing Tasks
    assertEquals(DateTimeType("2026-01-10").value, tasks.getValue("old-bcg").executionPeriod.start)
    assertEquals(TaskStatus.REQUESTED, tasks.getValue("old-penta2").status)
    assertEquals("ident-old-penta1", tasks.getValue("old-penta1").identifierFirstRep.value)
    assertEquals("Task/old-penta1", tasks.getValue("old-penta2").partOfFirstRep.reference)
    assertEquals(
      listOf("Task/old-bcg", "Task/old-penta1", "Task/old-penta2"),
      carePlan.activity.flatMap { it.outcomeReference }.map { it.reference },
    )
  }

  @Test
  fun testReconcileWithExistingTasksKeepsDueTasksActionable() {
    val existing =
      listOf(
        vaccineTask("old-visit", "Child Visit", TaskStatus.READY, "2026-09-01"),
        vaccineTask("old-later", "Later visit", TaskStatus.READY, "2026-09-01"),
      )
    val carePlan = carePlanWithActivity("old-visit", "old-later")

    // Regenerated Tasks start as requested; one is still due, the other moved into the future
    carePlan.addContained(
      vaccineTask("new-visit", "Child Visit", TaskStatus.REQUESTED, "2026-09-15"),
    )
    carePlan.addContained(
      vaccineTask("new-later", "Later visit", TaskStatus.REQUESTED, "2999-01-01"),
    )

    fhirCarePlanGenerator.reconcileWithExistingTasks(carePlan, existing, 1)

    val tasks = carePlan.contained.filterIsInstance<Task>().associateBy { it.logicalId }
    assertEquals(TaskStatus.READY, tasks.getValue("old-visit").status)
    assertEquals(TaskStatus.REQUESTED, tasks.getValue("old-later").status)
  }

  @Test
  fun testReconcileWithExistingTasksKeepsCompletedTasks() {
    val existing =
      listOf(
        vaccineTask("old-bcg", "BCG", TaskStatus.COMPLETED, "2026-01-01"),
        vaccineTask("old-penta1", "Penta 1", TaskStatus.READY, "2026-02-12"),
      )
    val carePlan = carePlanWithActivity("old-bcg", "old-penta1")
    val existingActivityCount = carePlan.activity.size

    carePlan.addContained(vaccineTask("new-bcg", "BCG", TaskStatus.READY, "2026-01-10"))
    carePlan.addContained(
      vaccineTask("new-penta1", "Penta 1", TaskStatus.REQUESTED, "2026-02-21", "new-bcg"),
    )
    carePlan.addContained(vaccineTask("new-mr1", "MR 1", TaskStatus.REQUESTED, "2026-10-01"))
    carePlan.addActivity().apply {
      listOf("new-bcg", "new-penta1", "new-mr1").forEach {
        addOutcomeReference(Reference("Task/$it"))
      }
    }

    fhirCarePlanGenerator.reconcileWithExistingTasks(carePlan, existing, existingActivityCount)

    val tasks = carePlan.contained.filterIsInstance<Task>().associateBy { it.logicalId }
    // Completed BCG is not regenerated, Penta 1 is updated in place, MR 1 is new
    assertEquals(setOf("old-penta1", "new-mr1"), tasks.keys)
    assertEquals("Task/old-bcg", tasks.getValue("old-penta1").partOfFirstRep.reference)
    assertEquals(
      listOf("Task/old-bcg", "Task/old-penta1", "Task/new-mr1"),
      carePlan.activity.flatMap { it.outcomeReference }.map { it.reference },
    )
  }

  @Test
  fun testReconcileWithExistingTasksDropsEmptiedNewActivity() {
    val existing = listOf(vaccineTask("old-bcg", "BCG", TaskStatus.READY, "2026-01-01"))
    val carePlan = carePlanWithActivity("old-bcg")

    carePlan.addContained(vaccineTask("new-bcg", "BCG", TaskStatus.READY, "2026-01-10"))
    carePlan.addActivity().addOutcomeReference(Reference("Task/new-bcg"))

    fhirCarePlanGenerator.reconcileWithExistingTasks(carePlan, existing, 1)

    assertEquals(1, carePlan.activity.size)
    assertEquals("Task/old-bcg", carePlan.activityFirstRep.outcomeReferenceFirstRep.reference)
    assertEquals("old-bcg", carePlan.contained.single().logicalId)
  }

  @Test
  fun testLoadCarePlanTasksMatchesNormalAndDoubledCarePlanReferences() = runTest {
    val patient = Patient().apply { id = "patient-1" }
    val carePlan = CarePlan().apply { id = "careplan-1" }
    fun task(id: String, basedOn: String) =
      Task().apply {
        this.id = id
        this.`for` = Reference("Patient/patient-1")
        this.basedOn = listOf(Reference(basedOn))
      }
    fhirEngine.create(
      patient,
      task("downloaded", "CarePlan/careplan-1"),
      task("generated-on-device", "CarePlan/CarePlan/careplan-1"),
      task("other-plan", "CarePlan/careplan-2"),
    )

    val tasks = fhirCarePlanGenerator.loadCarePlanTasks(carePlan, patient)

    assertEquals(setOf("downloaded", "generated-on-device"), tasks.map { it.logicalId }.toSet())
  }

  @Test
  fun testReconcileWithExistingTasksIgnoresTasksWithoutDescription() {
    val existing = listOf(vaccineTask("old-visit", "", TaskStatus.READY, "2026-01-01"))
    val carePlan = carePlanWithActivity("old-visit")

    carePlan.addContained(vaccineTask("new-visit", "", TaskStatus.READY, "2026-02-01"))
    carePlan.activityFirstRep.addOutcomeReference(Reference("Task/new-visit"))

    fhirCarePlanGenerator.reconcileWithExistingTasks(carePlan, existing, 1)

    assertEquals("new-visit", carePlan.contained.single().logicalId)
    assertEquals(2, carePlan.activityFirstRep.outcomeReference.size)
  }
}

private fun Date.asYyyyMmDd(): String = this.formatDate(SDF_YYYY_MM_DD)
