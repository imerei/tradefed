/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.tradefed.device;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.Log;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Helper class for monitoring the state of a {@link IDevice}.
 */
class DeviceStateMonitor implements IDeviceStateMonitor {

    private static final String LOG_TAG = "DeviceStateMonitor";
    private IDevice mDevice;
    private TestDeviceState mDeviceState;

    /** the time in ms to wait between 'poll for responsiveness' attempts */
    private static final long CHECK_POLL_TIME = 5 * 1000;
    /** the maximum operation time in ms for a 'poll for responsiveness' command */
    private static final int MAX_OP_TIME = 30 * 1000;

    /** The  time in ms to wait for a device to be online. */
    // TODO: make this configurable
    private static final long DEFAULT_ONLINE_TIMEOUT = 1 * 60 * 1000;

    /** The  time in ms to wait for a device to available. */
    // TODO: make this configurable
    private static final long DEFAULT_AVAILABLE_TIMEOUT = 6 * 60 * 1000;

    private List<DeviceStateListener> mStateListeners;
    private IDeviceManager mMgr;

    DeviceStateMonitor(IDeviceManager mgr, IDevice device) {
        mMgr = mgr;
        mDevice = device;
        mStateListeners = new ArrayList<DeviceStateListener>();
        mDeviceState = TestDeviceState.getStateByDdms(device.getState());
    }

    /**
     * Get the {@link RunUtil} instance to use.
     * <p/>
     * Exposed for unit testing.
     */
    IRunUtil getRunUtil() {
        return RunUtil.getInstance();
    }

    /**
     * {@inheritDoc}
     */
    public IDevice waitForDeviceOnline(long waitTime) {
        if (waitForDeviceState(TestDeviceState.ONLINE, waitTime)) {
            return getIDevice();
        }
        return null;
    }

    /**
     * @return
     */
    private IDevice getIDevice() {
        return mDevice;
    }

    /**
     * {@inheritDoc}
     */
    public IDevice waitForDeviceOnline() {
        return waitForDeviceOnline(DEFAULT_ONLINE_TIMEOUT);
    }

    /**
     * {@inheritDoc}
     */
    public boolean waitForDeviceNotAvailable(long waitTime) {
        mMgr.addFastbootListener(this);
        boolean result = waitForDeviceState(TestDeviceState.NOT_AVAILABLE, waitTime);
        mMgr.removeFastbootListener(this);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    public IDevice waitForDeviceAvailable(final long waitTime) {
        // A device is currently considered "available" if and only if three events are true:
        // 1. Device is online aka visible via DDMS/adb
        // 2. Device's package manager is responsive
        // 3. Device's external storage is mounted
        //
        // The current implementation waits for each event to occur in sequence.
        //
        // it will track the currently elapsed time and fail if it is
        // greater than waitTime

        long startTime = System.currentTimeMillis();
        IDevice device = waitForDeviceOnline(waitTime);
        if (device == null) {
            return null;
        }
        long elapsedTime = System.currentTimeMillis() - startTime;
        if (!waitForPmResponsive(waitTime - elapsedTime)) {
            return null;
        }
        elapsedTime = System.currentTimeMillis() - startTime;
        if (!waitForStoreMount(waitTime - elapsedTime)) {
            return null;
        }
        return device;
    }

    /**
     * {@inheritDoc}
     */
    public IDevice waitForDeviceAvailable() {
        return waitForDeviceAvailable(DEFAULT_AVAILABLE_TIMEOUT);
    }

    /**
     * Waits for the device package manager to be responsive.
     *
     * @param waitTime time in ms to wait before giving up
     * @return <code>true</code> if package manage becomes responsive before waitTime expires.
     * <code>false</code> otherwise
     */
    private boolean waitForPmResponsive(final long waitTime) {
        Log.i(LOG_TAG, String.format("Waiting %d ms for device %s package manager",
                waitTime, getSerialNumber()));
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < waitTime) {
            final CollectingOutputReceiver receiver = new CollectingOutputReceiver();
            final String cmd = "pm path android";
            try {
                getIDevice().executeShellCommand(cmd, receiver, MAX_OP_TIME);
                String output = receiver.getOutput();
                Log.v(LOG_TAG, String.format("%s returned %s", cmd, output));
                if (output.contains("package:")) {
                    return true;
                }
            } catch (IOException e) {
                Log.i(LOG_TAG, String.format("%s failed: %s", cmd, e.getMessage()));
            }
            getRunUtil().sleep(CHECK_POLL_TIME);
        }
        Log.w(LOG_TAG, String.format("Device %s package manager is unresponsive",
                getSerialNumber()));
        return false;
    }

