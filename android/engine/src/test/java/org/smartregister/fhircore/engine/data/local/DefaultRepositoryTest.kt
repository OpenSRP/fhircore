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

package org.smartregister.fhircore.engine.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.rest.param.ParamPrefixEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.SearchResult
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.get
import com.google.gson.Gson
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import java.time.ZoneId
import java.util.Date
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.hl7.fhir.r4.model.Address
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.CarePlan
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Condition
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Group
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Organization
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Procedure
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseStatus
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.RelatedPerson
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.ServiceRequest
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Task
import org.joda.time.LocalDate
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.smartregister.fhircore.engine.app.AppConfigService
import org.smartregister.fhircore.engine.app.fakes.Faker
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.UniqueIdAssignmentConfig
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.configuration.event.EventTriggerCondition
import org.smartregister.fhircore.engine.configuration.event.EventWorkflow
import org.smartregister.fhircore.engine.configuration.event.UpdateWorkflowValueConfig
import org.smartregister.fhircore.engine.configuration.profile.ManagingEntityConfig
import org.smartregister.fhircore.engine.data.local.DefaultRepository.Companion.PATIENT_CONDITION_RESOLVED_CODE
import org.smartregister.fhircore.engine.data.local.DefaultRepository.Companion.PATIENT_CONDITION_RESOLVED_DISPLAY
import org.smartregister.fhircore.engine.data.local.DefaultRepository.Companion.SNOMED_SYSTEM
import org.smartregister.fhircore.engine.domain.model.Code
import org.smartregister.fhircore.engine.domain.model.DataQuery
import org.smartregister.fhircore.engine.domain.model.FilterCriterionConfig
import org.smartregister.fhircore.engine.domain.model.KeyValueConfig
import org.smartregister.fhircore.engine.domain.model.ResourceConfig
import org.smartregister.fhircore.engine.domain.model.ResourceFilterExpression
import org.smartregister.fhircore.engine.domain.model.RuleConfig
import org.smartregister.fhircore.engine.robolectric.RobolectricTest
import org.smartregister.fhircore.engine.rulesengine.ConfigRulesExecutor
import org.smartregister.fhircore.engine.util.DefaultDispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.asReference
import org.smartregister.fhircore.engine.util.extension.formatDate
import org.smartregister.fhircore.engine.util.extension.generateMissingId
import org.smartregister.fhircore.engine.util.extension.loadResource
import org.smartregister.fhircore.engine.util.extension.plusDays
import org.smartregister.fhircore.engine.util.extension.updateLastUpdated
import org.smartregister.fhircore.engine.util.extension.yesterday
import org.smartregister.fhircore.engine.util.fhirpath.FhirPathDataExtractor

@HiltAndroidTest
class DefaultRepositoryTest : RobolectricTest() {

  @get:Rule val hiltRule = HiltAndroidRule(this)

  @Inject lateinit var gson: Gson

  @Inject lateinit var configRulesExecutor: ConfigRulesExecutor

  @Inject lateinit var fhirPathDataExtractor: FhirPathDataExtractor

  @Inject lateinit var fhirEngine: FhirEngine

  @Inject lateinit var parser: IParser

  @Inject lateinit var contentCache: ContentCache

  @BindValue
  val configService: ConfigService =
    spyk(AppConfigService(ApplicationProvider.getApplicationContext()))
  private val application = ApplicationProvider.getApplicationContext<Application>()
  private val configurationRegistry: ConfigurationRegistry = Faker.buildTestConfigurationRegistry()
  private val context = ApplicationProvider.getApplicationContext<HiltTestApplication>()
  private lateinit var dispatcherProvider: DefaultDispatcherProvider
  private lateinit var sharedPreferenceHelper: SharedPreferencesHelper
  private lateinit var defaultRepository: DefaultRepository
  private lateinit var patient: Patient
  private lateinit var questionnaireConfig: QuestionnaireConfig
  private lateinit var samplePatientRegisterQuestionnaire: Questionnaire

  @Before
  fun setUp() {
    hiltRule.inject()
    sharedPreferenceHelper = SharedPreferencesHelper(application, gson)
    dispatcherProvider = DefaultDispatcherProvider()
    defaultRepository =
      DefaultRepository(
        fhirEngine = fhirEngine,
        dispatcherProvider = dispatcherProvider,
        sharedPreferencesHelper = sharedPreferenceHelper,
        configurationRegistry = configurationRegistry,
        configService = configService,
        configRulesExecutor = configRulesExecutor,
        fhirPathDataExtractor = fhirPathDataExtractor,
        parser = parser,
        context = context,
        contentCache = contentCache,
      )
    patient =
      Faker.buildPatient().apply {
        address =
          listOf(
            Address().apply {
              city = "Mombasa"
              country = "Kenya"
            },
          )
      }
    questionnaireConfig =
      QuestionnaireConfig(
        id = "e5155788-8831-4916-a3f5-486915ce34b211", // Same as ID in
        // sample_patient_registration.json
        title = "Patient registration",
        type = "DEFAULT",
      )

    samplePatientRegisterQuestionnaire = Questionnaire().apply { id = questionnaireConfig.id }
  }

  @Test
  fun loadResourceShouldGetResourceUsingId() {
    val samplePatientId = "12345"
    val samplePatient: Patient = Patient().apply { id = samplePatientId }

    coEvery { fhirEngine.get<Patient>(any()) } answers { samplePatient }

    runBlocking {
      val actualPatient = defaultRepository.loadResource<Patient>(samplePatientId)
      Assert.assertEquals("12345", actualPatient?.id)
    }

    coVerify { fhirEngine.get<Patient>("12345") }
  }

  @Test
  fun loadResourceShouldGetResourceWithReference() = runTest {
    val sampleResource = Patient().apply { id = "123345677" }
    val sampleResourceReference = sampleResource.asReference()
    coEvery { fhirEngine.get(any(), sampleResource.logicalId) } answers { sampleResource }

    val result = defaultRepository.loadResource(sampleResourceReference)
    Assert.assertEquals(sampleResource, result)
    coVerify(exactly = 1) { fhirEngine.get(sampleResource.resourceType, sampleResource.logicalId) }
  }

  @Test
  fun loadResourceShouldGetResourceWithResourceTypeAndLogicalId() = runTest {
    val sampleResource = Patient().apply { id = "123345677" }
    coEvery { fhirEngine.get(any(), sampleResource.logicalId) } answers { sampleResource }

    val result = defaultRepository.loadResource(sampleResource.logicalId, ResourceType.Patient)
    Assert.assertEquals(sampleResource, result)
    coVerify(exactly = 1) { fhirEngine.get(sampleResource.resourceType, sampleResource.logicalId) }
  }

  @Test
  fun searchResourceForGivenTokenShouldReturn1PatientUsingId() {
    val samplePatientId = "12345"
    coEvery { fhirEngine.search<Patient>(any()) } returns
      listOf(SearchResult(resource = Faker.buildPatient(id = samplePatientId), null, null))

    runBlocking {
      val actualPatients =
        defaultRepository.searchResourceFor<Patient>(
          token = Patient.RES_ID,
          subjectId = samplePatientId,
          subjectType = ResourceType.Patient,
          dataQueries = emptyList(),
          configComputedRuleValues = emptyMap(),
        )
      Assert.assertEquals(1, actualPatients.size)
    }

    coVerify { fhirEngine.search<Patient>(any()) }
  }

