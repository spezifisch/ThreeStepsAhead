package com.github.spezifisch.threestepsahead;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;

import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;

import java.util.LinkedList;

public class MainActivity extends AppCompatActivity implements ServiceConnection {
    private Settings settings = null;

    private MapViewLoc map;
    private IMapController mapController;

    private ItemizedIconOverlay markersOverlay;
    private OverlayItem markerOverlay;
    private Drawable myMarker;

    private FloatingActionButton fab;

    private JoystickService joystickService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        settings = new Settings(getApplicationContext());

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
        NootLocation loc = settings.getLocation();
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
                settings.updateLocation(location);

                updateMarker(location, false);
            }

        });

        // start/stop button
        fab = (FloatingActionButton)findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean state = !settings.isStarted();
                settings.updateState(state);
                updateState();

                String sstate = state ? "on" : "off";

                Snackbar.make(view, "Location faker now " + sstate, Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });
        updateState();

        // start joystick overlay
        startService(new Intent(this, JoystickService.class));
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

    private void updateMarker(NootLocation loc, boolean center) {
        int lat = (int) (loc.getLatitude() * 1E6);
        int lng = (int) (loc.getLongitude() * 1E6);
        GeoPoint point = new GeoPoint(lat, lng);
        updateMarker(point, center);
    }

    private void updateMarker(Location loc, boolean center) {
        int lat = (int) (loc.getLatitude() * 1E6);
        int lng = (int) (loc.getLongitude() * 1E6);
        GeoPoint point = new GeoPoint(lat, lng);
        updateMarker(point, center);
    }

    private void updateState() {
        int id;
        if (settings.isStarted()) {
            id = getResources().getIdentifier("@android:drawable/ic_media_play", null, null);
        } else {
            id = getResources().getIdentifier("@android:drawable/ic_media_pause", null, null);
        }
        fab.setImageResource(id);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        joystickService = ((JoystickService.JoystickServiceBinder) service).getService();

        if (joystickService != null) {
            unbindService(this);
            joystickService.stopSelf();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        joystickService = null;
    }
}
