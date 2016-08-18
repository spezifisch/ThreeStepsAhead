package com.github.spezifisch.threestepsahead;

import java.lang.reflect.Method;
import java.util.Arrays;

import android.location.GpsStatus;
import android.location.Location;
import android.os.Message;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.XC_MethodHook;

import java.util.List;
import java.util.Random;

public class GPSMan implements IXposedHookLoadPackage {
    // our app
    private static final String THIS_APP = "com.github.spezifisch.threestepsahead";

    // hooked apps
    private List<String> hooked_apps = Arrays.asList(
            "com.vonglasow.michael.satstat",
            "com.nianticlabs.pokemongo"
    );

    private NootLocation nootlocation;
    private Random rand;
    private static Settings settings = new Settings();

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(THIS_APP)) {
            XposedBridge.log(THIS_APP + " app loaded");

            // for the main app to know xposed is running
            Class<?> clazz = XposedHelpers.findClass(THIS_APP + ".Settings", lpparam.classLoader);
            XposedHelpers.setStaticBooleanField(clazz, "xposed_loaded", true);
        } else if (isHookedApp(lpparam.packageName)) {
            XposedBridge.log(lpparam.packageName + " app loaded -> initing");
            // init stuff when hooked app is started

            // init random
            rand = new Random(System.currentTimeMillis() + 234213370);

            // init location
            settings.reload();
            if (nootlocation == null) {
                updateLocation();
            }

            // hooky!
            initHookListenerTransport(lpparam);
            initHookGpsStatus(lpparam);
            initHookGetLastKnownLocation(lpparam);
        }
    }

    private boolean isHookedApp(String packageName) {
        return hooked_apps.contains(packageName);
    }

    void initHookListenerTransport(LoadPackageParam lpparam) {
        class ListenerTransportHook extends XC_MethodHook {
            // this hooks an internal method of LocationManager, which calls OnLocationChanged and other callbacks

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                settings.reload();
                if (!settings.isStarted() || param.hasThrowable()) {
                    return;
                }

                Message message = (Message) param.args[0];
                if (message.what == 1) { // TYPE_LOCATION_CHANGED
                    // see: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/location/java/android/location/LocationManager.java

                    // get current location
                    updateLocation();

                    // overwrite given location
                    message.obj = getFakedLocation((Location)message.obj);;
                    param.args[0] = message;

                    XposedBridge.log("ListenerTransport Location faked: " + message.obj);
                }
            }
        }

        XposedHelpers.findAndHookMethod("android.location.LocationManager$ListenerTransport", lpparam.classLoader,
                "_handleMessage", Message.class, new ListenerTransportHook());
    }

    void initHookGpsStatus(LoadPackageParam lpparam) {
        class GpsStatusHook extends XC_MethodHook {
            // This hooks getGpsStatus function which returns GpsStatus.
            // We use the internal method setStatus to override the satellite info.

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                settings.reload();
                if (!settings.isStarted() || param.hasThrowable()) {
                    return;
                }

                GpsStatus gpsStatus = (GpsStatus) param.getResult();

                Method[] declaredMethods = GpsStatus.class.getDeclaredMethods();
                for (Method method: declaredMethods) {
			// TODO
                }
            }
        }

        XposedHelpers.findAndHookMethod("android.location.LocationManager", lpparam.classLoader,
                "getGpsStatus", GpsStatus.class, new GpsStatusHook());
    }

    void initHookGetLastKnownLocation(LoadPackageParam lpparam) {
        class LastKnownLocationHook extends XC_MethodHook {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                settings.reload();
                if (!settings.isStarted() || param.hasThrowable()) {
                    return;
                }

                if (param.args[0] != null) { // provider enabled + location returned
                    Location location = getFakedLocation((Location)param.args[0]);
                    param.setResult(location);

                    XposedBridge.log("getLastKnownLocation Location faked: " + location);
                }
            }
        }

        XposedHelpers.findAndHookMethod("android.location.LocationManager", lpparam.classLoader,
                "getLastKnownLocation", String.class, new LastKnownLocationHook());
    }

    private void updateLocation() {
        // get current fake location
        nootlocation = settings.getLocation();

        // add gaussian noise with given sigma
        nootlocation.setBearing((float)(nootlocation.getBearing() + rand.nextGaussian()*2.0));      // 2 deg
        nootlocation.setSpeed((float)Math.abs(nootlocation.getSpeed() + rand.nextGaussian()*0.2));  // 0.2 m/s
        double distance = rand.nextGaussian()*Math.max(5.0, nootlocation.getAccuracy())/3.0;        // 5 m or accuracy (getAccuracy looks rather than 3sigma)
        double theta = Math.toRadians(rand.nextFloat() * 360.0);                                    // direction of displacement should be uniformly distributed
        nootlocation.displace(distance, theta);
    }

    private Location getFakedLocation(Location location) {
        // overwrite faked parts of Location
        location.setLatitude(nootlocation.getLatitude());
        location.setLongitude(nootlocation.getLongitude());
        //location.setTime(System.currentTimeMillis());
        location.setAltitude(nootlocation.getAltitude());
        location.setSpeed(nootlocation.getSpeed());
        //location.setAccuracy(nootlocation.getAccuracy());
        location.setBearing(nootlocation.getBearing());

        return location;
    }
}
