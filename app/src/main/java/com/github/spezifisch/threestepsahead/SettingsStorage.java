package com.github.spezifisch.threestepsahead;
// based on: https://github.com/hilarycheng/xposed-gps/blob/master/src/com/diycircuits/gpsfake/Settings.java

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.widget.Toast;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class SettingsStorage {
    public static boolean xposed_loaded = false;

    private XSharedPreferences xSharedPreferences = null;
    private SharedPreferences sharedPreferences = null;
    private Location cached_location = new Location("gps");
    private static boolean cached_state = false;

    public SettingsStorage() {
        xSharedPreferences = new XSharedPreferences("com.github.spezifisch.threestepsahead", "gps");
        xSharedPreferences.makeWorldReadable();
    }

    public SettingsStorage(Context context) {
        sharedPreferences = context.getSharedPreferences("gps", Context.MODE_WORLD_READABLE);
    }

    public boolean xposedTest(Context context) {
        try {
            XposedBridge.log("SettingsStorage ok");
            return true;
        } catch (NoClassDefFoundError e) {
            Toast.makeText(context, "Xposed not found! Did you install it?", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    public boolean isXposedLoaded() {
        return xposed_loaded;
    }

    public double getFloat(String key) {
        if (sharedPreferences != null)
            return sharedPreferences.getFloat(key, (float)0.0);
        else if (xSharedPreferences != null)
            return xSharedPreferences.getFloat(key, (float)0.0);
        throw new RuntimeException("can't access value");
    }

    public Location getLocation() {
        boolean ok;
        Location loc = new Location("gps");
        try {
            loc.setLatitude(getFloat("latitude"));
            loc.setLongitude(getFloat("longitude"));
            loc.setAltitude(getFloat("altitude"));
            loc.setSpeed((float) getFloat("speed"));
            loc.setAccuracy((float) getFloat("accuracy"));
            loc.setBearing((float) getFloat("bearing"));

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

    public boolean isEnabled() {
        boolean state = cached_state;
        if (sharedPreferences != null)
            state = sharedPreferences.getBoolean("start", false);
        else if (xSharedPreferences != null)
            state = xSharedPreferences.getBoolean("start", false);
        cached_state = state;
        return state;
    }

    public void saveLocation(Location loc) {
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        prefEditor.putFloat("latitude", (float) loc.getLatitude());
        prefEditor.putFloat("longitude", (float) loc.getLongitude());
        prefEditor.putFloat("altitude", (float) loc.getAltitude());
        prefEditor.putFloat("speed", loc.getSpeed());
        prefEditor.putFloat("accuracy", loc.getAccuracy());
        prefEditor.putFloat("bearing", loc.getBearing());
        prefEditor.apply();
    }

    public void saveState(boolean start) {
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        prefEditor.putBoolean("enabled", start);
        prefEditor.apply();
    }
}

