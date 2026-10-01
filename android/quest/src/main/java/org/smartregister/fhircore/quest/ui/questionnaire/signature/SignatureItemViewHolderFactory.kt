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

import android.util.Base64
import android.view.View
import com.google.android.fhir.datacapture.views.QuestionnaireViewItem
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemAndroidViewHolderDelegate
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemAndroidViewHolderFactory
import java.util.Date
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.quest.R

private const val SIGNATURE_EXTENSION_URL =
  "http://hl7.org/fhir/StructureDefinition/questionnaire-itemControl"
private const val SIGNATURE_EXTENSION_SYSTEM = "http://aidbox.io/questionnaire-itemControl"
private const val SIGNATURE_EXTENSION_CODE = "signature"

/**
 * Matcher for questionnaire items marked with the Aidbox signature itemControl extension. Used by
 * fhircore's QuestionnaireItemViewHolderFactoryMatchersProvider to decide when to render the
 * signature pad instead of the default attachment picker.
 */
fun isSignatureItem(item: Questionnaire.QuestionnaireItemComponent): Boolean {
  val extension = item.getExtensionByUrl(SIGNATURE_EXTENSION_URL) ?: return false
  val value = extension.value as? CodeableConcept ?: return false
  return value.coding.any {
    it.system == SIGNATURE_EXTENSION_SYSTEM && it.code == SIGNATURE_EXTENSION_CODE
  }
}

object SignatureItemViewHolderFactory :
  QuestionnaireItemAndroidViewHolderFactory(R.layout.signature_capture_widget) {

  override fun getQuestionnaireItemViewHolderDelegate() =
    object : QuestionnaireItemAndroidViewHolderDelegate {

      override lateinit var questionnaireViewItem: QuestionnaireViewItem

      private val scope = MainScope()
      private lateinit var signaturePad: SignatureCaptureView

      override fun init(itemView: View) {
        signaturePad = itemView.findViewById(R.id.signature_pad)
        signaturePad.setOnSignatureChangedListener { hasSignature ->
          if (hasSignature) {
            val base64 =
              signaturePad.getSignaturePngBase64()
                ?: return@setOnSignatureChangedListener
            val attachment =
              Attachment().apply {
                contentType = "image/png"
                data = Base64.decode(base64, Base64.NO_WRAP)
                creation = Date()
              }
            val answer =
              QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                .setValue(attachment)
            scope.launch { questionnaireViewItem.setAnswer(answer) }
          } else {
            scope.launch { questionnaireViewItem.clearAnswer() }
          }
        }
      }

      override fun bind(questionnaireViewItem: QuestionnaireViewItem) {
        this.questionnaireViewItem = questionnaireViewItem
        val existingAttachment =
          questionnaireViewItem.answers.firstOrNull()?.value as? Attachment
        val base64 =
          existingAttachment?.data?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        when {
          // Has an answer and the pad is empty: this is a saved/loaded signature — show it
          // locked. If the pad already has content, it's our own just-written stroke, so
          // leave it (loading would lock the pad mid-draw).
          !base64.isNullOrEmpty() && signaturePad.isEmpty() ->
            signaturePad.loadSignaturePngBase64(base64)
          // Answer empty and pad empty: nothing to do, just ensure button state is correct.
          base64.isNullOrEmpty() && signaturePad.isEmpty() -> signaturePad.clearSilently()
          // Otherwise: pad has content, leave it alone (either in-flight write or user drawing).
          else -> Unit
        }
      }

      override fun setReadOnly(isReadOnly: Boolean) {
        signaturePad.isEnabled = !isReadOnly
      }
    }
}
