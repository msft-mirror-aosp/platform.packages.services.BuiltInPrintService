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

// Thin wrapper around the generated atom logger for dependency
// injection and testability

// Kotlin does not offer package-private visibility modifier.
// Clients outside of the package should use StatsAsyncLogger instead.

// Not intended to be subclassed, left "open" for mocking.  internal
// modifier isn't used as it doesn't play nice with @VisibleForTesting
// annotation within package clients.
open class StatsLogWrapper {
    open fun internalRequestPrinterCapabilitiesStatus(
        getLocalCapsStatus: StatsAsyncLogger.InternalLocalRequestCapabilitiesStatus,
        secure: Boolean,
    ) {
        BipsStatsLog.write(
            BipsStatsLog.BIPS_REQUEST_PRINTER_CAPABILITIES_STATUS,
            getLocalCapsStatus.rawValue,
            secure,
        )
    }

    open fun internalPrintJob(
        makeAndModel: String,
        jobOrigin: StatsAsyncLogger.OriginPrintJobEvent,
        result: StatsAsyncLogger.InternalLocalPrintJobResultPrintJobEvent,
        borderless: Boolean,
        size: StatsAsyncLogger.InternalFrameworkMediaSizePrintJobEvent,
        duplexMode: StatsAsyncLogger.InternalFrameworkDuplexModePrintJobEvent,
        mediaType: StatsAsyncLogger.InternalLocalMediaTypePrintJobEvent,
        color: StatsAsyncLogger.InternalFrameworkColorModePrintJobEvent,
        secure: Boolean,
        horizontalDpi: Int,
        verticalDpi: Int,
        pageCount: Int,
    ) {
        BipsStatsLog.write(
            BipsStatsLog.BIPS_PRINT_JOB,
            makeAndModel,
            jobOrigin.rawValue,
            result.rawValue,
            borderless,
            size.rawValue,
            duplexMode.rawValue,
            mediaType.rawValue,
            color.rawValue,
            secure,
            horizontalDpi,
            verticalDpi,
            pageCount,
        )
    }
}
