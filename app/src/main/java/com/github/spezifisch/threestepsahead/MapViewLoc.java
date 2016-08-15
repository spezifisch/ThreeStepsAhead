package com.github.spezifisch.threestepsahead;

import android.content.Context;
import android.graphics.Canvas;
import android.location.Criteria;
import android.location.Location;
import android.util.AttributeSet;
import android.view.MotionEvent;

import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.util.GeoPoint;

public class MapViewLoc extends MapView {

    private Overlay tapOverlay;
    private OnTapListener onTapListener;

    public MapViewLoc(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        // compatibility with layout preview
        if (!isInEditMode()) {
            super.dispatchDraw(c);
        }
    }

    private void prepareTagOverlay(){

        this.tapOverlay = new Overlay(this.getContext()) {

            @Override
            protected void draw(Canvas c, MapView osmv, boolean shadow) {

            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e, MapView mapView) {

                Projection proj = mapView.getProjection();
                GeoPoint p = (GeoPoint) proj.fromPixels((int) e.getX(), (int) e.getY());
                proj = mapView.getProjection();

                final GeoPoint geoPoint = (GeoPoint) proj.fromPixels((int) e.getX(), (int) e.getY());

                if(MapViewLoc.this.onTapListener != null){

                    MapViewLoc.this.onTapListener.onMapTapped(geoPoint);

                    Location location = new Location("");
                    location.setLatitude((double) geoPoint.getLatitudeE6() / 1000000);
                    location.setLongitude((double) geoPoint.getLongitudeE6() / 1000000);
                    location.setAccuracy(Criteria.ACCURACY_FINE);

                    MapViewLoc.this.onTapListener.onMapTapped(location);
                }

                return true;
            }
        };
    }

    public void addTapListener(OnTapListener onTapListener){

        this.prepareTagOverlay();

        this.getOverlays().add(0, this.tapOverlay);

        this.onTapListener = onTapListener;
    }

    public void removeTapListener(){

        if(this.tapOverlay != null && this.getOverlays().size() > 0){

            this.getOverlays().remove(0);
        }

        this.tapOverlay = null;
        this.onTapListener = null;
    }

    public interface OnTapListener{

        void onMapTapped(GeoPoint geoPoint);

        void onMapTapped(Location location);

    }

}

