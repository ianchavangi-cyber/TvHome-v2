package com.tvhome.launcher;

import android.content.Context;
import android.graphics.*;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Generic glass list: headers, actions and left/right value pickers. */
final class MenuView extends Overlay {
    static final class Row {
        String label;
        Supplier<String> value;
        Runnable click;
        IntConsumer adjust;
        boolean header;

        static Row header(String t) {
            Row r = new Row();
            r.label = t;
            r.header = true;
            return r;
        }

        static Row action(String label, Runnable click) {
            Row r = new Row();
            r.label = label;
            r.click = click;
            return r;
        }

        static Row action(String label, Supplier<String> value, Runnable click) {
            Row r = action(label, click);
            r.value = value;
            return r;
        }

        static Row choice(final Cfg cfg, final Cfg.Opt o, final Runnable changed) {
            Row r = new Row();
            r.label = o.label;
            r.value = () -> o.values[cfg.get(o)];
            r.adjust = d -> {
                int n = o.values.length;
                cfg.set(o, ((cfg.get(o) + d) % n + n) % n);
                if (changed != null) changed.run();
            };
            return r;
        }
    }

    private final Cfg cfg;
    private final BlurSource blur;
    private final float u;
    private final int sw, sh;
    private final GlassPainter glass;
    private final List<Row> rows = new ArrayList<>();
    private final Spring selY = new Spring(0, 330, 28, 0.3f), scroll = new Spring(0, 220, 27, 0.3f);
    private final Paint dimP = new Paint(), hiP = new Paint(Paint.ANTI_ALIAS_FLAG), strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleP, subP, labelP, valueP, headP, hintP;
    private final RectF panel = new RectF(), rr = new RectF();
    private String title = "", subtitle = "";
    private int sel;

    MenuView(Context c, Cfg cfg, BlurSource blur, int sw, int sh, float u) {
        super(c);
        this.cfg = cfg;
        this.blur = blur;
        this.u = u;
        this.sw = sw;
        this.sh = sh;
        glass = new GlassPainter(cfg, blur, u);
        titleP = Txt.paint(c, 36 * u, 600, 0xFFFFFFFF);
        subP = Txt.paint(c, 20 * u, 400, 0xAAFFFFFF);
        labelP = Txt.paint(c, 26 * u, 400, 0xFFFFFFFF);
        valueP = Txt.paint(c, 24 * u, 500, 0xFFFFFFFF);
        valueP.setTextAlign(Paint.Align.RIGHT);
        headP = Txt.paint(c, 17 * u, 600, 0x99FFFFFF);
        headP.setLetterSpacing(0.14f);
        hintP = Txt.paint(c, 18 * u, 400, 0x88FFFFFF);
        hintP.setTextAlign(Paint.Align.CENTER);
        dimP.setColor(0x73000000);
        hiP.setColor(0x2EFFFFFF);
        strokeP.setStyle(Paint.Style.STROKE);
        blur.watch(this);
    }

    MenuView setup(String title, String subtitle, List<Row> rows) {
        this.title = title;
        this.subtitle = subtitle;
        this.rows.clear();
        this.rows.addAll(rows);
        sel = 0;
        while (sel < rows.size() && rows.get(sel).header) sel++;
        if (sel >= rows.size()) sel = 0;
        selY.snap(rowTop(sel));
        return this;
    }

    private float rowH() {
        return 62 * u;
    }

    private float headH() {
        return 46 * u;
    }

    private float rowTop(int idx) {
        float y = 0;
        for (int i = 0; i < idx; i++) y += rows.get(i).header ? headH() : rowH();
        return y;
    }

    private float contentH() {
        return rowTop(rows.size());
    }

    private float viewH() {
        return Math.min(contentH(), sh * 0.56f);
    }

    private float panelW() {
        return Math.min(sw * 0.6f, 900 * u);
    }

    private void reveal() {
        if (rows.isEmpty()) return;
        float t = rowTop(sel), b = t + (rows.get(sel).header ? headH() : rowH());
        float vh = viewH();
        if (t < scroll.target) scroll.target = t - (sel > 0 && rows.get(sel - 1).header ? headH() : 0);
        else if (b > scroll.target + vh) scroll.target = b - vh;
        scroll.target = Math.max(0, Math.min(Math.max(0, contentH() - vh), scroll.target));
    }

    @Override
    boolean nav(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int d = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                int n = sel + d;
                while (n >= 0 && n < rows.size() && rows.get(n).header) n += d;
                if (n >= 0 && n < rows.size()) sel = n;
                reveal();
                kick();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (rows.isEmpty()) return true;
                Row r = rows.get(sel);
                if (r.adjust != null) r.adjust.accept(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                invalidate();
                return true;
            }
            default:
                return false;
        }
    }

    @Override
    void ok() {
        if (rows.isEmpty()) return;
        Row r = rows.get(sel);
        if (r.click != null) r.click.run();
        else if (r.adjust != null) r.adjust.accept(1);
        invalidate();
    }

    @Override
    boolean tick(float dt) {
        selY.target = rowTop(sel);
        boolean m = selY.step(dt);
        m |= scroll.step(dt);
        return m;
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        c.drawRect(0, 0, W, H, dimP);
        float pw = panelW(), vh = viewH();
        float headerH = 112 * u, footH = 64 * u;
        float ph = headerH + vh + footH;
        panel.set((W - pw) / 2f, (H - ph) / 2f, (W + pw) / 2f, (H + ph) / 2f);
        glass.draw(c, panel, 36 * u, 0, 0, Float.NaN);
        float x = panel.left + 44 * u;
        c.drawText(title, x, panel.top + 62 * u, titleP);
        if (!subtitle.isEmpty()) c.drawText(subtitle, x, panel.top + 94 * u, subP);
        float top = panel.top + headerH;
        c.save();
        c.clipRect(panel.left + 12 * u, top, panel.right - 12 * u, top + vh);
        c.translate(0, top - scroll.x);
        rr.set(panel.left + 22 * u, selY.x + 3 * u, panel.right - 22 * u, selY.x + rowH() - 3 * u);
        if (sel < rows.size() && !rows.get(sel).header) {
            c.drawRoundRect(rr, 18 * u, 18 * u, hiP);
            strokeP.setStrokeWidth(1.6f * u);
            strokeP.setColor((cfg.accent() & 0xFFFFFF) | 0x99000000);
            c.drawRoundRect(rr, 18 * u, 18 * u, strokeP);
        }
        float y = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.header) {
                c.drawText(r.label.toUpperCase(), x, y + headH() - 12 * u, headP);
                y += headH();
                continue;
            }
            float base = y + rowH() / 2f + 9 * u;
            labelP.setAlpha(i == sel ? 255 : 215);
            c.drawText(r.label, x, base, labelP);
            if (r.value != null) {
                String v = r.value.get();
                if (r.adjust != null && i == sel) v = "‹  " + v + "  ›";
                c.drawText(v, panel.right - 44 * u, base, valueP);
            }
            y += rowH();
        }
        c.restore();
        c.drawText("◀ ▶ change    OK select    Back close", panel.centerX(), panel.bottom - 24 * u, hintP);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        if (!panel.contains(e.getX(), e.getY())) {
            close();
            return true;
        }
        float top = panel.top + 112 * u;
        float yy = e.getY() - top + scroll.x;
        for (int i = 0; i < rows.size(); i++) {
            float t = rowTop(i), b = t + (rows.get(i).header ? headH() : rowH());
            if (yy >= t && yy < b && !rows.get(i).header) {
                sel = i;
                ok();
                kick();
                break;
            }
        }
        return true;
    }
}
