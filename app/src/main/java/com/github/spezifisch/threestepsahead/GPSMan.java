package com.github.spezifisch.threestepsahead;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

import android.location.GpsSatellite;
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
    private static final boolean DEBUG = false;
    private static boolean isHooked = false;

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
    private long lastLocationTime = 0;
    private Random rand;
    private boolean simulateNoise = true;

    // GPS satellite calculator
    private SpaceMan spaceMan;

    // device behaviour
    private boolean devEphemerisAlwaysFalse = true;  // some devices always report hasEphemeris=false
    private boolean devAlmanacAlwaysFalse = true;
    private boolean devFixAlwaysFalse = true;
    private boolean devGpsOnly = true;
    private double fixDropRate = 0.0043;             // fix=false probability [0;1], determined by SensorRawLogger data

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

            if (isHooked) {
                XposedBridge.log("already hooked!");
                return;
            }
            isHooked = true;

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

                    Location realLocation = (Location) message.obj;

                    if (realLocation != null) {
                        // update location noise
                        long locationTime = realLocation.getTime();
                        if (locationTime != lastLocationTime) {
                            XposedBridge.log("ListenerTransport updating location " + locationTime + " last " + lastLocationTime);
                            // only when real GPS location was updated
                            updateLocation();
                            lastLocationTime = locationTime;
                        }

                        // overwrite real location
                        message.obj = fakeLocation(realLocation);
                        param.args[0] = message;

                        Location mojl = (Location) message.obj;
                        XposedBridge.log("ListenerTransport Location faked(" + mojl.getTime() + ") " + mojl);
                    }
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

            protected void checkDeviceCharacteristics(GpsStatus origStatus) {
                if (origStatus == null) {
                    return;
                }

                // check original GpsStatus for device characteristics
                boolean old_alm = devAlmanacAlwaysFalse, old_eph = devEphemerisAlwaysFalse,
                        old_fix = devFixAlwaysFalse, old_gps = devGpsOnly;

                for (GpsSatellite gs: origStatus.getSatellites()) {
                    if (gs.getPrn() > 50) {
                        devGpsOnly = false;
                        continue; // we only care about GPS right now
                    }
                    if (gs.hasAlmanac()) {
                        devAlmanacAlwaysFalse = false;
                    }
                    if (gs.hasEphemeris()) {
                        devEphemerisAlwaysFalse = false;
                    }
                    if (gs.usedInFix()) {
                        devFixAlwaysFalse = false;
                    }
                }

                // print if it changed
                if (old_alm != devAlmanacAlwaysFalse) {
                    XposedBridge.log("devAlmanacAlwaysFalse now: " + devAlmanacAlwaysFalse);
                }
                if (old_eph != devEphemerisAlwaysFalse) {
                    XposedBridge.log("devEphemerisAlwaysFalse now: " + devEphemerisAlwaysFalse);
                }
                if (old_fix != devFixAlwaysFalse) {
                    XposedBridge.log("devFixAlwaysFalse now: " + devFixAlwaysFalse);
                }
                if (old_gps != devGpsOnly) {
                    XposedBridge.log("devGpsOnly now: " + devGpsOnly);
                }
            }

            protected ArrayList<SpaceMan.MyGpsSatellite> getMySatellites() {
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

                boolean firstTime = (spaceMan.getCalculatedLocation() == null);

                long elapsedSinceCalc = System.currentTimeMillis() - spaceMan.getNow(); // ms
                // time limit: azi/ele can change quite fast in edge cases
                boolean bigTime = (elapsedSinceCalc > 10*1000);
                // distance limit
                boolean bigDistance = false;
                if (!firstTime) { // need old location for this
                    // just an arbitrary value
                    bigDistance = (spaceMan.getCalculatedLocation().distanceTo(location) > 500.0 /* m */);
                }

                // need to recalculate sats?
                if (firstTime || bigDistance || bigTime) {
                    XposedBridge.log("recalculating satellites");

                    // calculate satellite positions for current location and time
                    spaceMan.setNow();
                    spaceMan.setGroundStationPosition(location);
                    spaceMan.calculatePositions();

                    if (DEBUG) {
                        spaceMan.dumpSatelliteInfo();
                        spaceMan.dumpGpsSatellites();
                    }
                }

                return spaceMan.getGpsSatellites();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable()) {
                    return;
                }
                if (!serviceClient.connect() || !settings.isEnabled()) {
                    return;
                }

                // real satellite data
                GpsStatus gpsStatus = (GpsStatus) param.getResult();

                // gather info about real GPS device to emulate it better
                checkDeviceCharacteristics(gpsStatus);

                // get satellites in view from set location
                ArrayList<SpaceMan.MyGpsSatellite> mygps = getMySatellites();

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
                                snrs[i] = Math.round(gs.snr + rand.nextGaussian()*1.2f); // variance is quite low
                            } else {
                                snrs[i] = gs.snr;
                            }
                            // these are always rounded to integers, no noise
                            elevations[i] = Math.round(gs.elevation);
                            azimuths[i] = Math.round(gs.azimuth);

                            int prnShift = (1 << (gs.prn - 1));
                            if (gs.hasEphemeris && !devEphemerisAlwaysFalse) {
                                ephemerisMask |= prnShift;
                            }
                            if (gs.hasAlmanac && !devAlmanacAlwaysFalse) {
                                almanacMask |= prnShift;
                            }
                            if (gs.usedInFix && !devFixAlwaysFalse) {
                                if (!simulateNoise) {
                                    usedInFixMask |= prnShift;
                                } else if (rand.nextFloat() > fixDropRate) {
                                    usedInFixMask |= prnShift;
                                }
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
            location.setBearing((float) (location.getBearing() + rand.nextGaussian() * 2.0) % 360.0f); // no rounding here
            if (location.getSpeed() > 1.0) {
                location.setSpeed((float) Math.abs(location.getSpeed() + rand.nextGaussian() * 0.2));
            } else {
                location.setSpeed((float) Math.abs(location.getSpeed() + rand.nextGaussian() * 0.05));
            }
            double distance = rand.nextGaussian() * Math.max(5.0, location.getAccuracy()) / 6.0;    // 5 m or accuracy, it's now really gaussian
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