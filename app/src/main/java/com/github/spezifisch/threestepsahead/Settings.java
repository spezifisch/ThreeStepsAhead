package com.github.spezifisch.threestepsahead;
// based on: https://github.com/hilarycheng/xposed-gps/blob/master/src/com/diycircuits/gpsfake/Settings.java

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class Settings {

    private Context context = null;
    private XSharedPreferences xSharedPreferences = null;
    private SharedPreferences sharedPreferences = null;
    private NootLocation cached_location = new NootLocation("");
    private static boolean cached_state = false;

    public Settings() {
        xSharedPreferences = new XSharedPreferences("com.github.spezifisch.threestepsahead", "gps");
        // xSharedPreferences.makeWorldReadable();
    }

    public Settings(Context context) {
        sharedPreferences = context.getSharedPreferences("gps", Context.MODE_WORLD_READABLE);
        this.context = context;
    }

    public double getFloat(String key) {
        if (sharedPreferences != null)
            return sharedPreferences.getFloat(key, (float)0.0);
        else if (xSharedPreferences != null)
            return xSharedPreferences.getFloat(key, (float)0.0);
        throw new RuntimeException("can't access value");
    }

    public long getLong(String key) {
        if (sharedPreferences != null)
            return sharedPreferences.getLong(key, 0);
        else if (xSharedPreferences != null)
            return xSharedPreferences.getLong(key, 0);
        throw new RuntimeException("can't access value");
    }

    public NootLocation getLocation() {
        boolean ok = false;
        NootLocation loc = new NootLocation("gps");
        try {
            loc.setLatitude(getFloat("latitude"));
            loc.setLongitude(getFloat("longitude"));
            loc.setAltitude(getFloat("altitude"));
            loc.setSpeed((float) getFloat("speed"));
            loc.setBearing((float) getFloat("bearing"));
            loc.setTime(getLong("time"));

            ok = true;
        } catch (Exception e) {
            ok = false;
        }

        if (Math.abs(loc.getLatitude()) < 0.00001 || Math.abs(loc.getLongitude()) < 0.00001) {
            ok = false; // good job if you really stand this exactly on the equator or meridian
        }

        if (ok) {
            // update cached value
            cached_location.set(loc);
        } else {
            XposedBridge.log("GetNumber failed, using cached location.");
            loc.set(cached_location);
        }

        return loc;
    }

    public boolean isStarted() {
        boolean state = cached_state;
        if (sharedPreferences != null)
            state = sharedPreferences.getBoolean("start", false);
        else if (xSharedPreferences != null)
            state = xSharedPreferences.getBoolean("start", false);
        cached_state = state;
        return state;
    }

    public void updateLocation(NootLocation loc) {
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        prefEditor.putFloat("latitude", (float) loc.getLatitude());
        prefEditor.putFloat("longitude", (float) loc.getLongitude());
        prefEditor.putFloat("altitude", (float) loc.getAltitude());
        prefEditor.putFloat("speed", loc.getSpeed());
        prefEditor.putFloat("bearing", loc.getBearing());
        prefEditor.putLong("time", loc.getTime());
        prefEditor.apply();
    }

    public void updateLocation(Location loc) {
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        prefEditor.putFloat("latitude", (float) loc.getLatitude());
        prefEditor.putFloat("longitude", (float) loc.getLongitude());
        prefEditor.putFloat("altitude", (float) loc.getAltitude());
        prefEditor.putFloat("speed", loc.getSpeed());
        prefEditor.putFloat("bearing", loc.getBearing());
        prefEditor.putLong("time", loc.getTime());
        prefEditor.apply();
    }

    public void updateState(boolean start) {
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        prefEditor.putBoolean("start",   start);
        prefEditor.apply();
    }

    public void reload() {
        if (xSharedPreferences != null) {
            xSharedPreferences.reload();
        }
    }
}

