package com.github.spezifisch.threestepsahead;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.location.Location;
import android.os.IBinder;
import android.os.Messenger;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jmedeisis.bugstick.Joystick;
import com.jmedeisis.bugstick.JoystickListener;

public class JoystickService extends Service {
    static final String TAG = "JoystickService";
    static private JoystickService me;

    protected WindowManager windowManager;
    protected LinearLayout joystickView;

    protected TextView textSpeedTrans, textSpeedRot;

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
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
        params.x = 0;
        params.y = 0;

        // create joystick view
        final LayoutInflater inflater = LayoutInflater.from(this);
        joystickView = (LinearLayout)inflater.inflate(R.layout.joystick, null, false);

        // get text fields
        textSpeedRot = (TextView)joystickView.findViewById(R.id.speed_rot);
        textSpeedTrans = (TextView)joystickView.findViewById(R.id.speed_trans);

        // add joystick overlay
        windowManager.addView(joystickView, params);

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

        private final String speedRotString = getString(R.string.speed_rot_value);
        private final String speedTransString = getString(R.string.speed_trans_value);


        public RunningMan() {
            stop();
        }

        public void stop() {
            updateVelocity(0.0, 0.0);
        }

        public void updateVelocity(double speed_trans, double speed_rot) {
            // update textview
            textSpeedTrans.setText(String.format(speedTransString, speed_trans));
            textSpeedRot.setText(String.format(speedRotString, Math.toDegrees(speed_rot)));

            // get previous location
            Location loc = settings.getLocation();

            // see how much time elapsed
            long now = System.currentTimeMillis();
            long tdiff = now - loc.getTime(); // ms
            if (tdiff > 1000) {
                // max. step, avoid jumps
                tdiff = 1000;
            }

            // advance angle (north = 0)
            double yaw = Math.toRadians(loc.getBearing());
            if (Math.abs(speed_rot) > Math.toRadians(1.0)) { // avoid accumulating errors for small angles
                yaw -= speed_rot * tdiff / 1000.0; // rad
            }

            // advance walk
            double dist = speed_trans * tdiff / 1000.0; // m
            // max. distance per step
            dist = Math.max(-100.0, dist);
            dist = Math.min(100.0, dist);

            // update location
            loc = LocationHelper.displace(loc, dist, yaw);
            loc.setBearing((float)Math.toDegrees(yaw));
            loc.setSpeed((float)Math.abs(speed_trans)); // only pos. speed? maybe.
            loc.setTime(now);
            settings.sendLocation(loc);

            Log.d(TAG, "tdiff " + tdiff + " yaw " + yaw + " dist " + dist + " time " + now + " new location: " + loc);
        }
    }

    // process joystick events
    public class MyJsListener implements JoystickListener {
        protected RunningMan runman;

        public MyJsListener() {
            runman = new RunningMan();
        }

        @Override
        public void onDown() {
            // trigger a location update with zero speed
            runman.stop();
        }

        @Override
        public void onDrag(float degrees, float offset) {
            final double SPEED_ROT_MAX_RADS = Math.toRadians(45.0);  // max. rotational velocity
            final double SPEED_TRANS_MAX = 4.2;                      // 4.2 m/s (15 km/h) max. trans. velocity

            // convert polar to cartesian
            double px = offset * Math.cos(Math.toRadians(degrees));
            double py = offset * Math.sin(Math.toRadians(degrees));

            // set angular and translational velocity proportional to px, py;
            double speed_rot = -px * SPEED_ROT_MAX_RADS;
            double speed_trans = py * SPEED_TRANS_MAX;

            runman.updateVelocity(speed_trans, speed_rot);
        }

        @Override
        public void onUp() {
            // stop movement
            runman.stop();
        }
    }

    public void showJoystick(boolean show) {
        if (show) {
            joystickView.setVisibility(View.VISIBLE);
        } else {
            joystickView.setVisibility(View.GONE);
        }
    }
}
