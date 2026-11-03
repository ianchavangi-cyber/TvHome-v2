package com.tvhome.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.*;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;

import java.util.*;

/** Installed apps, pins, hidden apps, launch history and icon rendering. */
final class AppRepo {
    static final String ALL = "tvhome:all", SETTINGS = "tvhome:settings";
    static final int MAX_PINS = 10;
    static final int ICON_PX = 256, CARD_W = 420, CARD_H = 236;

    static final class App {
        String pkg, label;
        boolean virtual;
        Intent intent;
        ResolveInfo ri;
        Bitmap icon, reflect, card;
        int color = 0xFF9AA4B2;
    }

    private final Context ctx;
    private final PackageManager pm;
    private final SharedPreferences sp;
    private final String[] defaultDock;
    final Map<String, App> byPkg = new LinkedHashMap<>();
    final List<App> all = new ArrayList<>();
    final App settingsApp = new App(), allApp = new App();

    AppRepo(Context ctx, Cfg cfg, String[] defaultDock) {
        this.ctx = ctx;
        this.pm = ctx.getPackageManager();
        this.sp = cfg.sp;
        this.defaultDock = defaultDock;
        settingsApp.pkg = SETTINGS;
        settingsApp.label = "Settings";
        settingsApp.virtual = true;
        settingsApp.intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        allApp.pkg = ALL;
        allApp.label = "All Apps";
        allApp.virtual = true;
    }

