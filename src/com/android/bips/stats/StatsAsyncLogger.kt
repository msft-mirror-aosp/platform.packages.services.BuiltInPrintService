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
}
