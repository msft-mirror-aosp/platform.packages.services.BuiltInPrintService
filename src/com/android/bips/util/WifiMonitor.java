/*
 * Copyright (C) 2016 The Android Open Source Project
 * Copyright (C) 2016 Mopria Alliance, Inc.
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

package com.android.bips.util;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.android.bips.BuiltInPrintService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Reliably reports on changes to Wi-Fi connectivity state */
public class WifiMonitor {
    private static final String TAG = WifiMonitor.class.getSimpleName();
    private static final boolean DEBUG = false;

    // Track multiple listeners in one instance.
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private boolean mCallbackRegistered = false;

    /** Current connectivity state or null if not known yet */
    private Boolean mConnected;

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static WifiMonitor sInstance;

    private final ConnectivityManager mConnectivityManager;
    private Set<Network> mActiveNetworks = new HashSet<Network>();

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    if (DEBUG) Log.d(TAG, "Network available: " + network);
                    mActiveNetworks.add(network);
                    notifyListeners();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    if (DEBUG) Log.d(TAG, "Network lost: " + network);
                    mActiveNetworks.remove(network);
                    notifyListeners();
                }
            };

    /**
     * Get the singleton instance of WifiMonitor, creating it if necessary.
     *
     * @param service The BuiltInPrintService instance to use for context.
     * @param listener The listener to notify of connection state changes.
     * @return The singleton instance of WifiMonitor.
     */
    public static WifiMonitor getInstance(BuiltInPrintService service, @NonNull Listener listener) {
        if (DEBUG) Log.d(TAG, "getInstance()");
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new WifiMonitor(service, listener);
            } else {
                // If the instance already exists, notify the listener immediately on the last
                // connection status
                boolean connected = !sInstance.mActiveNetworks.isEmpty();
                listener.onConnectionStateChanged(connected);
                if (!sInstance.mListeners.contains(listener)) {
                    sInstance.mListeners.add(listener);
                }
            }
            return sInstance;
        }
    }

    /**
     * Begin listening for connectivity changes, supplying the connectivity state to the listener
     * until stopped.
     */
    private WifiMonitor(BuiltInPrintService service, Listener listener) {
        if (DEBUG) Log.d(TAG, "WifiMonitor()");
        mConnectivityManager = service.getSystemService(ConnectivityManager.class);
        if (mConnectivityManager == null) {
            return;
        }

        mListeners.add(listener);
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        builder.addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET);
        mConnectivityManager.registerNetworkCallback(builder.build(), mNetworkCallback);
        mCallbackRegistered = true;
    }

    private void notifyListeners() {
        boolean connected = !mActiveNetworks.isEmpty();
        for (Listener listener : mListeners) {
            if (listener != null) {
                listener.onConnectionStateChanged(connected);
            }
        }
    }

    /** Cease monitoring Wi-Fi connectivity status */
    public void close(@NonNull Listener listener) {
        if (DEBUG) Log.d(TAG, "close()");
        mListeners.remove(listener);
        if (mListeners.isEmpty() && mConnectivityManager != null && mCallbackRegistered) {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mCallbackRegistered = false;
            synchronized (sLock) {
                sInstance = null;
            }
        }
    }

    /** Communicate changes to the Wi-Fi connection state */
    public interface Listener {
        /** Called when the Wi-Fi connection state changes */
        void onConnectionStateChanged(boolean isConnected);
    }
}
