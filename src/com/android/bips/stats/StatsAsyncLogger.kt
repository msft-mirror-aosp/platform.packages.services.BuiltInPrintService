/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bips.stats

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentInfo
import android.print.PrintJobInfo
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object StatsAsyncLogger {

    enum class JobOrigin(val code: Int) {
        SHARED_IMAGE(BipsStatsLog.BIPS_PRINT_JOB__JOB_ORIGIN__BIPS_JOB_ORIGIN_SHARED_IMAGE),
        SHARED_PDF(BipsStatsLog.BIPS_PRINT_JOB__JOB_ORIGIN__BIPS_JOB_ORIGIN_SHARED_PDF),
        DIRECT_PRINT(BipsStatsLog.BIPS_PRINT_JOB__JOB_ORIGIN__BIPS_JOB_ORIGIN_DIRECT_PRINT),
    }

    private val TAG = StatsAsyncLogger::class.java.simpleName
    private val DEBUG = false

    @VisibleForTesting val EVENT_REPORTED_MIN_INTERVAL: Duration = 10.milliseconds
    private val MAX_EVENT_QUEUE = 150

    // Only var for testing purposes
    private var semaphore = Semaphore(MAX_EVENT_QUEUE)
    // We must call start() before getting the HandlerThread's looper.
    // NOTE: We never quit() this HandlerThread because we want this
    // running for the lifetime of the process.
    private val handlerThread = HandlerThread("StatsEventLoggerWrapper").also { it.start() }
    private var eventHandler = Handler(handlerThread.getLooper())
    private var nextAvailableTimeMillis = SystemClock.uptimeMillis()
    private var statsLogWrapper = StatsLogWrapper()

    @VisibleForTesting
    fun testSetStatsLogWrapper(wrapper: StatsLogWrapper) {
        statsLogWrapper = wrapper
    }

    @VisibleForTesting
    fun testSetSemaphore(s: Semaphore) {
        semaphore = s
    }

    @VisibleForTesting
    fun testSetHandler(handler: Handler) {
        eventHandler = handler
    }

    fun PrintJob(
        makeAndModel: String,
        secure: Boolean,
        jobOrigin: JobOrigin,
        localJobRawResult: Int,
        jobInfo: PrintJobInfo,
        docInfo: PrintDocumentInfo,
        borderless: Boolean,
        duplexMode: Int,
        localMediaType: Int,
    ): Boolean {
        if (DEBUG) {
            Log.d(TAG, "Logging PrintJob event")
        }

        synchronized(semaphore) {
            if (!semaphore.tryAcquire()) {
                Log.w(TAG, "Logging too many events, dropping PrintJob event")
                return false
            }
            val pageCount =
                // pageRange.getSize() is hidden so this is essentially copied from framework
                jobInfo.getPages()?.sumOf { pageRange: PageRange ->
                    (pageRange.getEnd() - pageRange.getStart() + 1)
                } ?: 0
            val result =
                eventHandler.postAtTime(
                    Runnable {
                        synchronized(semaphore) {
                            statsLogWrapper.internalPrintJob(
                                makeAndModel,
                                jobOrigin.code,
                                localPrintJobResultMap.getOrDefault(
                                    localJobRawResult,
                                    BipsStatsLog
                                        .BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_UNSPECIFIED,
                                ),
                                borderless,
                                frameworkMediaSizeMap.getOrDefault(
                                    jobInfo.getAttributes().getMediaSize()?.getId(),
                                    BipsStatsLog
                                        .BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_UNSPECIFIED,
                                ),
                                duplexModeMap.getOrDefault(
                                    duplexMode,
                                    BipsStatsLog
                                        .BIPS_PRINT_JOB__DUPLEX_MODE__FRAMEWORK_DUPLEX_MODE_UNSPECIFIED,
                                ),
                                localMediaTypeMap.getOrDefault(
                                    localMediaType,
                                    BipsStatsLog
                                        .BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_UNSPECIFIED,
                                ),
                                frameworkColorModeMap.getOrDefault(
                                    jobInfo.getAttributes().getColorMode(),
                                    BipsStatsLog
                                        .BIPS_PRINT_JOB__COLOR__FRAMEWORK_COLOR_MODE_UNSPECIFIED,
                                ),
                                secure,
                                jobInfo.getAttributes().getResolution()?.getHorizontalDpi() ?: 0,
                                jobInfo.getAttributes().getResolution()?.getVerticalDpi() ?: 0,
                                pageCount,
                            )
                            semaphore.release()
                        }
                    },
                    nextAvailableTimeMillis,
                )
            if (!result) {
                Log.e(TAG, "Could not log PrintJob event")
                semaphore.release()
                return false
            }
            nextAvailableTimeMillis = getNextAvailableTimeMillis()
        }
        return true
    }

    // Returns true if event is now pending to be logged, false otherwise
    fun RequestPrinterCapabilitiesStatus(getLocalCapsStatus: Int, secure: Boolean): Boolean {
        if (DEBUG) {
            Log.d(TAG, "Logging RequestPrinterCapabilitiesStatus event")
        }
        synchronized(semaphore) {
            if (!semaphore.tryAcquire()) {
                Log.w(
                    TAG,
                    "Logging too many events, dropping RequestPrinterCapabilitiesStatus event",
                )
                return false
            }
            val result =
                eventHandler.postAtTime(
                    Runnable {
                        synchronized(semaphore) {
                            if (DEBUG) {
                                Log.d(TAG, "Async logging RequestPrinterCapabilitiesStatus event")
                            }
                            statsLogWrapper.internalRequestPrinterCapabilitiesStatus(
                                getLocalCapsStatus,
                                secure,
                            )
                            semaphore.release()
                        }
                    },
                    nextAvailableTimeMillis,
                )
            if (!result) {
                Log.e(TAG, "Could not log RequestPrinterCapabilitiesStatus event")
                semaphore.release()
                return false
            }
            nextAvailableTimeMillis = getNextAvailableTimeMillis()
        }
        return true
    }

    private fun getNextAvailableTimeMillis(): Long {
        return max(
            // Handles back to back records
            nextAvailableTimeMillis + EVENT_REPORTED_MIN_INTERVAL.inWholeMilliseconds,
            // Updates next time to more recent value if it wasn't recently
            SystemClock.uptimeMillis() + EVENT_REPORTED_MIN_INTERVAL.inWholeMilliseconds,
        )
    }

    // Returns true if successfully awaited all pending events, false otherwise
    fun tryAwaitingAllEvents(): Boolean {
        if (DEBUG) {
            Log.d(TAG, "Begin flushing events")
        }
        val acquired =
            semaphore.tryAcquire(
                MAX_EVENT_QUEUE,
                MAX_EVENT_QUEUE * EVENT_REPORTED_MIN_INTERVAL.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
        if (!acquired) {
            Log.w(TAG, "Time exceeded awaiting stats events")
            return false
        }
        if (DEBUG) {
            Log.d(TAG, "End flushing events")
        }
        return true
    }

    // Mappings for internal values to associated proto values.

    private val frameworkMediaSizeMap =
        // Keep this up to date to map any new media sizes in the framework
        mapOf(
            PrintAttributes.MediaSize.UNKNOWN_PORTRAIT.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_UNKNOWN_PORTRAIT,
            PrintAttributes.MediaSize.UNKNOWN_LANDSCAPE.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_UNKNOWN_LANDSCAPE,
            PrintAttributes.MediaSize.ISO_A0.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A0,
            PrintAttributes.MediaSize.ISO_A1.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A1,
            PrintAttributes.MediaSize.ISO_A2.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A2,
            PrintAttributes.MediaSize.ISO_A3.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A3,
            PrintAttributes.MediaSize.ISO_A4.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A4,
            PrintAttributes.MediaSize.ISO_A5.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A5,
            PrintAttributes.MediaSize.ISO_A6.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A6,
            PrintAttributes.MediaSize.ISO_A7.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A7,
            PrintAttributes.MediaSize.ISO_A8.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A8,
            PrintAttributes.MediaSize.ISO_A9.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A9,
            PrintAttributes.MediaSize.ISO_A10.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A10,
            PrintAttributes.MediaSize.ISO_B0.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B0,
            PrintAttributes.MediaSize.ISO_B1.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B1,
            PrintAttributes.MediaSize.ISO_B2.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B2,
            PrintAttributes.MediaSize.ISO_B3.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B3,
            PrintAttributes.MediaSize.ISO_B4.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B4,
            PrintAttributes.MediaSize.ISO_B5.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B5,
            PrintAttributes.MediaSize.ISO_B6.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B6,
            PrintAttributes.MediaSize.ISO_B7.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B7,
            PrintAttributes.MediaSize.ISO_B8.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B8,
            PrintAttributes.MediaSize.ISO_B9.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B9,
            PrintAttributes.MediaSize.ISO_B10.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B10,
            PrintAttributes.MediaSize.ISO_C0.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C0,
            PrintAttributes.MediaSize.ISO_C1.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C1,
            PrintAttributes.MediaSize.ISO_C2.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C2,
            PrintAttributes.MediaSize.ISO_C3.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C3,
            PrintAttributes.MediaSize.ISO_C4.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C4,
            PrintAttributes.MediaSize.ISO_C5.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C5,
            PrintAttributes.MediaSize.ISO_C6.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C6,
            PrintAttributes.MediaSize.ISO_C7.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C7,
            PrintAttributes.MediaSize.ISO_C8.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C8,
            PrintAttributes.MediaSize.ISO_C9.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C9,
            PrintAttributes.MediaSize.ISO_C10.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C10,
            PrintAttributes.MediaSize.NA_LETTER.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_LETTER,
            PrintAttributes.MediaSize.NA_GOVT_LETTER.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_GOVT_LETTER,
            PrintAttributes.MediaSize.NA_LEGAL.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_LEGAL,
            PrintAttributes.MediaSize.NA_JUNIOR_LEGAL.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_JUNIOR_LEGAL,
            PrintAttributes.MediaSize.NA_LEDGER.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_LEDGER,
            PrintAttributes.MediaSize.NA_TABLOID.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_TABLOID,
            PrintAttributes.MediaSize.NA_INDEX_3X5.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_INDEX_3X5,
            PrintAttributes.MediaSize.NA_INDEX_4X6.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_INDEX_4X6,
            PrintAttributes.MediaSize.NA_INDEX_5X8.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_INDEX_5X8,
            PrintAttributes.MediaSize.NA_MONARCH.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_MONARCH,
            PrintAttributes.MediaSize.NA_QUARTO.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_QUARTO,
            PrintAttributes.MediaSize.NA_FOOLSCAP.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_FOOLSCAP,
            PrintAttributes.MediaSize.ANSI_C.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ANSI_C,
            PrintAttributes.MediaSize.ANSI_D.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ANSI_D,
            PrintAttributes.MediaSize.ANSI_E.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ANSI_E,
            PrintAttributes.MediaSize.ANSI_F.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ANSI_F,
            PrintAttributes.MediaSize.NA_ARCH_A.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_A,
            PrintAttributes.MediaSize.NA_ARCH_B.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_B,
            PrintAttributes.MediaSize.NA_ARCH_C.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_C,
            PrintAttributes.MediaSize.NA_ARCH_D.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_D,
            PrintAttributes.MediaSize.NA_ARCH_E.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_E,
            PrintAttributes.MediaSize.NA_ARCH_E1.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_E1,
            PrintAttributes.MediaSize.NA_SUPER_B.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_SUPER_B,
            PrintAttributes.MediaSize.ROC_8K.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ROC_8K,
            PrintAttributes.MediaSize.ROC_16K.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ROC_16K,
            PrintAttributes.MediaSize.PRC_1.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_1,
            PrintAttributes.MediaSize.PRC_2.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_2,
            PrintAttributes.MediaSize.PRC_3.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_3,
            PrintAttributes.MediaSize.PRC_4.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_4,
            PrintAttributes.MediaSize.PRC_5.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_5,
            PrintAttributes.MediaSize.PRC_6.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_6,
            PrintAttributes.MediaSize.PRC_7.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_7,
            PrintAttributes.MediaSize.PRC_8.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_8,
            PrintAttributes.MediaSize.PRC_9.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_9,
            PrintAttributes.MediaSize.PRC_10.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_10,
            PrintAttributes.MediaSize.PRC_16K.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_16K,
            PrintAttributes.MediaSize.OM_PA_KAI.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_OM_PA_KAI,
            PrintAttributes.MediaSize.OM_DAI_PA_KAI.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_OM_DAI_PA_KAI,
            PrintAttributes.MediaSize.OM_JUURO_KU_KAI.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_OM_JUURO_KU_KAI,
            PrintAttributes.MediaSize.JIS_B10.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B10,
            PrintAttributes.MediaSize.JIS_B9.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B9,
            PrintAttributes.MediaSize.JIS_B8.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B8,
            PrintAttributes.MediaSize.JIS_B7.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B7,
            PrintAttributes.MediaSize.JIS_B6.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B6,
            PrintAttributes.MediaSize.JIS_B5.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B5,
            PrintAttributes.MediaSize.JIS_B4.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B4,
            PrintAttributes.MediaSize.JIS_B3.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B3,
            PrintAttributes.MediaSize.JIS_B2.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B2,
            PrintAttributes.MediaSize.JIS_B1.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B1,
            PrintAttributes.MediaSize.JIS_B0.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B0,
            PrintAttributes.MediaSize.JIS_EXEC.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_EXEC,
            PrintAttributes.MediaSize.JPN_CHOU4.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_CHOU4,
            PrintAttributes.MediaSize.JPN_CHOU3.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_CHOU3,
            PrintAttributes.MediaSize.JPN_CHOU2.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_CHOU2,
            PrintAttributes.MediaSize.JPN_HAGAKI.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_HAGAKI,
            PrintAttributes.MediaSize.JPN_OUFUKU.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_OUFUKU,
            PrintAttributes.MediaSize.JPN_KAHU.getId() to
                BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_KAHU,
        )

    private val localMediaTypeMap =
        mapOf(
            // These keys are defined in jni/include/wprint_df_types.h
            0 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PLAIN,
            1 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_SPECIAL,
            2 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PHOTO,
            3 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_TRANSPARENCY,
            4 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_IRON_ON,
            5 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_IRON_ON_MIRROR,
            6 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_ADVANCED_PHOTO,
            7 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_FAST_TRANSPARENCY,
            8 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_BROCHURE_GLOSSY,
            9 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_BROCHURE_MATTE,
            10 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PHOTO_GLOSSY,
            11 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PHOTO_MATTE,
            12 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PREMIUM_PHOTO,
            13 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_OTHER_PHOTO,
            14 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PRINTABLE_CD,
            15 to
                BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PREMIUM_PRESENTATION,
            // New types above this line
            98 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_AUTO,
            99 to BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_UNKNOWN,
        )

    private val frameworkColorModeMap =
        // Keep this up to date to map any new color modes in the framework
        mapOf(
            PrintAttributes.COLOR_MODE_COLOR to
                BipsStatsLog.BIPS_PRINT_JOB__COLOR__FRAMEWORK_COLOR_MODE_COLOR,
            PrintAttributes.COLOR_MODE_MONOCHROME to
                BipsStatsLog.BIPS_PRINT_JOB__COLOR__FRAMEWORK_COLOR_MODE_MONOCRHOME,
        )

    private val duplexModeMap =
        // Keep this up to date to map any new duplex modes in the framework
        mapOf(
            PrintAttributes.DUPLEX_MODE_LONG_EDGE to
                BipsStatsLog.BIPS_PRINT_JOB__DUPLEX_MODE__FRAMEWORK_DUPLEX_MODE_LONG_EDGE,
            PrintAttributes.DUPLEX_MODE_SHORT_EDGE to
                BipsStatsLog.BIPS_PRINT_JOB__DUPLEX_MODE__FRAMEWORK_DUPLEX_MODE_SHORT_EDGE,
            PrintAttributes.DUPLEX_MODE_NONE to
                BipsStatsLog.BIPS_PRINT_JOB__DUPLEX_MODE__FRAMEWORK_DUPLEX_MODE_NONE,
        )

    private val localPrintJobResultMap =
        // These keys are defined in jni/include/wtypes.h
        mapOf(
            0 to BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_COMPLETED,
            -2 to BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_CANCELLED,
            -3 to BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_FAILED_CORRUPT,
            -4 to BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_FAILED_CERTIFICATE,
            -1 to BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_FAILED_UNKNOWN,
        )
}
