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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Semaphore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*

// These are clear-box tests that primarily validate basic and concurrent interactions.

@RunWith(AndroidJUnit4::class)
open class StatsAsyncLoggerTest {
    val mStatsLogWrapper = mock<StatsLogWrapper>()
    val mHandler = mock<Handler>()
    val mSemaphore = mock<Semaphore>()

    @Before
    fun setup() {
        reset(mStatsLogWrapper)
        reset(mHandler)
        reset(mSemaphore)

        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        StatsAsyncLogger.testSetStatsLogWrapper(mStatsLogWrapper)
        StatsAsyncLogger.testSetHandler(mHandler)

        // Mocks should succeed by default
        whenever(mHandler.postAtTime(any(), any())).thenReturn(true)
        whenever(mSemaphore.tryAcquire()).thenReturn(true)
    }

    @Test
    fun printerCapsSuccessfullyLoggedTest() {
        val logWrapperInOrder = inOrder(mStatsLogWrapper)
        val handlerInOrder = inOrder(mHandler)
        val semaphoreInOrder = inOrder(mSemaphore)
        val timeCaptor = argumentCaptor<Long>()
        val runnableCaptor = argumentCaptor<Runnable>()

        // Arbitrary arguments
        StatsAsyncLogger.RequestPrinterCapabilitiesStatus(0, false)
        StatsAsyncLogger.RequestPrinterCapabilitiesStatus(42, true)

        handlerInOrder
            .verify(mHandler, times(2))
            .postAtTime(runnableCaptor.capture(), timeCaptor.capture())
        handlerInOrder.verifyNoMoreInteractions()

        // Validate delay args
        val firstTime = timeCaptor.firstValue
        val secondTime = timeCaptor.secondValue
        assertThat(secondTime - firstTime)
            .isAtLeast(StatsAsyncLogger.EVENT_REPORTED_MIN_INTERVAL.inWholeMilliseconds)
        assertThat(secondTime - firstTime)
            .isAtMost(2 * StatsAsyncLogger.EVENT_REPORTED_MIN_INTERVAL.inWholeMilliseconds)

        // Validate Runnable logic
        runnableCaptor.firstValue.run()
        runnableCaptor.secondValue.run()
        logWrapperInOrder
            .verify(mStatsLogWrapper)
            .internalRequestPrinterCapabilitiesStatus(0, false)
        logWrapperInOrder
            .verify(mStatsLogWrapper)
            .internalRequestPrinterCapabilitiesStatus(42, true)
        logWrapperInOrder.verifyNoMoreInteractions()

        // Validate Semaphore logic
        semaphoreInOrder.verify(mSemaphore, times(2)).tryAcquire()
        semaphoreInOrder.verify(mSemaphore, times(2)).release()
    }

    @Test
    fun failureToAcquireSemaphoreTicketNeverSchedulesEvent() {
        whenever(mSemaphore.tryAcquire()).thenReturn(false)
        assertThat(StatsAsyncLogger.RequestPrinterCapabilitiesStatus(0, false)).isFalse()
        verifyNoInteractions(mHandler)
    }

    @Test
    fun failureToScheduleReleasesSemaphoreTicket() {
        whenever(mHandler.postAtTime(any(), any())).thenReturn(false)
        assertThat(StatsAsyncLogger.RequestPrinterCapabilitiesStatus(0, false)).isFalse()
        verify(mSemaphore, times(1)).release()
    }

    @Test
    fun tryAwaitingAllEventsSucceeds() {
        whenever(mSemaphore.tryAcquire(any(), any(), any())).thenReturn(true)
        assertThat(StatsAsyncLogger.tryAwaitingAllEvents()).isTrue()
    }

    @Test
    fun tryAwaitingAllEventsFails() {
        whenever(mSemaphore.tryAcquire(any(), any(), any())).thenReturn(false)
        assertThat(StatsAsyncLogger.tryAwaitingAllEvents()).isFalse()
    }
}
