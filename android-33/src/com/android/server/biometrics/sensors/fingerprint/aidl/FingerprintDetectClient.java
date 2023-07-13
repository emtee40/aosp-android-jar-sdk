/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricOverlayConstants;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.DetectionConsumer;
import com.android.server.biometrics.sensors.SensorOverlays;

import java.util.function.Supplier;

/**
 * Performs fingerprint detection without exposing any matching information (e.g. accept/reject
 * have the same haptic, lockout counter is not increased).
 */
class FingerprintDetectClient extends AcquisitionClient<AidlSession> implements DetectionConsumer {

    private static final String TAG = "FingerprintDetectClient";

    private final boolean mIsStrongBiometric;
    @NonNull private final SensorOverlays mSensorOverlays;
    @Nullable private ICancellationSignal mCancellationSignal;

    FingerprintDetectClient(@NonNull Context context, @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, long requestId,
            @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull String owner, int sensorId,
            @NonNull BiometricLogger biometricLogger, @NonNull BiometricContext biometricContext,
            @Nullable IUdfpsOverlayController udfpsOverlayController, boolean isStrongBiometric) {
        super(context, lazyDaemon, token, listener, userId, owner, 0 /* cookie */, sensorId,
                true /* shouldVibrate */, biometricLogger, biometricContext);
        setRequestId(requestId);
        mIsStrongBiometric = isStrongBiometric;
        mSensorOverlays = new SensorOverlays(udfpsOverlayController, null /* sideFpsController*/);
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void stopHalOperation() {
        mSensorOverlays.hide(getSensorId());

        try {
            mCancellationSignal.cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    protected void startHalOperation() {
        mSensorOverlays.show(getSensorId(), BiometricOverlayConstants.REASON_AUTH_KEYGUARD, this);

        try {
            mCancellationSignal = doDetectInteraction();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting finger detect", e);
            mSensorOverlays.hide(getSensorId());
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    private ICancellationSignal doDetectInteraction() throws RemoteException {
        final AidlSession session = getFreshDaemon();

        if (session.hasContextMethods()) {
            return session.getSession().detectInteractionWithContext(getOperationContext());
        } else {
            return session.getSession().detectInteraction();
        }
    }

    @Override
    public void onInteractionDetected() {
        vibrateSuccess();

        try {
            getListener().onDetected(getSensorId(), getTargetUserId(), mIsStrongBiometric);
            mCallback.onClientFinished(this, true /* success */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when sending onDetected", e);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_DETECT_INTERACTION;
    }

    @Override
    public boolean interruptsPrecedingClients() {
        return true;
    }
}