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

package org.smartregister.fhircore.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.core.view.GravityCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import org.smartregister.fhircore.R
import org.smartregister.fhircore.fragment.PatientListFragment
import org.smartregister.fhircore.util.Utils
import org.smartregister.fhircore.util.Utils.addOnDrawableClickedListener

/** An activity representing a list of Patients. */
class PatientListActivity : BaseSimpleActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setUpViews()
  }

  override fun getContentLayout(): Int {
    return R.layout.activity_patient_list
  }

  private fun setUpViews() {
    findViewById<Button>(R.id.btn_register_new_patient).setOnClickListener { addPatient(it) }

    findViewById<ViewPager2>(R.id.patient_list_pager).adapter = PatientListPagerAdapter(this)

    var editText = findViewById<EditText>(R.id.edit_text_search)
    editText.doAfterTextChanged {
      if (it!!.isEmpty()) {
        editText.setOnTouchListener(null)
        editText.setCompoundDrawablesWithIntrinsicBounds(
          getDrawable(R.drawable.ic_search),
          null,
          null,
          null
        )
      } else {
        editText.setCompoundDrawablesWithIntrinsicBounds(
          null,
          null,
          getDrawable(R.drawable.ic_cancel),
          null
        )
        editText.addOnDrawableClickedListener(Utils.DrawablePosition.DRAWABLE_RIGHT) { it.clear() }
      }
    }

    setUpDrawerContent()
  }

  private fun setUpDrawerContent() {
    val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
    findViewById<ImageButton>(R.id.btn_drawer_menu).setOnClickListener {
      drawerLayout.openDrawer(GravityCompat.START)
    }
  }

  private fun addPatient(view: View) {
    // TO DO: Open patient registration form
    val context = view.context
    context.startActivity(
      Intent(context, QuestionnaireActivity::class.java).apply {
        putExtra(QuestionnaireActivity.QUESTIONNAIRE_TITLE_KEY, "Client info")
        putExtra(QuestionnaireActivity.QUESTIONNAIRE_FILE_PATH_KEY, "patient-registration.json")
      }
    )
  }

  // pager adapter
  private inner class PatientListPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
    override fun getItemCount(): Int {
      return 1
    }

    override fun createFragment(position: Int): Fragment {
      return PatientListFragment()
    }
  }
}
