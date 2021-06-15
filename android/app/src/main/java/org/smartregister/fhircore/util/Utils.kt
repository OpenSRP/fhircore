/*
 * Copyright 2021 Ona Systems Inc
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

package org.smartregister.fhircore.util

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import ca.uhn.fhir.rest.param.ParamPrefixEnum
import com.google.android.fhir.search.Search
import org.hl7.fhir.r4.model.Patient
import org.joda.time.DateTime
import org.joda.time.LocalDate
import org.joda.time.ReadablePartial
import org.joda.time.Years
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

object Utils {

  fun getAgeFromDate(dateOfBirth: String, currentDate: ReadablePartial? = null): Int {
    val date: DateTime = DateTime.parse(dateOfBirth)
    val age: Years = Years.yearsBetween(date.toLocalDate(), currentDate ?: LocalDate.now())
    return age.getYears()
  }

  fun addBasePatientFilter(search: Search) {
    search.filter(Patient.ADDRESS_CITY) {
      prefix = ParamPrefixEnum.EQUAL
      value = "NAIROBI"
    }
  }

  fun EditText.addOnDrawableClickedListener(
    drawablePosition: DrawablePosition,
    onClicked: () -> Unit
  ) {
    this.setOnTouchListener(
      object : View.OnTouchListener {

        override fun onTouch(v: View?, event: MotionEvent?): Boolean {
          if (event!!.action == MotionEvent.ACTION_UP &&
              isDrawableClicked(drawablePosition, event, v as EditText)
          ) {
            onClicked()
            return true
          }
          return false
        }
      }
    )
  }

  private fun isDrawableClicked(
    drawablePosition: DrawablePosition,
    event: MotionEvent?,
    view: EditText
  ): Boolean {
    return when (drawablePosition) {
      DrawablePosition.DRAWABLE_RIGHT ->
        event!!.rawX >=
          (view.right - view.compoundDrawables[drawablePosition.position].bounds.width())
      DrawablePosition.DRAWABLE_LEFT ->
        event!!.rawX <= (view.compoundDrawables[drawablePosition.position].bounds.width())
      else -> {
        return false
      }
    }
  }

  enum class DrawablePosition(val position: Int) {
    DRAWABLE_LEFT(0),
    DRAWABLE_TOP(1),
    DRAWABLE_RIGHT(2),
    DRAWABLE_BOTTOM(3)
  }

  fun addDays(initialDate: String, daysToAdd: Int = 0, returnDateFormat: String = "M-d-Y"): String {
    val fmt: DateTimeFormatter = DateTimeFormat.forPattern(returnDateFormat)
    val date: DateTime = DateTime.parse(initialDate)
    return date.plusDays(daysToAdd).toString(fmt)
  }

  fun getViewsByTag(root: ViewGroup, tag: String): ArrayList<View>? {
    val views = ArrayList<View>()
    val childCount = root.childCount
    for (i in 0 until childCount) {
      val child = root.getChildAt(i)
      if (child is ViewGroup) {
        views.addAll(getViewsByTag(child, tag)!!)
      }
      val tagObj = child.tag
      if (tagObj != null && tagObj == tag) {
        views.add(child)
      }
    }
    return views
  }

  fun hideViewsByTag(root: ViewGroup, tag: String) {
    getViewsByTag(root, tag)?.forEach { it.visibility = View.GONE }
  }

  fun hideViewById(root: ViewGroup, id: Int) {
    root.findViewById<View>(id).visibility = View.GONE
  }

  fun showViewById(root: ViewGroup, id: Int) {
    root.findViewById<View>(id).visibility = View.VISIBLE
  }
}
