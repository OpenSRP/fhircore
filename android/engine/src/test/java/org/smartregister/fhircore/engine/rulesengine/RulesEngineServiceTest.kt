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

package org.smartregister.fhircore.engine.rulesengine

import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.search.Order
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.coVerify
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.model.CarePlan
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.Task
import org.joda.time.DateTime
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.domain.model.RelatedResourceCount
import org.smartregister.fhircore.engine.domain.model.ServiceStatus
import org.smartregister.fhircore.engine.robolectric.RobolectricTest

@HiltAndroidTest
class RulesEngineServiceTest : RobolectricTest() {
  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @Inject lateinit var rulesFactory: RulesFactory

  @Inject lateinit var defaultRepository: DefaultRepository

  private lateinit var rulesEngineService: RulesFactory.RulesEngineService

  private val tasks =
    listOf(
      Task().apply {
        id = "task1"
        description = "Issue bed net"
        executionPeriod = Period().apply { start = DateTime.now().minusDays(2).toDate() }
      },
      Task().apply {
        id = "task2"
        description = "Malaria vaccination"
        executionPeriod = Period().apply { start = DateTime.now().toDate() }
      },
      Task().apply {
        id = "task3"
        description = "Covid vaccination"
        executionPeriod = Period().apply { start = DateTime.now().minusDays(1).toDate() }
      },
    )

  @Before
  fun setUp() {
    hiltRule.inject()
    rulesEngineService = rulesFactory.RulesEngineService()
    Assert.assertNotNull(rulesEngineService)
  }

  @After
  fun tearDown() {
    Locale.setDefault(Locale.ENGLISH)
  }

  @Test
  fun testTranslateWithDefaultLocaleReturnsCorrectTranslatedString() {
    val templateString = "Vaccine status"

    val result = rulesEngineService.translate(templateString)

    Assert.assertEquals("Translated Vaccine status", result)
  }

  @Test
  fun testTranslateWithOtherLocaleReturnsCorrectTranslatedString() {
    val templateString = "Vaccine status"
    Locale.setDefault(Locale.FRENCH)

    val result = rulesEngineService.translate(templateString)

    Assert.assertEquals("Statut Vaccinal Traduit", result)
  }

  @Test
  fun testTranslate_WithValidTranslation_ReturnsTranslatedText() {
    // Uses real translation from strings.properties
    val result = rulesEngineService.translate("Discuss Confidentiality")

    Assert.assertEquals("Discuss Confidentiality (English)", result)
  }

  @Test
  fun testTranslate_WithUnknownText_FallsBackToOriginal() {
    val originalText = "Some Unknown Task That Does Not Exist"

    val result = rulesEngineService.translate(originalText)

    // Should fallback to original text and never return template strings
    Assert.assertEquals(originalText, result)
    Assert.assertFalse(result.contains("{{"))
    Assert.assertFalse(result.contains("}}"))
  }

  @Test
  fun testTranslate_WithEmptyString_ReturnsEmptyString() {
    val result = rulesEngineService.translate("")

    Assert.assertEquals("", result)
  }

  @Test
  fun testTranslate_WithSpecialCharactersAndNumbers_HandlesCorrectly() {
    // Tests dashes, numbers, and special characters
    val result = rulesEngineService.translate("PHQ-9")

    Assert.assertEquals("PHQ-9 Screening", result)
  }

  @Test
  fun testTranslate_WithWhitespace_TrimsAndTranslates() {
    // Tests whitespace trimming and consecutive spaces
    val result = rulesEngineService.translate("  IPC     Session    1  ")

    Assert.assertEquals("IPC Session 1 (English)", result)
  }

  @Test
  fun testTranslate_WithUppercaseInput_MatchesLowercaseKey() {
    // Tests case insensitivity - uppercase converted to lowercase for key lookup
    val result = rulesEngineService.translate("DISCUSS CONFIDENTIALITY")

    Assert.assertEquals("Discuss Confidentiality (English)", result)
  }

  @Test
  fun testTranslate_WithParenthesesInOriginal_HandlesCorrectly() {
    // Tests parentheses and dashes in original text
    val result = rulesEngineService.translate("Information Only (Low Risk)")

    Assert.assertEquals("Information Only - Low Risk Category", result)
  }

