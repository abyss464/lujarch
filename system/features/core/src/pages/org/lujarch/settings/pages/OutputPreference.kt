/*
 * Copyright (C) 2026 The Lujarch Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lujarch.settings.pages

import android.content.Context
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/** Command output: monospace, selectable, not truncated. */
class OutputPreference(context: Context) : Preference(context) {
    var text: CharSequence = ""
        set(value) {
            if (field != value) {
                field = value
                notifyChanged()
            }
        }

    init {
        layoutResource = R.layout.preference_output
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.output_text) as TextView).text = text
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
    }
}
