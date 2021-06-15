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

package org.smartregister.fhircore.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.smartregister.fhircore.R
import org.smartregister.fhircore.domain.Pagination
import org.smartregister.fhircore.fragment.NavigationDirection
import org.smartregister.fhircore.fragment.PatientListFragment
import org.smartregister.fhircore.viewholder.PaginationViewHolder
import org.smartregister.fhircore.viewholder.PatientItemViewHolder
import org.smartregister.fhircore.viewmodel.PatientListViewModel

/** UI Controller helper class to monitor Patient viewmodel and display list of patients. */
class PatientItemRecyclerViewAdapter(
  private val onItemClicked:
    (PatientListFragment.Intention, PatientListViewModel.PatientItem) -> Unit,
  private val paginationListener: (NavigationDirection, Int) -> Unit,
  private val patientStatusObserver: (String, Observer<PatientListViewModel.PatientStatus>) -> Unit
) : ListAdapter<Any, RecyclerView.ViewHolder>(PatientItemDiffCallback()) {

  class PatientItemDiffCallback : DiffUtil.ItemCallback<Any>() {
    override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean =
      oldItem is PatientListViewModel.PatientItem &&
        newItem is PatientListViewModel.PatientItem &&
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean =
      oldItem is PatientListViewModel.PatientItem &&
        newItem is PatientListViewModel.PatientItem &&
        oldItem.id == newItem.id
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    return if (viewType == 1) {
      PaginationViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.list_pagination, parent, false)
      )
    } else {
      PatientItemViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.patient_list_item, parent, false)
      )
    }
  }

  override fun getItemViewType(position: Int): Int {
    return if (position == (currentList.size - 1)) 1 else 0
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    val item = currentList[position]

    when (holder) {
      is PatientItemViewHolder ->
        holder.bindTo(
          item as PatientListViewModel.PatientItem,
          onItemClicked,
          patientStatusObserver
        )
      is PaginationViewHolder -> holder.bindTo(item as Pagination, paginationListener)
    }
  }
}
