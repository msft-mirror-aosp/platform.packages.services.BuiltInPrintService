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

            // Marker level rounded down to the nearest int.  This goes all the
            // way to 0 instead of stopping at 1 because we do not need to leave
            // any visual indicator of the color.
            val level: Int =
                if (markerHighLevel != 0 && markerLevel > 0) {
                    markerLevel * 100 / markerHighLevel
                } else {
                    0 // set 0% for unknown
                }

            // Map common colors to human-friendly names.  Everything else has a fallback to
            // declaring the underying color value.
            val color = Color.parseColor(markerColor)
            val label =
                when (color) {
                    Color.BLACK -> holder.view.context.getString(R.string.marker_level_black, level)
                    Color.CYAN -> holder.view.context.getString(R.string.marker_level_cyan, level)
                    Color.MAGENTA ->
                        holder.view.context.getString(R.string.marker_level_magenta, level)
                    Color.YELLOW ->
                        holder.view.context.getString(R.string.marker_level_yellow, level)
                    Color.RED -> holder.view.context.getString(R.string.marker_level_red, level)
                    Color.GREEN -> holder.view.context.getString(R.string.marker_level_green, level)
                    Color.BLUE -> holder.view.context.getString(R.string.marker_level_blue, level)
                    Color.LTGRAY ->
                        holder.view.context.getString(R.string.marker_level_ltgray, level)
                    Color.DKGRAY ->
                        holder.view.context.getString(R.string.marker_level_dkgray, level)
                    LTCYAN -> holder.view.context.getString(R.string.marker_level_ltcyan, level)
                    LTMAGENTA ->
                        holder.view.context.getString(R.string.marker_level_ltmagenta, level)
                    VIOLET -> holder.view.context.getString(R.string.marker_level_violet, level)
                    else ->
                        holder.view.context.getString(
                            R.string.marker_level_custom,
                            Color.red(color),
                            Color.green(color),
                            Color.blue(color),
                            level,
                        )
                }
            holder.view.contentDescription = label
        }
    }

    override fun getItemCount(): Int {
        return mMarkerInfoList.size
    }

    companion object {
        /** Seekbar background */
        private const val BACKGROUND_COLOR = "#898383"

        /** Ink color for light cyan */
        private const val LTCYAN = 0x7FFFFF

        /** Ink color for light magenta */
        private const val LTMAGENTA = 0xFF7FFF

        /** Ink color for violet */
        private const val VIOLET = 0x7F00FF
    }
}