  @Test
  fun testTranslate_WithFrenchLocale_ReturnsCorrectTranslation() {
    Locale.setDefault(Locale.FRENCH)

    val result = rulesEngineService.translate("Discuss Confidentiality")

    Assert.assertEquals("Discuter de la Confidentialité", result)
  }

  @Test
  fun testComputeTotalCountShouldReturnSumOfAllCounts() {
    val totalCount =
      rulesEngineService.computeTotalCount(
        listOf(
          RelatedResourceCount(relatedResourceType = ResourceType.Task, "abc", 20),
          RelatedResourceCount(relatedResourceType = ResourceType.Task, "zyx", 40),
          RelatedResourceCount(relatedResourceType = ResourceType.Task, "xyz", 40),
        ),
      )
    Assert.assertEquals(100, totalCount)

    Assert.assertEquals(0, rulesEngineService.computeTotalCount(emptyList()))
    Assert.assertEquals(0, rulesEngineService.computeTotalCount(null))
  }

  @Test
  fun testRetrieveCountShouldReturnExactCount() {
    val relatedResourceCounts =
      listOf(
        RelatedResourceCount(relatedResourceType = ResourceType.Task, "abc", 20),
        RelatedResourceCount(relatedResourceType = ResourceType.Task, "zyx", 40),
        RelatedResourceCount(relatedResourceType = ResourceType.Task, "xyz", 40),
      )
    val theCount = rulesEngineService.retrieveCount("xyz", relatedResourceCounts)
    Assert.assertEquals(40, theCount)

    Assert.assertEquals(0, rulesEngineService.retrieveCount("abz", relatedResourceCounts))
    Assert.assertEquals(0, rulesEngineService.retrieveCount("abc", emptyList()))
    Assert.assertEquals(0, rulesEngineService.retrieveCount("abc", null))
  }

  @Test
  fun testSortingResourcesShouldReturnListOfSortedResourcesInAscendingOrder() {
    val sortedResources =
      rulesEngineService.sortResources(
        tasks,
        "Task.description",
        Enumerations.DataType.STRING.name,
        Order.ASCENDING.name,
      )
    Assert.assertEquals(3, sortedResources?.size)
    Assert.assertTrue(
      listOf("Covid vaccination", "Issue bed net", "Malaria vaccination").sorted() ==
        sortedResources?.map { (it as Task).description },
    )
    val sortedByDateResources =
      rulesEngineService.sortResources(
        resources = tasks,
        fhirPathExpression = "Task.executionPeriod.start",
        dataType = Enumerations.DataType.DATETIME.name,
        order = Order.ASCENDING.name,
      )
    Assert.assertEquals(listOf("task1", "task3", "task2"), sortedByDateResources?.map { it.id })
  }

  @Test
  fun testSortingResourcesShouldReturnListOfSortedResourcesInDescendingOrder() {
    val sortedResources =
      rulesEngineService.sortResources(
        tasks,
        "Task.description",
        Enumerations.DataType.STRING.name,
        Order.DESCENDING.name,
      )
    Assert.assertEquals(3, sortedResources?.size)
    Assert.assertTrue(
      listOf("Covid vaccination", "Issue bed net", "Malaria vaccination").reversed() ==
        sortedResources?.map { (it as Task).description },
    )
    val sortedByDateResources =
      rulesEngineService.sortResources(
        tasks,
        "Task.executionPeriod.start",
        Enumerations.DataType.DATETIME.name,
        Order.DESCENDING.name,
      )
    Assert.assertEquals(
      listOf("task1", "task3", "task2").reversed(),
      sortedByDateResources?.map { it.id },
    )
  }

  @Test
  fun `generateTaskServiceStatus() should return en empty string when Task is NULL`() {
    Assert.assertEquals(
      "",
      rulesEngineService.generateTaskServiceStatus(null),
    )
  }

  @Test
  fun `generateTaskServiceStatus() should return UPCOMING when Task#status is NULL`() {
    val task = Task().apply { status = Task.TaskStatus.NULL }

    Assert.assertEquals(
      ServiceStatus.UPCOMING.name,
      rulesEngineService.generateTaskServiceStatus(task),
    )
  }

