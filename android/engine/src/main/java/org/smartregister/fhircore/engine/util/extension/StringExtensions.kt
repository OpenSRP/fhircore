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

import android.content.Context
import android.telephony.PhoneNumberUtils
import java.text.MessageFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import org.apache.commons.text.CaseUtils
import org.apache.commons.text.StringSubstitutor
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import timber.log.Timber

const val DEFAULT_PLACEHOLDER_PREFIX = "@{"
const val DEFAULT_PLACEHOLDER_SUFFIX = "}"
const val BLACK_COLOR_HEX_CODE = "#000000"
const val TRUE = "true"

/**
 * Sample template string: { "saveFamilyButtonText" : {{ family.button.save }} } Sample properties
 * file content: family.button.save=Save Family
 *
 * @param lookupMap The Map with the key value items to be used for interpolation
 * @param prefix The prefix of the key variable to interpolate. In the above example it is {{.
 *   Default is @{
 * @param suffix The prefix of the key/variable to interpolate. In the above example it is }}.
 *   Default is }
 * @return String with the interpolated value. For the sample case above this would be: {
 *   "saveFamilyButtonText" : "Save Family" }
 */
fun String.interpolate(
  lookupMap: Map<String, Any>,
  prefix: String = DEFAULT_PLACEHOLDER_PREFIX,
  suffix: String = DEFAULT_PLACEHOLDER_SUFFIX,
): String =
  try {
    StringSubstitutor.replace(
      this.replace(Pattern.quote(prefix).plus(".*?").plus(Pattern.quote(suffix)).toRegex()) {
        it.value.replace("\\s+".toRegex(), "")
      },
      lookupMap,
      prefix,
      suffix,
    )
  } catch (e: IllegalStateException) {
    Timber.e(e)
    this
  }

/**
 * Wrapper method around the Java text formatter
 *
 * Example string format: Name {0} {1}, Age {2}
 *
 * @param locale this is the Locale to use e.g. Locale.ENGLISH
 * @param arguments this is a variable number of values to replace placeholders in order
 * @return the interpolated string with the placeholder variables replaced with the arguments
 *   values.
 *
 * In the example above, the result for passing arguments John, Doe, 35 would be: Name John Doe, Age
 * 35
 */
fun String.messageFormat(locale: Locale?, vararg arguments: Any?): String? =
  MessageFormat(this, locale).format(arguments)

/**
 * Creates a translation property key from string text by normalizing the input.
 *
 * This function:
 * 1. Trims leading/trailing whitespace
 * 2. Converts to lowercase
 * 3. Replaces all non-alphanumeric characters with dots
 * 4. Removes leading/trailing dots
 *
 * Example:
 * ```
 * "Discuss Confidentiality".translationPropertyKey() // Returns "discuss.confidentiality"
 * "  C-SSRS  ".translationPropertyKey() // Returns "c.ssrs"
 * ```
 *
 * @return string.properties key to be used in translation lookups
 */
fun String.translationPropertyKey(): String {
  return this.trim().lowercase(Locale.ENGLISH).replace("[^a-z0-9]+".toRegex(), ".").trim('.')
}

/**
 * This property returns the substring of the filepath after the last period '.' which is the
 * extension
 *
 * e.g /file/path/to/strings.txt would return txt
 */
val String.fileExtension
  get() = this.substringAfterLast('.')

/** Function that converts snake_case string to camelCase */
fun String.camelCase(): String = CaseUtils.toCamelCase(this, false, '_')

/**
 * Get the practitioner endpoint url and append the keycloak-uuid. The original String is assumed to
 * be a keycloak-uuid.
 */
fun String.practitionerEndpointUrl(): String = "PractitionerDetail?keycloak-uuid=$this"

/** Remove double white spaces from text and also remove space before comma */
fun String.removeExtraWhiteSpaces(): String =
  this.replace("\\s+".toRegex(), " ").replace(" ,", ",").trim()

/** Return an abbreviation for the provided string */
fun String?.abbreviate() = this?.firstOrNull() ?: ""

fun String.parseDate(pattern: String): Date? =
  SimpleDateFormat(pattern, Locale.ENGLISH).tryParse(this)

/** Compare characters of identical strings */
fun String.compare(anotherString: String): Boolean =
  this.toSortedSet().containsAll(anotherString.toSortedSet())

fun String.lastOffset() = this.uppercase() + "_" + SharedPreferenceKey.LAST_OFFSET.name

fun String.spaceByUppercase() =
  this.split(Regex("(?=\\p{Upper})")).joinToString(separator = " ").trim()

fun String?.formatPhoneNumber(context: Context): String? {
  if (this == null) return null
  return try {
    PhoneNumberUtils.formatNumber(this, context.resources.configuration.locales.get(0).country)
      ?: this
  } catch (formatException: NumberFormatException) {
    Timber.e(formatException, "Error formatting phone number: $this")
    this
  }
}

/**
 * Removes any '#' characters from the beginning of this string.
 *
 * @return The string without any leading '#' characters.
 */
fun String.removeHashPrefix(): String {
  return this.trimStart('#')
}
