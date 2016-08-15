package com.github.spezifisch.threestepsahead;
// based on: https://github.com/hilarycheng/xposed-gps/blob/master/src/com/diycircuits/gpsfake/GPSFake.java

import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;

import android.content.Context;
import android.app.AndroidAppHelper;
import android.location.Location;
import android.location.LocationListener;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.XC_MethodHook;

import java.util.List;
import java.util.Set;
import java.util.Random;

import static de.robv.android.xposed.XposedHelpers.findClass;

public class GPSMan implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    // packages to hook
    private List<String> hookPackages = Arrays.asList("com.vonglasow.michael.satstat");
    // methods of LocationListener to hook
    private List<String> hookLocationListener = Arrays.asList("onLocationChanged", "onProviderDisabled", "onProviderEnabled", "onStatusChanged");

    private boolean mLocationManagerHooked = false;
    private HashMap<Method, XC_MethodHook> mHook = new HashMap<>();
    private NootLocation location;
    private Random rand;
    private static Settings settings = new Settings();

    private boolean shouldHookPackage(String packageName) {
        return hookPackages.contains(packageName);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!shouldHookPackage(lpparam.packageName)) {
            return;
        }

        settings.reload();
        rand = new Random(System.currentTimeMillis() + 23981226);
        XposedBridge.log("Loaded app: " + lpparam.packageName);
    }

    private void updateLocation() {
        // get current fake location
        location = settings.getLocation();

        // add gaussian noise with given sigma
        location.setBearing((float)(location.getBearing() + rand.nextGaussian()*2.0));      // 2 deg
        location.setSpeed((float)Math.abs(location.getSpeed() + rand.nextGaussian()*0.2));  // 0.2 m/s
        double distance = rand.nextGaussian()*Math.max(5.0, location.getAccuracy())/3.0;    // 5 m or accuracy (getAccuracy looks rather than 3sigma)
        double theta = Math.toRadians(rand.nextFloat() * 360.0);                            // direction of displacement should be uniformly distributed
        location.displace(distance, theta);
    }

    private void handleGetSystemService(String name, Object instance) {
        if (name.equals(Context.LOCATION_SERVICE)) {
            if (!mLocationManagerHooked) {
                String packageName = AndroidAppHelper.currentPackageName();
                XposedBridge.log("Hooking LocationManager " + packageName + " -> " + instance.getClass().getName());

                if (shouldHookPackage(packageName)) {
                    XC_MethodHook methodHook = new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            settings.reload();
                            if (param.method.getName().equals("onProviderDisabled")) {
                                if (settings.isStarted()) {
                                    param.setResult(null);
                                }
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.hasThrowable()) {
                                return; // exception
                            }
                            settings.reload();
                            if (!settings.isStarted()) {
                                return;
                            }
                            if (location == null) {
                                updateLocation();
                            }

                            String methodName = param.method.getName();

                            // methods without parameters
                            switch (methodName) {
                                /** Location **/
                                case "getTime":
                                    param.setResult(System.currentTimeMillis());
                                    break;
                                case "getLatitude":
                                    param.setResult(location.getLatitude());
                                    break;
                                case "getLongitude":
                                    param.setResult(location.getLongitude());
                                    break;
                                case "getAltitude":
                                    // TODO
                                    //param.setResult(location.getAltitude());
                                    break;
                                case "getSpeed":
                                    param.setResult(location.getSpeed());
                                    break;
                                case "getAccuracy":
                                    // just pipe it through, why not?
                                    //param.setResult(location.getAccuracy());
                                    break;
                                case "getBearing":
                                    param.setResult(location.getBearing());
                                    break;

                                case "hasAltitude":
                                    //param.setResult(location.hasAltitude());
                                    break;
                                case "hasSpeed":
                                    param.setResult(location.hasSpeed());
                                    break;
                                case "hasAccuracy":
                                    //param.setResult(location.hasAccuracy());
                                    break;
                                case "hasBearing":
                                    param.setResult(location.hasBearing());
                                    break;

                                /** LocationManager **/
                                case "requestSingleUpdate":
                                case "requestLocationUpdates":
                                    // this is called to subscribe to async updates.
                                    // there a different signatures for this function.
                                    // look for the LocationListener parameter to hook its methods.
                                    for (int count = 0; count < param.args.length; count++) {
                                        XposedBridge.log("LocationManager requestLocationUpdates: " + param.args[count]);

                                        if (param.args[count] instanceof LocationListener) {
                                            LocationListener ll = (LocationListener) param.args[count];

                                            for (Method method : ll.getClass().getDeclaredMethods()) {
                                                int m = method.getModifiers();
                                                if (Modifier.isPublic(m) && !Modifier.isStatic(m)) {
                                                    if (!mHook.containsKey(method) && hookLocationListener.contains(method.getName())) {
                                                        XposedBridge.log("LocationListenerRLU hooked method " + method.getName());
                                                        mHook.put(method, this);
                                                        XposedBridge.hookMethod(method, this);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    break;

                                case "removeUpdates":
                                    Set<Method> mMethod = mHook.keySet();
                                    for (Method m : mMethod) {
                                        XposedBridge.unhookMethod(m, mHook.get(m));
                                    }
                                    mHook.clear();
                                    break;

                                case "getLastKnownLocation":
                                    hookLocation(this, (Location)param.getResult());
                                    break;

                                case "addGpsStatusListener":
                                case "registerGnssMeasurementsCallback":
                                case "registerGnssNavigationMessageCallback":
                                case "registerGnssStatusCallback":
                                case "addNmeaListener":
                                case "addProximityAlert":
                                    XposedBridge.log("LocationManager " + methodName + " called!");
                                    break;

                                /** LocationListener **/
                                case "isProviderEnabled":
                                    XposedBridge.log("LocationManager isProviderEnabled: " + param.args[0] + " " + param.getResult());
                                    if (settings.isStarted()) {
                                        param.setResult(true);
                                    }
                                    break;

                                case "onLocationChanged":
                                    // this is called by the original LocationManager on update.
                                    // hook the methods of the Location object which is supplied to the LocationListener.

                                    // get new values from settings
                                    updateLocation();

                                    if (param.args[0] instanceof Location) {
                                        hookLocation(this, (Location)param.args[0]);
                                    }

                                    break;

                                default:
                                    //XposedBridge.log("LocationManager result not modified");
                            }

                            XposedBridge.log("LocationManager hooked method called: " + methodName + " Result: " + param.getResult());
                        }
                    };

                    Class<?> hookClass = findClass(instance.getClass().getName(), null);
                    for (Method method: hookClass.getDeclaredMethods()) {
                        int m = method.getModifiers();
                        if (Modifier.isPublic(m) && !Modifier.isStatic(m)) {
                            XposedBridge.log("Hooking LocationManager Method " + method.getName());
                            XposedBridge.hookMethod(method, methodHook);
                        }
                    }
                }
                mLocationManagerHooked = true;
            }
        }
    }

    private void hookLocation(XC_MethodHook hook, Location ll) {
        for (Method method : ll.getClass().getDeclaredMethods()) {
            int m = method.getModifiers();

            if (Modifier.isPublic(m) && !Modifier.isStatic(m)) {
                if (!mHook.containsKey(method)) {
                    XposedBridge.log("LocationListenerOLC " + method.getName());
                    mHook.put(method, hook);
                    XposedBridge.hookMethod(method, hook);
                }
            }
        }
    }

    private void hookSystemService(String context) {
        try {
            XC_MethodHook methodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!param.hasThrowable()) {
                        try {
                            if (param.args.length > 0 && param.args[0] != null) {
                                // XposedBridge.log("Hook Method : " + mInstance + " " + mApp + " " + packageName);
                                String name = (String) param.args[0];
                                Object instance = param.getResult();
                                if (instance != null) {
                                    handleGetSystemService(name, instance);
                                }
                            }
                        } catch (Throwable ex) {
                            throw ex;
                        }
                    }
                }
            };

            Set<XC_MethodHook.Unhook> hookSet = new HashSet<>();
            Class<?> hookClass = findClass(context, null);

            // XposedBridge.log("Zygote Context Find Class " + hookClass);
            while (hookClass != null) {
                for (Method method : hookClass.getDeclaredMethods()) {
                    if (method != null && method.getName().equals("getSystemService")) {
                        hookSet.add(XposedBridge.hookMethod(method, methodHook));
                    }
                }
                hookClass = (hookSet.isEmpty() ? hookClass.getSuperclass() : null);
            }
        } catch (Exception ex) {
            XposedBridge.log("Zygote Context Hook Exception " + ex);
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        hookSystemService("android.app.ContextImpl");
        hookSystemService("android.app.Activity");
    }

}