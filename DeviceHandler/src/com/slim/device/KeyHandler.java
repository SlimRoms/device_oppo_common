/*
 * Copyright (C) 2014 Slimroms
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
package com.slim.device;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import com.slim.device.settings.ScreenOffGesture;

import com.android.internal.os.DeviceKeyHandler;
import com.android.internal.util.ArrayUtils;

import slim.action.Action;
import slim.action.ActionConstants;


public class KeyHandler implements DeviceKeyHandler {

    private static final String TAG = KeyHandler.class.getSimpleName();
    private static final int GESTURE_REQUEST = 1;

    // Supported scancodes
    private static final int MODE_TOTAL_SILENCE = 600;
    private static final int MODE_ALARMS_ONLY = 601;
    private static final int MODE_PRIORITY_ONLY = 602;
    private static final int MODE_NONE = 603;
    private static final int MODE_VIBRATE = 604;
    private static final int MODE_RING = 605;

    private static final int[] sSupportedGestures = new int[]{
        MODE_TOTAL_SILENCE,
        MODE_ALARMS_ONLY,
        MODE_PRIORITY_ONLY,
        MODE_NONE,
        MODE_VIBRATE,
        MODE_RING
    };

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final PowerManager mPowerManager;
    private final NotificationManager mNotificationManager;
    private Context mGestureContext = null;
    private EventHandler mEventHandler;
    private SensorManager mSensorManager;
    private Sensor mProximitySensor;
    private Vibrator mVibrator;
    WakeLock mProximityWakeLock;

    public KeyHandler(Context context) {
        mContext = context;
        mEventHandler = new EventHandler();
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mNotificationManager
                = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        mProximityWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "ProximityWakeLock");

        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            mVibrator = null;
        }

        try {
            mGestureContext = mContext.createPackageContext(
                    "com.slim.device", Context.CONTEXT_IGNORE_SECURITY);
        } catch (NameNotFoundException e) {
        }
    }

    private class EventHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            KeyEvent event = (KeyEvent) msg.obj;
            String action = null;
            switch(event.getScanCode()) {
            case MODE_TOTAL_SILENCE:
                setZenMode(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS);
                break;
            case MODE_ALARMS_ONLY:
                setZenMode(Settings.Global.ZEN_MODE_ALARMS);
                break;
            case MODE_PRIORITY_ONLY:
                setZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
                break;
            case MODE_NONE:
                setZenMode(Settings.Global.ZEN_MODE_OFF);
                break;
            case MODE_VIBRATE:
                setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
                break;
            case MODE_RING:
                setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
                break;
            default:
                action = getScreenOffGesturePref(event.getScanCode());
                break;
            }

            Log.d("TEST", "scanCode=" + event.getScanCode() + "action=" + action);

            if (action == null || action != null && action.equals(ActionConstants.ACTION_NULL)) {
                return;
            }
            if (action.equals(ActionConstants.ACTION_CAMERA)
                    || !action.startsWith("**")) {
                Action.processAction(mContext, ActionConstants.ACTION_WAKE_DEVICE, false);
            }
            doHapticFeedback();
            Action.processAction(mContext, action, false);
        }
    }

    private String getScreenOffGesturePref(int scanCode) {
        String action = getGestureSharedPreferences().getString(
                ScreenOffGesture.buildPreferenceKey(scanCode), null);
        if (TextUtils.isEmpty(action)) {
            int[] gestureCodes =
                    mGestureContext.getResources().getIntArray(R.array.gesture_scancodes);
            String[] gestureDefaults =
                    mGestureContext.getResources().getStringArray(R.array.gesture_defaults);
            for (int i = 0; i < gestureCodes.length; i++) {
                if (gestureCodes[i] == scanCode) {
                    return gestureDefaults[i];
                }
            }
        }
        return action;
    }

    private void setZenMode(int mode) {
        mNotificationManager.setZenMode(mode, null, TAG);
        if (mVibrator != null) {
            mVibrator.vibrate(50);
        }
    }

    private void setRingerModeInternal(int mode) {
        mAudioManager.setRingerModeInternal(mode);
        if (mVibrator != null) {
            mVibrator.vibrate(50);
        }
    }

    private void doHapticFeedback() {
        if (mVibrator == null) {
            return;
        }
            mVibrator.vibrate(50);
    }

    private SharedPreferences getGestureSharedPreferences() {
        return mGestureContext.getSharedPreferences(
                ScreenOffGesture.GESTURE_SETTINGS,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    public boolean handleKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return false;
        }
        int scanCode = event.getScanCode();
        boolean isKeySupported = isKeySupported(event.getScanCode());
        if (isKeySupported && !mEventHandler.hasMessages(GESTURE_REQUEST)) {
            Message msg = getMessageForKeyEvent(event);
            if (scanCode < MODE_TOTAL_SILENCE && mProximitySensor != null) {
                mEventHandler.sendMessageDelayed(msg, 200);
                processEvent(event);
            } else {
                mEventHandler.sendMessage(msg);
            }
        }
        return isKeySupported;
    }

    private boolean isKeySupported(int scanCode) {
        if (ArrayUtils.contains(sSupportedGestures, scanCode))
            return true;

        int[] gestureCodes = mGestureContext.getResources().getIntArray(R.array.gesture_scancodes);
        return ArrayUtils.contains(gestureCodes, scanCode);
    }

    private Message getMessageForKeyEvent(KeyEvent keyEvent) {
        Message msg = mEventHandler.obtainMessage(GESTURE_REQUEST);
        msg.obj = keyEvent;
        return msg;
    }

    private void processEvent(final KeyEvent keyEvent) {
        mProximityWakeLock.acquire();
        mSensorManager.registerListener(new SensorEventListener() {

            @Override
            public void onSensorChanged(SensorEvent event) {
                mProximityWakeLock.release();
                mSensorManager.unregisterListener(this);
                if (!mEventHandler.hasMessages(GESTURE_REQUEST)) {
                    // The sensor took to long, ignoring.
                    return;
                }
                mEventHandler.removeMessages(GESTURE_REQUEST);
                if (event.values[0] == mProximitySensor.getMaximumRange()) {
                    Message msg = getMessageForKeyEvent(keyEvent);
                    mEventHandler.sendMessage(msg);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        }, mProximitySensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

}
