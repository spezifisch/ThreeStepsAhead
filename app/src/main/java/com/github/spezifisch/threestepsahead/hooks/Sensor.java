/**
 * This file is part of ThreeStepsAhead.
 *
 * ThreeStepsAhead is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ThreeStepsAhead is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ThreeStepsAhead.  If not, see <http://www.gnu.org/licenses/>.
 *
 **
 * This file is based on XposedMod.java from disableproxsensor by Wardell Bagby.
 * The original file is licensed under MIT.
 * Original license:
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Wardell Bagby
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.spezifisch.threestepsahead.hooks;

import android.os.Build;
import android.util.SparseArray;

import com.github.spezifisch.threestepsahead.utils.FakeSensor;

import java.util.Arrays;

import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findClass;

public class Sensor {
    protected FakeSensor fakeSensor;

    public static void initZygote(IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
        /** Shout out to abusalimov for his Light Sensor fix that inspired disableproxsensor. */
    }

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        fakeSensor = new FakeSensor();

        hookSystemSensorManager(lpparam);
        //removeSensors(lpparam);
    }

    private void hookSystemSensorManager(final XC_LoadPackage.LoadPackageParam lpparam) {
        // Alright, so we start by creating a reference to the class that handles sensors.
        final Class<?> systemSensorManager = findClass(
                "android.hardware.SystemSensorManager", lpparam.classLoader);

        // Here, we grab the method that actually dispatches sensor events to tweak what it receives. Since the API seems to have changed in
        // Jelly Bean MR2, we use two different method hooks depending on the API.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            XC_MethodHook mockSensorHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    android.hardware.Sensor sensor = (android.hardware.Sensor) param.args[0];

                    //Use processName here always. Not packageName.
                    if (Main.shouldHookApp(lpparam.processName)) {
                        final float[] origValues = (float[]) param.args[1];
                        final long origTimestamp = (long) param.args[2];
                        final int origInAccuracy = (int) param.args[3];

                        final float[] values = fakeSensorValues(sensor, origValues, origInAccuracy, origTimestamp);

                        //noinspection SuspiciousSystemArraycopy
                        System.arraycopy(values, 0, param.args[1], 0, values.length);
                    }
                }
            };

            // This seems to work fine, but there might be a better method to override.
            // hook: onSensorChangedLocked(Sensor sensor, float[] values, long[] timestamp, int accuracy)
            XposedHelpers.findAndHookMethod(
                    "android.hardware.SystemSensorManager$ListenerDelegate", lpparam.classLoader,
                    "onSensorChangedLocked", android.hardware.Sensor.class, float[].class, long[].class, int.class,
                    mockSensorHook);
        } else {
            XC_MethodHook mockSensorHook = new XC_MethodHook() {
                @SuppressWarnings("unchecked")
                @Override
                protected void beforeHookedMethod(MethodHookParam param)
                        throws Throwable {
                    // This pulls the 'Handle to Sensor' array straight from the SystemSensorManager class, so it should always pull the appropriate sensor.
                    SparseArray<android.hardware.Sensor> sensors;
                    // Marshmallow converted our field into a module level one, so we have different code based on that. Otherwise, the same.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        sensors = (SparseArray<android.hardware.Sensor>)
                                XposedHelpers.getStaticObjectField(systemSensorManager, "sHandleToSensor");
                    } else {
                        Object systemSensorManager = XposedHelpers.getObjectField(param.thisObject, "mManager");
                        sensors = (SparseArray<android.hardware.Sensor>)
                                XposedHelpers.getObjectField(systemSensorManager, "mHandleToSensor");
                    }

                    // params.args[] is an array that holds the arguments that dispatchSensorEvent received, which are a handle pointing to a sensor
                    // in sHandleToSensor and a float[] of values that should be applied to that sensor.
                    int handle = (Integer) (param.args[0]); // This tells us which sensor was currently called.
                    android.hardware.Sensor sensor = sensors.get(handle);

                    if (Main.shouldHookApp(lpparam.processName)) {
                        final float[] origValues = (float[]) param.args[1];
                        final int origInAccuracy = (int) param.args[2];
                        final long origTimestamp = (long) param.args[3];

                        final float[] values = fakeSensorValues(sensor, origValues, origInAccuracy, origTimestamp);
                        /*The SystemSensorManager compares the array it gets with the array from the a SensorEvent,
                        and some sensors (looking at you, Proximity) only use one index in the array
                        but still send along a length 3 array, so we copy here instead of replacing it
                        outright. */

                        //noinspection SuspiciousSystemArraycopy
                        System.arraycopy(values, 0, param.args[1], 0, values.length);
                    }
                }
            };

            // hook: dispatchSensorEvent(int handle, float[] values, int inAccuracy, long timestamp)
            // see: https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/SystemSensorManager.java#666
            XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager$SensorEventQueue", lpparam.classLoader,
                    "dispatchSensorEvent", int.class, float[].class, int.class, long.class,
                    mockSensorHook);
        }
    }

    /**
     * Disable by removing the sensor data from the SensorManager. Apps will think the sensor does not exist.
     **//*
    private void removeSensors(final XC_LoadPackage.LoadPackageParam lpparam) {
        //This is the base method that gets called whenever the sensors are queried. All roads lead back to getFullSensorList!
        XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager", lpparam.classLoader, "getFullSensorList", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                //Without this, you'd never be able to edit the values for a removed sensor! Aaah!
                if (!lpparam.packageName.equals(Constants.PACKAGE_NAME)) {
                    //Create a new list so we don't modify the original list.
                    @SuppressWarnings("unchecked") List<android.hardware.Sensor> fullSensorList = new ArrayList<>((Collection<? extends android.hardware.Sensor>) param.getResult());
                    Iterator<android.hardware.Sensor> iterator = fullSensorList.iterator();
                    while (iterator.hasNext()) {
                        android.hardware.Sensor sensor = iterator.next();
                        if (!shouldAppHalt(lpparam.processName, sensor) && getSensorStatus(sensor) == Constants.SENSOR_STATUS_REMOVE_SENSOR) {
                            iterator.remove();
                        }
                    }
                    param.setResult(fullSensorList);
                }
            }
        });
    }*/

    private float[] fakeSensorValues(final android.hardware.Sensor sensor, float[] values, int inAccuracy, long timestamp) {
        XposedBridge.log("fakeSensorValues BEFORE: ts: " + timestamp + " type: " + sensor.getType() + " (" + sensor.getName() + ")" +
                " accuracy: " + inAccuracy + " values: " + Arrays.toString(values));

        fakeSensor.updateLocation(Main.State.location);

        switch (sensor.getType()) {
            case android.hardware.Sensor.TYPE_ACCELEROMETER:
                values = fakeSensor.getAccelerometer(values);
                break;

            case android.hardware.Sensor.TYPE_MAGNETIC_FIELD:
                values = fakeSensor.getMagneticField(values);
                break;

            case android.hardware.Sensor.TYPE_GYROSCOPE:
                values = fakeSensor.getGyroscope(values);
                break;

            case android.hardware.Sensor.TYPE_GRAVITY:
                values = fakeSensor.getGravity(values);
                break;

            case android.hardware.Sensor.TYPE_LINEAR_ACCELERATION:
                values = fakeSensor.getLinearAcceleration(values);
                break;

            case android.hardware.Sensor.TYPE_ROTATION_VECTOR:
                fakeSensor.updateRotationVector(values);
                values = fakeSensor.getRotationVector(values);
                break;

            default:
                XposedBridge.log("unhandled sensor");
        }

        XposedBridge.log("fakeSensorValues AFTER: ts: " + timestamp + " type: " + sensor.getType() + " (" + sensor.getName() + ")" +
                " accuracy: " + inAccuracy + " values: " + Arrays.toString(values));
        return values;
    }
}