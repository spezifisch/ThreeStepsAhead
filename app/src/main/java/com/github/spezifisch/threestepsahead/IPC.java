package com.github.spezifisch.threestepsahead;

import android.app.AndroidAppHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import java.util.HashSet;

import de.robv.android.xposed.XposedBridge;

public class IPC {
    private static final String THIS_APP = "com.github.spezifisch.threestepsahead";
    private static final boolean DEBUG = false;

    static public class msg {
        // internal
        static final int CONNECTED = 1;

        // setter
        static final int SETTER = 100; // invalid
        static final int SET_POS = 101;
        static final int SET_STATE = 102;
        static final int SET_TLE = 103;

        // getter
        static final int GETTER = 200; // invalid
        static final int GET_TLE = 201;
        static final int GET_POS = 202;
    }

    // TODO combine Client and SettingsClient?
    static public class Client implements ServiceConnection {
        private boolean inXposed = false;
        private boolean connected = false;
        private Messenger messenger;
        private Messenger service;
        private Handler incomingHandler;

        public Client(Handler h) {
            incomingHandler = h;
        }

        public Messenger getService() {
            return service;
        }

        public Messenger getMessenger() {
            return messenger;
        }

        // log to Xposed or Android facility
        protected void log(String s) {
            if (inXposed) {
                XposedBridge.log(s);
            } else {
                Log.d("Client", s);
            }
        }

        public void setInXposed(boolean state) {
            inXposed = state;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            log("Service connected!");
            this.service = new Messenger(service);
            Message message = Message.obtain(null, msg.CONNECTED, 0, 0);
            message.replyTo = messenger;
            try {
                this.service.send(message);
            } catch (RemoteException e) {
            }
            connected = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            connected = false;
        }

        public boolean isConnected() {
            return connected;
        }

        public boolean connect() {
            return connect(null);
        }

