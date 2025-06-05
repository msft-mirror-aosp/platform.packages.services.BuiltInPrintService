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
        jobOrigin: OriginPrintJobEvent,
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
                                jobOrigin,
                                InternalLocalPrintJobResultPrintJobEvent.fromLocalResult(
                                    localJobRawResult
                                ),
                                borderless,
                                InternalFrameworkMediaSizePrintJobEvent.fromMediaSizeId(
                                    jobInfo.getAttributes().getMediaSize()?.getId()
                                ),
                                InternalFrameworkDuplexModePrintJobEvent.fromDuplexMode(duplexMode),
                                InternalLocalMediaTypePrintJobEvent.fromBipsMediaType(
                                    localMediaType
                                ),
                                InternalFrameworkColorModePrintJobEvent.fromColorMode(
                                    jobInfo.getAttributes().getColorMode()
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

    // Each Enum class has a suffix that corresponds to an event
    // proto, e.g. InternalFrameworkMediaSize"PrintJobEvent" to be used for
    // proto values for print jobs.
    // Most of these are internal to the package so are prefixed with
    // Internal

    // PrintJob event enums

    // Public so it can be used by client
    enum class OriginPrintJobEvent(val rawValue: Int) {
        SHARED_IMAGE(BipsStatsLog.BIPS_PRINT_JOB__JOB_ORIGIN__BIPS_JOB_ORIGIN_SHARED_IMAGE),
        SHARED_PDF(BipsStatsLog.BIPS_PRINT_JOB__JOB_ORIGIN__BIPS_JOB_ORIGIN_SHARED_PDF),
        DIRECT_PRINT(BipsStatsLog.BIPS_PRINT_JOB__JOB_ORIGIN__BIPS_JOB_ORIGIN_DIRECT_PRINT),
    }

    enum class InternalFrameworkMediaSizePrintJobEvent(
        val mediaSizeId: String?,
        val rawValue: Int,
    ) {
        // Keep this up to date to map any new color modes in the framework
        UNKNOWN_PORTRAIT(
            PrintAttributes.MediaSize.UNKNOWN_PORTRAIT.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_UNKNOWN_PORTRAIT,
        ),
        UNKNOWN_LANDSCAPE(
            PrintAttributes.MediaSize.UNKNOWN_LANDSCAPE.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_UNKNOWN_LANDSCAPE,
        ),
        ISO_A0(
            PrintAttributes.MediaSize.ISO_A0.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A0,
        ),
        ISO_A1(
            PrintAttributes.MediaSize.ISO_A1.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A1,
        ),
        ISO_A2(
            PrintAttributes.MediaSize.ISO_A2.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A2,
        ),
        ISO_A3(
            PrintAttributes.MediaSize.ISO_A3.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A3,
        ),
        ISO_A4(
            PrintAttributes.MediaSize.ISO_A4.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A4,
        ),
        ISO_A5(
            PrintAttributes.MediaSize.ISO_A5.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A5,
        ),
        ISO_A6(
            PrintAttributes.MediaSize.ISO_A6.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A6,
        ),
        ISO_A7(
            PrintAttributes.MediaSize.ISO_A7.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A7,
        ),
        ISO_A8(
            PrintAttributes.MediaSize.ISO_A8.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A8,
        ),
        ISO_A9(
            PrintAttributes.MediaSize.ISO_A9.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A9,
        ),
        ISO_A10(
            PrintAttributes.MediaSize.ISO_A10.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_A10,
        ),
        ISO_B0(
            PrintAttributes.MediaSize.ISO_B0.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B0,
        ),
        ISO_B1(
            PrintAttributes.MediaSize.ISO_B1.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B1,
        ),
        ISO_B2(
            PrintAttributes.MediaSize.ISO_B2.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B2,
        ),
        ISO_B3(
            PrintAttributes.MediaSize.ISO_B3.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B3,
        ),
        ISO_B4(
            PrintAttributes.MediaSize.ISO_B4.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B4,
        ),
        ISO_B5(
            PrintAttributes.MediaSize.ISO_B5.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B5,
        ),
        ISO_B6(
            PrintAttributes.MediaSize.ISO_B6.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B6,
        ),
        ISO_B7(
            PrintAttributes.MediaSize.ISO_B7.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B7,
        ),
        ISO_B8(
            PrintAttributes.MediaSize.ISO_B8.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B8,
        ),
        ISO_B9(
            PrintAttributes.MediaSize.ISO_B9.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B9,
        ),
        ISO_B10(
            PrintAttributes.MediaSize.ISO_B10.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_B10,
        ),
        ISO_C0(
            PrintAttributes.MediaSize.ISO_C0.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C0,
        ),
        ISO_C1(
            PrintAttributes.MediaSize.ISO_C1.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C1,
        ),
        ISO_C2(
            PrintAttributes.MediaSize.ISO_C2.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C2,
        ),
        ISO_C3(
            PrintAttributes.MediaSize.ISO_C3.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C3,
        ),
        ISO_C4(
            PrintAttributes.MediaSize.ISO_C4.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C4,
        ),
        ISO_C5(
            PrintAttributes.MediaSize.ISO_C5.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C5,
        ),
        ISO_C6(
            PrintAttributes.MediaSize.ISO_C6.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C6,
        ),
        ISO_C7(
            PrintAttributes.MediaSize.ISO_C7.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C7,
        ),
        ISO_C8(
            PrintAttributes.MediaSize.ISO_C8.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C8,
        ),
        ISO_C9(
            PrintAttributes.MediaSize.ISO_C9.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C9,
        ),
        ISO_C10(
            PrintAttributes.MediaSize.ISO_C10.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ISO_C10,
        ),
        NA_LETTER(
            PrintAttributes.MediaSize.NA_LETTER.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_LETTER,
        ),
        NA_GOVT_LETTER(
            PrintAttributes.MediaSize.NA_GOVT_LETTER.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_GOVT_LETTER,
        ),
        NA_LEGAL(
            PrintAttributes.MediaSize.NA_LEGAL.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_LEGAL,
        ),
        NA_JUNIOR_LEGAL(
            PrintAttributes.MediaSize.NA_JUNIOR_LEGAL.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_JUNIOR_LEGAL,
        ),
        NA_LEDGER(
            PrintAttributes.MediaSize.NA_LEDGER.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_LEDGER,
        ),
        NA_TABLOID(
            PrintAttributes.MediaSize.NA_TABLOID.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_TABLOID,
        ),
        NA_INDEX_3X5(
            PrintAttributes.MediaSize.NA_INDEX_3X5.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_INDEX_3X5,
        ),
        NA_INDEX_4X6(
            PrintAttributes.MediaSize.NA_INDEX_4X6.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_INDEX_4X6,
        ),
        NA_INDEX_5X8(
            PrintAttributes.MediaSize.NA_INDEX_5X8.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_INDEX_5X8,
        ),
        NA_MONARCH(
            PrintAttributes.MediaSize.NA_MONARCH.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_MONARCH,
        ),
        NA_QUARTO(
            PrintAttributes.MediaSize.NA_QUARTO.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_QUARTO,
        ),
        NA_FOOLSCAP(
            PrintAttributes.MediaSize.NA_FOOLSCAP.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_FOOLSCAP,
        ),
        ANSI_C(
            PrintAttributes.MediaSize.ANSI_C.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ANSI_C,
        ),
        ANSI_D(
            PrintAttributes.MediaSize.ANSI_D.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ANSI_D,
        ),
        ANSI_E(
            PrintAttributes.MediaSize.ANSI_E.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ANSI_E,
        ),
        ANSI_F(
            PrintAttributes.MediaSize.ANSI_F.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ANSI_F,
        ),
        NA_ARCH_A(
            PrintAttributes.MediaSize.NA_ARCH_A.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_A,
        ),
        NA_ARCH_B(
            PrintAttributes.MediaSize.NA_ARCH_B.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_B,
        ),
        NA_ARCH_C(
            PrintAttributes.MediaSize.NA_ARCH_C.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_C,
        ),
        NA_ARCH_D(
            PrintAttributes.MediaSize.NA_ARCH_D.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_D,
        ),
        NA_ARCH_E(
            PrintAttributes.MediaSize.NA_ARCH_E.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_E,
        ),
        NA_ARCH_E1(
            PrintAttributes.MediaSize.NA_ARCH_E1.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_ARCH_E1,
        ),
        NA_SUPER_B(
            PrintAttributes.MediaSize.NA_SUPER_B.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_NA_SUPER_B,
        ),
        ROC_8K(
            PrintAttributes.MediaSize.ROC_8K.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ROC_8K,
        ),
        ROC_16K(
            PrintAttributes.MediaSize.ROC_16K.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_ROC_16K,
        ),
        PRC_1(
            PrintAttributes.MediaSize.PRC_1.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_1,
        ),
        PRC_2(
            PrintAttributes.MediaSize.PRC_2.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_2,
        ),
        PRC_3(
            PrintAttributes.MediaSize.PRC_3.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_3,
        ),
        PRC_4(
            PrintAttributes.MediaSize.PRC_4.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_4,
        ),
        PRC_5(
            PrintAttributes.MediaSize.PRC_5.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_5,
        ),
        PRC_6(
            PrintAttributes.MediaSize.PRC_6.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_6,
        ),
        PRC_7(
            PrintAttributes.MediaSize.PRC_7.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_7,
        ),
        PRC_8(
            PrintAttributes.MediaSize.PRC_8.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_8,
        ),
        PRC_9(
            PrintAttributes.MediaSize.PRC_9.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_9,
        ),
        PRC_10(
            PrintAttributes.MediaSize.PRC_10.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_10,
        ),
        PRC_16K(
            PrintAttributes.MediaSize.PRC_16K.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_PRC_16K,
        ),
        OM_PA_KAI(
            PrintAttributes.MediaSize.OM_PA_KAI.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_OM_PA_KAI,
        ),
        OM_DAI_PA_KAI(
            PrintAttributes.MediaSize.OM_DAI_PA_KAI.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_OM_DAI_PA_KAI,
        ),
        OM_JUURO_KU_KAI(
            PrintAttributes.MediaSize.OM_JUURO_KU_KAI.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_OM_JUURO_KU_KAI,
        ),
        JIS_B10(
            PrintAttributes.MediaSize.JIS_B10.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B10,
        ),
        JIS_B9(
            PrintAttributes.MediaSize.JIS_B9.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B9,
        ),
        JIS_B8(
            PrintAttributes.MediaSize.JIS_B8.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B8,
        ),
        JIS_B7(
            PrintAttributes.MediaSize.JIS_B7.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B7,
        ),
        JIS_B6(
            PrintAttributes.MediaSize.JIS_B6.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B6,
        ),
        JIS_B5(
            PrintAttributes.MediaSize.JIS_B5.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B5,
        ),
        JIS_B4(
            PrintAttributes.MediaSize.JIS_B4.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B4,
        ),
        JIS_B3(
            PrintAttributes.MediaSize.JIS_B3.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B3,
        ),
        JIS_B2(
            PrintAttributes.MediaSize.JIS_B2.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B2,
        ),
        JIS_B1(
            PrintAttributes.MediaSize.JIS_B1.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B1,
        ),
        JIS_B0(
            PrintAttributes.MediaSize.JIS_B0.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_B0,
        ),
        JIS_EXEC(
            PrintAttributes.MediaSize.JIS_EXEC.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JIS_EXEC,
        ),
        JPN_CHOU4(
            PrintAttributes.MediaSize.JPN_CHOU4.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_CHOU4,
        ),
        JPN_CHOU3(
            PrintAttributes.MediaSize.JPN_CHOU3.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_CHOU3,
        ),
        JPN_CHOU2(
            PrintAttributes.MediaSize.JPN_CHOU2.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_CHOU2,
        ),
        JPN_HAGAKI(
            PrintAttributes.MediaSize.JPN_HAGAKI.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_HAGAKI,
        ),
        JPN_OUFUKU(
            PrintAttributes.MediaSize.JPN_OUFUKU.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_OUFUKU,
        ),
        JPN_KAHU(
            PrintAttributes.MediaSize.JPN_KAHU.getId(),
            BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_JPN_KAHU,
        ),
        UNSPECIFIED(null, BipsStatsLog.BIPS_PRINT_JOB__SIZE__FRAMEWORK_MEDIA_SIZE_UNSPECIFIED);

        companion object {
            private val map =
                entries.associateBy(InternalFrameworkMediaSizePrintJobEvent::mediaSizeId)

            fun fromMediaSizeId(mediaSizeId: String?): InternalFrameworkMediaSizePrintJobEvent {
                return when (mediaSizeId) {
                    null -> InternalFrameworkMediaSizePrintJobEvent.UNSPECIFIED
                    else -> {
                        map.getOrDefault(
                            mediaSizeId,
                            InternalFrameworkMediaSizePrintJobEvent.UNSPECIFIED,
                        )
                    }
                }
            }
        }
    }

    enum class InternalLocalMediaTypePrintJobEvent(val mediaTypeId: Int?, val rawValue: Int) {
        // These numbers are defined in jni/include/wprint_df_types.h
        PLAIN(0, BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PLAIN),
        SPECIAL(1, BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_SPECIAL),
        PHOTO(2, BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PHOTO),
        TRANSPARENCY(
            3,
            BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_TRANSPARENCY,
        ),
        IRON_ON(4, BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_IRON_ON),
        IRON_ON_MIRROR(
            5,
            BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_IRON_ON_MIRROR,
        ),
        ADVANCED_PHOTO(
            6,
            BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_ADVANCED_PHOTO,
        ),
        FAST_TRANSPARENCY(
            7,
            BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_FAST_TRANSPARENCY,
        ),
        BROCHURE_GLOSSY(
            8,
            BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_BROCHURE_GLOSSY,
        ),
        BROCHURE_MATTE(
            9,
            BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_BROCHURE_MATTE,
        ),
        PHOTO_GLOSSY(
            10,
            BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PHOTO_GLOSSY,
        ),
        PHOTO_MATTE(11, BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PHOTO_MATTE),
        PREMIUM_PHOTO(
            12,
            BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PREMIUM_PHOTO,
        ),
        OTHER_PHOTO(13, BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_OTHER_PHOTO),
        PRINTABLE_CD(
            14,
            BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PRINTABLE_CD,
        ),
        PREMIUM_PRESENTATION(
            15,
            BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_PREMIUM_PRESENTATION,
        ),
        // New types above this line
        AUTO(98, BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_AUTO),
        UNKNOWN(99, BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_MEDIA_UNKNOWN),
        UNSPECIFIED(null, BipsStatsLog.BIPS_PRINT_JOB__MEDIA_TYPE__BIPS_MEDIA_TYPE_UNSPECIFIED);

        companion object {
            private val map = entries.associateBy(InternalLocalMediaTypePrintJobEvent::mediaTypeId)

            fun fromBipsMediaType(mediaTypeId: Int): InternalLocalMediaTypePrintJobEvent {
                return map.getOrDefault(
                    mediaTypeId,
                    InternalLocalMediaTypePrintJobEvent.UNSPECIFIED,
                )
            }
        }
    }

    enum class InternalFrameworkColorModePrintJobEvent(val colorMode: Int?, val rawValue: Int) {
        // Keep this up to date to map any new color modes in the framework
        COLOR(
            PrintAttributes.COLOR_MODE_COLOR,
            BipsStatsLog.BIPS_PRINT_JOB__COLOR__FRAMEWORK_COLOR_MODE_COLOR,
        ),
        MONOCHROME(
            PrintAttributes.COLOR_MODE_MONOCHROME,
            BipsStatsLog.BIPS_PRINT_JOB__COLOR__FRAMEWORK_COLOR_MODE_MONOCRHOME,
        ),
        UNSPECIFIED(null, BipsStatsLog.BIPS_PRINT_JOB__COLOR__FRAMEWORK_COLOR_MODE_UNSPECIFIED);

        companion object {
            private val map =
                entries.associateBy(InternalFrameworkColorModePrintJobEvent::colorMode)

            fun fromColorMode(colorMode: Int): InternalFrameworkColorModePrintJobEvent {
                return map.getOrDefault(
                    colorMode,
                    InternalFrameworkColorModePrintJobEvent.UNSPECIFIED,
                )
            }
        }
    }

    enum class InternalFrameworkDuplexModePrintJobEvent(val duplexMode: Int?, val rawValue: Int) {
        // Keep this up to date to map any new duplex modes in the framework
        LONG_EDGE(
            PrintAttributes.DUPLEX_MODE_LONG_EDGE,
            BipsStatsLog.BIPS_PRINT_JOB__DUPLEX_MODE__FRAMEWORK_DUPLEX_MODE_LONG_EDGE,
        ),
        SHORT_EDGE(
            PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
            BipsStatsLog.BIPS_PRINT_JOB__DUPLEX_MODE__FRAMEWORK_DUPLEX_MODE_SHORT_EDGE,
        ),
        NONE(
            PrintAttributes.DUPLEX_MODE_NONE,
            BipsStatsLog.BIPS_PRINT_JOB__DUPLEX_MODE__FRAMEWORK_DUPLEX_MODE_NONE,
        ),
        UNSPECIFIED(
            null,
            BipsStatsLog.BIPS_PRINT_JOB__DUPLEX_MODE__FRAMEWORK_DUPLEX_MODE_UNSPECIFIED,
        );

        companion object {
            private val map =
                entries.associateBy(InternalFrameworkDuplexModePrintJobEvent::duplexMode)

            fun fromDuplexMode(duplexMode: Int): InternalFrameworkDuplexModePrintJobEvent {
                return map.getOrDefault(
                    duplexMode,
                    InternalFrameworkDuplexModePrintJobEvent.UNSPECIFIED,
                )
            }
        }
    }

    enum class InternalLocalPrintJobResultPrintJobEvent(val localResult: Int?, val rawValue: Int) {
        // These keys are defined in jni/include/wtypes.h
        COMPLETED(0, BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_COMPLETED),
        CANCELLED(-2, BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_CANCELLED),
        FAILED_CORRUPT(
            -3,
            BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_FAILED_CORRUPT,
        ),
        FAILED_CERTIFICATE(
            -4,
            BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_FAILED_CERTIFICATE,
        ),
        FAILED_UNKNOWN(
            -1,
            BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_FAILED_UNKNOWN,
        ),
        UNSPECIFIED(null, BipsStatsLog.BIPS_PRINT_JOB__RESULT__BIPS_PRINT_JOB_RESULT_UNSPECIFIED);

        companion object {
            private val map =
                entries.associateBy(InternalLocalPrintJobResultPrintJobEvent::localResult)

            fun fromLocalResult(localResult: Int): InternalLocalPrintJobResultPrintJobEvent {
                return map.getOrDefault(
                    localResult,
                    InternalLocalPrintJobResultPrintJobEvent.UNSPECIFIED,
                )
            }
        }
    }
}
