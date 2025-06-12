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
import android.print.PrintAttributes
import android.print.PrintDocumentInfo
import android.print.PrintJobInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Semaphore
import org.junit.After
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

    val mPrintJobInfo = mock<PrintJobInfo>()
    val mPrintDocumentInfo = mock<PrintDocumentInfo>()
    val mPrintAttributes = mock<PrintAttributes>()

    @Before
    fun setup() {
        reset(mStatsLogWrapper)
        reset(mHandler)
        reset(mSemaphore)

        reset(mPrintJobInfo)
        reset(mPrintDocumentInfo)
        reset(mPrintAttributes)

        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        StatsAsyncLogger.testSetStatsLogWrapper(mStatsLogWrapper)
        StatsAsyncLogger.testSetHandler(mHandler)

        // Mocks should succeed by default
        whenever(mHandler.postAtTime(any(), any())).thenReturn(true)
        whenever(mSemaphore.tryAcquire()).thenReturn(true)
        whenever(mPrintJobInfo.getAttributes()).thenReturn(mPrintAttributes)
    }

    @After
    fun teardown() {
        StatsAsyncLogger.stopLogging()
    }

    @Test
    fun printerDiscoverySuccessfullyLoggedTest() {
        val logWrapperInOrder = inOrder(mStatsLogWrapper)
        val handlerInOrder = inOrder(mHandler)
        val semaphoreInOrder = inOrder(mSemaphore)
        val timeCaptor = argumentCaptor<Long>()
        val runnableCaptor = argumentCaptor<Runnable>()

        StatsAsyncLogger.startLogging()
        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        StatsAsyncLogger.testSetHandler(mHandler)
        StatsAsyncLogger.testSetStatsLogWrapper(mStatsLogWrapper)

        // Arbitrary arguments
        assertThat(
                StatsAsyncLogger.PrinterDiscovery(
                    StatsAsyncLogger.DiscoverySchemePrinterDiscoveryEvent.MDNS,
                    false,
                )
            )
            .isTrue()
        assertThat(
                StatsAsyncLogger.PrinterDiscovery(
                    StatsAsyncLogger.DiscoverySchemePrinterDiscoveryEvent.P2P,
                    true,
                )
            )
            .isTrue()

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
            .internalPrinterDiscovery(
                StatsAsyncLogger.DiscoverySchemePrinterDiscoveryEvent.MDNS,
                false,
            )
        logWrapperInOrder
            .verify(mStatsLogWrapper)
            .internalPrinterDiscovery(
                StatsAsyncLogger.DiscoverySchemePrinterDiscoveryEvent.P2P,
                true,
            )
        logWrapperInOrder.verifyNoMoreInteractions()

        // Validate Semaphore logic
        semaphoreInOrder.verify(mSemaphore, times(2)).tryAcquire()
        semaphoreInOrder.verify(mSemaphore, times(2)).release()
    }

    @Test
    fun discoveredPrinterCapsSuccessfullyLoggedTest() {
        val logWrapperInOrder = inOrder(mStatsLogWrapper)
        val handlerInOrder = inOrder(mHandler)
        val semaphoreInOrder = inOrder(mSemaphore)
        val timeCaptor = argumentCaptor<Long>()
        val runnableCaptor = argumentCaptor<Runnable>()

        StatsAsyncLogger.startLogging()
        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        StatsAsyncLogger.testSetHandler(mHandler)
        StatsAsyncLogger.testSetStatsLogWrapper(mStatsLogWrapper)

        // "foo" printer: Generally arbitrary arguments focusing more on creating non-empty lists.
        val colorsMaskFoo =
            (PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME)
        val supportedMediaSizesFoo =
            listOf<PrintAttributes.MediaSize>(
                PrintAttributes.MediaSize.NA_LETTER,
                PrintAttributes.MediaSize.JPN_HAGAKI,
            )
        val duplexModeMaskFoo =
            (PrintAttributes.DUPLEX_MODE_LONG_EDGE or PrintAttributes.DUPLEX_MODE_SHORT_EDGE)
        val secureFoo = true
        val supportedMediaTypesFoo =
            listOf(
                    StatsAsyncLogger.InternalMediaTypeDiscoveredPrinterCapsEvent.PLAIN,
                    StatsAsyncLogger.InternalMediaTypeDiscoveredPrinterCapsEvent.UNKNOWN,
                )
                .map { it.mediaTypeId!! }
        assertThat(
                StatsAsyncLogger.DiscoveredPrinterCapabilities(
                    "foo",
                    colorsMaskFoo,
                    supportedMediaSizesFoo,
                    duplexModeMaskFoo,
                    secureFoo,
                    supportedMediaTypesFoo,
                )
            )
            .isTrue()
        // "bar" printer: Generally arbitrary arguments focusing more on creating empty or default
        // values.
        val colorsMaskBar = 0
        val supportedMediaSizesBar = emptyList<PrintAttributes.MediaSize>()
        val duplexModeMaskBar = 0
        val secureBar = false
        val supportedMediaTypesBar = emptyList<Int>()
        assertThat(
                StatsAsyncLogger.DiscoveredPrinterCapabilities(
                    "bar",
                    colorsMaskBar,
                    supportedMediaSizesBar,
                    duplexModeMaskBar,
                    secureBar,
                    supportedMediaTypesBar,
                )
            )
            .isTrue()

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
            .internalDiscoveredPrinterCapabilities(
                eq("foo"),
                eq(
                    setOf(
                        StatsAsyncLogger.InternalColorModeDiscoveredPrinterCapsEvent.COLOR,
                        StatsAsyncLogger.InternalColorModeDiscoveredPrinterCapsEvent.MONOCHROME,
                    )
                ),
                eq(
                    setOf(
                        StatsAsyncLogger.InternalMediaSizeDiscoveredPrinterCapsEvent.NA_LETTER,
                        StatsAsyncLogger.InternalMediaSizeDiscoveredPrinterCapsEvent.JPN_HAGAKI,
                    )
                ),
                eq(
                    setOf(
                        StatsAsyncLogger.InternalDuplexModeDiscoveredPrinterCapsEvent.LONG_EDGE,
                        StatsAsyncLogger.InternalDuplexModeDiscoveredPrinterCapsEvent.SHORT_EDGE,
                    )
                ),
                eq(secureFoo),
                eq(
                    setOf(
                        StatsAsyncLogger.InternalMediaTypeDiscoveredPrinterCapsEvent.PLAIN,
                        StatsAsyncLogger.InternalMediaTypeDiscoveredPrinterCapsEvent.UNKNOWN,
                    )
                ),
            )
        logWrapperInOrder
            .verify(mStatsLogWrapper)
            .internalDiscoveredPrinterCapabilities(
                eq("bar"),
                eq(setOf()),
                eq(setOf()),
                eq(setOf()),
                eq(secureBar),
                eq(setOf()),
            )

        // Validate Semaphore logic
        semaphoreInOrder.verify(mSemaphore, times(2)).tryAcquire()
        semaphoreInOrder.verify(mSemaphore, times(2)).release()
    }

    @Test
    fun printJobSuccessfullyLoggedTest() {
        val logWrapperInOrder = inOrder(mStatsLogWrapper)
        val handlerInOrder = inOrder(mHandler)
        val semaphoreInOrder = inOrder(mSemaphore)
        val timeCaptor = argumentCaptor<Long>()
        val runnableCaptor = argumentCaptor<Runnable>()

        StatsAsyncLogger.startLogging()
        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        StatsAsyncLogger.testSetHandler(mHandler)
        StatsAsyncLogger.testSetStatsLogWrapper(mStatsLogWrapper)

        // Arbitrary arguments
        assertThat(
                StatsAsyncLogger.PrintJob(
                    "foo",
                    true, // is secure
                    StatsAsyncLogger.OriginPrintJobEvent.DIRECT_PRINT,
                    0, // Job success
                    mPrintJobInfo,
                    mPrintDocumentInfo,
                    true, // borderless
                    PrintAttributes.DUPLEX_MODE_LONG_EDGE,
                    0, // MEDIA_PLAIN defined in wprint_df_types.h
                )
            )
            .isTrue()
        assertThat(
                StatsAsyncLogger.PrintJob(
                    "bar",
                    false,
                    StatsAsyncLogger.OriginPrintJobEvent.SHARED_IMAGE,
                    -1, // Job failed unknown
                    mPrintJobInfo,
                    mPrintDocumentInfo,
                    false,
                    PrintAttributes.DUPLEX_MODE_NONE,
                    100, // Should be not exist (unspecified)
                )
            )
            .isTrue()

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
            .internalPrintJob(
                eq("foo"),
                eq(StatsAsyncLogger.OriginPrintJobEvent.DIRECT_PRINT),
                eq(StatsAsyncLogger.InternalLocalPrintJobResultPrintJobEvent.COMPLETED),
                eq(true),
                // TODO(b/422187009): Figure out how to properly mock/shadow PrintAttributes
                any(),
                eq(StatsAsyncLogger.InternalFrameworkDuplexModePrintJobEvent.LONG_EDGE),
                eq(StatsAsyncLogger.InternalLocalMediaTypePrintJobEvent.PLAIN),
                // TODO(b/422187009): Figure out how to properly mock/shadow PrintAttributes
                any(),
                eq(true),
                // TODO(b/422187009): Figure out how to properly mock/shadow PrintAttributes
                any(),
                any(),
                any(),
            )
        logWrapperInOrder
            .verify(mStatsLogWrapper)
            .internalPrintJob(
                eq("bar"),
                eq(StatsAsyncLogger.OriginPrintJobEvent.SHARED_IMAGE),
                eq(StatsAsyncLogger.InternalLocalPrintJobResultPrintJobEvent.FAILED_UNKNOWN),
                eq(false),
                // TODO(b/422187009): Figure out how to properly mock/shadow PrintAttributes
                any(),
                eq(StatsAsyncLogger.InternalFrameworkDuplexModePrintJobEvent.NONE),
                eq(StatsAsyncLogger.InternalLocalMediaTypePrintJobEvent.UNSPECIFIED),
                // TODO(b/422187009): Figure out how to properly mock/shadow PrintAttributes
                any(),
                eq(false),
                // TODO(b/422187009): Figure out how to properly mock/shadow PrintAttributes
                any(),
                any(),
                any(),
            )

        logWrapperInOrder.verifyNoMoreInteractions()

        // Validate Semaphore logic
        semaphoreInOrder.verify(mSemaphore, times(2)).tryAcquire()
        semaphoreInOrder.verify(mSemaphore, times(2)).release()
    }

    @Test
    fun printerCapsSuccessfullyLoggedTest() {
        val logWrapperInOrder = inOrder(mStatsLogWrapper)
        val handlerInOrder = inOrder(mHandler)
        val semaphoreInOrder = inOrder(mSemaphore)
        val timeCaptor = argumentCaptor<Long>()
        val runnableCaptor = argumentCaptor<Runnable>()

        StatsAsyncLogger.startLogging()
        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        StatsAsyncLogger.testSetHandler(mHandler)
        StatsAsyncLogger.testSetStatsLogWrapper(mStatsLogWrapper)

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
            .internalRequestPrinterCapabilitiesStatus(
                StatsAsyncLogger.InternalLocalRequestCapabilitiesStatus.OK,
                false,
            )
        logWrapperInOrder
            .verify(mStatsLogWrapper)
            .internalRequestPrinterCapabilitiesStatus(
                StatsAsyncLogger.InternalLocalRequestCapabilitiesStatus.UNSPECIFIED,
                true,
            )
        logWrapperInOrder.verifyNoMoreInteractions()

        // Validate Semaphore logic
        semaphoreInOrder.verify(mSemaphore, times(2)).tryAcquire()
        semaphoreInOrder.verify(mSemaphore, times(2)).release()
    }

    @Test
    fun failureToAcquireSemaphoreTicketNeverSchedulesEvent() {
        StatsAsyncLogger.startLogging()
        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        StatsAsyncLogger.testSetHandler(mHandler)

        whenever(mSemaphore.tryAcquire()).thenReturn(false)
        // Arbitrary Arguments
        // These numbers are defined in jni/include/wprint_df_types.h
        assertThat(StatsAsyncLogger.RequestPrinterCapabilitiesStatus(0, false)).isFalse()
        assertThat(
                StatsAsyncLogger.PrintJob(
                    "foo",
                    true, // is secure
                    StatsAsyncLogger.OriginPrintJobEvent.DIRECT_PRINT,
                    0, // Job success
                    mPrintJobInfo,
                    mPrintDocumentInfo,
                    true, // borderless
                    PrintAttributes.DUPLEX_MODE_LONG_EDGE,
                    2,
                )
            )
            .isFalse()
        assertThat(
                StatsAsyncLogger.DiscoveredPrinterCapabilities(
                    "foo",
                    0,
                    emptyList(),
                    0,
                    true,
                    emptyList(),
                )
            )
            .isFalse()
        assertThat(
                StatsAsyncLogger.PrinterDiscovery(
                    StatsAsyncLogger.DiscoverySchemePrinterDiscoveryEvent.MDNS,
                    false,
                )
            )
            .isFalse()
        verifyNoInteractions(mHandler)
    }

    @Test
    fun failureToScheduleReleasesSemaphoreTicket() {
        StatsAsyncLogger.startLogging()
        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        StatsAsyncLogger.testSetHandler(mHandler)

        whenever(mHandler.postAtTime(any(), any())).thenReturn(false)
        // Arbitrary Arguments
        assertThat(StatsAsyncLogger.RequestPrinterCapabilitiesStatus(0, false)).isFalse()
        assertThat(
                StatsAsyncLogger.PrintJob(
                    "foo",
                    true, // is secure
                    StatsAsyncLogger.OriginPrintJobEvent.DIRECT_PRINT,
                    0, // Job success
                    mPrintJobInfo,
                    mPrintDocumentInfo,
                    true, // borderless
                    PrintAttributes.DUPLEX_MODE_LONG_EDGE,
                    0, // MEDIA_PLAIN defined in wprint_df_types.h
                )
            )
            .isFalse()
        assertThat(
                StatsAsyncLogger.DiscoveredPrinterCapabilities("foo", 0, setOf(), 0, true, setOf())
            )
            .isFalse()
        assertThat(
                StatsAsyncLogger.PrinterDiscovery(
                    StatsAsyncLogger.DiscoverySchemePrinterDiscoveryEvent.MDNS,
                    false,
                )
            )
            .isFalse()
        verify(mSemaphore, times(4)).release()
    }

    @Test
    fun stopLoggingSucceeds() {
        StatsAsyncLogger.startLogging()
        whenever(mSemaphore.tryAcquire(any(), any(), any())).thenReturn(true)
        assertThat(StatsAsyncLogger.stopLogging()).isTrue()
    }

    @Test
    fun stopLoggingFails() {
        StatsAsyncLogger.startLogging()
        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        StatsAsyncLogger.testSetHandler(mHandler)
        whenever(mSemaphore.tryAcquire(any(), any(), any())).thenReturn(false)
        assertThat(StatsAsyncLogger.stopLogging()).isFalse()
    }

    @Test
    fun stopLoggingSucceedsAwaitingEvents() {
        StatsAsyncLogger.startLogging()

        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        whenever(mSemaphore.tryAcquire(any(), any(), any())).thenReturn(true)
        assertThat(StatsAsyncLogger.stopLogging()).isTrue()
    }

    @Test
    fun stopLoggingFailsAwaitingEvents() {
        StatsAsyncLogger.startLogging()

        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        whenever(mSemaphore.tryAcquire(any(), any(), any())).thenReturn(false)
        assertThat(StatsAsyncLogger.stopLogging()).isFalse()
    }

    @Test
    fun stopLoggingFailsToLog() {
        StatsAsyncLogger.startLogging()
        StatsAsyncLogger.stopLogging()

        StatsAsyncLogger.testSetSemaphore(mSemaphore)
        StatsAsyncLogger.testSetHandler(mHandler)
        StatsAsyncLogger.testSetStatsLogWrapper(mStatsLogWrapper)

        // Arbitrary Arguments
        assertThat(StatsAsyncLogger.RequestPrinterCapabilitiesStatus(0, false)).isFalse()
        assertThat(
                StatsAsyncLogger.PrintJob(
                    "foo",
                    true, // is secure
                    StatsAsyncLogger.OriginPrintJobEvent.DIRECT_PRINT,
                    0, // Job success
                    mPrintJobInfo,
                    mPrintDocumentInfo,
                    true, // borderless
                    PrintAttributes.DUPLEX_MODE_LONG_EDGE,
                    0, // MEDIA_PLAIN defined in wprint_df_types.h
                )
            )
            .isFalse()
        assertThat(
                StatsAsyncLogger.DiscoveredPrinterCapabilities("foo", 0, setOf(), 0, true, setOf())
            )
            .isFalse()
        assertThat(
                StatsAsyncLogger.PrinterDiscovery(
                    StatsAsyncLogger.DiscoverySchemePrinterDiscoveryEvent.MDNS,
                    false,
                )
            )
            .isFalse()
        verifyNoInteractions(mHandler)
        verifyNoInteractions(mSemaphore)
        verifyNoInteractions(mStatsLogWrapper)
    }

    @Test
    fun successiveStartLogging() {
        assertThat(StatsAsyncLogger.startLogging()).isTrue()
        assertThat(StatsAsyncLogger.startLogging()).isFalse()
    }

    @Test
    fun successiveStopLogging() {
        assertThat(StatsAsyncLogger.startLogging()).isTrue()

        assertThat(StatsAsyncLogger.stopLogging()).isTrue()
        assertThat(StatsAsyncLogger.stopLogging()).isFalse()
    }
}
