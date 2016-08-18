package com.github.spezifisch.threestepsahead;

import android.location.Location;
import android.os.Parcel;
import android.os.Parcelable;

public class NootLocation extends Location {
    public NootLocation(String s) {
        super(s);
    }

    private NootLocation(Parcel in) {
        super("");
    }

    public static final Parcelable.Creator<NootLocation> CREATOR = // not useful but needed
            new Parcelable.Creator<NootLocation>() {
                @Override
                public NootLocation createFromParcel(Parcel in) {
                    return new NootLocation(in);
                }

                @Override
                public NootLocation[] newArray(int size) {
                    return new NootLocation[size];
                }
            };

    public void displace(double distance, double theta) {
        double delta = distance / 6371e3;
        double lat1 = Math.toRadians(getLatitude());
        double lng1 = Math.toRadians(getLongitude());

        double lat2 = Math.asin( Math.sin(lat1) * Math.cos(delta) +
                Math.cos(lat1) * Math.sin(delta) * Math.cos(theta) );

        double lng2 = lng1 + Math.atan2( Math.sin(theta) * Math.sin(delta) * Math.cos(lat1),
                Math.cos(delta) - Math.sin(lat1) * Math.sin(lat2));

        lng2 = (lng2 + 3 * Math.PI) % (2 * Math.PI) - Math.PI;

        setLatitude(Math.toDegrees(lat2));
        setLongitude(Math.toDegrees(lng2));
    }
}
