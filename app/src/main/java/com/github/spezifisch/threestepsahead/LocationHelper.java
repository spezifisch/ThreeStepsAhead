package com.github.spezifisch.threestepsahead;

import android.location.Location;

public class LocationHelper {
    public static Location displace(Location loc, double distance, double theta) {
        double delta = distance / 6371e3;
        double lat1 = Math.toRadians(loc.getLatitude());
        double lng1 = Math.toRadians(loc.getLongitude());

        double lat2 = Math.asin( Math.sin(lat1) * Math.cos(delta) +
                Math.cos(lat1) * Math.sin(delta) * Math.cos(theta) );

        double lng2 = lng1 + Math.atan2( Math.sin(theta) * Math.sin(delta) * Math.cos(lat1),
                Math.cos(delta) - Math.sin(lat1) * Math.sin(lat2));

        lng2 = (lng2 + 3 * Math.PI) % (2 * Math.PI) - Math.PI;

        loc.setLatitude(Math.toDegrees(lat2));
        loc.setLongitude(Math.toDegrees(lng2));
        return loc;
    }
}
