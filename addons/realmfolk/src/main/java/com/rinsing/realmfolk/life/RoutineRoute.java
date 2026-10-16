package com.rinsing.realmfolk.life;

import java.util.*;
import java.util.function.Predicate;
import static com.rinsing.realmfolk.model.Society.*;

/** Route daily trips through known building entrances before asking for a distant path. */
public final class RoutineRoute {
    private RoutineRoute() { }

    public static List<Point> waypoints(Collection<Property> properties, String town, Point from,
                                        Point destination, Predicate<Point> usable) {
        Property start = containing(properties, town, from);
        Property finish = containing(properties, town, destination);
        var route = new ArrayList<Point>();
        if (start != finish) {
            add(route, entrance(start, from, destination, usable));
            add(route, entrance(finish, from, destination, usable));
        }
        add(route, destination);
        return List.copyOf(route);
    }

    private static Property containing(Collection<Property> properties, String town, Point point) {
        return properties.stream().filter(p -> town.equals(p.town) && p.bounds != null && p.bounds.contains(point))
                .min(Comparator.comparingLong((Property p) -> p.bounds.volume()).thenComparing(p -> p.id)).orElse(null);
    }

    private static Point entrance(Property property, Point from, Point destination, Predicate<Point> usable) {
        if (property == null || property.entrances == null) return null;
        return property.entrances.stream().filter(Objects::nonNull).filter(usable)
                .min(Comparator.comparingDouble((Point p) -> Math.sqrt(p.distanceSquared(from))
                        + Math.sqrt(p.distanceSquared(destination)))
                        .thenComparingInt(Point::x).thenComparingInt(Point::y).thenComparingInt(Point::z)).orElse(null);
    }

    private static void add(List<Point> route, Point point) {
        if (point != null && (route.isEmpty() || !route.get(route.size() - 1).equals(point))) route.add(point);
    }
}