  @Test
  fun addOrUpdateShouldCallFhirEngineUpdateWhenResourceExists() {
    val patientId = "15672-9234"
    val patient: Patient =
      Patient().apply {
        id = patientId
        active = true
        birthDate = LocalDate.parse("1996-08-17").toDate()
        gender = Enumerations.AdministrativeGender.MALE
        address =
          listOf(
            Address().apply {
              city = "Lahore"
              country = "Pakistan"
            },
          )
        name =
          listOf(
            HumanName().apply {
              given = mutableListOf(StringType("Salman"))
              family = "Ali"
            },
          )
        telecom = listOf(ContactPoint().apply { value = "12345" })
      }

    val savedPatientSlot = slot<Patient>()

    coEvery { fhirEngine.get(any(), any()) } answers { patient }
    coEvery { fhirEngine.update(any()) } just runs

    // Call the function under test
    runBlocking { defaultRepository.addOrUpdate(resource = patient) }

    coVerify { fhirEngine.get(ResourceType.Patient, patientId) }
    coVerify { fhirEngine.update(capture(savedPatientSlot)) }

    savedPatientSlot.captured.run {
      Assert.assertEquals(patient.idElement.idPart, idElement.idPart)
      Assert.assertEquals(patient.active, active)
      Assert.assertEquals(patient.birthDate, birthDate)
      Assert.assertEquals(patient.telecom[0].value, telecom[0].value)
      Assert.assertEquals(patient.name[0].family, name[0].family)
      Assert.assertEquals(patient.name[0].given[0].value, name[0].given[0].value)
      Assert.assertEquals(patient.address[0].city, address[0].city)
      Assert.assertEquals(patient.address[0].country, address[0].country)
    }

    // verify exception scenario
    coEvery { fhirEngine.get(ResourceType.Patient, any()) } throws
      mockk<ResourceNotFoundException>()
    coEvery { fhirEngine.create(any()) } returns listOf()
    runBlocking { defaultRepository.addOrUpdate(resource = Patient()) }
    coVerify(exactly = 1) { fhirEngine.create(any()) }
  }

  @Test
  fun saveShouldCallResourceGenerateMissingId() {
    mockkStatic(Resource::generateMissingId)
    val resource = spyk(Patient())

    coEvery { fhirEngine.get(ResourceType.Patient, any()) } throws
      ResourceNotFoundException("Exce", "Exce")
    coEvery { fhirEngine.create(any()) } returns listOf()

    runBlocking { defaultRepository.create(true, resource) }

    verify { resource.generateMissingId() }

    unmockkStatic(Resource::generateMissingId)
  }

  @Test
  fun `create() should add Resource_meta_lastUpdated`() {
    mockkStatic(Resource::updateLastUpdated)
    val resource = spyk(Patient())

    coEvery { fhirEngine.create(any()) } returns listOf()
    Assert.assertNull(resource.meta.lastUpdated)

    runBlocking { defaultRepository.create(true, resource) }

    Assert.assertNotNull(resource.meta.lastUpdated)
    verify { resource.updateLastUpdated() }
    unmockkStatic(Resource::updateLastUpdated)
  }

  @Test
  fun testCreateShouldNotDuplicateMetaTagsWithSameSystemCode() {
    val system = "https://smartregister.org/location-tag-id"
    val coding = Coding(system, "86453", "Location")
    val anotherCoding = Coding(system, "10200", "Location")
    val resource = Patient().apply { meta.addTag(coding) }

    // Meta contains 1 tag with code 86453
    Assert.assertEquals(1, resource.meta.tag.size)
    val firstTag = resource.meta.tag.first()
    Assert.assertEquals("86453", firstTag.code)
    Assert.assertEquals(system, firstTag.system)

    coEvery { fhirEngine.create(any()) } returns listOf(resource.id)
    every { configService.provideResourceTags(sharedPreferenceHelper) } returns
      listOf(coding, anotherCoding)
    runBlocking { defaultRepository.create(true, resource) }

    // Expecting 2 tags; tag with code 86453 should not be duplicated.
    Assert.assertEquals(2, resource.meta.tag.size)
    Assert.assertNotNull(resource.meta.lastUpdated)
    Assert.assertNotNull(resource.meta.getTag(system, "86453"))
  }

  @Test
  fun `update() should call Resource#updateLastUpdated and FhirEngine#update`() {
    mockkStatic(Resource::updateLastUpdated)
    val resource = spyk(Patient())

    coEvery { fhirEngine.update(any()) } just runs
    Assert.assertNull(resource.meta.lastUpdated)

    runBlocking { defaultRepository.update(resource) }

    Assert.assertNotNull(resource.meta.lastUpdated)
    verify { resource.updateLastUpdated() }
    coVerify { fhirEngine.update(resource) }
    unmockkStatic(Resource::updateLastUpdated)
  }

  @Test
  fun deleteShouldDeleteResourceFromEngine() = runTest {
    coEvery { fhirEngine.delete(any(), any()) } just runs
    val sampleResource = Patient().apply { id = "testid" }
    defaultRepository.delete(sampleResource)

    coVerify { fhirEngine.delete(sampleResource.resourceType, sampleResource.logicalId) }
  }

  @Test
  fun addOrUpdateShouldCallResourceGenerateMissingIdWhenResourceIdIsNull() {
    mockkStatic(Resource::generateMissingId)
    val resource = Patient()

    coEvery { fhirEngine.get(ResourceType.Patient, any()) } throws
      ResourceNotFoundException("Exce", "Exce")
    coEvery { fhirEngine.create(any()) } returns listOf()

    runBlocking { defaultRepository.addOrUpdate(resource = resource) }

    coVerify { resource.generateMissingId() }

    unmockkStatic(Resource::generateMissingId)
  }

  @Test
  fun loadManagingEntityShouldReturnPatient() =
    runTest(timeout = 30.seconds) {
      val patient = Faker.buildPatient(id = "12345")

      val relatedPerson =
        RelatedPerson().apply {
          id = "12983"
          this.patient = Reference("Patient/12345")
        }

      val group =
        Group().apply {
          id = "groupId"
          managingEntity = relatedPerson.asReference()
        }

      // Add required resources to the database
      try {
        fhirEngine.create(patient, relatedPerson, group)
      } catch (exception: Throwable) {
        println(exception.localizedMessage)
      }

      val managingEntity = defaultRepository.loadManagingEntity(group)
      Assert.assertEquals("12345", managingEntity?.logicalId)
    }

  @Test
  fun changeManagingEntityShouldVerifyFhirEngineCalls() {
    val patient =
      Patient().apply {
        id = "54321"
        addName().apply {
          addGiven("Sam")
          family = "Smith"
        }
        addTelecom().apply { value = "ssmith@mail.com" }
        addAddress().apply {
          district = "Mawar"
          city = "Jakarta"
        }
        gender = Enumerations.AdministrativeGender.MALE
      }
    val relatedPerson =
      RelatedPerson().apply {
        active = true
        name = patient.name
        birthDate = patient.birthDate
        telecom = patient.telecom
        address = patient.address
        gender = patient.gender
        this.patient = patient.asReference()
        id = "testRelatedPersonId"
      }

    val defaultRepositorySpy = spyk(defaultRepository)

    coEvery { fhirEngine.get<Patient>("54321") } returns patient

    coEvery { fhirEngine.get<RelatedPerson>("12983") } returns relatedPerson

    coEvery { fhirEngine.create(any()) } returns listOf()

    val group =
      Group().apply {
        id = "73847"
        managingEntity = Reference("RelatedPerson/12983")
        managingEntity.id = "33292"
      }
    coEvery { fhirEngine.get<Group>("73847") } returns group

    coEvery { fhirEngine.update(any()) } just runs

    coEvery { fhirEngine.get(relatedPerson.resourceType, relatedPerson.logicalId) } answers
      {
        relatedPerson
      }
    runBlocking {
      defaultRepositorySpy.changeManagingEntity(
        newManagingEntityId = "54321",
        groupId = "73847",
        ManagingEntityConfig(
          resourceType = ResourceType.Patient,
          relationshipCode =
            Code().apply {
              system = "http://hl7.org/fhir/ValueSet/relatedperson-relationshiptype"
              code = "99990006"
              display = "Family Head"
            },
        ),
      )
    }

    coVerify { fhirEngine.get<Patient>("54321") }

    coVerify { fhirEngine.get<Group>("73847") }

    coVerify { defaultRepositorySpy.addOrUpdate(resource = relatedPerson) }
  }

