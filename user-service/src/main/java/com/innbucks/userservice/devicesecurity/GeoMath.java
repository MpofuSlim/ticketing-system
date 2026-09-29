package com.innbucks.userservice.devicesecurity;

/** Great-circle distance, for the places model and impossible travel. */
public final class GeoMath {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoMath() {
    }

    public static double km(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Rounds to 3 decimals (~110 m) — the finest position DTX ever keeps (§3 rule 8). */
    public static Double round3(Double v) {
        return v == null ? null : Math.round(v * 1000.0) / 1000.0;
    }
}
