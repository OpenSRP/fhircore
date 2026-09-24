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

package org.smartregister.fhircore.engine.pdf

import java.util.Calendar
import java.util.Date
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert
import org.junit.Test

class HtmlPopulatorTest {

  @Test
  fun testIsNotEmptyShouldShowContentWhenAnswerExistInQR() {
    val html = "@is-not-empty('1234/link-a')<p>Text</p>@is-not-empty('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = Coding("system 1", "code 1", "display 1")
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Text</p>", populatedHtml)
  }

  @Test
  fun testIsNotEmptyShouldHideContentWhenAnswerIsEmptyInQR() {
    val html = "@is-not-empty('1234/link-a')<p>Text</p>@is-not-empty('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = emptyList()
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("", populatedHtml)
  }

  @Test
  fun testIsNotEmptyShouldHideContentWhenAnswerNotExistInQR() {
    val html = "@is-not-empty('1234/link-a')<p>Text</p>@is-not-empty('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply { linkId = "link-a" }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("", populatedHtml)
  }

  @Test
  fun testIsNotEmptyShouldHideContentWhenLinkIdNotExistInQR() {
    val html = "@is-not-empty('1234/link-a')<p>Text</p>@is-not-empty('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("", populatedHtml)
  }

  @Test
  fun testIsNotEmptyShouldShowMalformedTagAndContentIfLinkIdOfBothTagDoesNotMatch() {
    val html = "@is-not-empty('1234/link-a')<p>Text</p>@is-not-empty('1234/link-b')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals(
      "@is-not-empty('1234/link-a')<p>Text</p>@is-not-empty('1234/link-b')",
      populatedHtml,
    )
  }

  @Test
  fun testIsNotEmptyShouldShowMalformedTagAndContentIfOnly1TagExist() {
    val html = "@is-not-empty('1234/link-a')<p>Text</p>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("@is-not-empty('1234/link-a')<p>Text</p>", populatedHtml)
  }

  @Test
  fun testIsNotEmptyShouldShowContentAndNestedMalformedTagIfAnswerOfRootTagExist() {
    val html =
      "@is-not-empty('1234/link-a')@is-not-empty('1234/link-b')<p>Text</p>@is-not-empty('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = Coding("system 1", "code 1", "display 1")
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("@is-not-empty('1234/link-b')<p>Text</p>", populatedHtml)
  }

  @Test
  fun testIsNotEmptyShouldHideContentAndNestedMalformedTagIfAnswerOfRootTagIsNotExist() {
    val html =
      "@is-not-empty('1234/link-a')@is-not-empty('1234/link-b')<p>Text</p>@is-not-empty('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply { linkId = "link-a" }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("", populatedHtml)
  }

  @Test
  fun testIsNotEmptyShouldHideContentAndNestedMalformedTagIfAnswerOfRootTagIsEmpty() {
    val html =
      "@is-not-empty('1234/link-a')@is-not-empty('1234/link-b')<p>Text</p>@is-not-empty('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = emptyList()
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("", populatedHtml)
  }

  @Test
  fun testIsNotEmptyShouldShowEmptyContentIfAnswerExist() {
    val html = "@is-not-empty('1234/link-a')@is-not-empty('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = Coding("system 1", "code 1", "display 1")
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("", populatedHtml)
  }

  @Test
  fun testProcessAnswerAsListShouldShowAnswerAsListWhenAnswerExistInQR() {
    val html = "<ul>@answer-as-list('1234/link-a')</ul>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = Coding("system 1", "code 1", "display 1")
                },
              )
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = Coding("system 2", "code 2", "display 2")
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<ul><li>display 1</li><li>display 2</li></ul>", populatedHtml)
  }

  @Test
  fun testProcessAnswerAsListShouldShowEmptyAnswerAsListWhenAnswerNotExistInQR() {
    val html = "<ul>@answer-as-list('1234/link-a')</ul>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply { linkId = "link-a" }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<ul></ul>", populatedHtml)
  }

  @Test
  fun testProcessAnswerAsListShouldShowEmptyAnswerAsListWhenLinkIdNotExistInQR() {
    val html = "<ul>@answer-as-list('1234/link-a')</ul>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<ul></ul>", populatedHtml)
  }

  @Test
  fun testProcessAnswerShouldShowAnswerWhenAnswerExistInQR() {
    val html = "<p>@answer('1234/link-a')</p>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply { value = StringType("string 1") },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>string 1</p>", populatedHtml)
  }

  @Test
  fun testProcessAnswerShouldShowEmptyAnswerWhenAnswerNotExistInQR() {
    val html = "<p>@answer('1234/link-a')</p>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply { linkId = "link-a" }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p></p>", populatedHtml)
  }

  @Test
  fun testProcessAnswerShouldShowEmptyAnswerWhenLinkIdNotExistInQR() {
    val html = "<p>@answer('1234/link-a')</p>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p></p>", populatedHtml)
  }

  @Test
  fun testProcessAnswerShouldShowDateAnswerWhenAnswerOfTypeDateExistInQR() {
    val calendar = Calendar.getInstance().apply { set(2024, Calendar.MAY, 14) }
    val specificDate: Date = calendar.time
    val html = "<p>@answer('1234/link-a')</p>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = DateTimeType(specificDate)
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>14-May-2024</p>", populatedHtml)
  }

  @Test
  fun testProcessAnswerShouldShowDateAnswerWithFormatWhenDateFormatExistInTheTag() {
    val calendar = Calendar.getInstance().apply { set(2024, Calendar.MAY, 14) }
    val specificDate: Date = calendar.time
    val html = "<p>@answer('1234/link-a','MMMM d, yyyy')</p>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = DateTimeType(specificDate)
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>May 14, 2024</p>", populatedHtml)
  }

  @Test
  fun testProcessSubmittedDateShouldShow() {
    val calendar = Calendar.getInstance().apply { set(2024, Calendar.MAY, 14) }
    val specificDate: Date = calendar.time
    val html = "<p>@submitted-date('1234')</p>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          meta = Meta().apply { lastUpdated = specificDate }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>14-May-2024</p>", populatedHtml)
  }

  @Test
  fun testProcessSubmittedDateShouldShowWithFormatWhenDateFormatExistInTheTag() {
    val calendar = Calendar.getInstance().apply { set(2024, Calendar.MAY, 14) }
    val specificDate: Date = calendar.time
    val html = "<p>@submitted-date('1234','MMMM d, yyyy')</p>"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          meta = Meta().apply { lastUpdated = specificDate }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>May 14, 2024</p>", populatedHtml)
  }

  @Test
  fun testProcessContainsShouldShowContentWhenIndicatorCodeMatchesWithAnswerOfTypeCoding() {
    val html = "@contains('1234/link-a','code 2')<p>Text</p>@contains('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = Coding("system 1", "code 1", "display 1")
                },
              )
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = Coding("system 2", "code 2", "display 2")
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Text</p>", populatedHtml)
  }

  @Test
  fun testProcessContainsShouldHideContentWhenIndicatorCodeDoesNotMatchWithAnswerOfTypeCoding() {
    val html = "@contains('1234/link-a','code 3')<p>Text</p>@contains('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = Coding("system 1", "code 1", "display 1")
                },
              )
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = Coding("system 2", "code 2", "display 2")
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("", populatedHtml)
  }

  @Test
  fun testProcessContainsShouldShowContentWhenIndicatorStringIsContainedInAnswerOfTypeString() {
    val html = "@contains('1234/link-a','basket')<p>Text</p>@contains('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = StringType("basketball")
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Text</p>", populatedHtml)
  }

  @Test
  fun testProcessContainsShouldShowContentWhenIndicatorIntegerMatchesAnswerOfTypeInteger() {
    val html = "@contains('1234/link-a','10')<p>Text</p>@contains('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply { value = IntegerType("10") },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Text</p>", populatedHtml)
  }

  @Test
  fun testProcessContainsShouldShowContentWhenIndicatorDecimalMatchesAnswerOfTypeDecimal() {
    val html = "@contains('1234/link-a','1.5')<p>Text</p>@contains('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply { value = DecimalType("1.5") },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Text</p>", populatedHtml)
  }

  @Test
  fun testProcessContainsShouldShowContentWhenIndicatorBooleanMatchesAnswerOfTypeBoolean() {
    val html = "@contains('1234/link-a','true')<p>Text</p>@contains('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply { value = BooleanType("true") },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Text</p>", populatedHtml)
  }

  @Test
  fun testProcessContainsShouldShowContentWhenIndicatorQuantityMatchesAnswerOfTypeQuantity() {
    val html = "@contains('1234/link-a','3 years')<p>Text</p>@contains('1234/link-a')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = Quantity(null, 3, "system", "years", "years")
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Text</p>", populatedHtml)
  }

  @Test
  fun testProcessContainsShouldShowContentWhenIndicatorDateMatchesAnswerOfTypeDate() {
    val html = "@contains('1234/link-a','14-May-2024')<p>Text</p>@contains('1234/link-a')"
    val calendar = Calendar.getInstance().apply { set(2024, Calendar.MAY, 14) }
    val specificDate: Date = calendar.time
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/1234"
          addItem().apply {
            linkId = "link-a"
            answer = buildList {
              add(
                QuestionnaireResponseItemAnswerComponent().apply {
                  value = DateTimeType(specificDate)
                },
              )
            }
          }
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Text</p>", populatedHtml)
  }

  @Test
  fun testProcessIsQuestionnaireSubmittedShouldShowContentWhenTheRelatedQuestionnaireResponseExists() {
    val html =
      "@is-questionnaire-submitted('q-1234')<p>Text</p>@is-questionnaire-submitted('q-1234')"
    val questionnaireResponses =
      listOf(
        QuestionnaireResponse().apply {
          id = "1234"
          questionnaire = "Questionnaire/q-1234"
        },
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Text</p>", populatedHtml)
  }

  @Test
  fun testProcessIsQuestionnaireSubmittedShouldNotShowContentWhenTheRelatedQuestionnaireResponseDoesNotExists() {
    val html =
      "@is-questionnaire-submitted('q-1234')<p>Text</p>@is-questionnaire-submitted('q-1234')"
    val questionnaireResponses = listOf<QuestionnaireResponse>()
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("", populatedHtml)
  }

  @Test
  fun testTranslateShouldRemoveTagWhenUsingDefaultProvider() {
    val html = "<p>@translate('hello.world')</p>"
    val questionnaireResponses = listOf<QuestionnaireResponse>()
    // HtmlPopulator with default translation provider (returns empty string)
    val htmlPopulator = HtmlPopulator(questionnaireResponses)
    val populatedHtml = htmlPopulator.populateHtml(html)
    // With default provider, the tag should be removed (empty string)
    Assert.assertEquals("<p></p>", populatedHtml)
  }

  @Test
  fun testTranslateShouldTranslateUsingProvidedMap() {
    val html = "<p>@translate('hello.world')</p>"
    val questionnaireResponses = listOf<QuestionnaireResponse>()
    val translationMap = mapOf("hello.world" to "Hello World")
    val htmlPopulator = HtmlPopulator(questionnaireResponses) { key -> translationMap[key] ?: "" }
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Hello World</p>", populatedHtml)
  }

  @Test
  fun testTranslateShouldHandleMultipleTranslateTagsWithMap() {
    val html = "<p>@translate('key1') and @translate('key2')</p>"
    val questionnaireResponses = listOf<QuestionnaireResponse>()
    val translationMap =
      mapOf(
        "key1" to "Value 1",
        "key2" to "Value 2",
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses) { key -> translationMap[key] ?: "" }
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals("<p>Value 1 and Value 2</p>", populatedHtml)
  }

  @Test
  fun testTranslateShouldHandleTranslateTagInComplexContent() {
    val html =
      "<div>Welcome: @translate('welcome.message')</div><p>Thank you: @translate('thank.you.message')</p>"
    val questionnaireResponses = listOf<QuestionnaireResponse>()
    val translationMap =
      mapOf(
        "welcome.message" to "Welcome to our service",
        "thank.you.message" to "Thank you for using our app",
      )
    val htmlPopulator = HtmlPopulator(questionnaireResponses) { key -> translationMap[key] ?: "" }
    val populatedHtml = htmlPopulator.populateHtml(html)
    Assert.assertEquals(
      "<div>Welcome: Welcome to our service</div><p>Thank you: Thank you for using our app</p>",
      populatedHtml,
    )
  }
}
