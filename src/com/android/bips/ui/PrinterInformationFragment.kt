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

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.bips.R
import com.android.bips.ipp.JobStatus
import com.android.bips.jni.BackendConstants
import com.android.bips.jni.LocalPrinterCapabilities
import com.android.bips.jni.MediaSizes
import java.util.*

/**
 * Printer information fragment
 */
class PrinterInformationFragment : Fragment() {

    /** Printer Information view model */
    private val printerInformationViewModel: Lazy<PrinterInformationViewModel> =
        activityViewModels()
    private val statusMapping = LinkedHashMap(JobStatus.getBlockReasonsMap())
    private lateinit var printerName: TextView
    private lateinit var printerIcon: ImageView
    private lateinit var printerStatus: TextView
    private lateinit var printerStatusLayout: ConstraintLayout
    private lateinit var progressBarPrinterStatus: ProgressBar
    private lateinit var mediaReady: TextView
    private lateinit var mediaReadyLabel: TextView
    private lateinit var inkLevelsRecyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.printer_information,
            container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        printerName = view.findViewById(R.id.printerName)
        printerIcon = view.findViewById(R.id.printerIcon)
        printerStatus = view.findViewById(R.id.printerStatus)
        printerStatusLayout = view.findViewById(R.id.printerStatusLayout)
        progressBarPrinterStatus = view.findViewById(R.id.progressBarPrinterStatus)
        mediaReady = view.findViewById(R.id.mediaReady)
        mediaReadyLabel = view.findViewById(R.id.mediaReadyLabel)
        inkLevelsRecyclerView = view.findViewById(R.id.inkLevelsRecyclerView)
        super.onViewCreated(view, savedInstanceState)
        statusMapping[BackendConstants.PRINTER_STATE_IDLE] = R.string.printer_ready
        statusMapping[BackendConstants.PRINTER_STATE_RUNNING] = R.string.printer_state__printing
        statusMapping[BackendConstants.PRINTER_STATE_UNABLE_TO_CONNECT] =
            R.string.printer_state__offline
        statusMapping[BackendConstants.PRINTER_STATE_BLOCKED] =
            R.string.printer_state__check_printer

        activity?.apply {
            setPrinterImage(this)
            setPrinterStatus(this)
            printerInformationViewModel.value.getPrinterCapsLiveData().observe(this) {
                it?.also { caps ->
                    getIconBitmap(caps)
                    setMediaReadySize(caps)
                    setMarkerView(caps)
                    view.visibility = View.VISIBLE
                    printerName.text = caps.name
                } ?: run {
                    view.visibility = View.GONE
                }
            }
        }
    }

    private fun setMediaReadySize(caps: LocalPrinterCapabilities) {
        var mediaReadyString = ""
        caps.mediaReadySizes?.also { mediaReadySizes ->
            if (mediaReadySizes.isEmpty()) {
                mediaReady.visibility = View.GONE
                mediaReadyLabel.visibility = View.GONE
            }
            for (i in mediaReadySizes) {
                mediaReadyString += MediaSizes.getInstance(context)
                    .getMediaName(i, context) + "\n"
            }
            mediaReady.text = mediaReadyString.dropLast(1)
        } ?: run {
            mediaReady.visibility = View.GONE
            mediaReadyLabel.visibility = View.GONE
        }
    }

    private fun getIconBitmap(caps: LocalPrinterCapabilities) {
        caps.mPrinterIconUris?.also { iconUri ->
            if (iconUri.isNotEmpty()) {
                printerInformationViewModel.value.getBitmap(iconUri.last())
            }
        }
    }

    private fun setPrinterImage(fragmentActivity: FragmentActivity) {
        printerInformationViewModel.value.getPrinterBitmapLiveData()
            .observe(fragmentActivity) { printerImage ->
                if (printerImage != null) {
                    printerIcon.visibility = View.VISIBLE
                    printerIcon.setImageBitmap(printerImage)
                } else {
                    printerIcon.visibility = View.GONE
                }
            }
    }

    /**
     * Set Status Of Printer
     */
    private fun setPrinterStatus(fragmentActivity: FragmentActivity) {
        printerInformationViewModel.value.getPrinterUnavailableLiveData()
            .observe(fragmentActivity) {
                if (it) printerStatusLayout.visibility = View.GONE
            }
        printerInformationViewModel.value.getPrinterStatusLiveData()
            .observe(fragmentActivity) { callbackParams ->
                callbackParams.apply {
                    val reasonsList = blockedReasons?.toList() ?: emptyList()
                    val statusList = getPrinterStatus(printerState, reasonsList)
                    if (statusList.isEmpty()) {
                        printerStatusLayout.visibility = View.GONE
                    } else {
                        if (DEBUG) {
                            Log.e(TAG, "printer status list ${TextUtils.join("\n", statusList)}")
                        }
                        printerStatus.text = TextUtils.join("\n", statusList)
                        printerStatusLayout.visibility = View.VISIBLE
                        printerStatus.visibility = View.VISIBLE
                        progressBarPrinterStatus.visibility = View.GONE
                    }
                }
            }
    }

    /**
     * Maps the printer state and reasons into a list of status strings
     * If the printerReasons is not empty (printer is blocked), returns a list of (one or more)
     * blocked reasons, otherwise it will be a one item list of printer state. May return an empty
     * list if no resource id is found for the given status(es)
     */
    private fun getPrinterStatus(printerState: String, printerReasons: List<String>): Set<String> {
        val resourceIds: MutableSet<String> = LinkedHashSet()
        for (reason in printerReasons) {
            if (TextUtils.isEmpty(reason) ||
                reason == BackendConstants.BLOCKED_REASON__SPOOL_AREA_FULL &&
                BackendConstants.PRINTER_STATE_BLOCKED != printerState
            ) {
                continue
            }
            statusMapping[reason]?.also { resourceIds.add(getString(it)) }
        }
        if (resourceIds.isEmpty() || BackendConstants.PRINTER_STATE_RUNNING == printerState) {
            statusMapping[printerState]?.also { resourceIds.add(getString(it)) }
        }
        return resourceIds
    }

    /**
     * Set marker view
     * Fills supplies levels views based on capabilities
     * @param view view
     * @param caps the selected printer's capabilities
     */
    private fun setMarkerView(caps: LocalPrinterCapabilities) {
        val mMarkerInfoList = ArrayList<MarkerInfo>()
        for (i in caps.markerTypes.indices) {
            if ((validTonerTypes.contains(caps.markerTypes[i]) ||
                        validInkTypes.contains(caps.markerTypes[i])) && caps.markerLevel[i] >= 0
            ) {
                caps.markerColors[i].split("#").apply {
                    for (j in 1 until size) {
                        mMarkerInfoList.add(
                            MarkerInfo(
                                caps.markerTypes[i],
                                "#" + this[j],
                                caps.markerHighLevel[i],
                                caps.markerLowLevel[i],
                                caps.markerLevel[i]
                            )
                        )

                    }
                }
            }
        }
        with(inkLevelsRecyclerView) {
            this.layoutManager = LinearLayoutManager(activity)
            this.adapter = MarkerAdapter(mMarkerInfoList)
        }
    }

    companion object {
        private val validTonerTypes = listOf("toner", "toner-cartridge")
        private val validInkTypes = listOf("ink", "inkCartridge", "ink-cartridge")
        private const val TAG = "PrinterInformationFragment"
        private const val DEBUG = false
    }
}