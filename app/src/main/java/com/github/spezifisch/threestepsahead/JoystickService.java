package com.github.spezifisch.threestepsahead;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.location.Location;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.spezifisch.threestepsahead.utils.LocationHelper;
import com.github.spezifisch.threestepsahead.utils.SpaceMan;
import com.jmedeisis.bugstick.Joystick;
import com.jmedeisis.bugstick.JoystickListener;

public class JoystickService extends Service {
    static final String TAG = "JoystickService";
    static private JoystickService me;
    private static final boolean DEBUG = false;

    protected WindowManager windowManager;
    protected LinearLayout joystickView;
    protected boolean joystickViewAdded = false;
    protected WindowManager.LayoutParams joystickViewParams;

    protected TextView textSpeedTrans, textBearing;

    // IPC interface
    private SettingsStorage settingsStorage;
    private IPC.SettingsClient settings = new IPC.SettingsClient();
    final Messenger messenger = new Messenger(settings);
    private IPC.Client serviceClient = new IPC.Client(settings);

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    public JoystickService() {
        super();
        me = this;
        settings.setTagSuffix(TAG);
    }

    public static JoystickService get() {
        return me;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (joystickView != null) {
            return START_STICKY;
        }

        // get windowmanager to draw overlay
        final DisplayMetrics metrics = new DisplayMetrics();
        windowManager = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getMetrics(metrics);
        joystickViewParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        joystickViewParams.gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
        joystickViewParams.x = 0;
        joystickViewParams.y = 0;

        // create joystick view
        final LayoutInflater inflater = LayoutInflater.from(this);
        joystickView = (LinearLayout)inflater.inflate(R.layout.joystick, null, false);

        // get text fields
        textBearing = (TextView)joystickView.findViewById(R.id.speed_rot);
        textSpeedTrans = (TextView)joystickView.findViewById(R.id.speed_trans);

        // add callbacks for joystick events
        Joystick joystick = (Joystick)joystickView.findViewById(R.id.joystick);
        joystick.setJoystickListener(new MyJsListener());

        // file backend
        settingsStorage = SettingsStorage.getSettingsStorage(getApplicationContext());
        // load settings from file
        settings.setSettingsStorage(settingsStorage);

        // connect to myself
        serviceClient.connect(getApplicationContext());
        // listen to myself
        settings.setClient(serviceClient);
        // relay msgs to clients
        settings.setMaster(true);

        // show/hide joystick
        showJoystick(settingsStorage.isJoystickEnabled());

        // TODO use dynamic update
        String tles = SpaceMan.readTest1(getApplicationContext());
        settings.setTLE(tles);

        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (joystickView != null) {
            windowManager.removeView(joystickView);
        }
    }

    // keep track of current velocity according to joystick
    public class RunningMan {
        static final String TAG = "RunningMan";

        private final String speedBearingString = getString(R.string.bearing_value);
        private final String speedTransString = getString(R.string.speed_trans_value);

        protected long last_step = -1;

        public RunningMan() {
            stop();
        }

        public void stop() {
            updateVelocity(0.0, 0.0);
        }

        public void updateVelocity(double speed, double bearing) {
            boolean still = (Math.abs(speed) < 0.1);    // < 0.1 m/s, it's ok since noise makes it less artificial later

            long now = System.currentTimeMillis();
            if (last_step == -1) {
                last_step = now;
            }

            // here are some leftovers from previous implementation. they are not wrong so let's leave them in

            // see how much time elapsed since last updateVelocity call
            long step_diff = now - last_step; // ms
            if (step_diff > 1000) {
                // max. step, avoid jumps
                step_diff = 1000;
            }

            // throttle step update
            if ((step_diff < 5) && !still) {
                return;
            }

            last_step = now;

            // get previous location
            Location loc = settings.getLocation();

            // advance walk
            double dist = speed * step_diff / 1000.0; // m
            // max. distance per step
            dist = Math.max(-100.0, dist);
            dist = Math.min(100.0, dist);

            // update location
            loc = LocationHelper.displace(loc, dist, bearing);
            loc.setBearing((float) Math.toDegrees(bearing));
            loc.setSpeed((float) Math.abs(speed)); // only pos. speed? maybe.
            loc.setTime(now);
            settings.sendLocation(loc);

            if (DEBUG) {
                Log.d(TAG, "tdiff " + step_diff + " brng " + bearing + " dist " + dist + " time " + now + " new location: " + loc);
            }

            // update textview
            textSpeedTrans.setText(String.format(speedTransString, speed));
            textBearing.setText(String.format(speedBearingString, Math.toDegrees(bearing)));
        }
    }

    // process joystick events
    public class MyJsListener implements JoystickListener {
        protected RunningMan runman;

        // current joystick state
        boolean joystick_touched = false;
        double joystick_phi = 0;
        double joystick_r = 0;

        // updater
        private long UPDATER_PERIOD_ms;
        private Handler handler = new Handler(Looper.getMainLooper());
        Runnable updater = new Runnable() {
            @Override
            public void run() {
                update();
            }
        };

        public MyJsListener() {
            runman = new RunningMan();

            // use update rate that's in sync with screen refresh
            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            float refreshRate = display.getRefreshRate();

            // updater period must be an integer, so search for a good one
            if ((refreshRate % 60.0f) < 0.1) {          // 60 Hz
                UPDATER_PERIOD_ms = 50; // 20 Hz
            } else if ((refreshRate % 50.0f) < 0.1) {   // 50 Hz
                UPDATER_PERIOD_ms = 20; // 50 Hz
            } else {
                UPDATER_PERIOD_ms = 20;
            }
            Log.d(TAG, "update rate " + refreshRate + " -> updater " + UPDATER_PERIOD_ms);
        }

        @Override
        public void onDown() {
            // trigger a location update with zero speed
            runman.stop();

            joystick_touched = true;
            joystick_phi = 0;
            joystick_r = 0;

            startUpdater();
        }

        @Override
        public void onDrag(float degrees, float offset) {
            joystick_phi = Math.toRadians(degrees);
            joystick_r = offset;
        }

        @Override
        public void onUp() {
            // stop movement
            runman.stop();
            joystick_touched = false;

            stopUpdater();
        }

        public void startUpdater() {
            handler.postDelayed(updater, UPDATER_PERIOD_ms);
        }

        public void stopUpdater() {
            handler.removeCallbacks(updater);
        }

        public void update() {
            if (!joystick_touched) {
                return;
            }

            // 4.2 m/s (15 km/h) max. trans. velocity
            final double SPEED_TRANS_MAX = 4.2;

            // set velocity proportional to radius
            double speed = joystick_r * SPEED_TRANS_MAX;

            // set angle according to joystick angle
            // angle is defined CCW (north = 90°), bearing is CW (north = 0°)
            double bearing = -joystick_phi + Math.toRadians(90);

            // update location
            runman.updateVelocity(speed, bearing);

            // start again
            startUpdater();
        }
    }

    public void showJoystick(boolean show) {
        if (!joystickViewAdded) {
            try {
                // add joystick overlay
                windowManager.addView(joystickView, joystickViewParams);
                joystickViewAdded = true;

            } catch (SecurityException e) {
                Log.e(TAG, "Overlay permission failed! Not adding View.");
                return;
            }
        }

        if (show) {
            joystickView.setVisibility(View.VISIBLE);
        } else {
            joystickView.setVisibility(View.GONE);
        }
    }
}
