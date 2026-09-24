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

package org.smartregister.fhircore.quest.app.fakes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import org.hl7.fhir.r4.model.Basic
import org.hl7.fhir.r4.model.Binary
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.engine.auth.AuthCredentials
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceDataSource
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceService
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.app.AppConfigService
import org.smartregister.fhircore.quest.ui.login.LoginActivity

object Faker {

  val authCredentials =
    AuthCredentials(username = "demo", salt = "ChUmvi", passwordHash = "GENERATED_PASSWORD_HASH")

  val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
    useAlternativeNames = true
  }

  private const val APP_DEBUG = "app/debug"

  private val sampleImageJSONString =
    "{\n" +
      "  \"id\": \"d60ff460-7671-466a-93f4-c93a2ebf2077\",\n" +
      "  \"resourceType\": \"Binary\",\n" +
      "  \"contentType\": \"image/jpeg\",\n" +
      "  \"data\": \"iVBORw0KGgoAAAANSUhEUgAAAFMAAABTCAYAAADjsjsAAAAABHNCSVQICAgIfAhkiAAAABl0RVh0U29mdHdhcmUAZ25vbWUtc2NyZWVuc2hvdO8Dvz4AAAAtdEVYdENyZWF0aW9uIFRpbWUARnJpIDE5IEFwciAyMDI0IDA3OjIxOjM4IEFNIEVBVIqENmYAAADTSURBVHic7dDBCcAgAMBAdf/p+nQZXSIglLsJQube3xkk1uuAPzEzZGbIzJCZITNDZobMDJkZMjNkZsjMkJkhM0NmhswMmRkyM2RmyMyQmSEzQ2aGzAyZGTIzZGbIzJCZITNDZobMDJkZMjNkZsjMkJkhM0NmhswMmRkyM2RmyMyQmSEzQ2aGzAyZGTIzZGbIzJCZITNDZobMDJkZMjNkZsjMkJkhM0NmhswMmRkyM2RmyMyQmSEzQ2aGzAyZGTIzZGbIzJCZITNDZobMDJkZMjN0AXiwBCviCqIRAAAAAElFTkSuQmCC\"\n" +
      "}"
  private val testDispatcher = UnconfinedTestDispatcher()
  private val configService = AppConfigService(ApplicationProvider.getApplicationContext())
  private val testDispatcherProvider =
    object : DispatcherProvider {
      override fun default() = testDispatcher

      override fun io() = testDispatcher

      override fun main() = testDispatcher

      override fun unconfined() = testDispatcher
    }

  fun buildTestConfigurationRegistry(): ConfigurationRegistry {
    val fhirResourceService = mockk<FhirResourceService>()
    val fhirResourceDataSource = spyk(FhirResourceDataSource(fhirResourceService))
    coEvery { fhirResourceService.getResource(any()) } returns Bundle()

    val configurationRegistry =
      spyk(
        ConfigurationRegistry(
          fhirEngine = mockk(),
          fhirResourceDataSource = fhirResourceDataSource,
          sharedPreferencesHelper =
            spyk(SharedPreferencesHelper(ApplicationProvider.getApplicationContext(), Gson())),
          dispatcherProvider = testDispatcherProvider,
          configService = configService,
          json = json,
          context = ApplicationProvider.getApplicationContext<HiltTestApplication>(),
        ),
      )

    coEvery { configurationRegistry.addOrUpdate(any()) } just runs

    runBlocking {
      configurationRegistry.loadConfigurations(
        appId = APP_DEBUG,
        context = ApplicationProvider.getApplicationContext(),
      ) {}
    }

    return configurationRegistry
  }

  fun buildSharedPreferencesHelper() =
    SharedPreferencesHelper(
      ApplicationProvider.getApplicationContext<Application>(),
      Gson(),
    )

  fun buildPatient(
    id: String = "sampleId",
    family: String = "Mandela",
    given: String = "Nelson",
    age: Int = 78,
    gender: Enumerations.AdministrativeGender = Enumerations.AdministrativeGender.MALE,
  ): Patient {
    return Patient().apply {
      this.id = id
      this.identifierFirstRep.value = id
      this.addName().apply {
        this.family = family
        this.given.add(StringType(given))
      }
      this.gender = gender
      this.birthDate = DateType(Date()).apply { add(Calendar.YEAR, -age) }.dateTimeValue().value

      this.addAddress().apply {
        district = "Dist 1"
        city = "City 1"
      }
    }
  }

  fun buildBasicResource(
    id: String = "sampleId",
  ): Basic {
    return Basic().apply {
      this.id = id
      this.identifierFirstRep.value = id
    }
  }

  open class TestLoginActivity : LoginActivity() {
    override fun pinActive() = true

    override fun pinEnabled() = true

    override fun deviceOnline() = false

    override fun isRefreshTokenActive() = true
  }

  open class TestLoginActivityInActivePin : LoginActivity() {
    override fun pinActive() = false

    override fun pinEnabled() = true

    override fun deviceOnline() = true
  }

  open class TestLoginActivityWithoutPin : LoginActivity() {
    override fun pinActive() = false

    override fun pinEnabled() = false

    override fun deviceOnline() = false

    override fun isRefreshTokenActive() = true
  }

  open class TestLoginActivityLoggedOut : TestLoginActivityWithoutPin() {
    override fun userLoggedOut() = true
  }

  fun buildBinaryResource(
    id: String = "d60ff460-7671-466a-93f4-c93a2ebf2077",
  ): Binary {
    return Binary().apply {
      this.id = id
      this.contentType = "image/jpeg"
      this.data = sampleImageJSONString.toByteArray()
    }
  }
}
