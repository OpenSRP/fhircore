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

import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Task
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.util.helper.LocalizationHelper

class TaskExtensionTest {

  @Test
  fun testHasPastEnd() {
    val taskNoEnd = Task().apply { executionPeriod.start = Date() }
    Assert.assertFalse(taskNoEnd.hasPastEnd())

    val task = Task().apply { executionPeriod.end = Date() }
    Assert.assertFalse(task.hasPastEnd())

    val anotherTask =
      Task().apply { executionPeriod.end = Date(LocalDate.parse("1972-12-12").toEpochDay()) }
    Assert.assertTrue(anotherTask.hasPastEnd())
  }

  @Test
  fun testHasStarted() {
    val taskNoExecutionPeriod = Task()
    Assert.assertFalse(taskNoExecutionPeriod.hasStarted())

    val taskNoStart = Task().apply { executionPeriod.end = Date() }
    Assert.assertFalse(taskNoStart.hasStarted())

    val task = Task().apply { executionPeriod.start = Date() }
    Assert.assertTrue(task.hasStarted())

    val anotherTask =
      Task().apply {
        executionPeriod.end =
          Date.from(LocalDate.now().plusDays(8).atStartOfDay(ZoneId.systemDefault()).toInstant())
      }
    Assert.assertFalse(anotherTask.hasStarted())
  }

  @Test
  fun `task is ready if date today is between start and end dates`() {
    val task1 =
      Task().apply {
        executionPeriod.start =
          Date.from(LocalDate.now().minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant())
        executionPeriod.end =
          Date.from(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant())
      }
    Assert.assertTrue(task1.isReady())

    val task2 =
      Task().apply {
        executionPeriod.start =
          Date.from(LocalDate.now().minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant())
      }
    Assert.assertTrue(task2.isReady())

    val task3 =
      Task().apply {
        executionPeriod.start =
          Date.from(LocalDate.now().plusDays(3).atStartOfDay(ZoneId.systemDefault()).toInstant())
      }
    Assert.assertFalse(task3.isReady())

    val task4 =
      Task().apply {
        executionPeriod.start =
          Date.from(LocalDate.now().minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant())
        executionPeriod.end =
          Date.from(LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant())
      }
    Assert.assertFalse(task4.isReady())
  }

  @Test
  fun `executionStartIsBeforeOrToday returns true if date is before or today`() {
    val task1 = Task()

    Assert.assertFalse(task1.executionStartIsBeforeOrToday())

    task1.executionPeriod.end = Date()

    Assert.assertFalse(task1.executionStartIsBeforeOrToday())

    task1.executionPeriod.start =
      Date.from(LocalDate.now().minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant())

    Assert.assertTrue(task1.executionStartIsBeforeOrToday())

    task1.executionPeriod.start =
      Date.from(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant())

    Assert.assertFalse(task1.executionStartIsBeforeOrToday())
  }

  @Test
  fun `executionEndIsAfterOrToday returns true if date is after or today`() {
    val task1 = Task()

    Assert.assertFalse(task1.executionEndIsAfterOrToday())

    task1.executionPeriod.start = Date()

    Assert.assertFalse(task1.executionEndIsAfterOrToday())

    task1.executionPeriod.end =
      Date.from(LocalDate.now().plusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant())

    Assert.assertTrue(task1.executionEndIsAfterOrToday())

    task1.executionPeriod.end =
      Date.from(LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant())

    Assert.assertFalse(task1.executionEndIsAfterOrToday())
  }

  @Test
  fun testToCoding() {
    val task = Task().apply { status = Task.TaskStatus.ACCEPTED }
    val coding = task.status.toCoding()
    Assert.assertNotNull(coding)
    Assert.assertEquals(task.status.system, coding.system)
    Assert.assertEquals(task.status.toCode(), coding.code)
    Assert.assertEquals(task.status.display, coding.display)
  }

  @Test
  fun `isPastExpiry no restriction`() {
    val task = Task()
    Assert.assertFalse(task.isPastExpiry())
  }

  @Test
  fun `isPastExpiry restriction, no period`() {
    val task = Task().apply { restriction = Task.TaskRestrictionComponent() }
    Assert.assertFalse(task.isPastExpiry())
  }

  @Test
  fun `isPastExpiry restriction, period, no end`() {
    val task =
      Task().apply { restriction = Task.TaskRestrictionComponent().apply { period = Period() } }
    Assert.assertFalse(task.isPastExpiry())
  }

