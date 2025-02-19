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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.SSLCertificateSocketFactory
import android.net.TrafficStats
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.bips.BuiltInPrintService
import com.android.bips.jni.JobCallbackParams
import com.android.bips.jni.LocalPrinterCapabilities
import com.android.bips.jni.PrinterStatusMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

/**
 * Printer Information ViewModel
 */
class PrinterInformationViewModel : ViewModel() {
    companion object {
        private const val TAG = "PrinterInformationViewModel"
        private const val DEBUG = false
    }
    private val HTTPS = "https"
    private val HTTP = "http"

    /** Printer capabilities live data */
    private val printerCapsLiveData = MutableLiveData<LocalPrinterCapabilities>()

    /** Printer status live data */
    private val printerStatusLiveData = MutableLiveData<JobCallbackParams>()
    private val printerUnavailableLiveData = MutableLiveData<Boolean>()
    private val printerBitmapLiveData = MutableLiveData<Bitmap>()

    private lateinit var printerStatusMonitor: PrinterStatusMonitor

    fun getPrinterCapsLiveData(): LiveData<LocalPrinterCapabilities> {
        return printerCapsLiveData
    }

    fun setPrinterCapsLiveData(localPrinterCapabilities: LocalPrinterCapabilities) {
        printerCapsLiveData.value = localPrinterCapabilities
    }

    fun getPrinterStatusLiveData(): LiveData<JobCallbackParams> {
        return printerStatusLiveData
    }

    fun getPrinterUnavailableLiveData(): LiveData<Boolean> {
        return printerUnavailableLiveData
    }

    fun setPrinterUnavailableLiveData(status: Boolean) {
        printerUnavailableLiveData.value = status
    }

    fun getPrinterBitmapLiveData(): LiveData<Bitmap> {
        return printerBitmapLiveData
    }

    fun getBitmap(iconUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            TrafficStats.setThreadStatsTag(0xF00D)
            var con: HttpURLConnection? = null
            try {
                if (DEBUG) Log.d(TAG, "Fetching icon from $iconUri")
                val url = URL(iconUri)
                val protocol = url.protocol
                if (protocol.equals(HTTPS, ignoreCase = true)) {
                    con = url.openConnection() as HttpsURLConnection
                    (con as HttpsURLConnection?)?.sslSocketFactory =
                        SSLCertificateSocketFactory.getInsecure(0, null)
                    (con as HttpsURLConnection?)?.hostnameVerifier =
                        HostnameVerifier { s, sslSession -> true }
                } else if (protocol.equals(HTTP, ignoreCase = true)) {
                    con = url.openConnection() as HttpURLConnection
                } else {
                    printerBitmapLiveData.postValue(null)
                }
                con?.doInput = true
                con?.connect()
                if (DEBUG) Log.d(TAG, "Connected with " + con?.responseCode?.toString())
                if (con?.responseCode == HttpURLConnection.HTTP_OK) {
                    con.inputStream.use { `in` ->
                        printerBitmapLiveData.postValue(BitmapFactory.decodeStream(`in`))
                    }
                }
            } catch (e: IllegalStateException) {
                if (DEBUG) Log.e(TAG, "Failed to download printer icon $e")
            } catch (e: IOException) {
                if (DEBUG) Log.e(TAG, "Failed to download printer icon $e")
            } finally {
                con?.disconnect()
                TrafficStats.clearThreadStatsTag()
            }
        }
    }

    fun getPrinterStatus(uri: Uri, printService: BuiltInPrintService) {
        viewModelScope.launch(Dispatchers.IO) {
            printerStatusMonitor = PrinterStatusMonitor(uri, printService, ::onPrinterStatus)
        }
    }

    fun stopPrinterStatusMonitor(printService: BuiltInPrintService) {
        if (::printerStatusMonitor.isInitialized) {
            printerStatusMonitor.stopMonitor(printService)
        }
    }

    private fun onPrinterStatus(status: JobCallbackParams?) {
        printerStatusLiveData.postValue(status)
    }
}