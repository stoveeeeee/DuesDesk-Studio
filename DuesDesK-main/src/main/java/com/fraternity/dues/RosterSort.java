package com.fraternity.dues;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/** Builds roster comparators from a sort key: "first", "last", or "<metric>-asc" / "<metric>-desc". */
final class RosterSort {

    private RosterSort() {}

    /** Returns null for an empty or unrecognized key, meaning "keep the stored order". */
    static <T> Comparator<T> comparator(String sort, Function<T, String> name, Map<String, ToDoubleFunction<T>> metrics) {
        if (sort == null || sort.isBlank()) return null;
        Comparator<T> byName;
        if ("first".equals(sort)) {
            byName = Comparator.comparing(item -> firstName(name.apply(item)));
            return byName.thenComparing(item -> lastName(name.apply(item)));
        }
        if ("last".equals(sort)) {
            byName = Comparator.comparing(item -> lastName(name.apply(item)));
            return byName.thenComparing(item -> firstName(name.apply(item)));
        }
        int dash = sort.lastIndexOf('-');
        if (dash < 0) return null;
        ToDoubleFunction<T> metric = metrics.get(sort.substring(0, dash));
        String direction = sort.substring(dash + 1);
        if (metric == null || !(direction.equals("asc") || direction.equals("desc"))) return null;
        Comparator<T> byMetric = Comparator.comparingDouble(metric);
        if (direction.equals("desc")) byMetric = byMetric.reversed();
        return byMetric.thenComparing(item -> lastName(name.apply(item)));
    }

    private static String parts(String name, boolean last) {
        String trimmed = name == null ? "" : name.trim();
        int space = trimmed.lastIndexOf(' ');
        if (space < 0) return trimmed.toLowerCase(Locale.ROOT);
        return (last ? trimmed.substring(space + 1) : trimmed.substring(0, trimmed.indexOf(' ')))
                .toLowerCase(Locale.ROOT);
    }

    static String firstName(String name) { return parts(name, false); }

    static String lastName(String name) { return parts(name, true); }
}