  @Test
  fun `generateTaskServiceStatus() should return UPCOMING when Task#status is Requested`() {
    val task = Task().apply { status = Task.TaskStatus.REQUESTED }

    Assert.assertEquals(
      ServiceStatus.UPCOMING.name,
      rulesEngineService.generateTaskServiceStatus(task),
    )
  }

  @Test
  fun `generateTaskServiceStatus() should return DUE when Task#status is READY`() {
    val task = Task().apply { status = Task.TaskStatus.READY }

    Assert.assertEquals(ServiceStatus.DUE.name, rulesEngineService.generateTaskServiceStatus(task))
  }

  @Test
  fun `generateTaskServiceStatus() should return INPROGRESS when Task#status is INPROGRESS`() {
    val task = Task().apply { status = Task.TaskStatus.INPROGRESS }

    Assert.assertEquals(
      ServiceStatus.IN_PROGRESS.name,
      rulesEngineService.generateTaskServiceStatus(task),
    )
  }

  @Test
  fun `generateTaskServiceStatus() should return COMPLETED when Task#status is COMPLETED`() {
    val task = Task().apply { status = Task.TaskStatus.COMPLETED }

    Assert.assertEquals(
      ServiceStatus.COMPLETED.name,
      rulesEngineService.generateTaskServiceStatus(task),
    )
  }

  @Test
  fun `generateTaskServiceStatus() should return EXPIRED when Task#status is CANCELLED`() {
    val task = Task().apply { status = Task.TaskStatus.CANCELLED }

    Assert.assertEquals(
      ServiceStatus.EXPIRED.name,
      rulesEngineService.generateTaskServiceStatus(task),
    )
  }

  @Test
  fun `generateTaskServiceStatus() should return FAILED when Task#status is FAILED`() {
    val task = Task().apply { status = Task.TaskStatus.FAILED }

    Assert.assertEquals(
      ServiceStatus.FAILED.name,
      rulesEngineService.generateTaskServiceStatus(task),
    )
  }

  @Test
  fun `generateTaskServiceStatus() should return OVERDUE when Task#executionPeriod#hasEnd() and Task#executionPeriod#end#before(today())`() {
    val sdf = SimpleDateFormat("dd/MM/yyyy")
    val startDate: Date? = sdf.parse("01/01/2023")
    val endDate: Date? = sdf.parse("01/02/2023")

    val task =
      Task().apply {
        status = Task.TaskStatus.INPROGRESS
        executionPeriod =
          Period().apply {
            start = startDate
            end = endDate
          }
      }

    Assert.assertEquals(
      ServiceStatus.OVERDUE.name,
      rulesEngineService.generateTaskServiceStatus(task),
    )
  }

  @Test
  fun `generateTaskServiceStatus() should not return OVERDUE when Task#executionPeriod#hasEnd() andTask#executionPeriod#end#before(today()) and Task#status is not INPROGRESS or READY`() {
    val sdf = SimpleDateFormat("dd/MM/yyyy")
    val startDate: Date? = sdf.parse("01/01/2023")
    val endDate: Date? = sdf.parse("01/02/2023")

    val task =
      Task().apply {
        status = Task.TaskStatus.REQUESTED
        executionPeriod =
          Period().apply {
            start = startDate
            end = endDate
          }
      }

    Assert.assertEquals(
      ServiceStatus.UPCOMING.name,
      rulesEngineService.generateTaskServiceStatus(task),
    )
  }

  @Test
  fun `generateListTaskServiceStatus() should return OVERDUE when list have Task#status is READY but date passed`() {
    val taskList = ArrayList<Task>()
    val task = Task().apply { status = Task.TaskStatus.REQUESTED }
    taskList.add(task)

    val sdf = SimpleDateFormat("dd/MM/yyyy")
    val startDate: Date? = sdf.parse("01/01/2023")
    val endDate: Date? = sdf.parse("01/02/2023")

    val taskOver =
      Task().apply {
        status = Task.TaskStatus.READY
        executionPeriod =
          Period().apply {
            start = startDate
            end = endDate
          }
      }

    taskList.add(taskOver)
    Assert.assertTrue(
      rulesEngineService.taskServiceStatusExist(taskList, ServiceStatus.OVERDUE.name),
    )
  }

