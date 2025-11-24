/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bips.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkerAdapterTest {

    private lateinit var context: Context
    private lateinit var parent: ViewGroup

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_AppCompat) // Use a theme that has basic styles.
        parent = LinearLayout(context)
    }

    private fun initiateMarkerViewHolder(markerInfo: MarkerInfo): MarkerAdapter.MarkerViewHolder {
        val markerInfoList = arrayListOf(markerInfo)
        val adapter = MarkerAdapter(markerInfoList)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        return holder
    }

    @Test
    fun getItemCount_returnsCorrectSize() {
        val markerInfoList =
            arrayListOf(
                MarkerInfo("markerType", "#000000", 100, 10, 50),
                MarkerInfo("cyan", "#00FFFF", 100, 10, 80),
            )
        val adapter = MarkerAdapter(markerInfoList)
        assertEquals(2, adapter.itemCount)
    }

    @Test
    fun onBindViewHolder_normalInkLevel_warningIsInvisible() {
        val markerInfo = MarkerInfo("markerType", "#000000", 100, 10, 11)
        val holder = initiateMarkerViewHolder(markerInfo)

        assertEquals(View.INVISIBLE, holder.warningImage.visibility)
    }

    @Test
    fun onBindViewHolder_belowLowInkLevel_warningIsVisible() {
        val markerInfo = MarkerInfo("markerType", "#000000", 100, 10, 5)
        val holder = initiateMarkerViewHolder(markerInfo)

        assertEquals(View.VISIBLE, holder.warningImage.visibility)
    }

    @Test
    fun onBindViewHolder_atLowInkLevel_warningIsVisible() {
        val markerInfo = MarkerInfo("markerType", "#000000", 100, 10, 10)
        val holder = initiateMarkerViewHolder(markerInfo)

        assertEquals(View.VISIBLE, holder.warningImage.visibility)
    }

    @Test
    fun onBindViewHolder_setsSeekBarMax() {
        val markerInfo = MarkerInfo("markerType", "#000000", 1234, 10, 50)
        val holder = initiateMarkerViewHolder(markerInfo)

        assertEquals(1234, holder.seekbar.max)
    }

    @Test
    fun onBindViewHolder_setsProgressLevel() {
        val markerInfo = MarkerInfo("markerType", "#000000", 100, 10, 50)
        val holder = initiateMarkerViewHolder(markerInfo)

        val progressDrawable = holder.seekbar.progressDrawable as LayerDrawable
        val progressLayer = progressDrawable.getDrawable(1)
        // Expected level is markerLevel * 10000 / markerHighLevel
        assertEquals(50 * 10000 / 100, progressLayer.level)
    }

    @Test
    fun onBindViewHolder_zeroHighLevel_progressIsMinimum() {
        val markerInfo = MarkerInfo("markerType", "#000000", 0, 10, 50)
        val holder = initiateMarkerViewHolder(markerInfo)

        val progressDrawable = holder.seekbar.progressDrawable as LayerDrawable
        val progressLayer = progressDrawable.getDrawable(1)
        // Expected progress level to be 100 when markerHighLevel is equal to 0
        assertEquals(100, progressLayer.level)
    }

    @Test
    fun onBindViewHolder_lowMarkerLevel_progressIsMinimum() {
        val markerInfo = MarkerInfo("markerType", "#000000", 100, 10, 1)
        val holder = initiateMarkerViewHolder(markerInfo)

        val progressDrawable = holder.seekbar.progressDrawable as LayerDrawable
        val progressLayer = progressDrawable.getDrawable(1)
        // Expected progress level to be 100 when markerLevel is below 1
        assertEquals(100, progressLayer.level)
    }

    @Test
    fun onBindViewHolder_setsContentDescriptionForBlack() {
        val markerInfo = MarkerInfo("markerType", "#000000", 100, 10, 75) // Black
        val holder = initiateMarkerViewHolder(markerInfo)

        val expectedLabel = context.getString(R.string.marker_level_black, 75)
        assertEquals(expectedLabel, holder.view.contentDescription)
    }

    @Test
    fun onBindViewHolder_setsContentDescriptionForCustomColor() {
        val markerInfo = MarkerInfo("markerType", "#0C2238", 100, 10, 99)
        val holder = initiateMarkerViewHolder(markerInfo)

        val expectedLabel = context.getString(R.string.marker_level_custom, 12, 34, 56, 99)
        assertEquals(expectedLabel, holder.view.contentDescription)
    }

    @Test
    fun onBindViewHolder_zeroMarkerLevel_setsContentDescriptionToZeroPercent() {
        val markerInfo = MarkerInfo("markerType", "#000000", 100, 10, 0)
        val holder = initiateMarkerViewHolder(markerInfo)

        val expectedLabel = context.getString(R.string.marker_level_black, 0)
        assertEquals(expectedLabel, holder.view.contentDescription)
    }

    @Test
    fun onBindViewHolder_zeroHighLevel_setsContentDescriptionToZeroPercent() {
        val markerInfo = MarkerInfo("markerType", "#000000", 0, 10, 50)
        val holder = initiateMarkerViewHolder(markerInfo)

        val expectedLabel = context.getString(R.string.marker_level_black, 0)
        assertEquals(expectedLabel, holder.view.contentDescription)
    }
}
