package com.tvhome.launcher;

import android.content.Context;
import android.graphics.*;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/** "Recent" / "Suggested" row of app cards that sits above the dock (the dock's UP target). */
final class ContentRowView extends AnimView {
    interface Host {
        void launch(AppRepo.App a);

        void focusUp();

        void focusDown();

        void focusRow();
    }

    private final AppRepo repo;
    private final Cfg cfg;
    private final Host host;
    private final float u;
    private final int sw;
    private final List<AppRepo.App> items = new ArrayList<>();
    private final Spring fpos = new Spring(0, 240, 22, 0.002f), act = new Spring(0, 170, 24, 0.002f);
    private final Paint cardP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleP, labelP;
    private final RectF r = new RectF();
    private String title = "";
    int sel;

    ContentRowView(Context c, Cfg cfg, AppRepo repo, int sw, float u, Host host) {
        super(c);
        this.cfg = cfg;
        this.repo = repo;
        this.host = host;
        this.u = u;
        this.sw = sw;
        titleP = Txt.paint(c, 26 * u, 500, 0xE6FFFFFF);
        titleP.setShadowLayer(8 * u, 0, 2 * u, 0xAA000000);
        labelP = Txt.paint(c, 22 * u, 500, 0xFFFFFFFF);
        labelP.setTextAlign(Paint.Align.CENTER);
        labelP.setShadowLayer(8 * u, 0, 2 * u, 0xCC000000);
        strokeP.setStyle(Paint.Style.STROKE);
    }

    private float left() {
        return Math.max(0.05f * sw, 48 * u);
    }

    private float tileW() {
        return 232 * u;
    }

    private float tileH() {
        return tileW() * 9f / 16f;
    }

    private float gapX() {
        return 24 * u;
    }

    int wantedHeight() {
        return items.isEmpty() ? 0 : (int) (46 * u + tileH() * 1.12f + 60 * u);
    }

    boolean hasItems() {
        return !items.isEmpty();
    }

    /** Re-reads the list according to the "Content row" setting. @return true if the size changed */
    boolean refresh() {
        int before = items.size();
        items.clear();
        int mode = cfg.get(Cfg.ROW);
        int max = Math.max(3, (int) ((sw - 2 * left()) / (tileW() + gapX())));
        if (mode == 0) {
            items.addAll(repo.recent(max));
            title = "Recent";
        } else if (mode == 1) {
            items.addAll(repo.suggested(max));
            title = "Suggested for you";
        }
        for (AppRepo.App a : items) repo.ensureCard(a);
        sel = Math.max(0, Math.min(sel, items.size() - 1));
        fpos.snap(sel);
        invalidate();
        return before != items.size() || before == 0;
    }

    void setActive(boolean on) {
        act.target = on ? 1f : 0f;
        kick();
    }

    boolean onKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (sel > 0) sel--;
                else fpos.v -= 2f;
                kick();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (sel < items.size() - 1) sel++;
                else fpos.v += 2f;
                kick();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                host.focusUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                host.focusDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (sel < items.size()) host.launch(items.get(sel));
                return true;
            default:
                return false;
        }
    }

    @Override
    boolean tick(float dt) {
        fpos.target = sel;
        boolean m = fpos.step(dt);
        m |= act.step(dt);
        return m;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (items.isEmpty()) return;
        float tw = tileW(), th = tileH(), gx = gapX(), l = left();
        c.drawText(title, l, 30 * u, titleP);
        float top = 46 * u + th * 0.06f;
        for (int i = 0; i < items.size(); i++) {
            AppRepo.App a = items.get(i);
            float g = (float) Math.exp(-Math.pow(i - fpos.x, 2) / 0.45f) * act.x;
            float s = 1f + 0.075f * g;
            float cx = l + i * (tw + gx) + tw / 2f, cy = top + th / 2f - 6 * u * g;
            r.set(cx - tw * s / 2f, cy - th * s / 2f, cx + tw * s / 2f, cy + th * s / 2f);
            if (a.card != null) {
                cardP.setAlpha(235);
                c.drawBitmap(a.card, null, r, cardP);
            }
            float rad = th * s * 0.128f;
            strokeP.setStrokeWidth(1.4f * u);
            strokeP.setColor(0x40FFFFFF);
            c.drawRoundRect(r, rad, rad, strokeP);
            if (g > 0.03f) {
                int acc = cfg.accent() & 0xFFFFFF;
                strokeP.setStrokeWidth(6 * u);
                strokeP.setColor(acc | ((int) (44 * g) << 24));
                r.inset(-3 * u, -3 * u);
                c.drawRoundRect(r, rad + 3 * u, rad + 3 * u, strokeP);
                strokeP.setStrokeWidth(2.2f * u);
                strokeP.setColor(acc | ((int) (225 * g) << 24));
                r.inset(2 * u, 2 * u);
                c.drawRoundRect(r, rad + 1 * u, rad + 1 * u, strokeP);
                labelP.setAlpha((int) (255 * Math.min(1f, g * 1.2f)));
                c.drawText(a.label, cx, top + th * 1.06f + 30 * u, labelP);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        float tw = tileW(), gx = gapX();
        int i = (int) ((e.getX() - left()) / (tw + gx));
        if (i >= 0 && i < items.size()) {
            sel = i;
            host.focusRow();
            host.launch(items.get(i));
        }
        return true;
    }
}