  @Test
  fun `generateListTaskServiceStatus() should return DUE when list have Task#status is READY`() {
    val taskList = ArrayList<Task>()
    val task0 = Task().apply { status = Task.TaskStatus.READY }
    val task1 = Task().apply { status = Task.TaskStatus.INPROGRESS }
    val task2 = Task().apply { status = Task.TaskStatus.RECEIVED }
    val task3 = Task().apply { status = Task.TaskStatus.CANCELLED }
    val task4 = Task().apply { status = Task.TaskStatus.REJECTED }
    taskList.add(task0)
    taskList.add(task1)
    taskList.add(task2)
    taskList.add(task3)
    taskList.add(task4)

    Assert.assertTrue(rulesEngineService.taskServiceStatusExist(taskList, ServiceStatus.DUE.name))
  }

  @Test
  fun testGenerateListTaskServiceStatusShouldReturnUPCOMINGWhenListHaveTaskStatusIsREQUESTED() {
    val taskList = ArrayList<Task>()
    val task0 = Task().apply { status = Task.TaskStatus.REQUESTED }
    val task2 = Task().apply { status = Task.TaskStatus.COMPLETED }
    val task3 = Task().apply { status = Task.TaskStatus.RECEIVED }

    taskList.add(task0)
    taskList.add(task2)
    taskList.add(task3)

    Assert.assertTrue(
      rulesEngineService.taskServiceStatusExist(taskList, ServiceStatus.UPCOMING.name),
    )
  }

  @Test
  fun testGenerateListTaskServiceStatusShouldReturnINPROGRESSWhenListHaveTaskStatusIsINPROGRESS() {
    val taskList = ArrayList<Task>()
    val task1 = Task().apply { status = Task.TaskStatus.INPROGRESS }
    val task2 = Task().apply { status = Task.TaskStatus.COMPLETED }
    val task3 = Task().apply { status = Task.TaskStatus.CANCELLED }
    taskList.add(task1)
    taskList.add(task2)
    taskList.add(task3)

    Assert.assertTrue(
      rulesEngineService.taskServiceStatusExist(taskList, ServiceStatus.IN_PROGRESS.name),
    )
  }

  @Test
  fun testGenerateListTaskServiceStatusShouldReturnEXPIREDWhenListHaveTaskStatusIsEXPIRED() {
    val taskList = ArrayList<Task>()
    val task2 = Task().apply { status = Task.TaskStatus.COMPLETED }
    val task3 = Task().apply { status = Task.TaskStatus.CANCELLED }
    taskList.add(task2)
    taskList.add(task3)

    Assert.assertTrue(
      rulesEngineService.taskServiceStatusExist(taskList, ServiceStatus.EXPIRED.name),
    )
  }

  @Test
  fun testGenerateListTaskServiceStatusShouldReturnCOMPLETEDWhenListHaveTaskStatusIsCOMPLETED() {
    val taskList = ArrayList<Task>()
    val task2 = Task().apply { status = Task.TaskStatus.COMPLETED }
    taskList.add(task2)

    Assert.assertTrue(
      rulesEngineService.taskServiceStatusExist(taskList, ServiceStatus.COMPLETED.name),
    )
  }

  @Test
  fun testFilterResourcesWithFhirPathExtraction() {
    val task =
      Task().apply {
        executionPeriod =
          Period().apply {
            start = DateTime.now().minusMonths(2).toDate() // 2 months ago
            end = DateTime.now().minusMonths(1).toDate() // Task to end after a month
          }
      }

    val resources =
      listOf(
        CarePlan().apply {
          period =
            Period().apply {
              start = DateTime.now().minusDays(1).toDate()
              end = DateTime.now().plusDays(1).toDate() // Ends tomorrow
            }
        },
        CarePlan().apply {
          period =
            Period().apply {
              start = DateTime.now().minusMonths(12).toDate()
              end = DateTime.now().minusMonths(6).toDate() // Ended 6 months ago
            }
        },
        CarePlan().apply {
          period =
            Period().apply {
              start = DateTime.now().minusMonths(3).toDate() // 3 months ago
              end = DateTime.now().minusMonths(2).toDate() // Ended 2 months ago
            }
        },
      )

    // Comparison will be in the format -> CarePlan.period.end >= Task.executionPeriod.start
    val filteredResources =
      rulesEngineService.filterResources(
        resources = resources,
        fhirPathExpression = "CarePlan.period.end",
        dataType = "DATETIME",
        value = task.executionPeriod.start,
        compareToResult = arrayOf(1, 0),
      )
    Assert.assertFalse(filteredResources.isNullOrEmpty())
    Assert.assertEquals(2, filteredResources?.size)
    Assert.assertEquals(
      resources[0].period.end,
      (filteredResources!!.first() as CarePlan).period.end,
    )
    Assert.assertEquals(resources[2].period.end, (filteredResources.last() as CarePlan).period.end)
  }

