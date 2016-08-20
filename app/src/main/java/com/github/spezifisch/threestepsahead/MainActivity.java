package com.github.spezifisch.threestepsahead;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.Space;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;

import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;

import java.util.LinkedList;

public class MainActivity extends AppCompatActivity
        implements IPC.LocationUpdateListener, IPC.StateUpdateListener {
    static final String TAG = "MainActivity";

    private MapViewLoc map;
    private IMapController mapController;

    private ItemizedIconOverlay markersOverlay;
    private OverlayItem markerOverlay;
    private Drawable myMarker;

    private FloatingActionButton fab;

    // IPC to JoystickService
    private SettingsStorage settingsStorage;
    private IPC.SettingsClient settings = new IPC.SettingsClient();
    private IPC.Client serviceClient = new IPC.Client(settings);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settingsStorage = new SettingsStorage(getApplicationContext());
        /*if (!settingsStorage.xposedTest(getApplicationContext())) {
            Log.e(TAG, "xposed not found, bye");
            finish();
            return;
        }*/

        // start joystick overlay
        startService(new Intent(this, JoystickService.class));

        // settings
        settings.setSettingsStorage(settingsStorage);

        // start IPC
        serviceClient.connect(getApplicationContext());
        settings.setTagSuffix("MainActivity");
        settings.setClient(serviceClient);
        settings.setOnLocationUpdateListener(this);
        settings.setOnStateUpdateListener(this);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // OSM map
        map = (MapViewLoc) findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.CYCLEMAP);
        //map.setBuiltInZoomControls(true); // interferes with Snackbar
        map.setMultiTouchControls(true);

        // set zoom
        mapController = map.getController();
        mapController.setZoom(14);

        // set marker
        myMarker = ContextCompat.getDrawable(this, R.drawable.pointer);
        markersOverlay = new ItemizedIconOverlay<>(new LinkedList<OverlayItem>(),
                myMarker, null, getApplicationContext());
        map.getOverlays().add(markersOverlay);

        // marker location from settings
        Location loc = settings.getLocation();
        updateMarker(loc, true);

        // click handler
        map.addTapListener(new MapViewLoc.OnTapListener() {

            @Override
            public void onMapTapped(GeoPoint geoPoint) {}

            @Override
            public void onMapTapped(Location location) {
                Snackbar.make(findViewById(R.id.map),
                        "Latitude: " + location.getLatitude() + " Longitude: " + location.getLongitude(),
                        Snackbar.LENGTH_LONG)
                .setAction("Action", null).show();

                // write to settings for GPSMan
                settings.sendLocation(location);

                updateMarker(location, false);
            }

        });

        // start/stop button
        fab = (FloatingActionButton)findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean state = !settings.isEnabled();
                settings.sendState(state);
                updateState(state);

                String sstate = state ? "on" : "off";

                Snackbar.make(view, "Location faker now " + sstate, Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });
        updateState(settings.isEnabled());

        // warning if Xposed hook failed
        if (!settings.isXposedLoaded()) {
            Log.e(TAG, "Xposed failed!");

            Snackbar.make(findViewById(R.id.map),
                    "Xposed Hooks not found! Did you restart yet?", Snackbar.LENGTH_INDEFINITE)
                    .setAction("Action", null).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void OnLocationUpdate(Location loc) {
        Log.d(TAG, "got location update:" + loc);
        updateMarker(loc, true);

        // TODO throttle
        settings.saveSettings();
    }

    @Override
    public void OnStateUpdate() {
        updateState(settings.isEnabled());

        settings.saveSettings();
    }

    private void updateMarker(GeoPoint pt, boolean center) {
        final String markerTitle = "Fake Location", markerDescription = "You seem to be here.";

        // add marker for new location
        markerOverlay = new OverlayItem(markerTitle, markerDescription, pt);
        markerOverlay.setMarker(myMarker);

        // replace marker. there's no method for updating it directly
        markersOverlay.removeAllItems();
        markersOverlay.addItem(markerOverlay);

        // pan map?
        if (center) {
            mapController.setCenter(pt);
        } else {
            // redraw map to show new marker
            findViewById(R.id.map).invalidate();
        }
    }

    private void updateMarker(Location loc, boolean center) {
        int lat = (int) (loc.getLatitude() * 1E6);
        int lng = (int) (loc.getLongitude() * 1E6);
        GeoPoint point = new GeoPoint(lat, lng);
        updateMarker(point, center);
    }

    private void updateState(boolean enabled) {
        int id;
        if (enabled) {
            id = getResources().getIdentifier("@android:drawable/ic_media_play", null, null);
        } else {
            id = getResources().getIdentifier("@android:drawable/ic_media_pause", null, null);
        }
        fab.setImageResource(id);
    }
}
