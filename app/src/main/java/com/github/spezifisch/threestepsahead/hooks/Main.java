package com.github.spezifisch.threestepsahead.hooks;

import android.location.Location;

import com.github.spezifisch.threestepsahead.IPC;
import com.github.spezifisch.threestepsahead.SettingsStorage;
import com.github.spezifisch.threestepsahead.utils.LocationHelper;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Main implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    public static class Settings {
        static final String THIS_APP = "com.github.spezifisch.threestepsahead";
        static final boolean DEBUG = false;
        static boolean simulateNoise = true;
        static final long RAND_ADD = 234213370;
        static final boolean HOOK_SENSORS = false;

        // apps to hook
        static final List<String> hookedApps = Arrays.asList(
                "com.google.android.gms",
                "com.vonglasow.michael.satstat",
                "com.nianticlabs.pokemongo",
                "com.google.android.gms.location.sample.locationupdates"
        );
    }

    public static class Shared {
        // IPC to JoystickService
        static SettingsStorage settingsStorage;
        static IPC.SettingsClient settings;
        static IPC.Client serviceClient;
        static String packageName;
    };

    public static class State {
        static Location location;
        static boolean locationFromSettings = false;
        static Random rand;
    };

    protected GPS gps;
    protected Sensor sensor;

    public static boolean shouldHookApp(String s) {
        return Settings.hookedApps.contains(s);
    }

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
        // file settings
        Shared.settingsStorage = SettingsStorage.getSettingsStorage();

        if (!Settings.simulateNoise) {
            XposedBridge.log("!!! Noise deactivated. This is not a good idea.");
        }

        GPS.initZygote(startupParam);
        if (Settings.HOOK_SENSORS) {
            Sensor.initZygote(startupParam);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // set a field in our main app so that it knows xposed is running
        if (lpparam.packageName.equals(Settings.THIS_APP)) {
            XposedBridge.log(Settings.THIS_APP + " app loaded");

            Class<?> clazz = XposedHelpers.findClass(Settings.THIS_APP + ".SettingsStorage", lpparam.classLoader);
            XposedHelpers.setStaticBooleanField(clazz, "xposed_loaded", true);
        }

        if (shouldHookApp(lpparam.packageName)) {
            XposedBridge.log(lpparam.packageName + " app loaded -> initing");

            // init random
            State.rand = new Random(System.currentTimeMillis() + Settings.RAND_ADD);

            Shared.packageName = lpparam.packageName;

            // IPC instance
            Shared.settings = new IPC.SettingsClient();
            Shared.settings.setTagSuffix("Hook-" + lpparam.packageName);
            Shared.settings.setInXposed(true);
            Shared.settings.setSettingsStorage(Shared.settingsStorage);

            // pair Service and Client
            Shared.serviceClient = new IPC.Client(Shared.settings);
            Shared.serviceClient.setInXposed(true);
            Shared.settings.setClient(Shared.serviceClient);

            // init location from settings if possible
            if (State.location == null) {
                updateLocation(0);
                State.locationFromSettings = true;
            }

            // install hooks for app
            gps = new GPS();
            gps.handleLoadPackage(lpparam);

            if (Settings.HOOK_SENSORS) {
                sensor = new Sensor();
                sensor.handleLoadPackage(lpparam);
            }
        }
    }

    public static boolean connectAndRun() {
        if (!Shared.serviceClient.connect()) {
            return false;   // don't run
        }
        if (!Shared.settings.isEnabled()) {
            return false;   // don't run
        }

        // update location via IPC
        if (State.locationFromSettings) {
            boolean ok = Shared.settings.requestLocation();
            if (ok) {
                State.locationFromSettings = false;
            }
        }

        return true;
    }

    public static void updateLocation(final long origLocationTime) {
        // get current fake location
        State.location = Shared.settings.getLocation();

        // add gaussian noise with given sigma
        if (Settings.simulateNoise) {
            final Location l = State.location;

            // seed random with orig. location's timestamp to get deterministic output values.
            // we do this to get the same noise in all instances for the same location update,
            // so all apps get the same noisy fake location. But alas it doesn't work.
            if (origLocationTime != 0) {
                State.rand = new Random(origLocationTime + Settings.RAND_ADD);
            }

            // bearing
            final float randBearing = (float) (l.getBearing() + State.rand.nextGaussian() * 2.0) % 360.0f;

            // speed
            float randSpeed;
            if (l.getSpeed() > 1.0) {
                randSpeed = (float) Math.abs(l.getSpeed() + State.rand.nextGaussian() * 0.2);
            } else {
                randSpeed = (float) Math.abs(l.getSpeed() + State.rand.nextGaussian() * 0.05);
            }

            // lat/lon
            final double randDistance = State.rand.nextGaussian() * Math.max(5.0, l.getAccuracy()) / 6.0;
            final double randTheta = Math.toRadians(State.rand.nextFloat() * 360.0);

            State.location.setBearing(randBearing);
            State.location.setSpeed(randSpeed);
            State.location = LocationHelper.displace(State.location, randDistance, randTheta);
        }
    }
}
