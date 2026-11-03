package com.tvhome.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** All user-facing options. Every option is a small integer choice. */
final class Cfg {
    static final class Opt {
        final String key, label;
        final int def;
        final String[] values;

        Opt(String key, String label, int def, String... values) {
            this.key = key;
            this.label = label;
            this.def = def;
            this.values = values;
        }
    }

    static final Opt PRESET = new Opt("dock_preset", "Appearance preset", 0, "Midnight Glass", "Frost", "Graphite", "Aurora");
    static final Opt POS = new Opt("dock_pos", "Dock position", 1, "Low", "Standard", "High");
    static final Opt ALPHA = new Opt("dock_alpha", "Transparency", 2, "Clear", "Light", "Medium", "Dense");
    static final Opt BLUR = new Opt("dock_blur", "Blur intensity", 2, "Off", "Light", "Medium", "Strong");
    static final Opt SIZE = new Opt("dock_size", "Dock size", 1, "Small", "Medium", "Large");
    static final Opt STYLE = new Opt("dock_style", "Dock style", 1, "Compact", "Expanded");
    static final Opt MAGNIFY = new Opt("dock_magnify", "Icon magnification", 1, "Off", "On");
    static final Opt SPACING = new Opt("dock_spacing", "Icon spacing", 1, "Tight", "Normal", "Wide");
    static final Opt LABELS = new Opt("dock_labels", "App labels", 1, "Off", "On");
    static final Opt ROW = new Opt("row_mode", "Content row", 0, "Recent apps", "Suggested apps", "Off");
    static final Opt FX = new Opt("fx_reduced", "Reduced effects (low-power TVs)", 0, "Off", "On");

    static final Opt ROTATE = new Opt("wp_rotate", "Auto-rotate wallpapers", 1, "Off", "On");
    static final Opt INTERVAL = new Opt("wp_interval", "Rotation interval", 2, "1 minute", "5 minutes", "15 minutes", "30 minutes", "1 hour");
    static final Opt FAVONLY = new Opt("wp_favonly", "Rotate through", 0, "All enabled categories", "Favorites only");
    static final Opt DIM = new Opt("wp_dim", "Wallpaper brightness", 1, "Bright", "Balanced", "Dim");
    static final Opt MOTION = new Opt("wp_motion", "Ambient motion", 1, "Off", "On");
    static final Opt SAVER = new Opt("saver_delay", "Screensaver after", 2, "Off", "2 minutes", "5 minutes", "10 minutes", "20 minutes");

    static final Opt[] DOCK_OPTS = {PRESET, POS, ALPHA, BLUR, SIZE, STYLE, MAGNIFY, SPACING, LABELS, ROW, FX};
    static final Opt[] WALL_OPTS = {ROTATE, INTERVAL, FAVONLY, DIM, MOTION, SAVER};

    static final int[] TINT = {0xFF0A0F1E, 0xFFDCE6F7, 0xFF17181B, 0xFF10243F};
    static final int[] ACCENT = {0xFFBFD4FF, 0xFFFFFFFF, 0xFFE6E6E6, 0xFF7FE3D6};
    static final int[] ALPHA_V = {0x2C, 0x4C, 0x6E, 0x96};
    private static final long[] INTERVALS = {60_000L, 300_000L, 900_000L, 1_800_000L, 3_600_000L};
    private static final long[] SAVERS = {0L, 120_000L, 300_000L, 600_000L, 1_200_000L};
    private static final float[] DIMS = {0.10f, 0.22f, 0.36f};

    final SharedPreferences sp;

    Cfg(Context c) {
        sp = c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    int get(Opt o) {
        int v = sp.getInt(o.key, o.def);
        return Math.max(0, Math.min(o.values.length - 1, v));
    }

    void set(Opt o, int v) {
        sp.edit().putInt(o.key, v).apply();
    }

    boolean on(Opt o) {
        return get(o) == 1;
    }

    boolean reduced() {
        return on(FX);
    }

    long rotateMs() {
        return INTERVALS[get(INTERVAL)];
    }

    long saverMs() {
        return SAVERS[get(SAVER)];
    }

    float dim() {
        return DIMS[get(DIM)];
    }

    int tint() {
        return TINT[get(PRESET)];
    }

    int accent() {
        return ACCENT[get(PRESET)];
    }

    boolean lightPreset() {
        return get(PRESET) == 1;
    }
}
