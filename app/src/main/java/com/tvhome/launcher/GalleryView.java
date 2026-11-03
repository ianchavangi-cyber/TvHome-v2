package com.tvhome.launcher;

import android.content.Context;
import android.graphics.*;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ambient Gallery: category tabs + thumbnail grid. OK sets the wallpaper, hold OK favourites. */
final class GalleryView extends GridBase {
    interface Host {
        void setWallpaper(Gallery.Wp w);

        void toast(String s);
    }

    private final Gallery gallery;
    private final Host host;
    private final List<String> tabIds = new ArrayList<>(), tabNames = new ArrayList<>();
    private List<Gallery.Wp> items = new ArrayList<>();
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(28 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }

        @Override
        protected void entryRemoved(boolean ev, String k, Bitmap o, Bitmap n) {
            shaders.remove(k);
        }
    };
    private final Map<String, BitmapShader> shaders = new HashMap<>();
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private final Paint shP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleP, subP, tabP, tabOnP, msgP, infoP, hintP, tagP;
    private final Matrix mx = new Matrix();
    private final Spring tabScroll = new Spring(0, 220, 27, 0.3f);
    private int tab = 1;
    private boolean inTabs;
    private Gallery.Wp currentWp;

    GalleryView(Context c, Cfg cfg, Gallery gallery, BlurSource blur, int sw, int sh, float u, Host host) {
        super(c, cfg, blur, sw, sh, u);
        this.gallery = gallery;
        this.host = host;
        this.cols = 4;
        tabIds.add(Gallery.FAV);
        tabNames.add("★  Favorites");
        for (Gallery.Cat k : Gallery.CATS) {
            tabIds.add(k.id);
            tabNames.add(k.title);
        }
        titleP = Txt.paint(c, 40 * u, 600, 0xFFFFFFFF);
        subP = Txt.paint(c, 20 * u, 400, 0x99FFFFFF);
        tabP = Txt.paint(c, 22 * u, 500, 0xCCFFFFFF);
        tabOnP = Txt.paint(c, 22 * u, 600, 0xFFFFFFFF);
        msgP = Txt.paint(c, 26 * u, 400, 0xAAFFFFFF);
        msgP.setTextAlign(Paint.Align.CENTER);
        infoP = Txt.paint(c, 22 * u, 500, 0xFFFFFFFF);
        hintP = Txt.paint(c, 18 * u, 400, 0x88FFFFFF);
        hintP.setTextAlign(Paint.Align.CENTER);
        tagP = Txt.paint(c, 17 * u, 600, 0xFFFFFFFF);
    }

    void openFor(Gallery.Wp current) {
        currentWp = current;
        inTabs = false;
        tab = 1;
        for (int i = 0; i < tabIds.size(); i++) if (current != null && tabIds.get(i).equals(current.cat)) tab = i;
        reload();
    }

    /** Re-read the current tab (call when the catalogue changes). */
    void reload() {
        items = gallery.listFor(tabIds.get(tab));
        int keep = 0;
        if (currentWp != null) for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(currentWp.id)) keep = i;
        reset();
        if (keep > 0) snapTo(keep);
        invalidate();
    }

    /** Catalogue refreshed in the background: only fill in an empty tab, never disturb browsing. */
    void catalogUpdated() {
        if (items.isEmpty()) reload();
    }

    void currentChanged(Gallery.Wp w) {
        currentWp = w;
        invalidate();
    }

    @Override
    int count() {
        return items.size();
    }

    @Override
    float gridTop() {
        return 262 * u;
    }

    @Override
    protected float areaBottom() {
        return sh - 150 * u;
    }

    // ------------------------------------------------------------------ input

    @Override
    boolean nav(int code) {
        if (inTabs) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (tab > 0) {
                        tab--;
                        reload();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (tab < tabIds.size() - 1) {
                        tab++;
                        reload();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    inTabs = false;
                    kick();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    toggleRotation();
                    return true;
                default:
                    return false;
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && sel < cols) {
            inTabs = true;
            kick();
            return true;
        }
        if (items.isEmpty()) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) inTabs = true;
            else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                inTabs = true;
                return nav(code);
            }
            kick();
            return true;
        }
        return super.nav(code);
    }

    private void toggleRotation() {
        String id = tabIds.get(tab);
        if (Gallery.FAV.equals(id)) {
            host.toast("Use Settings › Rotate through › Favorites only");
            return;
        }
        boolean on = !gallery.catEnabled(id);
        gallery.setCatEnabled(id, on);
        host.toast(tabNames.get(tab) + (on ? " included in rotation" : " removed from rotation"));
        invalidate();
    }

    @Override
    void ok() {
        if (inTabs) {
            if (!items.isEmpty()) inTabs = false;
            kick();
        } else super.ok();
    }

    @Override
    void longOk() {
        if (inTabs) toggleRotation();
        else super.longOk();
    }

    @Override
    void pick(int i) {
        host.setWallpaper(items.get(i));
    }

    @Override
    void menu(int i) {
        Gallery.Wp w = items.get(i);
        boolean now = gallery.toggleFav(w);
        host.toast(now ? "Added to favorites ★" : "Removed from favorites");
        if (Gallery.FAV.equals(tabIds.get(tab)) && !now) {
            int keep = Math.min(sel, Math.max(0, gallery.listFor(Gallery.FAV).size() - 1));
            items = gallery.listFor(Gallery.FAV);
            select(keep);
        }
        invalidate();
    }

    @Override
    boolean tick(float dt) {
        return super.tick(dt) | tabScroll.step(dt);
    }

    // ------------------------------------------------------------------ drawing

    private Bitmap thumb(final Gallery.Wp w) {
        Bitmap b = thumbs.get(w.id);
        if (b == null && pending.add(w.id)) {
            gallery.loadThumb(w, bmp -> {
                pending.remove(w.id);
                if (bmp != null) {
                    thumbs.put(w.id, bmp);
                    invalidate();
                }
            });
        }
        return b;
    }

    @Override
    void drawTile(Canvas c, int i, RectF r, boolean focused) {
        Gallery.Wp w = items.get(i);
        float rad = r.height() * 0.128f;
        Bitmap b = thumb(w);
        if (b != null) {
            BitmapShader s = shaders.get(w.id);
            if (s == null) {
                s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shaders.put(w.id, s);
            }
            float k = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
            mx.setScale(k, k);
            mx.postTranslate(r.left + (r.width() - b.getWidth() * k) / 2f, r.top + (r.height() - b.getHeight() * k) / 2f);
            s.setLocalMatrix(mx);
            shP.setShader(s);
            c.drawRoundRect(r, rad, rad, shP);
        } else {
            fillP.setColor(0xFF1B1E24);
            c.drawRoundRect(r, rad, rad, fillP);
        }
        strokeP.setStrokeWidth(1.2f * u);
        strokeP.setColor(0x33FFFFFF);
        c.drawRoundRect(r, rad, rad, strokeP);
        if (gallery.isFav(w)) c.drawText("★", r.right - 34 * u, r.top + 34 * u, tagP);
        if (currentWp != null && currentWp.id.equals(w.id)) {
            fillP.setColor(0xCC000000);
            RectF t = new RectF(r.left + 10 * u, r.top + 10 * u, r.left + 118 * u, r.top + 40 * u);
            c.drawRoundRect(t, 15 * u, 15 * u, fillP);
            c.drawText("● Current", t.left + 12 * u, t.bottom - 8 * u, tagP);
        }
    }

    @Override
    void drawHeader(Canvas c) {
        float l = left();
        c.drawText("Ambient Gallery", l, 92 * u, titleP);
        int on = 0;
        for (Gallery.Cat k : Gallery.CATS) if (gallery.catEnabled(k.id)) on++;
        c.drawText(on + " of " + Gallery.CATS.length + " categories in rotation   ·   photos via Wikimedia Commons", l, 126 * u, subP);

        // tabs
        float y = 150 * u, h = 52 * u, x = l - tabScroll.x;
        float selL = 0, selR = 0;
        c.save();
        c.clipRect(0, y - 8 * u, sw, y + h + 8 * u);
        for (int i = 0; i < tabIds.size(); i++) {
            String id = tabIds.get(i);
            boolean rot = !Gallery.FAV.equals(id) && gallery.catEnabled(id);
            String t = (rot ? "● " : Gallery.FAV.equals(id) ? "" : "○ ") + tabNames.get(i);
            Paint p = i == tab ? tabOnP : tabP;
            float w = p.measureText(t) + 40 * u;
            if (i == tab) {
                selL = x + tabScroll.x - l;
                selR = selL + w;
            }
            r.set(x, y, x + w, y + h);
            fillP.setColor(i == tab ? (inTabs ? 0x42FFFFFF : 0x2CFFFFFF) : 0x14FFFFFF);
            c.drawRoundRect(r, h / 2f, h / 2f, fillP);
            if (i == tab && inTabs) {
                strokeP.setStrokeWidth(2.2f * u);
                strokeP.setColor((cfg.accent() & 0xFFFFFF) | 0xE6000000);
                c.drawRoundRect(r, h / 2f, h / 2f, strokeP);
            }
            c.drawText(t, x + 20 * u, y + h / 2f + 8 * u, p);
            x += w + 14 * u;
        }
        c.restore();
        float vis = sw - 2 * l;
        if (selR - tabScroll.target > vis) tabScroll.target = selR - vis;
        if (selL < tabScroll.target) tabScroll.target = Math.max(0, selL);

        // empty-state message
        if (items.isEmpty()) {
            String m;
            if (Gallery.FAV.equals(tabIds.get(tab))) m = "No favorites yet. Hold OK on any wallpaper to add it.";
            else if (!gallery.online) m = "Can't reach Wikimedia Commons right now. Check the TV's internet connection.";
            else m = "Loading wallpapers…";
            c.drawText(m, sw / 2f, sh * 0.58f, msgP);
        }

        // info bar
        if (!items.isEmpty() && sel < items.size()) {
            String s = Gallery.credit(items.get(sel));
            float max = sw - 2 * l;
            while (s.length() > 8 && infoP.measureText(s) > max) s = s.substring(0, s.length() - 2);
            c.drawText(s, l, sh - 92 * u, infoP);
        }
        c.drawText(inTabs ? "◀ ▶ category    hold OK include / exclude from rotation    ▼ wallpapers"
                : "OK set wallpaper    hold OK favorite ★    ▲ categories    Back close", sw / 2f, sh - 40 * u, hintP);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        if (e.getY() > 140 * u && e.getY() < 210 * u) {
            float x = left() - tabScroll.x;
            for (int i = 0; i < tabIds.size(); i++) {
                String id = tabIds.get(i);
                boolean rot = !Gallery.FAV.equals(id) && gallery.catEnabled(id);
                String t = (rot ? "● " : Gallery.FAV.equals(id) ? "" : "○ ") + tabNames.get(i);
                float w = tabOnP.measureText(t) + 40 * u;
                if (e.getX() >= x && e.getX() < x + w + 14 * u) {
                    tab = i;
                    inTabs = true;
                    reload();
                    return true;
                }
                x += w + 14 * u;
            }
            return true;
        }
        return super.onTouchEvent(e);
    }
}