        public boolean connect(Context context) {
            if (isConnected()) {
                return true;
            }
            ComponentName cn;
            if (context == null) {
                // only valid from Xposed context in hooked app
                context = AndroidAppHelper.currentApplication();
                cn = new ComponentName(THIS_APP, THIS_APP + ".JoystickService");
            } else {
                cn = new ComponentName(context, JoystickService.class);
            }

            if (context != null) {
                if (messenger == null) {
                    messenger = new Messenger(incomingHandler);
                }

                Intent intent = new Intent();
                intent.setComponent(cn);
                if (!context.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                    Toast.makeText(AndroidAppHelper.currentApplication(), "Unable to start service! Did you reboot after updating?", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        }
    }

    public interface LocationUpdateListener {
        void OnLocationUpdate(Location loc);
    }

    public interface StateUpdateListener {
        void OnStateUpdate();
    }

    public interface TLEUpdateListener {
        void OnTLEUpdate();
    }

    static public class SettingsClient extends Handler {
        private String BASE_TAG = "SettingsClient", TAG = BASE_TAG;
        private boolean inXposed = false;

        // IPC.Client object for communication with the Service
        protected Client client;

        // List of connected clients (for Service)
        protected HashSet<Messenger> clients;
        protected boolean isMaster = false;

        // callbacks for user class
        protected LocationUpdateListener locationUpdateListener;
        protected StateUpdateListener stateUpdateListener;
        protected TLEUpdateListener tleUpdateListener;

        // settings file storage
        protected SettingsStorage settingsStorage;

        // settings
        private Location loc;
        private boolean enabled;
        private String tle = "";

        public SettingsClient() {
            loc = new Location("");
            enabled = false;
            clients = new HashSet<>();
        }

        public void setTagSuffix(String tag) {
            TAG = BASE_TAG + tag;
        }

        // log to Xposed or Android facility
        protected void log(String s) {
            if (inXposed) {
                XposedBridge.log(s);
            } else {
                Log.d(TAG, s);
            }
        }

        public void setInXposed(boolean state) {
            inXposed = state;
        }

        // set Client whose Messenger is used to communicate with Master
        public void setClient(Client c) {
            client = c;
        }

        // master has the duty to relay messages to other clients
        public void setMaster(boolean state) {
            isMaster = state;
        }

        // load settings from file
        public void setSettingsStorage(SettingsStorage ss) {
            settingsStorage = ss;

            // load settings from file
            Location sl = settingsStorage.getLocation();
            if (sl != null) {
                loc.set(sl);
            }
            enabled = settingsStorage.isEnabled();
        }

        public void saveSettings() {
            settingsStorage.saveLocation(loc);
            settingsStorage.saveState(enabled);
        }

        public boolean isXposedLoaded() {
            return settingsStorage.isXposedLoaded();
        }

        // callbacks
        public void setOnLocationUpdateListener(LocationUpdateListener lul) {
            locationUpdateListener = lul;
        }

        public void setOnStateUpdateListener(StateUpdateListener sul) {
            stateUpdateListener = sul;
        }

        public void setOnTLEUpdateListener(TLEUpdateListener tul) {
            tleUpdateListener = tul;
        }

        // settings
        public Location getLocation() {
            return loc;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setTLE(String t) {
            tle = t;
        }

        public String getTLE() {
            return tle;
        }

        // IPC receiver
        @Override
        public void handleMessage(Message message) {
            try {
                if (DEBUG) {
                    log("js binder msg " + message);
                }
                Bundle bundle = message.getData();
                boolean self_message = false;

                if (bundle != null) {
                    // relay SET messages to clients
                    if (isMaster && message.what > msg.SETTER && message.what < msg.GETTER) {
                        for (Messenger c : clients) {
                            if (c == client.getMessenger()) {
                                // it's me
                                continue;
                            }

                            if (DEBUG) {
                                log("relay to client " + c);
                            }

                            // build new message with same data. can't reuse same message object
                            Message mnew = Message.obtain(null, message.what);
                            mnew.setData(bundle);
                            send(c, mnew);
                        }
                    }

                    // don't process message from myself
                    if (bundle.getString("source", "").equals(TAG)) {
                        self_message = true;
                    }
                }

                switch (message.what) {
                    case msg.CONNECTED:
                        if (client == null || message.replyTo != client.getMessenger()) {
                            log("Client connected: " + message.replyTo);
                            clients.add(message.replyTo);
                        } else {
                            log("Master connected: " + message.replyTo);
                        }
                        break;

                    case msg.SET_POS:
                        if (bundle == null) {
                            return;
                        }
                        loc.setLatitude(bundle.getDouble("latitude", 0));
                        loc.setLongitude(bundle.getDouble("longitude", 0));
                        loc.setAltitude(bundle.getDouble("altitude", 0));
                        loc.setBearing(bundle.getFloat("bearing", 0));
                        loc.setSpeed(bundle.getFloat("speed", 0));
                        loc.setAccuracy(bundle.getFloat("accuracy", 0));

                        // call back
                        if (locationUpdateListener != null && !self_message) {
                            locationUpdateListener.OnLocationUpdate(loc);
                        }
                        break;

                    case msg.SET_STATE:
                        if (bundle == null) {
                            return;
                        }
                        enabled = bundle.getBoolean("enabled", true);

                        // call back
                        if (stateUpdateListener != null && !self_message) {
                            stateUpdateListener.OnStateUpdate();
                        }
                        break;

                    case msg.SET_TLE:
                        log("got TLE update");
                        if (bundle == null) {
                            return;
                        }
                        tle = bundle.getString("tle", "");

                        // call back
                        if (tleUpdateListener != null && !self_message) {
                            tleUpdateListener.OnTLEUpdate();
                        }
                        break;

                    case msg.GET_TLE:
                        if (isMaster) {
                            log("master responding to GET_TLE");
                            sendTLE(message.replyTo);
                        }
                        break;

                    case msg.GET_POS:
                        if (isMaster) {
                            log("master responding to GET_POS");
                            if (loc != null) {
                                sendLocation(message.replyTo, loc);
                            } else {
                                log("loc not set yet");
                            }
                        }
                        break;

                    default:
                        super.handleMessage(message);
                }
            } catch (Throwable e) {
                log("handleMessage failed!");
                e.printStackTrace();
            }
        }

        // IPC sender
        protected boolean send(Messenger m, Message message) {
            try {
                m.send(message);
            } catch (RemoteException e) {
                log("IPC send exception");
                e.printStackTrace();
                return false;
            }

            return true;
        }

        protected boolean send(Message message) {
            // send to remote service if configured
            if (client == null) {
                log("send: no remote set");
                return false;
            }

            return send(client.getService(), message);
        }

        public boolean sendLocation(Location location) {
            if (client == null) {
                log("send: no remote set");
                return false;
            }

            return sendLocation(client.getService(), location);
        }

        public boolean sendLocation(Messenger messenger, Location location) {
            Message message = Message.obtain(null, msg.SET_POS);
            Bundle bundle = new Bundle();
            bundle.putString("source", TAG);
            bundle.putDouble("latitude", location.getLatitude());
            bundle.putDouble("longitude", location.getLongitude());
            bundle.putDouble("altitude", location.getAltitude());
            bundle.putFloat("bearing", location.getBearing());
            bundle.putFloat("speed", location.getSpeed());
            bundle.putFloat("accuracy", location.getAccuracy());
            message.setData(bundle);

            if (DEBUG) {
                log("sendLocation: " + location);
            }
            return send(messenger, message);
        }

        public boolean sendState(boolean enabled) {
            Message message = Message.obtain(null, msg.SET_STATE);
            Bundle bundle = new Bundle();
            bundle.putString("source", TAG);
            bundle.putBoolean("enabled", enabled);
            message.setData(bundle);

            log("sendState: " + enabled);
            return send(message);
        }

        public boolean sendTLE(Messenger m) {
            Message message = Message.obtain(null, msg.SET_TLE);
            Bundle bundle = new Bundle();
            bundle.putString("source", TAG);
            bundle.putString("tle", tle);
            message.setData(bundle);

            log("sendTLE: ...");
            return send(m, message);
        }

        public boolean requestTLE() {
            Message message = Message.obtain(null, msg.GET_TLE);
            Bundle bundle = new Bundle();
            bundle.putString("source", TAG);
            message.setData(bundle);
            message.replyTo = client.getMessenger();

            log("requestTLE: ...");
            return send(message);
        }

        public boolean requestLocation() {
            if (!client.isConnected()) {
                log("requestLocation: client not yet connected to master");
                return false;
            }

            Message message = Message.obtain(null, msg.GET_POS);
            Bundle bundle = new Bundle();
            bundle.putString("source", TAG);
            message.setData(bundle);
            message.replyTo = client.getMessenger();

            log("requestLocation: ...");
            return send(message);
        }
    }
}
