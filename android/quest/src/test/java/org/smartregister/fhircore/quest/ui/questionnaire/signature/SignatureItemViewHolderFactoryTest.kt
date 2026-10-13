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

package org.smartregister.fhircore.quest.ui.questionnaire.signature

import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert
import org.junit.Test

class SignatureItemViewHolderFactoryTest {

  @Test
  fun isSignatureItemReturnsTrueForMatchingExtension() {
    val item = itemWithCodings(coding(SYSTEM, CODE))
    Assert.assertTrue(isSignatureItem(item))
  }

  @Test
  fun isSignatureItemReturnsTrueWhenOneCodingAmongManyMatches() {
    val item =
      itemWithCodings(
        coding("http://other.example", "other"),
        coding(SYSTEM, CODE),
        coding("http://another.example", "another"),
      )
    Assert.assertTrue(isSignatureItem(item))
  }

  @Test
  fun isSignatureItemReturnsFalseWhenNoExtension() {
    val item = Questionnaire.QuestionnaireItemComponent()
    Assert.assertFalse(isSignatureItem(item))
  }

  @Test
  fun isSignatureItemReturnsFalseWhenSystemDoesNotMatch() {
    val item = itemWithCodings(coding("http://other.example", CODE))
    Assert.assertFalse(isSignatureItem(item))
  }

  @Test
  fun isSignatureItemReturnsFalseWhenCodeDoesNotMatch() {
    val item = itemWithCodings(coding(SYSTEM, "not-signature"))
    Assert.assertFalse(isSignatureItem(item))
  }

  @Test
  fun isSignatureItemReturnsFalseWhenValueIsNotCodeableConcept() {
    val extension =
      Extension(EXTENSION_URL).apply {
        value = StringType(CODE)
      }
    val item = Questionnaire.QuestionnaireItemComponent().apply { addExtension(extension) }
    Assert.assertFalse(isSignatureItem(item))
  }

  companion object {
    private const val EXTENSION_URL =
      "http://hl7.org/fhir/StructureDefinition/questionnaire-itemControl"
    private const val SYSTEM = "http://aidbox.io/questionnaire-itemControl"
    private const val CODE = "signature"

    private fun coding(system: String?, code: String?): Coding =
      Coding().apply {
        this.system = system
        this.code = code
      }

    private fun itemWithCodings(
      vararg codings: Coding,
    ): Questionnaire.QuestionnaireItemComponent {
      val codeableConcept = CodeableConcept()
      codings.forEach(codeableConcept::addCoding)
      val extension = Extension(EXTENSION_URL).apply { value = codeableConcept }
      return Questionnaire.QuestionnaireItemComponent().apply { addExtension(extension) }
    }
  }
}