    /**
     * Waits for the device's external store to be mounted.
     *
     * @param waitTime time in ms to wait before giving up
     * @return <code>true</code> if external store is mounted before waitTime expires.
     * <code>false</code> otherwise
     */
    private boolean waitForStoreMount(final long waitTime) {
        Log.i(LOG_TAG, String.format("Waiting %d ms for device %s external store", waitTime,
                getSerialNumber()));
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < waitTime) {
            final CollectingOutputReceiver receiver = new CollectingOutputReceiver();
            final String cmd = "cat /proc/mounts";
            String externalStore = getMountPoint(IDevice.MNT_EXTERNAL_STORAGE);
            if (externalStore != null) {
                try {
                    getIDevice().executeShellCommand(cmd, receiver, MAX_OP_TIME);
                    String output = receiver.getOutput();
                    Log.v(LOG_TAG, String.format("%s returned %s", cmd, output));
                    if (output.contains(externalStore)) {
                        return true;
                    }
                } catch (IOException e) {
                    Log.i(LOG_TAG, String.format("%s failed: %s", cmd, e.getMessage()));
                }

            } else {
                Log.w(LOG_TAG, String.format("Failed to get external store mount point for %s",
                        getSerialNumber()));
            }
            getRunUtil().sleep(CHECK_POLL_TIME);
        }
        Log.w(LOG_TAG, String.format("Device %s external storage is not mounted after %d ms",
                getSerialNumber(), waitTime));
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public String getMountPoint(String mountName) {
        String mountPoint = getIDevice().getMountPoint(mountName);
        if (mountPoint != null) {
            return mountPoint;
        }
        // cached mount point is null - try querying directly
        CollectingOutputReceiver receiver = new CollectingOutputReceiver();
        try {
            getIDevice().executeShellCommand("echo $" + mountName, receiver);
            return receiver.getOutput().trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    public TestDeviceState getDeviceState() {
        return mDeviceState;
    }

    /**
     * {@inheritDoc}
     */
    public boolean waitForDeviceBootloader(long time) {
        mMgr.addFastbootListener(this);
        boolean result =  waitForDeviceState(TestDeviceState.FASTBOOT, time);
        mMgr.removeFastbootListener(this);
        return result;
    }

    private boolean waitForDeviceState(TestDeviceState state, long time) {
        String deviceSerial = getSerialNumber();
        if (getDeviceState() == state) {
            Log.i(LOG_TAG, String.format("Device %s is already %s", deviceSerial, state));
            return true;
        }
        Log.i(LOG_TAG, String.format("Waiting for device %s to be %s; it is currently %s...",
                deviceSerial, state, getDeviceState()));
        DeviceStateListener listener = new DeviceStateListener(state);
        addDeviceStateListener(listener);
        synchronized (listener) {
            try {
                listener.wait(time);
            } catch (InterruptedException e) {
                Log.w(LOG_TAG, "wait for device state interrupted");
            }
        }
        removeDeviceStateListener(listener);
        return getDeviceState().equals(state);
    }

    /**
     * @param listener
     */
    private void removeDeviceStateListener(DeviceStateListener listener) {
        synchronized (mStateListeners) {
            mStateListeners.remove(listener);
        }
    }

    /**
     * @param listener
     */
    private void addDeviceStateListener(DeviceStateListener listener) {
        synchronized (mStateListeners) {
            mStateListeners.add(listener);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setState(TestDeviceState deviceState) {
        mDeviceState = deviceState;
        // create a copy of listeners to prevent holding mStateListeners lock when notifying
        // and to protect from list modification when iterating
        Collection<DeviceStateListener> listenerCopy = new ArrayList<DeviceStateListener>(
                mStateListeners.size());
        synchronized (mStateListeners) {
            listenerCopy.addAll(mStateListeners);
        }
        for (DeviceStateListener listener: listenerCopy) {
            listener.stateChanged(deviceState);
        }
    }

    private static class DeviceStateListener {
        private final TestDeviceState mExpectedState;

        public DeviceStateListener(TestDeviceState expectedState) {
            mExpectedState = expectedState;
        }

        public void stateChanged(TestDeviceState newState) {
            if (mExpectedState.equals(newState)) {
                synchronized (this) {
                    notify();
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public String getSerialNumber() {
        return getIDevice().getSerialNumber();
    }
}
