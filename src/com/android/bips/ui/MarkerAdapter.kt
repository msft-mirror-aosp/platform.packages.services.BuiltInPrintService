/*
 * Copyright (C) 2024 The Android Open Source Project
 * Copyright (C) 2024 Mopria Alliance, Inc.
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

package com.android.bips.ui

import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.bips.R

/**
 * Marker Adapter
 *
 * Recyclerview adapter for showing ink levels in printer information screen
 *
 * @property mMarkerInfoList list of marker info
 * @constructor constructor
 */
class MarkerAdapter(private val mMarkerInfoList: ArrayList<MarkerInfo>) :
    RecyclerView.Adapter<MarkerAdapter.MarkerViewHolder>() {
        inner class MarkerViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            var seekbar: ProgressBar = itemView.findViewById(R.id.seekbar)
            var warningImage: ImageView = itemView.findViewById(R.id.warningImage)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MarkerViewHolder {
        val v =
            LayoutInflater.from(parent.context).inflate(R.layout.item_marker_type, parent, false)
        return MarkerViewHolder(v)
    }

    override fun onBindViewHolder(holder: MarkerViewHolder, position: Int) {
        with(mMarkerInfoList[position]) {
            holder.seekbar.max = markerHighLevel
            val progressBarDrawable = holder.seekbar.progressDrawable as LayerDrawable
            progressBarDrawable.getDrawable(0).colorFilter =
                BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    Color.parseColor(BACKGROUND_COLOR),
                    BlendModeCompat.SRC_IN
                )
            progressBarDrawable.getDrawable(1).colorFilter =
                BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    Color.parseColor(markerColor),
                    BlendModeCompat.SRC_IN
                )
            // Set progress level on a scale of 0-10000
            progressBarDrawable.getDrawable(1).level =
                if (markerHighLevel != 0 && markerLevel > 1) {
                    markerLevel * 10000 / markerHighLevel
                } else {
                    100 // set 1% as minimum level
                }

            if (markerLevel <= markerLowLevel) {
                holder.warningImage.visibility = View.VISIBLE
            } else {
                holder.warningImage.visibility = View.INVISIBLE
            }
        }
    }

    override fun getItemCount(): Int {
        return mMarkerInfoList.size
    }

    companion object {
        /** Seekbar background */
        private const val BACKGROUND_COLOR = "#898383"
    }
}