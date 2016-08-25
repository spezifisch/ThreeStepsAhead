/**
 * Based on MySettings.java from Untoasted
 * Copyright 2014 Eric Gingell (c)
 *
 *     ButteredToast is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ButteredToast is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with UnToasted.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.spezifisch.threestepsahead;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;


public class SettingsStorage {
    static private final String THIS_APP = "com.github.spezifisch.threestepsahead", SETTINGS = "gps";

    /** to be overwritten by Xposed init hook */
    public static boolean xposed_loaded = false;

    /** Private properties. */
    private XSharedPreferences xSharedPreferences = null;
    private SharedPreferences sharedPreferences = null;
    private Context mContext = null;
    private Location cached_location = new Location("gps");
    private static boolean cached_state = false;

    /** Factories. */
    public static SettingsStorage getSettingsStorage() {
        return new SettingsStorage(SETTINGS);
    }

    public static SettingsStorage getSettingsStorage(Context context) {
        return new SettingsStorage(context, SETTINGS);
    }

    /** Constructors. */
    /**
     * Make a new instance using XSharedPreferences.
     * @param name - The file name to be read from.
     */
    public SettingsStorage(String name) {
        try {
            xSharedPreferences = new XSharedPreferences(THIS_APP, name);
            xSharedPreferences.makeWorldReadable();
        } catch (Throwable e) {
            XposedBridge.log(e);
        }
    }
    /**
     * Make a new instance using SharedPreferences.
     * @param context - The app context from which to retrieve the SharedPreferences.
     * @param name - The file name to read/write from.
     */
    @SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    public SettingsStorage(Context context, String name) {
        mContext = context;
        sharedPreferences = mContext.getSharedPreferences(name, Context.MODE_WORLD_READABLE);
    }

    /** Public methods */
    public boolean isXposedLoaded() {
        return xposed_loaded;
    }

    /**
     * Used to add or change 'name' in the preference file.
     * @param name - The property name.
     * @param value - The value of said property.
     * @throws Throwable - Thrown if attempting to write to the file when it's either open by XSharedPreferences or not open.
     */
    public void put(String name, String value) throws Throwable {
        if (sharedPreferences == null) {
            throw new Throwable("Readonly or unavailable");
        }
        sharedPreferences.edit().putString(name, value).apply();
    }
    /**
     * Used to add or change 'name' in the preference file.
     * @param name - The property name.
     * @param value - The value of said property.
     * @throws Throwable - Thrown if attempting to write to the file when it's either open by XSharedPreferences or not open.
     */
    public void put(String name, boolean value) throws Throwable {
        if (sharedPreferences == null) {
            throw new Throwable("Readonly or unavailable");
        }
        sharedPreferences.edit().putBoolean(name, value).apply();
    }
    /**
     * Used to add or change 'name' in the preference file.
     * @param name - The property name.
     * @param value - The value of said property.
     * @throws Throwable - Thrown if attempting to write to the file when it's either open by XSharedPreferences or not open.
     */
    public void put(String name, float value) throws Throwable {
        if (sharedPreferences == null) {
            throw new Throwable("Readonly or unavailable");
        }
        sharedPreferences.edit().putFloat(name, value).apply();
    }

    /**
     * Used to get 'name' from the preference file.
     * @param name - The property name.
     * @param value - The value of said property.
     * @throws Throwable - Thrown if neither XSharedPreferences nor SharedPreferences has the file open.
     */
    public String get(String name, String defValue) throws Throwable {
        if (xSharedPreferences != null) {
            return xSharedPreferences.getString(name, defValue);
        } else if (sharedPreferences != null) {
            return sharedPreferences.getString(name, defValue);
        } else throw new Throwable("Can't get pref, " + name);
    }

    /**
     * Used to get 'name' from the preference file.
     * @param name - The property name.
     * @param value - The value of said property.
     * @throws Throwable - Thrown if neither XSharedPreferences nor SharedPreferences has the file open.
     */
    public boolean get(String name, boolean defValue) throws Throwable {
        if (xSharedPreferences != null) {
            return xSharedPreferences.getBoolean(name, defValue);
        } else if (sharedPreferences != null) {
            return sharedPreferences.getBoolean(name, defValue);
        } else throw new Throwable("Can't get pref, " + name);
    }

    /**
     * Used to get 'name' from the preference file.
     * @param name - The property name.
     * @param value - The value of said property.
     * @throws Throwable - Thrown if neither XSharedPreferences nor SharedPreferences has the file open.
     */
    public float get(String name, float defValue) throws Throwable {
        if (xSharedPreferences != null) {
            return xSharedPreferences.getFloat(name, defValue);
        } else if (sharedPreferences != null) {
            return sharedPreferences.getFloat(name, defValue);
        } else throw new Throwable("Can't get pref, " + name);
    }

    public boolean safeGet(String key, boolean def) {
        try {
            return get(key, def);
        } catch (Throwable e) {
            return def;
        }
    }

    /**
     * Use to get the XSharedPreferences stored in this instance.
     * @return XSharedPreferences xSharedPreferences
     */
    public XSharedPreferences get() {
        return xSharedPreferences;
    }
    /**
     * Use to get the SharedPreferences stored in this instance.
     * @param context - only used to disambiguate SharedPreferences from XSharedPreferences
     * @return SharedPreferences sharedPreferences
     */
    public SharedPreferences get(Context context) {
        return sharedPreferences;
    }

    /** Private methods. */

    /**
     * Used internally to reload the shared prefs file.
     */
    public void reload() {
        if (xSharedPreferences != null) {
            xSharedPreferences.reload();
        }
    }

    public Location getLocation() {
        boolean ok;
        Location loc = new Location("gps");
        try {
            loc.setLatitude(get("latitude", 0));
            loc.setLongitude(get("longitude", 0));
            loc.setAltitude(get("altitude", 0));
            loc.setSpeed(get("speed", 0));
            loc.setAccuracy(get("accuracy", 0));
            loc.setBearing(get("bearing", 0));

            ok = true;
        } catch (Throwable e) {
            ok = false;
        }

        if (Math.abs(loc.getLatitude()) < 0.00001 || Math.abs(loc.getLongitude()) < 0.00001) {
            ok = false; // good job if you really stand this exactly on the equator or meridian
        }

        if (ok) {
            // update cached value
            cached_location.set(loc);
        } else {
            loc.set(cached_location);
        }

        return loc;
    }

    public boolean isEnabled() {
        cached_state = safeGet("start", cached_state);
        return cached_state;
    }

    public boolean isJoystickEnabled() {
        return safeGet("show_joystick", true);
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