  @Test
  fun changeManagingEntityShouldVerifyFhirEngineCallsEvenWithAnOrganizationAsTheCurrentManagingEntity() {
    val patient =
      Patient().apply {
        id = "54321"
        addName().apply {
          addGiven("Sam")
          family = "Smith"
        }
        addTelecom().apply { value = "ssmith@mail.com" }
        addAddress().apply {
          district = "Mawar"
          city = "Jakarta"
        }
        gender = Enumerations.AdministrativeGender.MALE
      }
    val relatedPerson =
      RelatedPerson().apply {
        active = true
        name = patient.name
        birthDate = patient.birthDate
        telecom = patient.telecom
        address = patient.address
        gender = patient.gender
        this.patient = patient.asReference()
        id = "testRelatedPersonId"
      }

    val organization =
      Organization().apply {
        id = "12983"
        name = "Test Organization"
      }

    val defaultRepositorySpy = spyk(defaultRepository)

    coEvery { fhirEngine.get<Patient>("54321") } returns patient
    coEvery { fhirEngine.get<Organization>("12983") } returns organization
    coEvery { fhirEngine.get<RelatedPerson>(any()) } returns relatedPerson
    coEvery { fhirEngine.create(any()) } returns listOf()

    val group =
      Group().apply {
        id = "73847"
        managingEntity = Reference("Organization/12983")
        managingEntity.id = "33292"
      }
    coEvery { fhirEngine.get<Group>("73847") } returns group
    coEvery { fhirEngine.update(any()) } just runs

    runBlocking {
      defaultRepositorySpy.changeManagingEntity(
        newManagingEntityId = "54321",
        groupId = "73847",
        ManagingEntityConfig(
          resourceType = ResourceType.Patient,
          relationshipCode =
            Code().apply {
              system = "http://hl7.org/fhir/ValueSet/relatedperson-relationshiptype"
              code = "99990006"
              display = "Family Head"
            },
        ),
      )
    }

    coVerify { fhirEngine.get<Patient>("54321") }
    coVerify { fhirEngine.get<Group>("73847") }
    coVerify(inverse = true) { defaultRepositorySpy.addOrUpdate(resource = relatedPerson) }
  }

  @Test
  fun removeGroupGivenGroupAlreadyDeletedShouldThrowIllegalStateException() {
    val group =
      Group().apply {
        id = "73847"
        active = false
      }
    coEvery { fhirEngine.loadResource<Group>("73847") } returns group

    Assert.assertThrows(IllegalStateException::class.java) {
      runBlocking { defaultRepository.removeGroup(group.logicalId, false, emptyMap()) }
    }
  }

  @Test
  fun removeGroupShouldRemoveManagingEntityRelatedPerson() = runTest {
    val managingEntityRelatedPerson = RelatedPerson().apply { id = "testRelatedPersonId" }
    val defaultRepositorySpy =
      spyk(
        DefaultRepository(
          fhirEngine = fhirEngine,
          dispatcherProvider = dispatcherProvider,
          sharedPreferencesHelper = mockk(),
          configurationRegistry = mockk(),
          configService = mockk(),
          configRulesExecutor = mockk(),
          fhirPathDataExtractor = fhirPathDataExtractor,
          parser = parser,
          context = context,
          contentCache = contentCache,
        ),
      )
    coEvery { fhirEngine.search<RelatedPerson>(any()) } returns
      listOf(SearchResult(resource = managingEntityRelatedPerson, null, null))
    coEvery { defaultRepositorySpy.delete(any()) } just runs
    coEvery { defaultRepositorySpy.addOrUpdate(resource = any()) } just runs
    val group =
      Group().apply {
        id = "testGroupId"
        active = true
        managingEntity = managingEntityRelatedPerson.asReference()
      }
    coEvery { fhirEngine.loadResource<Group>(group.id) } returns group

    defaultRepositorySpy.removeGroup(group.id, isDeactivateMembers = false, emptyMap())
    coVerify { defaultRepositorySpy.delete(managingEntityRelatedPerson) }
    Assert.assertFalse(group.active)
  }

  @Test
  fun removeGroupMemberShouldDeactivateGroupMember() = runTest {
    val memberId = "testMemberId"
    val patientMemberRep =
      Patient().apply {
        id = memberId
        active = true
      }
    val defaultRepositorySpy = spyk(defaultRepository)
    coEvery { defaultRepositorySpy.addOrUpdate(resource = any()) } just runs
    coEvery { fhirEngine.get(patientMemberRep.resourceType, memberId) } returns patientMemberRep

    defaultRepositorySpy.removeGroupMember(
      memberId = memberId,
      groupId = null,
      groupMemberResourceType = patientMemberRep.resourceType,
      configComputedRuleValues = emptyMap(),
    )
    Assert.assertFalse(patientMemberRep.active)
    coVerify { defaultRepositorySpy.addOrUpdate(resource = patientMemberRep) }
  }

  @Test
  fun removeGroupMemberShouldCatchResourceNotFoundException() = runTest {
    val memberId = "testMemberId"
    val patientMemberRep =
      Patient().apply {
        id = memberId
        active = true
      }
    val defaultRepositorySpy = spyk(defaultRepository)
    coEvery { defaultRepositorySpy.addOrUpdate(resource = any()) } just runs
    coEvery { fhirEngine.get(patientMemberRep.resourceType, memberId) }
      .throws(ResourceNotFoundException("type", "id"))

    defaultRepositorySpy.removeGroupMember(
      memberId = memberId,
      groupId = null,
      groupMemberResourceType = patientMemberRep.resourceType,
      configComputedRuleValues = emptyMap(),
    )
    Assert.assertTrue(patientMemberRep.active)
  }

  @Test
  fun testDeleteWithResourceId() = runTest {
    val fhirEngine: FhirEngine = mockk(relaxUnitFun = true)
    val defaultRepository =
      spyk(
        DefaultRepository(
          fhirEngine = fhirEngine,
          dispatcherProvider = dispatcherProvider,
          sharedPreferencesHelper = mockk(),
          configurationRegistry = mockk(),
          configService = mockk(),
          configRulesExecutor = mockk(),
          fhirPathDataExtractor = fhirPathDataExtractor,
          parser = parser,
          context = context,
          contentCache = contentCache,
        ),
      )

    defaultRepository.delete(resourceType = ResourceType.Patient, resourceId = "123")

    coVerify { fhirEngine.delete(any(), any()) }
  }

