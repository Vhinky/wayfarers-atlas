package com.wayfarersatlas;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Marker sharing format:  atlas|name|dimension|x|y|z|icon|RRGGBB  (plain text, works through chat or clipboard). */
public final class ShareUtil {
    private static final Pattern PATTERN = Pattern.compile(
            "atlas\\|([^|]{1,32})\\|([a-z0-9_.:/\\-]{1,64})\\|(-?\\d{1,9})\\|(-?\\d{1,4})\\|(-?\\d{1,9})\\|(\\d{1,2})\\|([0-9A-Fa-f]{6})");

    public static String encode(Marker m) {
        return "atlas|" + m.name.replace('|', ' ') + "|" + m.dim + "|" + m.x + "|" + m.y + "|" + m.z
                + "|" + m.icon + "|" + String.format("%06X", m.color & 0xFFFFFF);
    }

    public static Matcher find(String text) {
        Matcher m = PATTERN.matcher(text);
        return m.find() ? m : null;
    }

    public static Marker fromMatcher(Matcher m) {
        try {
            return new Marker(m.group(1).trim(), m.group(2), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)), Integer.parseInt(m.group(7), 16), Math.floorMod(Integer.parseInt(m.group(6)), Icons.COUNT));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Adds the marker from a share code to the current world. Returns the marker, or null if the text is not a code. */
    public static Marker importCode(String text) {
        Matcher m = find(text);
        if (m == null) return null;
        Marker mk = fromMatcher(m);
        if (mk == null) return null;
        for (Marker e : MarkerStore.MARKERS) {
            if (e.dim.equals(mk.dim) && e.x == mk.x && e.y == mk.y && e.z == mk.z && e.name.equals(mk.name)) return e;
        }
        MarkerStore.MARKERS.add(mk);
        MarkerStore.save();
        return mk;
    }

    private ShareUtil() {}
}
