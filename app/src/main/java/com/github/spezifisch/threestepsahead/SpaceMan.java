package com.github.spezifisch.threestepsahead;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;

import de.robv.android.xposed.XposedBridge;
import uk.me.g4dpz.satellite.GroundStationPosition;
import uk.me.g4dpz.satellite.SatPos;
import uk.me.g4dpz.satellite.Satellite;
import uk.me.g4dpz.satellite.SatelliteFactory;
import uk.me.g4dpz.satellite.TLE;

public class SpaceMan {
    private static final String TAG = "SpaceMan";

    // time for calculation
    protected Date now;
    // our location
    protected Location calculatedLocation;
    protected GroundStationPosition groundStationPosition;

    // satellite data cache (shared)
    public class SatelliteInfo {
        public TLE tle;
        public Satellite satellite;
        public SatPos satPos;
    }
    protected ArrayList<SatelliteInfo> sats = new ArrayList<>();

    // parsed data for GpsSatellite list in GpsStatus
    public class MyGpsSatellite {
        public float azimuth, elevation; // deg
        public int prn;
        public float snr;
        boolean hasAlmanac, hasEphemeris, usedInFix;

        @Override
        public String toString() {
            return "MyGpsSatellite PRN " + prn + " SNR " + snr +
                    " Azi " + azimuth + " Ele " + elevation +
                    " ALM " + hasAlmanac + " EPH " + hasEphemeris + " USE " + usedInFix;
        }
    }
    protected ArrayList<MyGpsSatellite> gpsSatellites = new ArrayList<>();

    public SpaceMan() {
        setNow();
    }

    public SpaceMan(String tles, Location loc) {
        setNow();
        setGroundStationPosition(loc);

        parseTLE(tles);

        calculatePositions();

        dumpSatelliteInfo();
        dumpGpsSatellites();
    }

    public void setNow() {
        setNow(System.currentTimeMillis());
    }

    public void setNow(long time) {
        now = new Date(time);
    }

    public long getNow() {
        return now.getTime();
    }

    public void setGroundStationPosition(Location loc) {
        groundStationPosition = new GroundStationPosition(loc.getLatitude(), loc.getLongitude(), loc.getAltitude());
        calculatedLocation = loc;
    }

    public Location getCalculatedLocation() {
        return calculatedLocation;
    }

    public ArrayList<MyGpsSatellite> getGpsSatellites() {
        return gpsSatellites;
    }

    public int getTLECount() {
        return sats.size();
    }

    static public String readTest1(Context context) {
        // src: http://celestrak.com/NORAD/elements/gps-ops.txt
        InputStream is = context.getResources().openRawResource(R.raw.gps_ops_test1);
        return readFile(is);
    }

    static public String readTest2() {
        String s = "GPS BIIR-2  (PRN 13)    \n" +
                "1 24876U 97035A   16231.42778959  .00000000  00000-0  10000-3 0  9999\n" +
                "2 24876  55.6359 239.0367 0038451 115.0383 245.4021  2.00563122139758";
        return s;
    }

    static public String readFile(InputStream is) {
        StringBuilder fileContent = new StringBuilder("");

        byte[] buffer = new byte[1024];
        int n;
        try {
            while ((n = is.read(buffer)) != -1) {
                fileContent.append(new String(buffer, 0, n));
            }
        } catch (IOException e) {
        }

        return fileContent.toString();
    }

    public void parseTLE(String tles) {
        parseTLE(tles.split("\n"));
    }

    public void parseTLE(String[] tles) {
        sats.clear();

        for (int i = 0; (i+2) < tles.length; i += 3) {
            String[] tlein = new String[3];
            tlein[0] = tles[i];
            tlein[1] = tles[i + 1];
            tlein[2] = tles[i + 2];

            SatelliteInfo si = new SatelliteInfo();
            si.tle = new TLE(tlein);
            si.satellite = SatelliteFactory.createSatellite(si.tle);
            sats.add(si);
        }
    }

    public void calculatePositions() {
        gpsSatellites.clear();

        for (SatelliteInfo si: sats) {
            si.satPos = si.satellite.getPosition(groundStationPosition, now);

            // add visible sats to MyGpsSatellite list
            if (si.satPos.isAboveHorizon()) {
                MyGpsSatellite gs = new MyGpsSatellite();
                gs.azimuth = (float)Math.toDegrees(si.satPos.getAzimuth());
                gs.elevation = (float)Math.toDegrees(si.satPos.getElevation());
                gs.prn = Integer.valueOf(si.tle.getName().split("\\(PRN ")[1].split("\\)")[0]);

                // guessed range, the higher above us the better
                gs.snr = Math.round(20.0f + 70.0f * gs.elevation/90.0f);

                // some quick decisions
                gs.hasAlmanac = (gs.elevation > 20.0);
                gs.hasEphemeris = (gs.elevation > 10.0);
                gs.usedInFix = (gs.elevation > 10.0);

                // unlikely to see lower satellites
                if (gs.elevation > 10.0) {
                    gpsSatellites.add(gs);
                }
            }
        }
    }

    public void dumpSatelliteInfo() {
        for (SatelliteInfo si: sats) {
            log("SAT " + si.tle.getName() + " above_horizon " + si.satPos.isAboveHorizon());
            log("azi " + si.satPos.getAzimuth() + " ele " + si.satPos.getElevation() +
                    "lon " + si.satPos.getLongitude() + " lat " + si.satPos.getLatitude() + " alt " + si.satPos.getAltitude());
        }
    }

    public void dumpGpsSatellites() {
        log("Fix: " + gpsSatellites.size() + " sats");
        for (MyGpsSatellite gs : gpsSatellites) {
            log(gs.toString());
        }
    }

    // TODO get rid of this
    public boolean inXposed = false;
    protected void log(String s) {
        if (inXposed) {
            XposedBridge.log(s);
        } else {
            Log.d(TAG, s);
        }
    }
}
