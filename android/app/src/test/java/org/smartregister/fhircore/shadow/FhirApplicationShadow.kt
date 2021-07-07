/*
 * Copyright 2021 Ona Systems, Inc
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

package org.smartregister.fhircore.shadow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.sync.FhirDataSource
import io.mockk.spyk
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplication
import org.smartregister.fhircore.FhirApplication

@Implements(FhirApplication::class)
class FhirApplicationShadow : ShadowApplication() {

  val dataSource =
    spyk<FhirDataSource>(
      object : FhirDataSource {
        override suspend fun loadData(path: String): Bundle {
          return Bundle()
        }

        override suspend fun insert(
          resourceType: String,
          resourceId: String,
          payload: String
        ): Resource {
          return Patient()
        }

        override suspend fun update(
          resourceType: String,
          resourceId: String,
          payload: String
        ): OperationOutcome {
          return OperationOutcome()
        }

        override suspend fun delete(resourceType: String, resourceId: String): OperationOutcome {
          return OperationOutcome()
        }
      }
    )

  @Implementation
  fun constructFhirEngine(): FhirEngine {
    val clazz = Class.forName("com.google.android.fhir.FhirServices\$Builder")
    val constructor = clazz.getDeclaredConstructor(FhirDataSource::class.java, Context::class.java)
    constructor.isAccessible = true
    val builder = constructor.newInstance(dataSource, ApplicationProvider.getApplicationContext())
    val obj =
      Class.forName("com.google.android.fhir.FhirServices")
        .cast(builder.javaClass.getDeclaredMethod("build").invoke(builder))
    val field = obj?.javaClass?.getDeclaredField("fhirEngine")
    field?.isAccessible = true
    return field?.get(obj) as FhirEngine
  }
}
