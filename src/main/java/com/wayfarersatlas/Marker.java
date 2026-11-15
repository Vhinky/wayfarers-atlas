package com.wayfarersatlas;

public class Marker {
    /** y of a marker placed on an area that was never loaded: the surface is looked up when you teleport. */
    public static final int NO_Y = -9999;

    public String name;
    public String dim;
    public int x, y, z;
    public int color; // 0xRRGGBB (used by the first icons only, see Icons.TINTED)
    public int icon;  // 0..99
    public int iconSet;     // 0 (missing in old files) = the old 50-icon set, 2 = current set
    public transient boolean death; // made by the death page (kept in the Atlas with a "|d" ending)
    public transient boolean hidden; // hidden everywhere, found only in the list (kept with a "|w" ending)
    public transient String serverId; // a marker of the server (/atlas marker): not edited or deleted, only hidden
    public transient String teamUid;  // a marker of your FTB team (kept by the server for the team)
    public transient String folder;   // folder in the list of markers (kept with a "|g:<name>" ending), or null

    public Marker() {}

    public Marker(String name, String dim, int x, int y, int z, int color, int icon) {
        this.name = name;
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.icon = icon;
        this.iconSet = 2;
    }

    /** Not drawn on the maps or in the world: hidden itself, or its folder is hidden. */
    public boolean off() {
        return hidden || folder != null && MarkerStore.folderHidden(folder);
    }

    public String posText() {
        return x + ", " + (y == NO_Y ? "?" : Integer.toString(y)) + ", " + z;
    }
}
