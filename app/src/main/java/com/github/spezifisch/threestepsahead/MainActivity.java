package com.github.spezifisch.threestepsahead;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;

import java.util.LinkedList;

public class MainActivity extends AppCompatActivity
        implements IPC.LocationUpdateListener, IPC.StateUpdateListener,
        NavigationView.OnNavigationItemSelectedListener {
    static final String TAG = "MainActivity";

    private Menu menu;
    private MapViewLoc map;
    private IMapController mapController;

    private ItemizedIconOverlay markersOverlay;
    private OverlayItem markerOverlay;
    private Drawable myMarker;

    // IPC to JoystickService
    private SettingsStorage settingsStorage;
    private IPC.SettingsClient settings = new IPC.SettingsClient();
    private IPC.Client serviceClient = new IPC.Client(settings);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // start joystick overlay
        startService(new Intent(this, JoystickService.class));

        // settings
        settingsStorage = SettingsStorage.getSettingsStorage(getApplicationContext());
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

        // navigation drawer
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.setDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        // warning if Xposed hook failed
        if (!settings.isXposedLoaded()) {
            Log.e(TAG, "Xposed failed!");

            Snackbar.make(findViewById(R.id.map),
                    "Xposed Hooks not found! Did you restart yet?", Snackbar.LENGTH_INDEFINITE)
                    .setAction("Action", null).show();
        }

        // start/stop button
        updateState(settings.isEnabled());
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        this.menu = menu;
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_startstop) {
            // toggle location spoofer
            boolean state = !settings.isEnabled();
            settings.sendState(state);
            updateState(state);

            String sstate = state ? "on" : "off";

            Snackbar.make(findViewById(R.id.map), "Location Spoofer now " + sstate, Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show();
            return true;
        } else if (id == R.id.action_joystick) {
            // toggle joystick
            boolean state = !settingsStorage.isJoystickEnabled();
            JoystickService.get().showJoystick(state);

            try {
                settingsStorage.put("show_joystick", state);
            } catch (Throwable e) {
            }
        }

        return super.onOptionsItemSelected(item);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        /*if (id == R.id.nav_camera) {
            // Handle the camera action
        } else if (id == R.id.nav_gallery) {

        } else if (id == R.id.nav_slideshow) {

        } else if (id == R.id.nav_manage) {

        } else if (id == R.id.nav_share) {

        } else if (id == R.id.nav_send) {

        }*/

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
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
        if (menu == null) {
            return;
        }

        int id;
        if (enabled) {
            if (settings.isXposedLoaded()) {
                id = R.string.service_started;
            } else {
                id = R.string.service_failed;
            }
        } else {
            id = R.string.service_stopped;
        }
        menu.findItem(R.id.action_startstop).setTitle(getString(id));
    }
}