  @Test
  fun testRemoveGroupDeletesRelatedPersonAndUpdatesGroup() {
    defaultRepository = spyk(defaultRepository)
    val groupId = "73847"
    val patientId = "6745"
    val patient = Patient().setId(patientId)

    val group =
      Group().apply {
        id = groupId
        active = true
        member = mutableListOf(Group.GroupMemberComponent(Reference("Patient/$patientId")))
      }
    coEvery { fhirEngine.loadResource<Group>("73847") } returns group
    coEvery { fhirEngine.get(ResourceType.Patient, patientId) } returns patient

    val relatedPersonId = "1234"
    val relatedPerson = RelatedPerson().setId(relatedPersonId)
    coEvery { fhirEngine.search<RelatedPerson>(any()) } returns
      listOf(SearchResult(resource = relatedPerson as RelatedPerson, null, null))
    coEvery { defaultRepository.delete(any()) } just runs
    coEvery { defaultRepository.addOrUpdate(resource = any()) } just runs

    runBlocking { defaultRepository.removeGroup(groupId, true, emptyMap()) }

    coVerify { defaultRepository.delete(relatedPerson) }
    coVerify { defaultRepository.addOrUpdate(resource = patient) }
    coVerify { defaultRepository.addOrUpdate(resource = group) }
  }

  @Test
  fun removeGroupMemberDeletesRelatedPersonAndUpdatesGroup() {
    defaultRepository = spyk(defaultRepository)
    val groupId = "73847"
    val memberId = "6745"
    val groupMemberResourceType = ResourceType.Patient
    val patient =
      Patient().apply {
        id = memberId
        active = true
      }
    val relatedPersonId = "1234"
    val relatedPerson =
      RelatedPerson(Reference(patient).apply { id = memberId }).apply { id = relatedPersonId }

    val group =
      Group().apply {
        id = groupId
        active = true
        member = mutableListOf(Group.GroupMemberComponent(Reference("Patient/$memberId")))
        managingEntity = Reference("RelatedPerson/$relatedPersonId")
      }
    coEvery { fhirEngine.loadResource<Group>("73847") } returns group
    coEvery { fhirEngine.get(ResourceType.Patient, memberId) } returns patient

    coEvery { fhirEngine.search<RelatedPerson>(any()) } returns
      listOf(SearchResult(resource = relatedPerson, null, null))
    coEvery { defaultRepository.delete(any()) } just runs
    coEvery { defaultRepository.addOrUpdate(resource = any()) } just runs

    runBlocking {
      defaultRepository.removeGroupMember(
        memberId = memberId,
        groupId = groupId,
        groupMemberResourceType = groupMemberResourceType,
        emptyMap(),
      )
    }

    coVerify { defaultRepository.delete(relatedPerson) }
    coVerify { defaultRepository.addOrUpdate(resource = group) }
  }

  @Test
  fun addOrUpdateShouldUpdateLastUpdatedToNow() {
    val date = Date()
    val patientId = "15672-9234"
    val patient: Patient =
      Patient().apply {
        meta.lastUpdated = date.plusDays(-20)
        id = "15672-9234"
        active = true
      }
    val savedPatientSlot = slot<Patient>()
    coEvery { fhirEngine.get(any(), any()) } answers { patient }
    coEvery { fhirEngine.update(any()) } just runs
    runBlocking { defaultRepository.addOrUpdate(resource = patient) }
    coVerify { fhirEngine.get(ResourceType.Patient, patientId) }
    coVerify { fhirEngine.update(capture(savedPatientSlot)) }
    Assert.assertEquals(
      date.formatDate("mm-dd-yyyy"),
      patient.meta.lastUpdated.formatDate("mm-dd-yyyy"),
    )
  }

  @Test
  fun testCloseResourceUpdatesCorrectTaskStatus() {
    val task =
      Task().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = Task.TaskStatus.READY
      }
    coEvery { fhirEngine.update(any()) } just runs
    val taskSlot = slot<Task>()