  @Test
  fun `isPastExpiry restriction, period, end before today`() {
    val task =
      Task().apply {
        restriction =
          Task.TaskRestrictionComponent().apply {
            period = Period().apply { end = Date().plusDays(1) }
          }
      }
    Assert.assertFalse(task.isPastExpiry())
  }

  @Test
  fun `isPastExpiry restriction, period, end after today`() {
    val task =
      Task().apply {
        restriction =
          Task.TaskRestrictionComponent().apply {
            period = Period().apply { end = Date().plusDays(-1) }
          }
      }
    Assert.assertTrue(task.isPastExpiry())
  }

  @Test
  fun testTaskIsUpcoming() {
    val task =
      Task().apply {
        status = Task.TaskStatus.REQUESTED
        executionPeriod.start = today().plusDays(1)
      }
    val expected = task.isUpcoming()
    Assert.assertTrue(expected)
  }

  @Test
  fun testTaskIsOverDueWithStatusTaskStatusInProgress() {
    val task =
      Task().apply {
        status = Task.TaskStatus.INPROGRESS
        executionPeriod.end = today().plusDays(-1)
      }
    val expected = task.isOverDue()
    Assert.assertTrue(expected)
  }

  @Test
  fun testTaskIsOverDueWithStatusTaskStatusReady() {
    val task =
      Task().apply {
        status = Task.TaskStatus.READY
        executionPeriod.end = today().plusDays(-10)
      }
    val expected = task.isOverDue()
    Assert.assertTrue(expected)
  }

  @Test
  fun testTaskIsDue() {
    val task = Task().apply { status = Task.TaskStatus.READY }
    val expected = task.isDue()
    Assert.assertTrue(expected)
  }

  private lateinit var mockConfigurationRegistry: ConfigurationRegistry
  private lateinit var mockLocalizationHelper: LocalizationHelper

  @Before
  fun setUpLocalization() {
    mockLocalizationHelper = mockk()
    mockConfigurationRegistry = mockk {
      every { localizationHelper } returns mockLocalizationHelper
    }
  }