    void load() {
        byPkg.clear();
        all.clear();
        List<ResolveInfo> list = new ArrayList<>();
        list.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        list.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        for (ResolveInfo ri : list) {
            String p = ri.activityInfo.packageName;
            if (p.equals(ctx.getPackageName()) || byPkg.containsKey(p)) continue;
            App a = new App();
            a.pkg = p;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.ri = ri;
            a.intent = new Intent(Intent.ACTION_MAIN).setClassName(p, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            byPkg.put(p, a);
            all.add(a);
        }
        Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    // ---------------------------------------------------------------- pins / hidden

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split("\n")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private static String join(Collection<String> c) {
        StringBuilder sb = new StringBuilder();
        for (String s : c) sb.append(s).append('\n');
        return sb.toString();
    }

    private String defaultBrowser() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE);
            ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                return ri.activityInfo.packageName;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private List<String> pinList() {
        String s = sp.getString("pins", null);
        if (s == null) {
            List<String> d = new ArrayList<>();
            for (String p : defaultDock) if (byPkg.containsKey(p) && !d.contains(p)) d.add(p);
            String b = defaultBrowser();
            if (b != null && byPkg.containsKey(b) && !d.contains(b)) d.add(b);
            d.add(SETTINGS);
            while (d.size() > MAX_PINS) d.remove(d.size() - 2);
            sp.edit().putString("pins", join(d)).apply();
            return d;
        }
        return split(s);
    }

    List<App> pinnedApps() {
        List<App> out = new ArrayList<>();
        for (String p : pinList()) {
            App a = p.equals(SETTINGS) ? settingsApp : byPkg.get(p);
            if (a != null && !isHidden(p)) out.add(a);
        }
        return out;
    }

    void savePins(List<String> pkgs) {
        sp.edit().putString("pins", join(pkgs)).apply();
    }

    boolean isPinned(String pkg) {
        return pinList().contains(pkg);
    }

    /** @return false if the dock is full */
    boolean pin(String pkg) {
        List<String> l = pinList();
        if (l.contains(pkg)) return true;
        if (l.size() >= MAX_PINS) return false;
        l.add(pkg);
        savePins(l);
        return true;
    }

    void unpin(String pkg) {
        List<String> l = pinList();
        l.remove(pkg);
        savePins(l);
    }

    Set<String> hiddenSet() {
        return new HashSet<>(split(sp.getString("hidden", "")));
    }

    boolean isHidden(String pkg) {
        return hiddenSet().contains(pkg);
    }

    void setHidden(String pkg, boolean hide) {
        Set<String> h = hiddenSet();
        if (hide) {
            h.add(pkg);
            unpin(pkg);
        } else h.remove(pkg);
        sp.edit().putString("hidden", join(h)).apply();
    }

    // ---------------------------------------------------------------- history

    void recordLaunch(App a) {
        if (a.virtual) return;
        sp.edit().putInt("lc_" + a.pkg, sp.getInt("lc_" + a.pkg, 0) + 1)
                .putLong("lt_" + a.pkg, System.currentTimeMillis()).apply();
    }

    List<App> recent(int n) {
        final Set<String> hid = hiddenSet();
        List<App> l = new ArrayList<>();
        for (App a : all) if (!hid.contains(a.pkg) && sp.getLong("lt_" + a.pkg, 0) > 0) l.add(a);
        Collections.sort(l, (x, y) -> Long.compare(sp.getLong("lt_" + y.pkg, 0), sp.getLong("lt_" + x.pkg, 0)));
        return l.size() > n ? new ArrayList<>(l.subList(0, n)) : l;
    }

    List<App> suggested(int n) {
        final Set<String> hid = hiddenSet();
        final List<String> pins = pinList();
        List<App> l = new ArrayList<>();
        for (App a : all) if (!hid.contains(a.pkg) && !pins.contains(a.pkg)) l.add(a);
        // most launched first; never-launched apps keep alphabetical order
        Collections.sort(l, (x, y) -> Integer.compare(sp.getInt("lc_" + y.pkg, 0), sp.getInt("lc_" + x.pkg, 0)));
        return l.size() > n ? new ArrayList<>(l.subList(0, n)) : l;
    }

    // ---------------------------------------------------------------- visuals

    private static Path squircle(float s) {
        Path p = new Path();
        p.addRoundRect(new RectF(0, 0, s, s), s * 0.23f, s * 0.23f, Path.Direction.CW);
        return p;
    }

    void ensureIcon(App a) {
        if (a.icon != null) return;
        int px = ICON_PX;
        Bitmap bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        if (a.virtual) {
            drawGlyph(c, px, a == allApp ? 1 : 0);
        } else {
            Drawable d = null;
            try {
                d = a.ri.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            if (d == null) d = pm.getDefaultActivityIcon();
            c.save();
            c.clipPath(squircle(px));
            if (d instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
                int q = px / 4;
                if (ad.getBackground() != null) {
                    ad.getBackground().setBounds(-q, -q, px + q, px + q);
                    ad.getBackground().draw(c);
                }
                if (ad.getForeground() != null) {
                    ad.getForeground().setBounds(-q, -q, px + q, px + q);
                    ad.getForeground().draw(c);
                }
            } else {
                int iw = Math.max(1, d.getIntrinsicWidth()), ih = Math.max(1, d.getIntrinsicHeight());
                float ratio = iw / (float) ih;
                if (Math.abs(ratio - 1f) > 0.2f) {
                    c.drawColor(0xFF1E2025);
                    float w = px * 0.86f, h = w / ratio;
                    if (h > px * 0.86f) {
                        h = px * 0.86f;
                        w = h * ratio;
                    }
                    d.setBounds((int) ((px - w) / 2), (int) ((px - h) / 2), (int) ((px + w) / 2), (int) ((px + h) / 2));
                } else {
                    d.setBounds(0, 0, px, px);
                }
                d.draw(c);
            }
            c.restore();
        }
        a.icon = bm;
        a.color = sampleColor(bm);
        // reflection: bottom 30% flipped, faded out
        int rh = (int) (px * 0.30f);
        Bitmap ref = Bitmap.createBitmap(px, rh, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(ref);
        Matrix m = new Matrix();
        m.setScale(1f, -1f);
        m.postTranslate(0, px);
        rc.save();
        rc.clipRect(0, 0, px, rh);
        rc.drawBitmap(bm, m, null);
        rc.restore();
        Paint mask = new Paint();
        mask.setShader(new LinearGradient(0, 0, 0, rh, 0x99FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        rc.drawRect(0, 0, px, rh, mask);
        a.reflect = ref;
    }

    void ensureCard(App a) {
        if (a.card != null) return;
        ensureIcon(a);
        Bitmap bm = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, CARD_W, CARD_H), 30, 30, Path.Direction.CW);
        c.clipPath(clip);
        Drawable banner = null;
        if (a.ri != null) {
            try {
                banner = a.ri.activityInfo.loadBanner(pm);
            } catch (Throwable ignored) {
            }
        }
        if (banner != null) {
            banner.setBounds(0, 0, CARD_W, CARD_H);
            banner.draw(c);
        } else {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, CARD_W, CARD_H, 0xFF2A2D35, 0xFF15171B, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, CARD_W, CARD_H, p);
            p.setShader(new RadialGradient(CARD_W / 2f, CARD_H / 2f, CARD_W * 0.45f, (a.color & 0x00FFFFFF) | 0x55000000, 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, CARD_W, CARD_H, p);
            int s = (int) (CARD_H * 0.62f);
            c.drawBitmap(a.icon, null, new Rect((CARD_W - s) / 2, (CARD_H - s) / 2, (CARD_W + s) / 2, (CARD_H + s) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        a.card = bm;
    }

    private static int sampleColor(Bitmap bm) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(bm, 8, 8, true);
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) {
                    int p = s.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    n++;
                }
            if (n == 0) return 0xFF9AA4B2;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
            hsv[2] = Math.max(hsv[2], 0.78f);
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return 0xFF9AA4B2;
        }
    }

    /** kind 0 = gear, 1 = grid */
    private static void drawGlyph(Canvas c, int s, int kind) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, s, 0xFF3A3F4A, 0xFF181A1F, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(0, 0, s, s), s * 0.23f, s * 0.23f, p);
        p.setShader(null);
        p.setColor(0xEBFFFFFF);
        float cx = s / 2f, cy = s / 2f;
        if (kind == 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.085f);
            c.drawCircle(cx, cy, s * 0.17f, p);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 8; i++) {
                c.save();
                c.rotate(i * 45f, cx, cy);
                c.drawRoundRect(new RectF(cx - s * 0.05f, cy - s * 0.31f, cx + s * 0.05f, cy - s * 0.19f), s * 0.02f, s * 0.02f, p);
                c.restore();
            }
        } else {
            float cell = s * 0.14f, gap = s * 0.06f, start = cx - (cell * 3 + gap * 2) / 2f;
            for (int r = 0; r < 3; r++)
                for (int q = 0; q < 3; q++) {
                    float x = start + q * (cell + gap), y = start + r * (cell + gap);
                    c.drawRoundRect(new RectF(x, y, x + cell, y + cell), cell * 0.28f, cell * 0.28f, p);
                }
        }
    }
}
