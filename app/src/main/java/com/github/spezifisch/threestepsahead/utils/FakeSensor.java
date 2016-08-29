package com.github.spezifisch.threestepsahead.utils;

import android.location.Location;

public class FakeSensor {
    protected Location location;
    protected float[] rotation;

    public void updateLocation(Location loc) {
        location = loc;
    }

    public void updateRotationVector(float[] values) {
        rotation = values;
    }

    public float[] getAccelerometer(float[] values) {
        return values;
    }

    public float[] getMagneticField(float[] values) {
        return values;
    }

    public float[] getGyroscope(float[] values) {
        return values;
    }

    public float[] getGravity(float[] values) {
        return values;
    }

    public float[] getLinearAcceleration(float[] values) {
        return values;
    }

    public float[] getRotationVector(float[] values) {
        return values;
    }
}