    val updatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "Task.status",
        value = JsonPrimitive("cancelled"),
        resourceType = ResourceType.Task,
      )
    val eventWorkflow = EventWorkflow(updateValues = listOf(updatedValues))

    runBlocking { defaultRepository.closeResource(task, eventWorkflow) }
    coVerify { fhirEngine.update(capture(taskSlot)) }
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", taskSlot.captured.id)
    Assert.assertEquals(Task.TaskStatus.CANCELLED, taskSlot.captured.status)
  }

  @Test
  fun testCloseResourceUpdatesCorrectCarePlanStatus() {
    val carePlan =
      CarePlan().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = CarePlan.CarePlanStatus.DRAFT
      }
    coEvery { fhirEngine.update(any()) } just runs
    val carePlanSlot = slot<CarePlan>()

    val updatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "CarePlan.status",
        value = JsonPrimitive("completed"),
        resourceType = ResourceType.CarePlan,
      )
    val eventWorkflow = EventWorkflow(updateValues = listOf(updatedValues))

    runBlocking { defaultRepository.closeResource(carePlan, eventWorkflow) }
    coVerify { fhirEngine.update(capture(carePlanSlot)) }
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", carePlanSlot.captured.id)
    Assert.assertEquals(CarePlan.CarePlanStatus.COMPLETED, carePlanSlot.captured.status)
  }

  @Test
  fun testUpdateResourcesRecursivelyClosesFilteredResource() = runTest {
    val patient =
      Patient().apply {
        id = "123345677"
        active = true
      }

    val serviceRequest =
      ServiceRequest().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = ServiceRequest.ServiceRequestStatus.DRAFT
        subject = patient.asReference()
      }

    val resourceConfig =
      ResourceConfig(id = "serviceRequest-id", resource = serviceRequest.resourceType)

    fhirEngine.create(patient, serviceRequest)

    val updatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "ServiceRequest.status",
        value = JsonPrimitive("revoked"),
        resourceType = ResourceType.ServiceRequest,
      )
    val resourceFilterExpression =
      ResourceFilterExpression(
        conditionalFhirPathExpressions = listOf("ServiceRequest.status != 'completed'"),
        resourceType = ResourceType.ServiceRequest,
      )
    val eventWorkflow =
      EventWorkflow(
        updateValues = listOf(updatedValues),
        resourceFilterExpressions = listOf(resourceFilterExpression),
      )

    defaultRepository.updateResourcesRecursively(
      resourceConfig = resourceConfig,
      subject = patient,
      eventWorkflow = eventWorkflow,
    )

    val serviceRequestSlot = slot<ServiceRequest>()
    coVerify { fhirEngine.update(capture(serviceRequestSlot)) }
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", serviceRequestSlot.captured.id)
    Assert.assertEquals(
      ServiceRequest.ServiceRequestStatus.REVOKED,
      serviceRequestSlot.captured.status,
    )
  }

  @Test
  fun testUpdateResourcesRecursivelyDoesNotCloseFilteredOutResource() = runTest {
    val patient =
      Patient().apply {
        id = "123345677"
        active = true
      }

    val serviceRequest =
      ServiceRequest().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = ServiceRequest.ServiceRequestStatus.COMPLETED
        subject = patient.asReference()
      }

    val resourceConfig =
      ResourceConfig(id = "serviceRequest-id", resource = serviceRequest.resourceType)

    fhirEngine.create(patient, serviceRequest)

    val updatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "ServiceRequest.status",
        value = JsonPrimitive("revoked"),
        resourceType = ResourceType.ServiceRequest,
      )

    val resourceFilterExpression =
      ResourceFilterExpression(
        conditionalFhirPathExpressions = listOf("ServiceRequest.status != 'completed'"),
        resourceType = ResourceType.ServiceRequest,
      )
    val eventWorkflow =
      EventWorkflow(
        updateValues = listOf(updatedValues),
        resourceFilterExpressions = listOf(resourceFilterExpression),
      )

    defaultRepository.updateResourcesRecursively(
      resourceConfig = resourceConfig,
      subject = patient,
      eventWorkflow = eventWorkflow,
    )

    coVerify(exactly = 0) { fhirEngine.update(any()) }
  }

  @Test
  fun testNonCareplanRelatedResourcesUpdatedCorrectlyAndWithNoSpecifiedBaseResource() = runTest {
    val encounter =
      Encounter().apply {
        id = "test-Encounter"
        period = Period().apply { start = Date().plusDays(-2) }
        status = Encounter.EncounterStatus.INPROGRESS
        type =
          listOf(
            CodeableConcept().apply {
              coding =
                listOf(
                  Coding().apply {
                    system = "http://smartregister.org/"
                    code = "SVISIT"
                    display = "Service Point Visit"
                  },
                )
              text = "Service Point Visit"
            },
          )
      }
    val eventWorkflow =
      EventWorkflow(
        triggerConditions =
          listOf(
            EventTriggerCondition(
              eventResourceId = "encounterToBeClosed",
              matchAll = false,
              conditionalFhirPathExpressions =
                listOf(
                  "true",
                ),
            ),
          ),
        eventResources =
          listOf(
            ResourceConfig(
              id = "encounterToBeClosed",
              resource = ResourceType.Encounter,
              configRules = listOf(),
              dataQueries =
                listOf(
                  DataQuery(
                    paramName = "reason-code",
                    filterCriteria =
                      listOf(
                        FilterCriterionConfig.TokenFilterCriterionConfig(
                          dataType = Enumerations.DataType.CODEABLECONCEPT,
                          value = Code(system = "http://smartregister.org/", code = "SVISIT"),
                        ),
                      ),
                  ),
                ),
            ),
          ),
        updateValues =
          listOf(
            UpdateWorkflowValueConfig(
              jsonPathExpression = "Encounter.status",
              value = JsonPrimitive("finished"),
              resourceType = ResourceType.Encounter,
            ),
          ),
        resourceFilterExpressions =
          listOf(
            ResourceFilterExpression(
              conditionalFhirPathExpressions = listOf("Encounter.period.end < now()"),
              matchAll = true,
            ),
          ),
      )
    fhirEngine.create(encounter)
    defaultRepository.updateResourcesRecursively(
      resourceConfig = eventWorkflow.eventResources[0],
      eventWorkflow = eventWorkflow,
    )
    val resourceSlot = slot<Encounter>()
    val captured = resourceSlot.captured
    coVerify { fhirEngine.update(capture(resourceSlot)) }
    Assert.assertEquals("test-Encounter", captured.id)
    Assert.assertEquals(Encounter.EncounterStatus.FINISHED, captured.status)
  }

  @Test
  fun testUpdateResourcesRecursivelyClosesResource() = runTest {
    val patient =
      Patient().apply {
        id = "123345677"
        active = true
      }

    val carePlan =
      CarePlan().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = CarePlan.CarePlanStatus.DRAFT
        subject = patient.asReference()
      }

    val resourceConfig = ResourceConfig(id = "carePlan-id", resource = carePlan.resourceType)

    fhirEngine.create(patient, carePlan)

    val updatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "CarePlan.status",
        value = JsonPrimitive("completed"),
        resourceType = ResourceType.CarePlan,
      )
    val eventWorkflow = EventWorkflow(updateValues = listOf(updatedValues))
    defaultRepository.updateResourcesRecursively(
      resourceConfig = resourceConfig,
      subject = patient,
      eventWorkflow = eventWorkflow,
    )

    val carePlanSlot = slot<CarePlan>()
    coVerify { fhirEngine.update(capture(carePlanSlot)) }
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", carePlanSlot.captured.id)
    Assert.assertEquals(CarePlan.CarePlanStatus.COMPLETED, carePlanSlot.captured.status)
  }

  @Test
  fun testUpdateResources() = runTest {
    val patient =
      Patient().apply {
        id = "Patient/6c9553f4-f237-498c-b500-058883841d51"
        active = true
      }

    val carePlan =
      CarePlan().apply {
        id = "CarePlan/3e04863c-41cc-47a4-b57e-eb1cc4e00ef8"
        status = CarePlan.CarePlanStatus.ACTIVE
        subject = patient.asReference()
      }

    val resourceConfig =
      ResourceConfig(
        id = "carePlan-id",
        resource = carePlan.resourceType,
        configRules =
          listOf(
            RuleConfig(
              name = "patientId",
              condition = "true",
              actions =
                listOf(
                  "data.put('patientId', fhirPath.extractValue(Patient, 'Patient.id'))",
                ),
            ),
          ),
        dataQueries =
          listOf(
            DataQuery(
              paramName = "instantiates-canonical",
              filterCriteria =
                listOf(
                  FilterCriterionConfig.ReferenceFilterCriterionConfig(
                    dataType = Enumerations.DataType.REFERENCE,
                    value = "PlanDefinition/cd39380b-2359-4b98-8ab9-df7f90fe9392",
                  ),
                ),
            ),
            DataQuery(
              paramName = "subject",
              filterCriteria =
                listOf(
                  FilterCriterionConfig.ReferenceFilterCriterionConfig(
                    dataType = Enumerations.DataType.REFERENCE,
                    value = "patientId",
                  ),
                ),
            ),
          ),
      )

    fhirEngine.create(patient, carePlan)

    val updatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "CarePlan.status",
        value = JsonPrimitive("completed"),
      )
    val eventWorkflow = EventWorkflow(updateValues = listOf(updatedValues))
    defaultRepository.updateResourcesRecursively(
      resourceConfig = resourceConfig,
      subject = patient,
      eventWorkflow = eventWorkflow,
    )

    val resourceSlot = slot<Resource>()
    val captured = resourceSlot.captured as CarePlan
    coVerify { fhirEngine.update(capture(resourceSlot)) }
    Assert.assertEquals("3e04863c-41cc-47a4-b57e-eb1cc4e00ef8", captured.logicalId)
    Assert.assertEquals(CarePlan.CarePlanStatus.COMPLETED, captured.status)
  }

  @Test
  fun testUpdateResourcesRecursively() = runTest {
    val patient =
      Patient().apply {
        id = "Patient/6c9553f4-f237-498c-b500-058883841d51"
        active = true
      }

    val task =
      Task().apply {
        id = "Task/e0bd5cd6-5a36-45c2-8c32-1dac77909179"
        status = Task.TaskStatus.READY
        `for` = patient.asReference()
        executionPeriod.end = Date(java.time.LocalDate.parse("2024-04-03").toEpochDay())
      }

    val resourceConfig =
      ResourceConfig(
        resource = task.resourceType,
        configRules =
          listOf(
            RuleConfig(
              name = "patientId",
              condition = "true",
              actions =
                listOf(
                  "data.put('patientId', fhirPath.extractValue(Patient, 'Patient.id'))",
                ),
            ),
            RuleConfig(
              name = "taskEnd",
              condition = "true",
              actions =
                listOf(
                  "data.put('taskEnd', fhirPath.extractValue(Task, 'Task.executionPeriod.end'))",
                ),
            ),
          ),
        dataQueries =
          listOf(
            DataQuery(
              paramName = "subject",
              filterCriteria =
                listOf(
                  FilterCriterionConfig.ReferenceFilterCriterionConfig(
                    dataType = Enumerations.DataType.REFERENCE,
                    computedRule = "patientId",
                  ),
                ),
            ),
            DataQuery(
              paramName = "period",
              filterCriteria =
                listOf(
                  FilterCriterionConfig.DateFilterCriterionConfig(
                    dataType = Enumerations.DataType.DATETIME,
                    valueAsDateTime = true,
                    computedRule = "2024-04-03",
                    value = "2024-04-03",
                    prefix = ParamPrefixEnum.LESSTHAN,
                  ),
                ),
            ),
          ),
      )

    fhirEngine.create(patient, task)

    val updatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "Task.status",
        value = JsonPrimitive("completed"),
      )

    val eventWorkflow = EventWorkflow(updateValues = listOf(updatedValues))
    defaultRepository.updateResourcesRecursively(
      resourceConfig = resourceConfig,
      subject = patient,
      eventWorkflow = eventWorkflow,
    )

    val resourceSlot = slot<Resource>()
    val captured = resourceSlot.captured as Task
    coVerify { fhirEngine.update(capture(resourceSlot)) }
    Assert.assertEquals("e0bd5cd6-5a36-45c2-8c32-1dac77909179", captured.id)
    Assert.assertEquals(Task.TaskStatus.COMPLETED, captured.status)
  }

  @Test
  fun testFilterRelatedResourcesShouldReturnTrueIfProvidedExpressionEvaluatesToTrue() {
    val resourceConfig =
      ResourceConfig(
        resource = ResourceType.Task,
        filterFhirPathExpressions = listOf(KeyValueConfig("Task.status", "ready")),
      )
    val task =
      Task().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = Task.TaskStatus.READY
      }
    val result = defaultRepository.filterRelatedResource(task, resourceConfig)
    Assert.assertTrue(result)
  }

  @Test
  fun testFilterRelatedResourcesShouldReturnFalseIfProvidedExpressionEvaluatesToFalse() {
    val resourceConfig =
      ResourceConfig(
        resource = ResourceType.Task,
        filterFhirPathExpressions = listOf(KeyValueConfig("Task.status", "cancelled")),
      )
    val task =
      Task().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = Task.TaskStatus.READY
      }
    val result = defaultRepository.filterRelatedResource(task, resourceConfig)
    Assert.assertFalse(result)
  }

  @Test
  fun testFilterRelatedResourcesShouldReturnTrueIfExpressionIsNotProvided() {
    val resourceConfig = ResourceConfig(resource = ResourceType.Task)
    val task =
      Task().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = Task.TaskStatus.READY
      }
    val result = defaultRepository.filterRelatedResource(task, resourceConfig)
    Assert.assertTrue(result)
  }

  @Test
  fun testCloseResourceUpdatesCorrectProcedureStatus() {
    val procedure =
      Procedure().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = Procedure.ProcedureStatus.UNKNOWN
      }
    coEvery { fhirEngine.update(any()) } just runs
    val procedureSlot = slot<Procedure>()

    val updatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "Procedure.status",
        value = JsonPrimitive("stopped"),
        resourceType = ResourceType.Procedure,
      )
    val eventWorkflow = EventWorkflow(updateValues = listOf(updatedValues))
    runBlocking { defaultRepository.closeResource(procedure, eventWorkflow) }
    coVerify { fhirEngine.update(capture(procedureSlot)) }
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", procedureSlot.captured.id)
    Assert.assertEquals(Procedure.ProcedureStatus.STOPPED, procedureSlot.captured.status)
  }

  @Test
  fun testCloseResourceUpdatesCorrectServiceRequestStatus() {
    val serviceRequest =
      ServiceRequest().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = ServiceRequest.ServiceRequestStatus.ACTIVE
      }
    coEvery { fhirEngine.update(any()) } just runs
    val serviceRequestSlot = slot<ServiceRequest>()
    val updatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "ServiceRequest.status",
        value = JsonPrimitive("revoked"),
        resourceType = ResourceType.ServiceRequest,
      )
    val eventWorkflow = EventWorkflow(updateValues = listOf(updatedValues))
    runBlocking {
      defaultRepository.closeResource(
        serviceRequest,
        eventWorkflow,
      )
    }
    coVerify { fhirEngine.update(capture(serviceRequestSlot)) }
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", serviceRequestSlot.captured.id)
    Assert.assertEquals(
      ServiceRequest.ServiceRequestStatus.REVOKED,
      serviceRequestSlot.captured.status,
    )
  }

  @Test
  fun testCloseResourceUpdatesCorrectConditionStatus() {
    val condition =
      Condition().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        clinicalStatus =
          CodeableConcept().apply {
            coding =
              listOf(
                Coding().apply {
                  system = "sample system"
                  display = "sample display"
                  code = "sample code"
                },
              )
          }
      }
    coEvery { fhirEngine.update(any()) } just runs
    val conditionSlot = slot<Condition>()

    val updatedValueCode =
      UpdateWorkflowValueConfig(
        "Condition.clinicalStatus.coding[0].code",
        JsonPrimitive(PATIENT_CONDITION_RESOLVED_CODE),
        resourceType = ResourceType.Condition,
      )
    val updatedValueDisplay =
      UpdateWorkflowValueConfig(
        "Condition.clinicalStatus.coding[0].display",
        JsonPrimitive(PATIENT_CONDITION_RESOLVED_DISPLAY),
        resourceType = ResourceType.Condition,
      )
    val updatedValueSystem =
      UpdateWorkflowValueConfig(
        "Condition.clinicalStatus.coding[0].system",
        JsonPrimitive(SNOMED_SYSTEM),
        resourceType = ResourceType.Condition,
      )

    val eventWorkflow =
      EventWorkflow(
        updateValues = listOf(updatedValueCode, updatedValueDisplay, updatedValueSystem),
      )
    runBlocking { defaultRepository.closeResource(condition, eventWorkflow) }
    coVerify { fhirEngine.update(capture(conditionSlot)) }
    val capturedCode = conditionSlot.captured.clinicalStatus.coding.first()
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", conditionSlot.captured.id)
    Assert.assertEquals(PATIENT_CONDITION_RESOLVED_CODE, capturedCode.code)
    Assert.assertEquals(SNOMED_SYSTEM, capturedCode.system)
    Assert.assertEquals(PATIENT_CONDITION_RESOLVED_DISPLAY, capturedCode.display)
  }

  @Test
  fun testCloseResourceUpdatesRestOfTheResourcesWhenUpdatingOneResourceThrowsAnException() {
    val carePlan = CarePlan().apply { id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53" }
    coEvery { fhirEngine.update(any()) } just runs
    val carePlanSlot = slot<CarePlan>()

    val carePlanUpdatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "CarePlan.status",
        value = JsonPrimitive("completed"),
        resourceType = ResourceType.CarePlan,
      )
    val carePlanEventWorkflow = EventWorkflow(updateValues = listOf(carePlanUpdatedValues))

    runBlocking { defaultRepository.closeResource(carePlan, carePlanEventWorkflow) }
    coVerify { fhirEngine.update(capture(carePlanSlot)) }
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", carePlanSlot.captured.id)
    Assert.assertNull(carePlanSlot.captured.status)

    val procedure =
      Procedure().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        status = Procedure.ProcedureStatus.UNKNOWN
      }
    coEvery { fhirEngine.update(any()) } just runs
    val procedureSlot = slot<Procedure>()

    val updatedValues =
      UpdateWorkflowValueConfig(
        jsonPathExpression = "Procedure.status",
        value = JsonPrimitive("stopped"),
        resourceType = ResourceType.Procedure,
      )
    val eventWorkflow = EventWorkflow(updateValues = listOf(updatedValues))
    runBlocking { defaultRepository.closeResource(procedure, eventWorkflow) }
    coVerify { fhirEngine.update(capture(procedureSlot)) }
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", procedureSlot.captured.id)
    Assert.assertEquals(Procedure.ProcedureStatus.STOPPED, procedureSlot.captured.status)
  }

  // TODO Refactor/Remove after https://github.com/opensrp/fhircore/issues/2488
  @Test
  fun testCloseResourceUpdatesCorrectConditionStatusForClosePNCCondition() {
    val condition =
      Condition().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        clinicalStatus =
          CodeableConcept().apply {
            coding =
              listOf(
                Coding().apply {
                  system = "sample system"
                  display = "sample display"
                  code = "sample code"
                },
              )
          }
        onset =
          DateTimeType(
            Date.from(
              java.time.LocalDate.now()
                .minusDays(30)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant(),
            ),
          )
      }
    coEvery { fhirEngine.update(any()) } just runs
    val conditionSlot = slot<Condition>()
    val updatedValueCode =
      UpdateWorkflowValueConfig(
        "Condition.clinicalStatus.coding[0].code",
        JsonPrimitive(PATIENT_CONDITION_RESOLVED_CODE),
        resourceType = ResourceType.Condition,
      )
    val updatedValueDisplay =
      UpdateWorkflowValueConfig(
        "Condition.clinicalStatus.coding[0].display",
        JsonPrimitive(PATIENT_CONDITION_RESOLVED_DISPLAY),
        resourceType = ResourceType.Condition,
      )
    val updatedValueSystem =
      UpdateWorkflowValueConfig(
        "Condition.clinicalStatus.coding[0].system",
        JsonPrimitive(SNOMED_SYSTEM),
        resourceType = ResourceType.Condition,
      )

    val eventWorkflow =
      EventWorkflow(
        updateValues = listOf(updatedValueCode, updatedValueDisplay, updatedValueSystem),
      )

    runBlocking {
      defaultRepository.closeResource(
        condition,
        eventWorkflow = eventWorkflow,
      )
    }
    coVerify { fhirEngine.update(capture(conditionSlot)) }
    val capturedCode = conditionSlot.captured.clinicalStatus.coding.first()
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", conditionSlot.captured.id)
    Assert.assertEquals(PATIENT_CONDITION_RESOLVED_CODE, capturedCode.code)
    Assert.assertEquals(SNOMED_SYSTEM, capturedCode.system)
    Assert.assertEquals(PATIENT_CONDITION_RESOLVED_DISPLAY, capturedCode.display)
  }

  // TODO Refactor/Remove after https://github.com/opensrp/fhircore/issues/2488
  @Test
  fun testCloseResourceUpdatesCorrectConditionStatusForCloseSickChildCondition() {
    val condition =
      Condition().apply {
        id = "37793d31-def5-40bd-a2e3-fdaf5a0ddc53"
        clinicalStatus =
          CodeableConcept().apply {
            coding =
              listOf(
                Coding().apply {
                  system = "sample system"
                  display = "sample display"
                  code = "sample code"
                },
              )
          }
        onset =
          DateTimeType(
            Date.from(
              java.time.LocalDate.now()
                .minusDays(30)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant(),
            ),
          )
      }
    coEvery { fhirEngine.update(any()) } just runs
    val conditionSlot = slot<Condition>()
    val updatedValueCode =
      UpdateWorkflowValueConfig(
        "Condition.clinicalStatus.coding[0].code",
        JsonPrimitive(PATIENT_CONDITION_RESOLVED_CODE),
        resourceType = ResourceType.Condition,
      )
    val updatedValueDisplay =
      UpdateWorkflowValueConfig(
        "Condition.clinicalStatus.coding[0].display",
        JsonPrimitive(PATIENT_CONDITION_RESOLVED_DISPLAY),
        resourceType = ResourceType.Condition,
      )
    val updatedValueSystem =
      UpdateWorkflowValueConfig(
        "Condition.clinicalStatus.coding[0].system",
        JsonPrimitive(SNOMED_SYSTEM),
        resourceType = ResourceType.Condition,
      )

    val eventWorkflow =
      EventWorkflow(
        updateValues = listOf(updatedValueCode, updatedValueDisplay, updatedValueSystem),
      )

    runBlocking {
      defaultRepository.closeResource(
        condition,
        eventWorkflow,
      )
    }
    coVerify { fhirEngine.update(capture(conditionSlot)) }
    val capturedCode = conditionSlot.captured.clinicalStatus.coding.first()
    Assert.assertEquals("37793d31-def5-40bd-a2e3-fdaf5a0ddc53", conditionSlot.captured.id)
    Assert.assertEquals(PATIENT_CONDITION_RESOLVED_CODE, capturedCode.code)
    Assert.assertEquals(SNOMED_SYSTEM, capturedCode.system)
    Assert.assertEquals(PATIENT_CONDITION_RESOLVED_DISPLAY, capturedCode.display)
  }

  @Test
  fun `createRemote() should correctly invoke FhirEngine#createRemote`() {
    val resource = spyk(Patient())
    coEvery { fhirEngine.create(resource, isLocalOnly = true) } returns listOf(resource.id)

    runBlocking { defaultRepository.createRemote(false, resource) }

    coVerify { fhirEngine.create(resource, isLocalOnly = true) }
  }

  @Test
  fun testRetrieveUniqueIdAssignmentResourceShouldReturnAResource() =
    runTest(timeout = 30.seconds) {
      val group1 =
        Group().apply {
          id = "grp1"
          addCharacteristic(
            Group.GroupCharacteristicComponent(
              CodeableConcept().apply { text = "phn" },
              CodeableConcept().apply { text = "1234" },
              BooleanType(true),
            ),
          )
          addCharacteristic(
            Group.GroupCharacteristicComponent(
              CodeableConcept().apply { text = "phn" },
              CodeableConcept().apply { text = "123456" },
              BooleanType(false),
            ),
          )
          active = true
          type = Group.GroupType.DEVICE
          name = "Unique IDs"
        }

      val group2 =
        Group().apply {
          id = "grp2"
          addCharacteristic(
            Group.GroupCharacteristicComponent(
              CodeableConcept().apply { text = "phn" },
              CodeableConcept().apply { text = "56789" },
              BooleanType(false),
            ),
          )
          active = false
          type = Group.GroupType.DEVICE
          name = "Unique IDs"
        }

      val uniqueIdAssignmentConfig =
        UniqueIdAssignmentConfig(
          linkId = "phn",
          idFhirPathExpression =
            "Group.characteristic.where(exclude=false and code.text='phn').first().value.text",
          readOnly = false,
          resource = ResourceType.Group,
          resourceFilterExpression =
            ResourceFilterExpression(
              conditionalFhirPathExpressions =
                listOf(
                  "Group.active = true and Group.type = 'device' and Group.name = 'Unique IDs'",
                ),
              matchAll = true,
            ),
        )

      fhirEngine.create(group1, group2)
      val resource =
        defaultRepository.retrieveUniqueIdAssignmentResource(uniqueIdAssignmentConfig, emptyMap())
      Assert.assertNotNull(resource)
      Assert.assertTrue(resource is Group)
      Assert.assertEquals("1234", (resource as Group).characteristic[0].valueCodeableConcept.text)
      Assert.assertFalse(resource.characteristic[1].exclude)
    }

  @Test
  fun testRetrieveFlattenedSubLocationsShouldReturnCorrectLocationIds() =
    runTest(timeout = 120.seconds) {
      val location1 = Location().apply { id = "loc1" }
      val location2 =
        Location().apply {
          id = "loc2"
          partOf = location1.asReference()
        }
      val location3 =
        Location().apply {
          id = "loc3"
          partOf = location1.asReference()
        }
      val location4 =
        Location().apply {
          id = "loc4"
          partOf = location3.asReference()
        }
      val location5 =
        Location().apply {
          id = "loc5"
          partOf = location4.asReference()
        }

      fhirEngine.create(location1, location2, location3, location4, location5, isLocalOnly = true)

      val location1SubLocations =
        defaultRepository.retrieveFlattenedSubLocationIds(listOf(location1.logicalId))
      Assert.assertEquals(5, location1SubLocations.size)
      Assert.assertTrue(location1SubLocations.contains(location2.logicalId))
      Assert.assertTrue(location1SubLocations.contains(location3.logicalId))
      Assert.assertTrue(location1SubLocations.contains(location4.logicalId))
      Assert.assertTrue(location1SubLocations.contains(location5.logicalId))

      val location4SubLocations =
        defaultRepository.retrieveFlattenedSubLocationIds(listOf(location4.logicalId))
      Assert.assertEquals(2, location4SubLocations.size)
      Assert.assertEquals(location5.logicalId, location4SubLocations.last())
    }

  @Test
  fun testSearchLatestQuestionnaireResponseShouldReturnLatestQuestionnaireResponse() =
    runTest(timeout = 90.seconds) {
      val sampleEncounter =
        Encounter().apply {
          id = "encounter-id-1"
          subject = patient.asReference()
        }
      Assert.assertNull(
        defaultRepository.searchQuestionnaireResponse(
          resourceId = patient.logicalId,
          resourceType = ResourceType.Patient,
          questionnaireId = questionnaireConfig.id,
          encounterId = sampleEncounter.id,
        ),
      )

      val questionnaireResponses =
        listOf(
          QuestionnaireResponse().apply {
            id = "qr1"
            meta.lastUpdated = Date()
            subject = patient.asReference()
            questionnaire = samplePatientRegisterQuestionnaire.asReference().reference
            encounter = sampleEncounter.asReference()
          },
          QuestionnaireResponse().apply {
            id = "qr2"
            meta.lastUpdated = yesterday()
            subject = patient.asReference()
            questionnaire = samplePatientRegisterQuestionnaire.asReference().reference
          },
        )

      // Add QuestionnaireResponse to database
      fhirEngine.create(
        patient,
        samplePatientRegisterQuestionnaire,
        *questionnaireResponses.toTypedArray(),
      )

      val latestQuestionnaireResponse =
        defaultRepository.searchQuestionnaireResponse(
          resourceId = patient.logicalId,
          resourceType = ResourceType.Patient,
          questionnaireId = questionnaireConfig.id,
          encounterId = sampleEncounter.id,
        )
      Assert.assertNotNull(latestQuestionnaireResponse)
      Assert.assertEquals("QuestionnaireResponse/qr1", latestQuestionnaireResponse?.id)
      Assert.assertEquals(
        "Encounter/encounter-id-1",
        latestQuestionnaireResponse?.encounter?.reference,
      )
    }

  @Test
  fun testSearchLatestQuestionnaireResponseShouldPreferMostRecentlyAuthoredResponse() =
    runTest(timeout = 90.seconds) {
      // Both responses were uploaded in one sync, so lastUpdated does not reflect their order
      val questionnaireResponses =
        listOf(
          QuestionnaireResponse().apply {
            id = "qr-older-edit"
            authored = yesterday()
            meta.lastUpdated = Date()
            subject = patient.asReference()
            questionnaire = samplePatientRegisterQuestionnaire.asReference().reference
          },
          QuestionnaireResponse().apply {
            id = "qr-latest-edit"
            authored = Date()
            meta.lastUpdated = yesterday()
            subject = patient.asReference()
            questionnaire = samplePatientRegisterQuestionnaire.asReference().reference
          },
        )
      fhirEngine.create(
        patient,
        samplePatientRegisterQuestionnaire,
        *questionnaireResponses.toTypedArray(),
      )

      val latestQuestionnaireResponse =
        defaultRepository.searchQuestionnaireResponse(
          resourceId = patient.logicalId,
          resourceType = ResourceType.Patient,
          questionnaireId = questionnaireConfig.id,
          encounterId = null,
        )
      Assert.assertEquals(
        "QuestionnaireResponse/qr-latest-edit",
        latestQuestionnaireResponse?.id,
      )
    }

  @Test
  fun testSearchLatestQuestionnaireResponseWhenSaveDraftIsTrueShouldReturnLatestQuestionnaireResponse() =
    runTest(timeout = 90.seconds) {
      Assert.assertNull(
        defaultRepository.searchQuestionnaireResponse(
          resourceId = patient.logicalId,
          resourceType = ResourceType.Patient,
          questionnaireId = questionnaireConfig.id,
          encounterId = null,
          questionnaireResponseStatus = QuestionnaireResponseStatus.INPROGRESS.toCode(),
        ),
      )

      val questionnaireResponses =
        listOf(
          QuestionnaireResponse().apply {
            id = "qr1"
            meta.lastUpdated = Date()
            subject = patient.asReference()
            questionnaire = samplePatientRegisterQuestionnaire.asReference().reference
            status = QuestionnaireResponseStatus.INPROGRESS
          },
          QuestionnaireResponse().apply {
            id = "qr2"
            meta.lastUpdated = yesterday()
            subject = patient.asReference()
            questionnaire = samplePatientRegisterQuestionnaire.asReference().reference
            status = QuestionnaireResponseStatus.COMPLETED
          },
        )

      // Add QuestionnaireResponse to database
      fhirEngine.create(
        patient,
        samplePatientRegisterQuestionnaire,
        *questionnaireResponses.toTypedArray(),
      )

      val latestQuestionnaireResponse =
        defaultRepository.searchQuestionnaireResponse(
          resourceId = patient.logicalId,
          resourceType = ResourceType.Patient,
          questionnaireId = questionnaireConfig.id,
          encounterId = null,
          questionnaireResponseStatus = QuestionnaireResponseStatus.INPROGRESS.toCode(),
        )
      Assert.assertNotNull(latestQuestionnaireResponse)
      Assert.assertEquals("QuestionnaireResponse/qr1", latestQuestionnaireResponse?.id)
    }

  @Test
  fun testRetrieveFlattenedSubLocationsShouldReturnCorrectLocationIdsWhenFilterDataByLocationLineageMetaTagIsTrue() =
    runTest(timeout = 120.seconds) {
      val location1 = Location().apply { id = "loc1" }
      val location1LineageTag =
        Coding().apply {
          system = "http://example.org/location-lineage"
          code = location1.logicalId
          display = "Location 1"
        }
      val location2 =
        Location().apply {
          id = "loc2"
          meta = Meta().apply { tag.add(location1LineageTag) }
          partOf = location1.asReference()
        }
      val location2LineageTag =
        Coding().apply {
          system = "http://example.org/location-lineage"
          code = location2.logicalId
          display = "Location 2"
        }
      val location3 =
        Location().apply {
          id = "loc3"
          meta = Meta().apply { tag.add(location1LineageTag) }
          partOf = location2.asReference()
        }
      val location4 =
        Location().apply {
          id = "loc4"
          partOf = location3.asReference()
        }
      val location5 =
        Location().apply {
          id = "loc5"
          meta = Meta().apply { tag.add(location2LineageTag) }
          partOf = location4.asReference()
        }

      fhirEngine.create(location1, location2, location3, location4, location5, isLocalOnly = true)

      val location1SubLocations =
        defaultRepository.retrieveFlattenedSubLocationIds(
          listOf(location1.logicalId),
          filterDataByLocationLineageMetaTag = true,
        )
      Assert.assertEquals(3, location1SubLocations.size)
      Assert.assertTrue(location1SubLocations.contains(location2.logicalId))
      Assert.assertTrue(location1SubLocations.contains(location3.logicalId))

      val location2SubLocations =
        defaultRepository.retrieveFlattenedSubLocationIds(
          listOf(location2.logicalId),
          filterDataByLocationLineageMetaTag = true,
        )
      Assert.assertEquals(2, location2SubLocations.size)
      Assert.assertEquals(location5.logicalId, location2SubLocations.last())
    }
}