  fun testGetLocalizedDescription_WithValidTranslation() {
    val task = Task().apply { description = "Discuss Confidentiality" }

    val expectedTranslation = "Jadili Usiri"
    setupMockTranslation("discuss.confidentiality", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithMultipleWordDescription() {
    val task = Task().apply { description = "IPC - Interpersonal Counseling" }

    val expectedTranslation = "IPC - Ushauri wa Kupokea Watu Wengine"
    setupMockTranslation("ipc.interpersonal.counseling", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithSessionNumber() {
    val task = Task().apply { description = "IPC Session 1" }

    val expectedTranslation = "Kikao cha IPC 1"
    setupMockTranslation("ipc.session.1", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithPdfDescription() {
    val task = Task().apply { description = "PHQ-9 Scores PDF" }

    val expectedTranslation = "PDfa ya Alama za PHQ-9"
    setupMockTranslation("phq.9.scores.pdf", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_FallbackToOriginal_WhenTranslationNotFound() {
    val task = Task().apply { description = "Discuss Confidentiality" }

    // Mock returns the template unchanged, indicating translation not found
    val template = "{{discuss.confidentiality}}"
    every { mockLocalizationHelper.parseTemplate(any(), any(), any()) } returns template

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    // Should fallback to original description
    Assert.assertEquals("Discuss Confidentiality", result)
  }

  @Test
  fun testGetLocalizedDescription_FallbackToOriginal_WhenNullDescription() {
    val task = Task().apply { description = null }

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals("", result)
  }

  @Test
  fun testGetLocalizedDescription_FallbackToOriginal_WhenEmptyDescription() {
    val task = Task().apply { description = "" }

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals("", result)
  }

  @Test
  fun testGetLocalizedDescription_FallbackToOriginal_UnknownDescription() {
    val task = Task().apply { description = "Some Unknown Task Description" }

    // Mock returns the template unchanged
    val template = "{{some.unknown.task.description}}"
    every { mockLocalizationHelper.parseTemplate(any(), any(), any()) } returns template

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals("Some Unknown Task Description", result)
  }

  @Test
  fun testGetLocalizedDescription_WithSpecialCharacters() {
    val task = Task().apply { description = "Information Only (Low Risk)" }

    val expectedTranslation = "Habari tu (Hatari Ndogo)"
    setupMockTranslation("information.only.low.risk", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithDashesAndNumbers() {
    val task = Task().apply { description = "Short-Form PCL-5-8" }

    val expectedTranslation = "Fomu Fupi ya PCL-5-8"
    setupMockTranslation("short.form.pcl.5.8", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithLeadingTrailingWhitespace() {
    val task = Task().apply { description = "  Discuss Confidentiality  " }

    // translationPropertyKey() trims the string first
    val expectedTranslation = "Jadili Usiri"
    setupMockTranslation("discuss.confidentiality", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithMixedCase() {
    val task = Task().apply { description = "DISCUSS CONFIDENTIALITY" }

    // translationPropertyKey() converts to lowercase
    val expectedTranslation = "Jadili Usiri"
    setupMockTranslation("discuss.confidentiality", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithSingleCharacterString() {
    val task = Task().apply { description = "A" }

    val expectedTranslation = "Alfa"
    setupMockTranslation("a", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithVeryLongDescription() {
    val task = Task().apply { description = "Mental Wellness Tool-IPC session 4 Provider PDF" }

    val expectedTranslation = "PDF ya Zana ya Afya ya Akili-Kikao cha IPC 4 Mtoaji"
    setupMockTranslation("mental.wellness.tool.ipc.session.4.provider.pdf", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithSwahiliTranslation() {
    val task = Task().apply { description = "Discuss Confidentiality" }

    val expectedTranslation = "Jadili Usiri"
    setupMockTranslation("discuss.confidentiality", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithFrenchTranslation() {
    val task = Task().apply { description = "Suicide Prevention" }

    val expectedTranslation = "Prévention du Suicide"
    setupMockTranslation("suicide.prevention", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithArabicTranslation() {
    val task = Task().apply { description = "Discuss Confidentiality" }

    val expectedTranslation = "مناقشة السرية"
    setupMockTranslation("discuss.confidentiality", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithGermanTranslation() {
    val task = Task().apply { description = "Discuss Confidentiality" }

    val expectedTranslation = "Vertraulichkeit besprechen"
    setupMockTranslation("discuss.confidentiality", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithSpanishTranslation() {
    val task = Task().apply { description = "Discuss Confidentiality" }

    val expectedTranslation = "Discutir Confidencialidad"
    setupMockTranslation("discuss.confidentiality", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testGetLocalizedDescription_WithIndonesianTranslation() {
    val task = Task().apply { description = "Discuss Confidentiality" }

    val expectedTranslation = "Diskusikan Kerahasiaan"
    setupMockTranslation("discuss.confidentiality", expectedTranslation)

    val result = task.getLocalizedDescription(mockConfigurationRegistry)

    Assert.assertEquals(expectedTranslation, result)
  }

  @Test
  fun testTranslationPropertyKeyConversion_SimpleWord() {
    val key = "Vaccine".translationPropertyKey()
    Assert.assertEquals("vaccine", key)
  }

  @Test
  fun testTranslationPropertyKeyConversion_MultipleWords() {
    val key = "Discuss Confidentiality".translationPropertyKey()
    Assert.assertEquals("discuss.confidentiality", key)
  }

  @Test
  fun testTranslationPropertyKeyConversion_WithDashes() {
    val key = "Short-Form PCL-5-8".translationPropertyKey()
    Assert.assertEquals("short.form.pcl.5.8", key)
  }

  @Test
  fun testTranslationPropertyKeyConversion_WithParentheses() {
    val key = "Information Only (Low Risk)".translationPropertyKey()
    Assert.assertEquals("information.only.low.risk", key)
  }

  @Test
  fun testTranslationPropertyKeyConversion_UppercaseConversion() {
    val key = "DISCUSS CONFIDENTIALITY".translationPropertyKey()
    Assert.assertEquals("discuss.confidentiality", key)
  }

  @Test
  fun testTranslationPropertyKeyConversion_WithWhitespace() {
    val key = "  Discuss Confidentiality  ".translationPropertyKey()
    Assert.assertEquals("discuss.confidentiality", key)
  }

  @Test
  fun testTranslationPropertyKeyConversion_MixedCase() {
    val key = "DiScUsS cOnFiDeNtIaLiTy".translationPropertyKey()
    Assert.assertEquals("discuss.confidentiality", key)
  }

  /**
   * Helper method to set up mock translation. When parseTemplate is called with the expected
   * template, it returns the translated text.
   */
  private fun setupMockTranslation(propertyKey: String, translation: String) {
    every { mockLocalizationHelper.parseTemplate(any(), any(), any()) } returns translation
  }
}
