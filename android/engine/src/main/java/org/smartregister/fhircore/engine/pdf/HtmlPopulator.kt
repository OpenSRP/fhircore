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

import java.util.Date
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.hl7.fhir.r4.model.BaseDateTimeType
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
import org.smartregister.fhircore.engine.util.extension.allItems
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.formatDate
import org.smartregister.fhircore.engine.util.extension.makeItReadable
import org.smartregister.fhircore.engine.util.extension.valueToString

/**
 * HtmlPopulator class is responsible for processing an HTML template by replacing custom tags with
 * data from QuestionnaireResponses. The class uses various regex patterns to find and replace
 * custom tags such
 * as @is-not-empty, @answer-as-list, @answer, @submitted-date, @contains, @is-questionnaire-submitted,
 * and @translate.
 *
 * @property questionnaireResponses The QuestionnaireResponses object containing data for
 *   replacement.
 * @property translationProvider A high-order function that takes a translation key and returns the
 *   translated string. Defaults to returning an empty string.
 */
class HtmlPopulator(
  questionnaireResponses: List<QuestionnaireResponse>,
  private val translationProvider: (String) -> String = { "" },
) {
  private var answerMap: Map<String, List<QuestionnaireResponseItemAnswerComponent>>
  private var submittedDateMap: Map<String, Date>
  private var questionnaireIds: List<String>

  init {
    val answerMap = mutableMapOf<String, List<QuestionnaireResponseItemAnswerComponent>>()
    val submittedDateMap = mutableMapOf<String, Date>()
    val questionnaireIds = mutableListOf<String>()

    questionnaireResponses.forEach { questionnaireResponse ->
      val questionnaireId = questionnaireResponse.questionnaire.extractLogicalIdUuid()
      questionnaireResponse.allItems
        .associateBy(
          keySelector = { "$questionnaireId/${it.linkId}" },
          valueTransform = { it.answer },
        )
        .let { answerMap.putAll(it) }
      submittedDateMap[questionnaireId] = questionnaireResponse.meta.lastUpdated ?: Date()
      questionnaireIds.add(questionnaireId)
    }

    this.answerMap = answerMap
    this.submittedDateMap = submittedDateMap
    this.questionnaireIds = questionnaireIds
  }

  /**
   * Populates the provided HTML template with data from the QuestionnaireResponse.
   *
   * After a tag got replaced, the current index will be used twice, adding an increment will skip a
   * character right after the current index.
   *
   * @param rawHtml The raw HTML template containing custom tags to be replaced.
   * @return The populated HTML with all custom tags replaced by corresponding data.
   */
  fun populateHtml(rawHtml: String): String {
    val html = StringBuilder(rawHtml)
    var i = 0
    while (i < html.length) {
      when {
        html.startsWith("@is-not-empty", i) -> {
          val matcher = isNotEmptyPattern.matcher(html.substring(i))
          if (matcher.find()) processIsNotEmpty(i, html, matcher) else i++
        }
        html.startsWith("@answer-as-list", i) -> {
          val matcher = answerAsListPattern.matcher(html.substring(i))
          if (matcher.find()) processAnswerAsList(i, html, matcher) else i++
        }
        html.startsWith("@answer", i) -> {
          val matcher = answerPattern.matcher(html.substring(i))
          if (matcher.find()) processAnswer(i, html, matcher) else i++
        }
        html.startsWith("@submitted-date", i) -> {
          val matcher = submittedDatePattern.matcher(html.substring(i))
          if (matcher.find()) processSubmittedDate(i, html, matcher) else i++
        }
        html.startsWith("@contains", i) -> {
          val matcher = containsPattern.matcher(html.substring(i))
          if (matcher.find()) processContains(i, html, matcher) else i++
        }
        html.startsWith("@is-questionnaire-submitted", i) -> {
          val matcher = isQuestionnaireSubmittedPattern.matcher(html.substring(i))
          if (matcher.find()) processIsQuestionnaireSubmitted(i, html, matcher) else i++
        }
        html.startsWith("@translate", i) -> {
          val matcher = translatePattern.matcher(html.substring(i))
          if (matcher.find()) processTranslate(i, html, matcher) else i++
        }
        else -> i++
      }
    }
    return html.toString()
  }

  /**
   * Processes the @is-not-empty tag by checking if the specified linkId has an answer. Replaces the
   * tag with the content if the answer exists, otherwise removes the tag.
   *
   * @param i The starting index of the tag in the HTML.
   * @param html The StringBuilder containing the HTML.
   * @param matcher The Matcher object for the regex pattern.
   */
  private fun processIsNotEmpty(i: Int, html: StringBuilder, matcher: Matcher) {
    val linkId = matcher.group(1)
    val content = matcher.group(2) ?: ""
    val doesAnswerExist = answerMap.getOrDefault(linkId, listOf()).isNotEmpty()
    if (doesAnswerExist) {
      html.replace(i, matcher.end() + i, content)
      // Start index is the index of '@' symbol, End index is the index after the ')' symbol.
      // For example: @is-not-empty('link')Text@is-not-empty('link')
      // The args we put the the replace function: The Start index is 0, the '@' symbol. The
      // End index is the index after the ')' symbol.
      // Note: The ones that are going to be replaced are from the Start index which is an '@' of
      // the first tag, until the index before the End index which is an ')' of the second tag.
    } else {
      html.replace(i, matcher.end() + i, "")
    }
  }

  /**
   * Processes the @answer-as-list tag by replacing it with a list of answers for the specified
   * linkId.
   *
   * @param i The starting index of the tag in the HTML.
   * @param html The StringBuilder containing the HTML.
   * @param matcher The Matcher object for the regex pattern.
   */
  private fun processAnswerAsList(i: Int, html: StringBuilder, matcher: Matcher) {
    val linkId = matcher.group(1)
    val answerAsList =
      answerMap.getOrDefault(linkId, listOf()).joinToString(separator = "") { answer ->
        "<li>${answer.value.valueToString()}</li>"
      }
    html.replace(i, matcher.end() + i, answerAsList)
  }

  /**
   * Processes the @answer tag by replacing it with the answer for the specified linkId.
   *
   * @param i The starting index of the tag in the HTML.
   * @param html The StringBuilder containing the HTML.
   * @param matcher The Matcher object for the regex pattern.
   */
  private fun processAnswer(i: Int, html: StringBuilder, matcher: Matcher) {
    val linkId = matcher.group(1)
    val dateFormat = matcher.group(2)
    val answer =
      answerMap.getOrDefault(linkId, listOf()).joinToString { answer ->
        if (dateFormat == null) {
          answer.value.valueToString()
        } else {
          answer.value.valueToString(dateFormat)
        }
      }
    html.replace(i, matcher.end() + i, answer)
  }

  /**
   * Processes the @submitted-date tag by replacing it with the formatted date.
   *
   * @param i The starting index of the tag in the HTML.
   * @param html The StringBuilder containing the HTML.
   * @param matcher The Matcher object for the regex pattern.
   */
  private fun processSubmittedDate(i: Int, html: StringBuilder, matcher: Matcher) {
    val questionnaireId = matcher.group(1)
    val dateFormat = matcher.group(2)
    val date =
      if (dateFormat == null) {
        submittedDateMap.getOrDefault(questionnaireId, Date()).formatDate()
      } else {
        submittedDateMap.getOrDefault(questionnaireId, Date()).formatDate(dateFormat)
      }
    html.replace(i, matcher.end() + i, date)
  }

  /**
   * Processes the @contains tag by checking if the specified linkId contains the indicator.
   * Replaces the tag with the content if the indicator is found, otherwise removes the tag.
   *
   * @param i The starting index of the tag in the HTML.
   * @param html The StringBuilder containing the HTML.
   * @param matcher The Matcher object for the regex pattern.
   */
  private fun processContains(i: Int, html: StringBuilder, matcher: Matcher) {
    val linkId = matcher.group(1)
    val indicator = matcher.group(2) ?: ""
    val content = matcher.group(3) ?: ""
    val doesAnswerExist =
      answerMap.getOrDefault(linkId, listOf()).any {
        when {
          it.hasValueCoding() -> it.valueCoding.code == indicator
          it.hasValueStringType() -> it.valueStringType.value.contains(indicator)
          it.hasValueIntegerType() -> it.valueIntegerType.value == indicator.toInt()
          it.hasValueDecimalType() -> it.valueDecimalType.value == indicator.toBigDecimal()
          it.hasValueBooleanType() -> it.valueBooleanType.value == indicator.toBoolean()
          it.hasValueQuantity() ->
            "${it.valueQuantity.value.toPlainString()} ${it.valueQuantity.unit}" == indicator
          it.hasValueDateType() || it.hasValueDateTimeType() ->
            (it.value as BaseDateTimeType).value.makeItReadable() == indicator
          else -> false
        }
      }
    if (doesAnswerExist) {
      html.replace(i, matcher.end() + i, content)
    } else {
      html.replace(i, matcher.end() + i, "")
    }
  }

  /**
   * Processes the @is-questionnaire-submitted tag by checking if the corresponding
   * [QuestionnaireResponse] exists. Replaces the tag with the content if the indicator is true,
   * otherwise removes the tag.
   *
   * @param i The starting index of the tag in the HTML.
   * @param html The StringBuilder containing the HTML.
   * @param matcher The Matcher object for the regex pattern.
   */
  private fun processIsQuestionnaireSubmitted(i: Int, html: StringBuilder, matcher: Matcher) {
    val id = matcher.group(1)
    val content = matcher.group(2) ?: ""
    val doesQuestionnaireExists = questionnaireIds.contains(id)
    if (doesQuestionnaireExists) {
      html.replace(i, matcher.end() + i, content)
    } else {
      html.replace(i, matcher.end() + i, "")
    }
  }

  /**
   * Processes the @translate tag by calling the translation provider lambda to resolve the
   * translation key.
   *
   * @param i The starting index of the tag in the HTML.
   * @param html The StringBuilder containing the HTML.
   * @param matcher The Matcher object for the regex pattern.
   */
  private fun processTranslate(i: Int, html: StringBuilder, matcher: Matcher) {
    val translationKey = matcher.group(1)
    val translatedValue = translationProvider(translationKey)
    html.replace(i, matcher.end() + i, translatedValue)
  }

  companion object {
    // Compile regex patterns for different tags
    private val isNotEmptyPattern =
      Pattern.compile("@is-not-empty\\('([^']+)'\\)((?s).*?)@is-not-empty\\('\\1'\\)")
    private val answerAsListPattern = Pattern.compile("@answer-as-list\\('([^']+)'\\)")
    private val answerPattern = Pattern.compile("@answer\\('([^']+)'(?:,'([^']+)')?\\)")
    private val submittedDatePattern =
      Pattern.compile("@submitted-date\\('([^']+)'(?:,'([^']+)')?\\)")
    private val containsPattern =
      Pattern.compile("@contains\\('([^']+)','([^']+)'\\)((?s).*?)@contains\\('\\1'\\)")
    private val isQuestionnaireSubmittedPattern =
      Pattern.compile(
        "@is-questionnaire-submitted\\('([^']+)'\\)((?s).*?)@is-questionnaire-submitted\\('\\1'\\)",
      )
    private val translatePattern = Pattern.compile("@translate\\('([^']+)'\\)")
  }
}
