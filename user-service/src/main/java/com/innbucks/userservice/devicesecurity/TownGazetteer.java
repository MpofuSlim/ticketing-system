package com.innbucks.userservice.devicesecurity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a rounded position into the nearest town name for "Your devices"
 * ({@code lastSeenNear}, §5.6): a town, never coordinates. A small, reviewable
 * list per market rather than a geocoding service, because this is a label, not
 * a decision — nothing in the risk engine reads it. A position further than
 * {@link #MAX_KM} from every listed town yields null rather than a misleading
 * name.
 */
@Component
public class TownGazetteer {

    static final double MAX_KM = 60;

    private record Town(String name, double lat, double lng) {
    }

    private static final Map<String, List<Town>> TOWNS = Map.of(
            "ZW", List.of(
                    new Town("Harare", -17.829, 31.052),
                    new Town("Chitungwiza", -18.013, 31.076),
                    new Town("Bulawayo", -20.150, 28.583),
                    new Town("Mutare", -18.970, 32.670),
                    new Town("Gweru", -19.450, 29.817),
                    new Town("Kwekwe", -18.928, 29.815),
                    new Town("Kadoma", -18.333, 29.915),
                    new Town("Masvingo", -20.063, 30.828),
                    new Town("Chinhoyi", -17.367, 30.200),
                    new Town("Marondera", -18.185, 31.552),
                    new Town("Norton", -17.883, 30.700),
                    new Town("Chegutu", -18.130, 30.140),
                    new Town("Bindura", -17.301, 31.331),
                    new Town("Beitbridge", -22.217, 30.000),
                    new Town("Hwange", -18.364, 26.498),
                    new Town("Victoria Falls", -17.933, 25.833),
                    new Town("Kariba", -16.517, 28.800),
                    new Town("Zvishavane", -20.326, 30.066),
                    new Town("Rusape", -18.528, 32.128),
                    new Town("Chiredzi", -21.050, 31.667),
                    new Town("Gwanda", -20.933, 29.000),
                    new Town("Karoi", -16.817, 29.683),
                    new Town("Plumtree", -20.483, 27.800)),
            "KE", List.of(
                    new Town("Nairobi", -1.286, 36.817),
                    new Town("Mombasa", -4.043, 39.668),
                    new Town("Kisumu", -0.092, 34.768),
                    new Town("Nakuru", -0.303, 36.080),
                    new Town("Eldoret", 0.514, 35.270),
                    new Town("Thika", -1.033, 37.069),
                    new Town("Machakos", -1.517, 37.263),
                    new Town("Nyeri", -0.420, 36.947),
                    new Town("Meru", 0.047, 37.649),
                    new Town("Kakamega", 0.283, 34.752),
                    new Town("Malindi", -3.217, 40.117),
                    new Town("Garissa", -0.453, 39.646)));

    private final List<Town> towns;

    public TownGazetteer(@Value("${innbucks.country:ZW}") String country) {
        this.towns = TOWNS.getOrDefault(country == null ? "" : country.trim().toUpperCase(Locale.ROOT), List.of());
    }

    /** The nearest listed town within {@link #MAX_KM}, or null. */
    public String nearest(Double lat, Double lng) {
        if (lat == null || lng == null) return null;
        String best = null;
        double bestKm = MAX_KM;
        for (Town t : towns) {
            double d = GeoMath.km(lat, lng, t.lat(), t.lng());
            if (d <= bestKm) {
                bestKm = d;
                best = t.name();
            }
        }
        return best;
    }
}