  @Test
  fun testGetResourceByReferenceReturnsNullWhenResourceNotFound() = runTest {
    val resourceId = "uuid"

    coEvery { defaultRepository.fhirEngine.get(ResourceType.Patient, resourceId) } throws
      ResourceNotFoundException(ResourceType.Patient.name, resourceId)

    Assert.assertNull(rulesEngineService.getResourceByReference("Patient/uuid"))

    coVerify { defaultRepository.fhirEngine.get(ResourceType.Patient, resourceId) }
  }

  @Test
  fun testGetResourceByReferenceReturnsNullWhenReferenceIsNull() = runTest {
    Assert.assertNull(rulesEngineService.getResourceByReference(null))
  }

  @Test
  fun testGetResourceByReferenceReturnsNullWhenReferenceIsEmpty() = runTest {
    Assert.assertNull(rulesEngineService.getResourceByReference(""))
  }

  @Test
  fun testGetResourceByReferenceReturnsResourceWhenReferenceIsValid() = runTest {
    val expectedResource = Patient().apply { id = "uuid" }

    coEvery {
      defaultRepository.fhirEngine.get(ResourceType.Patient, expectedResource.logicalId)
    } answers { expectedResource }

    val result = rulesEngineService.getResourceByReference("Patient/uuid")

    Assert.assertEquals(expectedResource, result)

    coVerify {
      defaultRepository.fhirEngine.get(expectedResource.resourceType, expectedResource.logicalId)
    }
  }

  @Test
  fun testGetResourceByIdAndTypeReturnsNullWhenResourceIdIsNullAndResourceTypeIsValid() = runTest {
    Assert.assertNull(
      rulesEngineService.getResourceByIdAndType(null, ResourceType.Patient.name),
    )
  }

  @Test
  fun testGetResourceByIdAndTypeReturnsNullWhenResourceIdIsEmptyAndResourceTypeIsValid() = runTest {
    Assert.assertNull(
      rulesEngineService.getResourceByIdAndType("", ResourceType.Patient.name),
    )
  }

  @Test
  fun testGetResourceByIdAndTypeReturnsNullWhenResourceIdIsValidAndResourceTypeIsNull() = runTest {
    Assert.assertNull(
      rulesEngineService.getResourceByIdAndType("uuid", null),
    )
  }

  @Test
  fun testGetResourceByIdAndTypeReturnsNullWhenResourceIdIsValidAndResourceTypeIsEmpty() = runTest {
    Assert.assertNull(
      rulesEngineService.getResourceByIdAndType("uuid", ""),
    )
  }

  @Test
  fun testGetResourceByIdAndTypeReturnsNullWhenResourceNotFound() = runTest {
    val resourceId = "uuid"

    coEvery { defaultRepository.fhirEngine.get(ResourceType.Patient, resourceId) } throws
      ResourceNotFoundException(ResourceType.Patient.name, resourceId)

    Assert.assertNull(
      rulesEngineService.getResourceByIdAndType(resourceId, ResourceType.Patient.name),
    )

    coVerify { defaultRepository.fhirEngine.get(ResourceType.Patient, resourceId) }
  }

  @Test
  fun testGetResourceByIdAndTypeReturnsNullWhenResourceIdAndTypeAreValid() = runTest {
    val expectedResource = Patient().apply { id = "uuid" }

    coEvery {
      defaultRepository.fhirEngine.get(ResourceType.Patient, expectedResource.logicalId)
    } answers { expectedResource }

    val result =
      rulesEngineService.getResourceByIdAndType(
        expectedResource.logicalId,
        ResourceType.Patient.name,
      )

    Assert.assertEquals(expectedResource, result)

    coVerify {
      defaultRepository.fhirEngine.get(expectedResource.resourceType, expectedResource.logicalId)
    }
  }
}
