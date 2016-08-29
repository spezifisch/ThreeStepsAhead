package com.github.spezifisch.threestepsahead.hooks;

import java.lang.reflect.Method;
import java.util.ArrayList;

import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.os.Message;

import com.github.spezifisch.threestepsahead.utils.SpaceMan;

import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XC_MethodHook;

public class GPS {
    private static final boolean DEBUG = false;

    // GPS satellite calculator
    private SpaceMan spaceMan;

    // device behaviour
    private boolean devEphemerisAlwaysFalse = true;  // some devices always report hasEphemeris=false
    private boolean devAlmanacAlwaysFalse = true;
    private boolean devFixAlwaysFalse = true;
    private boolean devGpsOnly = true;
    private double fixDropRate = 0.0043;             // fix=false probability [0;1], determined by SensorRawLogger data

    private long lastLocationTime = 0;

    public static void initZygote(final IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
        // hook nothing globally
    }

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // hook LocationManager of relevant packages
        initHookListenerTransport(lpparam);
        initHookGpsStatus(lpparam);
        initHookGetLastKnownLocation(lpparam);
    }

    void initHookListenerTransport(final XC_LoadPackage.LoadPackageParam lpparam) {
        class ListenerTransportHook extends XC_MethodHook {
            // this hooks an internal method of LocationManager, which calls OnLocationChanged and other callbacks

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable()) {
                    return;
                }
                if (!Main.connectAndRun()) {
                    return;
                }
                checkInitialLocation();

                Message message = (Message) param.args[0];
                if (message.what == 1) { // TYPE_LOCATION_CHANGED
                    // see: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/location/java/android/location/LocationManager.java

                    Location realLocation = (Location) message.obj;

                    if (realLocation != null) {
                        // update location noise
                        long locationTime = realLocation.getTime();
                        if (locationTime != lastLocationTime) {
                            if (DEBUG) {
                                XposedBridge.log(Main.Shared.packageName + " | " + locationTime +
                                        " ListenerTransport updating location " + locationTime + " last " + lastLocationTime);
                            }

                            // only when real GPS location was updated
                            Main.updateLocation(locationTime);
                            lastLocationTime = locationTime;
                        }

                        // overwrite real location
                        message.obj = fakeLocation(realLocation);
                        param.args[0] = message;

                        Location mojl = (Location) message.obj;
                        if (DEBUG) {
                            XposedBridge.log(Main.Shared.packageName + " | " + locationTime +
                                    " ListenerTransport Location faked(" + mojl.getTime() + ") " + mojl);
                        }
                    }
                } else {
                    XposedBridge.log("ListenerTransport unhandled message(" + message.what + ") " + message.obj);
                }
            }
        }

        XposedHelpers.findAndHookMethod("android.location.LocationManager$ListenerTransport", lpparam.classLoader,
                "_handleMessage", Message.class, new ListenerTransportHook());
    }

    void initHookGpsStatus(final XC_LoadPackage.LoadPackageParam lpparam) {
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
                if (Main.Shared.serviceClient.isConnected() && spaceMan.getTLECount() == 0) {
                    Main.Shared.settings.requestTLE(); // async, getTLE probably takes a bit
                    spaceMan.parseTLE(Main.Shared.settings.getTLE());
                }

                boolean firstTime = (spaceMan.getCalculatedLocation() == null);

                long elapsedSinceCalc = System.currentTimeMillis() - spaceMan.getNow(); // ms
                // time limit: azi/ele can change quite fast in edge cases
                boolean bigTime = (elapsedSinceCalc > 10*1000);
                // distance limit
                boolean bigDistance = false;
                if (!firstTime) { // need old location for this
                    // just an arbitrary value
                    bigDistance = (spaceMan.getCalculatedLocation().distanceTo(Main.State.location) > 500.0 /* m */);
                }

                // need to recalculate sats?
                if (firstTime || bigDistance || bigTime) {
                    XposedBridge.log("recalculating satellites");

                    // calculate satellite positions for current location and time
                    spaceMan.setNow();
                    spaceMan.setGroundStationPosition(Main.State.location);
                    spaceMan.calculatePositions();

                    if (Main.Settings.DEBUG) {
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
                if (!Main.connectAndRun()) {
                    return;
                }
                checkInitialLocation();

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
                            if (Main.Settings.simulateNoise) {
                                snrs[i] = Math.round(gs.snr + Main.State.rand.nextGaussian()*1.2f); // variance is quite low
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
                                if (!Main.Settings.simulateNoise) {
                                    usedInFixMask |= prnShift;
                                } else if (Main.State.rand.nextFloat() > fixDropRate) {
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

    void initHookGetLastKnownLocation(final XC_LoadPackage.LoadPackageParam lpparam) {
        class LastKnownLocationHook extends XC_MethodHook {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable()) {
                    return;
                }
                if (!Main.connectAndRun()) {
                    return;
                }
                checkInitialLocation();

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

    private Location fakeLocation(Location loc) {
        final Location l = Main.State.location;

        // overwrite faked parts of Location
        loc.setLatitude(l.getLatitude());
        loc.setLongitude(l.getLongitude());
        //location.setTime(System.currentTimeMillis());
        //loc.setAltitude(l.getAltitude());
        loc.setSpeed(l.getSpeed());
        //loc.setAccuracy(l.getAccuracy());
        loc.setBearing(l.getBearing());

        return loc;
    }

}