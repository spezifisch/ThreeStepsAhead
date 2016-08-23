package com.github.spezifisch.threestepsahead;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

import android.app.AndroidAppHelper;
import android.location.GpsStatus;
import android.location.Location;
import android.os.Message;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.XC_MethodHook;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

public class GPSMan implements IXposedHookLoadPackage {
    // our app
    private static final String THIS_APP = "com.github.spezifisch.threestepsahead";
    private static final boolean DEBUG = false;

    // whitelist for apps not to hook
    private List<String> whitelist = Arrays.asList(
            THIS_APP,
            "com.github.spezifisch.sensorrawlogger"
    );

    // IPC to JoystickService
    private SettingsStorage settingsStorage;
    private IPC.SettingsClient settings;
    private IPC.Client serviceClient;

    private Location location;
    private Random rand;
    private boolean simulateNoise = true;

    // GPS satellite calculator
    private SpaceMan spaceMan;

    private boolean inWhitelist(String s) {
        return whitelist.contains(s);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(THIS_APP)) {
            XposedBridge.log(THIS_APP + " app loaded");

            // for the main app to know xposed is running
            Class<?> clazz = XposedHelpers.findClass(THIS_APP + ".SettingsStorage", lpparam.classLoader);
            XposedHelpers.setStaticBooleanField(clazz, "xposed_loaded", true);
        } else if (!inWhitelist(lpparam.packageName)) {
            XposedBridge.log(lpparam.packageName + " app loaded -> initing");

            if (!simulateNoise) {
                XposedBridge.log("Noise deactivated. This is not a good idea.");
            }

            // init random
            rand = new Random(System.currentTimeMillis() + 234213370);

            // file settings
            settingsStorage = SettingsStorage.getSettingsStorage();

            // IPC instance
            settings = new IPC.SettingsClient();
            settings.setTagSuffix("GPSMan");
            settings.setInXposed(true);
            settings.setSettingsStorage(settingsStorage);

            // pair Service and Client
            serviceClient = new IPC.Client(settings);
            serviceClient.setInXposed(true);
            settings.setClient(serviceClient);

            // init location
            if (location == null) {
                updateLocation();
            }

            // hooky!
            initHookListenerTransport(lpparam);
            initHookGpsStatus(lpparam);
            initHookGetLastKnownLocation(lpparam);
        }
    }

    void initHookListenerTransport(LoadPackageParam lpparam) {
        class ListenerTransportHook extends XC_MethodHook {
            // this hooks an internal method of LocationManager, which calls OnLocationChanged and other callbacks

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable()) {
                    return;
                }
                if (!serviceClient.connect() || !settings.isEnabled()) {
                    return;
                }

                Message message = (Message) param.args[0];
                if (message.what == 1) { // TYPE_LOCATION_CHANGED
                    // see: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/location/java/android/location/LocationManager.java

                    // get current location
                    updateLocation();

                    // overwrite given location
                    message.obj = fakeLocation((Location)message.obj);;
                    param.args[0] = message;

                    XposedBridge.log("ListenerTransport Location faked: " + message.obj);
                } else {
                    XposedBridge.log("ListenerTransport unhandled message(" + message.what + ") " + message.obj);
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
                if (param.hasThrowable()) {
                    return;
                }
                if (!serviceClient.connect() || !settings.isEnabled()) {
                    return;
                }

                // initialize gps calc
                if (spaceMan == null) {
                    spaceMan = new SpaceMan();
                    spaceMan.inXposed = true;
                }

                // update TLE
                if (serviceClient.isConnected() && spaceMan.getTLECount() == 0) {
                    settings.requestTLE(); // async, getTLE probably takes a bit
                    spaceMan.parseTLE(settings.getTLE());
                }

                // calculate satellite positions for current location and time
                spaceMan.setNow();
                spaceMan.setGroundStationPosition(location);
                spaceMan.calculatePositions();

                if (DEBUG) {
                    spaceMan.dumpSatelliteInfo();
                    spaceMan.dumpGpsSatellites();
                }

                // get satellites in view
                ArrayList<SpaceMan.MyGpsSatellite> mygps = spaceMan.getGpsSatellites();

                GpsStatus gpsStatus = (GpsStatus) param.getResult();

                // use internal method setStatus to overwrite satellite info
                Method[] declaredMethods = GpsStatus.class.getDeclaredMethods();
                for (Method method: declaredMethods) {
                    if (method.getName().equals("setStatus") && method.getParameterTypes().length >= 8) {
                        // setStatus(int svCount, int[] prns, float[] snrs, float[] elevations, float[] azimuths,
                        //           int ephemerisMask, int almanacMask, int usedInFixMask)

                        // put data from my gps sats in these great arrays
                        int svCount = mygps.size();
                        int[] prns = new int[svCount];
                        float[] snrs = new float[svCount];
                        float[] elevations = new float[svCount];
                        float[] azimuths = new float[svCount];
                        int ephemerisMask = 0;
                        int almanacMask = 0;
                        int usedInFixMask = 0;

                        int i = 0;
                        for (SpaceMan.MyGpsSatellite gs: mygps) {
                            prns[i] = gs.prn;
                            if (simulateNoise) {
                                snrs[i] = Math.round(gs.snr + rand.nextGaussian()*1.0f);
                            } else {
                                snrs[i] = gs.snr;
                            }
                            // these should be known exactly(?)
                            elevations[i] = gs.elevation;
                            azimuths[i] = gs.azimuth;

                            int prnShift = (1 << (gs.prn - 1));
                            if (gs.hasEphemeris) {
                                ephemerisMask |= prnShift;
                            }
                            if (gs.hasAlmanac) {
                                almanacMask |= prnShift;
                            }
                            if (gs.usedInFix) {
                                //if (!simulateNoise) {
                                    usedInFixMask |= prnShift;
                                //} else if (gs.elevation > 22.0f || rand.nextFloat() < 0.97f) { // 3% drop for low sats
                                //    usedInFixMask |= prnShift;
                                //}
                            }

                            i++;
                        }

                        // call private setStatus method to apply these values
                        try {
                            method.setAccessible(true);
                            method.invoke(gpsStatus, svCount, prns, snrs, elevations, azimuths, ephemerisMask, almanacMask, usedInFixMask);
                            method.setAccessible(false);
                            param.setResult(gpsStatus);

                            XposedBridge.log("GpsStatus faked: " + gpsStatus);
                        } catch (Throwable e) {
                            XposedBridge.log(e);
                        }

                        break;
                    }
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
                if (param.hasThrowable()) {
                    return;
                }
                if (!serviceClient.connect() || !settings.isEnabled()) {
                    return;
                }

                if (param.getResult() != null) { // provider enabled + location returned
                    Location location = fakeLocation((Location)param.getResult());
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
        location = settings.getLocation();

        // add gaussian noise with given sigma
        if (simulateNoise) {
            location.setBearing((float) (location.getBearing() + rand.nextGaussian() * 2.0));       // 2 deg
            if (location.getSpeed() > 1.0) {
                location.setSpeed((float) Math.abs(location.getSpeed() + rand.nextGaussian() * 0.2));
            } else {
                location.setSpeed((float) Math.abs(location.getSpeed() + rand.nextGaussian() * 0.05));
            }
            double distance = rand.nextGaussian() * Math.max(5.0, location.getAccuracy()) / 3.0;    // 5 m or accuracy (getAccuracy looks rather than 3sigma)
            double theta = Math.toRadians(rand.nextFloat() * 360.0);                                // direction of displacement should be uniformly distributed
            location = LocationHelper.displace(location, distance, theta);
        }
    }

    private Location fakeLocation(Location loc) {
        // overwrite faked parts of Location
        loc.setLatitude(location.getLatitude());
        loc.setLongitude(location.getLongitude());
        //location.setTime(System.currentTimeMillis());
        //loc.setAltitude(location.getAltitude());
        loc.setSpeed(location.getSpeed());
        //loc.setAccuracy(location.getAccuracy());
        loc.setBearing(location.getBearing());

        return loc;
    }
}