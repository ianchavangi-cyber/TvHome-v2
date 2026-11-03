package com.tvhome.launcher;

import android.content.Context;
import android.graphics.*;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Full-screen blurred overlay with a scrolling grid of 16:9 tiles and a gliding focus frame. */
abstract class GridBase extends Overlay {
    protected final Cfg cfg;
    protected final BlurSource blur;
    protected final float u;
    protected final int sw, sh;
    protected int cols = 4, sel;
    protected final Spring scroll = new Spring(0, 220, 27, 0.3f);
    protected final Spring fx = new Spring(0, 330, 26, 0.3f), fy = new Spring(0, 330, 26, 0.3f);
    protected final Paint bgP = new Paint(Paint.FILTER_BITMAP_FLAG), dimP = new Paint(), strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final RectF r = new RectF();
    private boolean placed;

    GridBase(Context c, Cfg cfg, BlurSource blur, int sw, int sh, float u) {
        super(c);
        this.cfg = cfg;
        this.blur = blur;
        this.u = u;
        this.sw = sw;
        this.sh = sh;
        strokeP.setStyle(Paint.Style.STROKE);
        blur.watch(this);
    }

    abstract int count();

    abstract void drawTile(Canvas c, int i, RectF r, boolean focused);

    abstract void pick(int i);

    void menu(int i) {
    }

    abstract void drawHeader(Canvas c);

    abstract float gridTop();

    protected float left() {
        return Math.max(0.05f * sw, 48 * u);
    }

    protected float gx() {
        return 26 * u;
    }

    protected float tw() {
        return (sw - 2 * left() - (cols - 1) * gx()) / cols;
    }

    protected float th() {
        return tw() * 9f / 16f;
    }

    protected float pitch() {
        return th() + 60 * u;
    }

    protected float areaBottom() {
        return sh - 96 * u;
    }

    protected float tileX(int i) {
        return left() + (i % cols) * (tw() + gx());
    }

    protected float tileY(int i) {
        return (i / cols) * pitch();
    }

    protected void ensureVisible() {
        float t = tileY(sel), b = t + pitch();
        float h = areaBottom() - gridTop();
        if (t < scroll.target) scroll.target = t;
        else if (b > scroll.target + h) scroll.target = b - h;
        scroll.target = Math.max(0, scroll.target);
    }

    protected void select(int i) {
        sel = Math.max(0, Math.min(count() - 1, i));
        fx.target = tileX(sel);
        fy.target = tileY(sel);
        if (!placed) {
            fx.snap(fx.target);
            fy.snap(fy.target);
            placed = true;
        }
        ensureVisible();
        kick();
    }

    /** Select without animating (used when a screen opens or its content changes). */
    protected void snapTo(int i) {
        select(i);
        fx.snap(fx.target);
        fy.snap(fy.target);
        scroll.snap(scroll.target);
    }

    protected void reset() {
        placed = false;
        scroll.snap(0);
        select(0);
    }

    @Override
    boolean nav(int code) {
        int n = count();
        if (n == 0) return code != KeyEvent.KEYCODE_MENU ? true : false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                select(sel - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                select(sel + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (sel - cols >= 0) select(sel - cols);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (sel + cols < n) select(sel + cols);
                else if (sel / cols < (n - 1) / cols) select(n - 1);
                return true;
            case KeyEvent.KEYCODE_MENU:
                menu(sel);
                return true;
            default:
                return false;
        }
    }

    @Override
    void ok() {
        if (count() > 0) pick(sel);
    }

    @Override
    void longOk() {
        if (count() > 0) menu(sel);
    }

    @Override
    boolean tick(float dt) {
        boolean m = scroll.step(dt);
        m |= fx.step(dt);
        m |= fy.step(dt);
        return m;
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        if (blur.cur != null && !cfg.reduced()) {
            c.drawBitmap(blur.cur[2], null, new RectF(0, 0, W, H), bgP);
            dimP.setColor(0xA6000000);
        } else dimP.setColor(0xF2080A0E);
        c.drawRect(0, 0, W, H, dimP);
        drawHeader(c);
        float gt = gridTop();
        c.save();
        c.clipRect(0, gt - 34 * u, W, areaBottom());
        c.translate(0, gt - scroll.x);
        int n = count();
        float tw = tw(), th = th();
        int first = Math.max(0, (int) Math.floor(scroll.x / pitch()) * cols);
        int last = Math.min(n, first + cols * (int) (Math.ceil((areaBottom() - gt) / pitch()) + 2));
        for (int i = first; i < last; i++) {
            if (i == sel) continue;
            r.set(tileX(i), tileY(i), tileX(i) + tw, tileY(i) + th);
            drawTile(c, i, r, false);
        }
        if (sel < n) {
            float s = 1.06f;
            float cx = fx.x + tw / 2f, cy = fy.x + th / 2f;
            r.set(cx - tw * s / 2f, cy - th * s / 2f, cx + tw * s / 2f, cy + th * s / 2f);
            drawTile(c, sel, r, true);
            float rad = th * s * 0.128f;
            int acc = cfg.accent() & 0xFFFFFF;
            strokeP.setStrokeWidth(7 * u);
            strokeP.setColor(acc | 0x2E000000);
            r.inset(-3.5f * u, -3.5f * u);
            c.drawRoundRect(r, rad + 3.5f * u, rad + 3.5f * u, strokeP);
            strokeP.setStrokeWidth(2.4f * u);
            strokeP.setColor(acc | 0xE6000000);
            r.inset(2.4f * u, 2.4f * u);
            c.drawRoundRect(r, rad + 1.2f * u, rad + 1.2f * u, strokeP);
        }
        c.restore();
    }

    /** Which tile is at this point (view coords), or -1. */
    protected int tileAt(float x, float y) {
        float gt = gridTop();
        if (y < gt - 20 * u || y > areaBottom()) return -1;
        float cy = y - gt + scroll.x;
        int row = (int) (cy / pitch());
        float cx = x - left();
        int col = (int) (cx / (tw() + gx()));
        if (col < 0 || col >= cols || cx < 0) return -1;
        int i = row * cols + col;
        return i < count() ? i : -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        int i = tileAt(e.getX(), e.getY());
        if (i >= 0) {
            select(i);
            pick(i);
        }
        return true;
    }
}
