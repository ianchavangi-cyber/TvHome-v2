package com.tvhome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating glass dock: pinned apps + a fixed "All Apps" button. Everything is drawn in one canvas
 * pass so focus can never get lost and magnification can be driven by one spring.
 */
final class GlassDockView extends AnimView {
    interface Host {
        void launch(AppRepo.App a);

        void openAll();

        void pinsChanged(List<String> pkgs);

        void focusUp();

        void focusDock();

        void editChanged(boolean on);
    }

    private static final class Slot {
        AppRepo.App app;
        float xo, xv;
    }

    private final Cfg cfg;
    private final AppRepo repo;
    private final Host host;
    private final float u;
    private final int sw;
    private final GlassPainter glass;
    private final List<Slot> slots = new ArrayList<>();
    private final Spring fpos = new Spring(0, 240, 22, 0.002f);
    private final Spring act = new Spring(0, 170, 24, 0.002f);
    private final Spring hl = new Spring(0, 90, 15, 0.5f);

    private final Paint iconP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint softP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text, hint;
    private final RadialGradient blob = new RadialGradient(0, 0, 100f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private final RadialGradient shade = new RadialGradient(0, 0, 100f, 0x73000000, 0x00000000, Shader.TileMode.CLAMP);
    private final Matrix mx = new Matrix();
    private final RectF pill = new RectF(), dst = new RectF(), cap = new RectF();
    private final int[] loc = new int[2];

    // metrics
    private float icon, gap, padH, padT, padB, reflect, maxS, pillW, pillH, head, sepGap, liftMax, viewH;
    private boolean magnify, labels, expanded, fx;
    private float[] cx = new float[1], wd = new float[1], gg = new float[1];

    int sel;
    private boolean edit, grabbed, dragging, moved, hlInit;
    private float dragX, launchT;
    private int downIdx = -1;
    private PorterDuffColorFilter glowFilter;
    private int glowColor;
    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (downIdx >= 0 && !moved) {
                if (!edit) setEdit(true);
                if (downIdx < slots.size()) {
                    grabbed = true;
                    dragging = true;
                }
                kick();
            }
        }
    };

    GlassDockView(Context c, Cfg cfg, AppRepo repo, BlurSource blur, int sw, float u, Host host) {
        super(c);
        this.cfg = cfg;
        this.repo = repo;
        this.host = host;
        this.u = u;
        this.sw = sw;
        this.glass = new GlassPainter(cfg, blur, u);
        text = Txt.paint(c, 22 * u, 500, 0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        hint = Txt.paint(c, 17 * u, 400, 0xE6FFFFFF);
        hint.setTextAlign(Paint.Align.CENTER);
        strokeP.setStyle(Paint.Style.STROKE);
        blur.watch(this);
        setFocusable(false);
    }

    // ------------------------------------------------------------------ data / settings

    void setApps(List<AppRepo.App> pinned) {
        slots.clear();
        for (AppRepo.App a : pinned) {
            repo.ensureIcon(a);
            Slot s = new Slot();
            s.app = a;
            slots.add(s);
        }
        repo.ensureIcon(repo.allApp);
        sel = Math.max(0, Math.min(sel, slots.size()));
        applySettings();
        fpos.snap(sel);
        kick();
    }

    void selectPackage(String pkg) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).app.pkg.equals(pkg)) {
                sel = i;
                fpos.snap(i);
                return;
            }
        }
    }

    String selectedPackage() {
        return sel < slots.size() ? slots.get(sel).app.pkg : null;
    }

    void applySettings() {
        fx = !cfg.reduced();
        expanded = cfg.get(Cfg.STYLE) == 1;
        magnify = cfg.on(Cfg.MAGNIFY);
        labels = cfg.on(Cfg.LABELS);
        icon = new float[]{64, 80, 96}[cfg.get(Cfg.SIZE)] * u;
        gap = new float[]{14, 24, 36}[cfg.get(Cfg.SPACING)] * u;
        padH = (expanded ? 40 : 28) * u;
        padT = (expanded ? 22 : 16) * u;
        padB = (expanded ? 20 : 14) * u;
        sepGap = gap * 0.9f + 6 * u;
        maxS = magnify ? 1.25f : 1f;
        int n = slots.size() + 1;
        float base = n * icon + Math.max(0, n - 1) * gap + (n >= 2 ? sepGap : 0);
        float extra = icon * (maxS - 1f) * 1.7f;
        pillW = base + extra + 2 * padH;
        float avail = sw - 2 * Math.max(0.05f * sw, 48 * u);
        if (pillW > avail) {
            float f = avail / pillW;
            icon *= f;
            gap *= f;
            padH *= f;
            sepGap *= f;
            pillW = avail;
        }
        reflect = (expanded && fx) ? icon * 0.30f : 0f;
        pillH = padT + icon + reflect + padB;
        liftMax = magnify ? icon * 0.14f : icon * 0.04f;
        head = icon * (maxS - 1f) / 2f + liftMax + 22 * u + 44 * u + 52 * u;
        viewH = head + pillH + 44 * u;
        if (cx.length != n) {
            cx = new float[n];
            wd = new float[n];
            gg = new float[n];
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) Math.ceil(viewH));
    }

    // ------------------------------------------------------------------ state

    void setActive(boolean on) {
        act.target = on ? 1f : 0f;
        kick();
    }

    float viewHeight() {
        return viewH;
    }

    float headroom() {
        return head;
    }

    boolean isEditing() {
        return edit;
    }

    void setEdit(boolean on) {
        if (edit == on) return;
        edit = on;
        grabbed = false;
        dragging = false;
        host.editChanged(on);
        kick();
    }

    private int total() {
        return slots.size() + 1;
    }

    private AppRepo.App appAt(int i) {
        return i < slots.size() ? slots.get(i).app : repo.allApp;
    }

    private void moveSel(int d) {
        int n = sel + d;
        if (n < 0 || n >= total()) {
            fpos.v += d * 2.2f; // little rubber-band bump at the ends
            kick();
            return;
        }
        sel = n;
        kick();
    }

    private void swap(int from, int to) {
        Slot a = slots.get(from), b = slots.get(to);
        float d = cx[to] - cx[from];
        slots.set(from, b);
        slots.set(to, a);
        a.xo -= d;
        b.xo += d;
        sel = to;
        persist();
        kick();
    }

    private void persist() {
        List<String> l = new ArrayList<>();
        for (Slot s : slots) l.add(s.app.pkg);
        host.pinsChanged(l);
    }

    /** @return true if the key was consumed */
    boolean onKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int d = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                if (edit && grabbed && sel < slots.size()) {
                    int to = sel + d;
                    if (to >= 0 && to < slots.size()) swap(sel, to);
                    else {
                        fpos.v += d * 2f;
                        kick();
                    }
                } else moveSel(d);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                activate();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (edit) {
                    if (sel < slots.size()) {
                        slots.remove(sel);
                        sel = Math.min(sel, slots.size());
                        grabbed = false;
                        persist();
                        applySettings();
                        fpos.v += 1.5f;
                        kick();
                    }
                } else host.focusUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                fpos.v += 0.8f; // nowhere lower to go: tiny nudge instead of losing focus
                kick();
                return true;
            case KeyEvent.KEYCODE_MENU:
                setEdit(!edit);
                return true;
            default:
                return false;
        }
    }

    void longPressOk() {
        if (!edit) setEdit(true);
        if (sel < slots.size()) grabbed = true;
        kick();
    }

    private void activate() {
        if (edit) {
            if (sel < slots.size()) grabbed = !grabbed;
            else host.openAll();
            kick();
            return;
        }
        AppRepo.App a = appAt(sel);
        if (a == repo.allApp) host.openAll();
        else host.launch(a);
    }

    /** Subtle pop + fade as an app launches, then run [end]. */
    void launchAnim(final Runnable end) {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(230);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(a -> {
            launchT = (float) a.getAnimatedValue();
            setAlpha(1f - 0.35f * launchT);
            invalidate();
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                end.run();
            }
        });
        va.start();
    }

    void resetLaunch() {
        launchT = 0f;
        setAlpha(1f);
        invalidate();
    }

    // ------------------------------------------------------------------ animation

    @Override
    boolean tick(float dt) {
        fpos.target = sel;
        boolean m = fpos.step(dt);
        m |= act.step(dt);
        if (cx.length > 0 && wd[0] > 0) hl.target = cxAt(fpos.x);
        m |= hl.step(dt);
        for (Slot s : slots) {
            float a = -320f * s.xo - 28f * s.xv;
            s.xv += a * dt;
            s.xo += s.xv * dt;
            if (Math.abs(s.xo) < 0.3f && Math.abs(s.xv) < 3f) {
                s.xo = 0;
                s.xv = 0;
            } else m = true;
        }
        return m || edit || dragging;
    }

    private float cxAt(float p) {
        int n = cx.length;
        float q = Math.max(0, Math.min(n - 1, p));
        int i = (int) Math.floor(q);
        int j = Math.min(n - 1, i + 1);
        return cx[i] + (cx[j] - cx[i]) * (q - i);
    }

    // ------------------------------------------------------------------ drawing

    private static float falloff(float d) {
        return (float) Math.exp(-d * d / 0.605f);
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = total();
        if (cx.length != n) applySettings();
        float W = getWidth();
        float a = act.x;
        if (!hlInit && wd[0] > 0) {
            hlInit = true;
            hl.snap(cxAt(fpos.x));
        }
        pill.set((W - pillW) / 2f, head, (W + pillW) / 2f, head + pillH);
        getLocationInWindow(loc);
        glass.draw(c, pill, pillH / 2f, -loc[0], -loc[1], hl.x);

        // per-item scale / position
        float tot = 0;
        for (int i = 0; i < n; i++) {
            gg[i] = falloff(Math.abs(i - fpos.x)) * a;
            float s = 1f + (maxS - 1f) * gg[i];
            if (i == sel) s += 0.30f * launchT;
            wd[i] = icon * s;
            tot += wd[i];
        }
        tot += Math.max(0, n - 1) * gap + (n >= 2 ? sepGap : 0);
        float x = W / 2f - tot / 2f;
        for (int i = 0; i < n; i++) {
            if (i > 0) x += gap + (i == n - 1 ? sepGap : 0);
            cx[i] = x + wd[i] / 2f + (i < slots.size() ? slots.get(i).xo : 0);
            x += wd[i];
        }
        if (dragging && sel < slots.size()) cx[sel] = dragX;
        float cy = pill.top + padT + icon / 2f;

        // divider before "All Apps"
        if (n >= 2) {
            float dx = (cx[n - 2] + wd[n - 2] / 2f + cx[n - 1] - wd[n - 1] / 2f) / 2f;
            softP.setShader(null);
            softP.setColor(0x38FFFFFF);
            c.drawRoundRect(dx - u * 0.9f, cy - icon * 0.30f, dx + u * 0.9f, cy + icon * 0.30f, u, u, softP);
        }

        // reflections, then shadows
        for (int i = 0; i < n; i++) {
            AppRepo.App app = appAt(i);
            if (app.reflect != null && reflect > 0) {
                float rw = icon * (1f + (wd[i] / icon - 1f) * 0.6f);
                float y0 = cy + icon * 0.5f + 2 * u;
                dst.set(cx[i] - rw / 2f, y0, cx[i] + rw / 2f, y0 + rw * 0.30f);
                iconP.setAlpha(95);
                c.drawBitmap(app.reflect, null, dst, iconP);
            }
            if (gg[i] > 0.05f) {
                mx.setScale(wd[i] * 0.58f / 100f, icon * 0.13f / 100f);
                mx.postTranslate(cx[i], cy + icon * 0.46f);
                shade.setLocalMatrix(mx);
                softP.setShader(shade);
                softP.setAlpha((int) (255 * Math.min(1f, gg[i] * 1.1f)));
                c.drawRect(cx[i] - wd[i], cy, cx[i] + wd[i], cy + icon, softP);
                softP.setShader(null);
            }
        }

        // soft app-coloured glow behind the selected icon
        int near = Math.max(0, Math.min(n - 1, Math.round(fpos.x)));
        if (a > 0.02f) {
            AppRepo.App app = appAt(near);
            if (glowFilter == null || glowColor != app.color) {
                glowColor = app.color;
                glowFilter = new PorterDuffColorFilter(app.color, PorterDuff.Mode.SRC_IN);
            }
            mx.setScale(icon * 1.05f / 100f, icon * 1.05f / 100f);
            mx.postTranslate(cx[near], cy - liftMax * gg[near]);
            blob.setLocalMatrix(mx);
            softP.setShader(blob);
            softP.setColorFilter(glowFilter);
            softP.setAlpha((int) (150 * a * falloff(Math.abs(near - fpos.x))));
            c.drawRect(cx[near] - icon * 1.4f, cy - icon * 1.4f, cx[near] + icon * 1.4f, cy + icon * 1.4f, softP);
            softP.setColorFilter(null);
            softP.setShader(null);
        }

        // icons (selected last so it sits on top)
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                if ((i == near) != (pass == 1)) continue;
                AppRepo.App app = appAt(i);
                if (app.icon == null) continue;
                float w = wd[i];
                float lift = liftMax * gg[i] + ((edit && grabbed && i == sel) ? icon * 0.10f : 0f);
                dst.set(cx[i] - w / 2f, cy - w / 2f - lift, cx[i] + w / 2f, cy + w / 2f - lift);
                iconP.setAlpha(255);
                c.save();
                if (edit && i < slots.size() && !(grabbed && i == sel)) {
                    c.rotate(1.3f * (float) Math.sin(System.nanoTime() / 1.1e8 + i * 1.7), dst.centerX(), dst.centerY());
                }
                c.drawBitmap(app.icon, null, dst, iconP);
                c.restore();
                float g = gg[i] * (edit && grabbed && i == sel ? 1f : 1f);
                if (g > 0.04f && i == near) {
                    float rad = w * 0.23f;
                    int acc = cfg.accent() & 0xFFFFFF;
                    strokeP.setShader(null);
                    strokeP.setStrokeWidth(u * 7f);
                    strokeP.setColor((acc) | ((int) (46 * g) << 24));
                    cap.set(dst.left - u * 3.5f, dst.top - u * 3.5f, dst.right + u * 3.5f, dst.bottom + u * 3.5f);
                    c.drawRoundRect(cap, rad + u * 3.5f, rad + u * 3.5f, strokeP);
                    strokeP.setStrokeWidth(u * 2.2f);
                    strokeP.setColor((acc) | ((int) ((210 + 45 * launchT) * g) << 24));
                    cap.set(dst.left - u * 1.1f, dst.top - u * 1.1f, dst.right + u * 1.1f, dst.bottom + u * 1.1f);
                    c.drawRoundRect(cap, rad + u * 1.1f, rad + u * 1.1f, strokeP);
                }
            }
        }

        // floating label + edit hint
        if (a > 0.02f && (labels || edit)) {
            String t = appAt(Math.min(sel, n - 1)).label;
            float lx = cxAt(fpos.x);
            float iconTop = cy - icon * maxS / 2f - liftMax - u * 6f;
            drawCapsule(c, t, text, lx, iconTop - 14 * u, 20 * u, 10 * u, a, 0xB3101216);
        }
        if (edit) {
            float iconTop = cy - icon * maxS / 2f - liftMax;
            drawCapsule(c, "Editing dock   OK grab / drop   ◀ ▶ move   ▲ remove   Back to finish", hint,
                    W / 2f, iconTop - 14 * u - 52 * u - 12 * u, 22 * u, 9 * u, 1f, 0xCC1A1D24);
        }
    }

    private void drawCapsule(Canvas c, String s, Paint p, float x, float bottom, float padX, float padY, float alpha, int bg) {
        float tw = p.measureText(s);
        float h = p.getTextSize() + padY * 2f;
        float left = Math.max(12 * u, Math.min(getWidth() - 12 * u - tw - padX * 2, x - tw / 2f - padX));
        cap.set(left, bottom - h, left + tw + padX * 2, bottom);
        capP.setStyle(Paint.Style.FILL);
        capP.setColor(bg);
        capP.setAlpha((int) (Color.alpha(bg) * alpha));
        c.drawRoundRect(cap, h / 2f, h / 2f, capP);
        strokeP.setShader(null);
        strokeP.setStrokeWidth(u * 1.2f);
        strokeP.setColor(((int) (0x40 * alpha) << 24) | 0xFFFFFF);
        c.drawRoundRect(cap, h / 2f, h / 2f, strokeP);
        int old = p.getAlpha();
        p.setAlpha((int) (255 * alpha));
        c.drawText(s, cap.centerX(), cap.centerY() + p.getTextSize() * 0.35f, p);
        p.setAlpha(old);
    }

    // ------------------------------------------------------------------ touch / pointer

    private int hit(float x, float y) {
        if (y < pill.top - icon * 0.6f || y > pill.bottom) return -1;
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < total(); i++) {
            float d = Math.abs(x - cx[i]);
            if (d < wd[i] / 2f + gap / 2f && d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int i = hit(e.getX(), e.getY());
                if (i < 0) return false;
                downIdx = i;
                moved = false;
                sel = i;
                host.focusDock();
                dragX = e.getX();
                postDelayed(longPress, 520);
                kick();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (downIdx >= 0 && Math.abs(e.getX() - dragX) > 10 * u && !dragging) {
                    if (!(edit && downIdx < slots.size())) {
                        removeCallbacks(longPress);
                    } else {
                        dragging = true;
                        grabbed = true;
                    }
                }
                if (dragging && sel < slots.size()) {
                    moved = true;
                    dragX = e.getX();
                    int t = sel;
                    while (t > 0 && dragX < cx[t - 1]) t--;
                    while (t < slots.size() - 1 && dragX > cx[t + 1]) t++;
                    if (t != sel) swap(sel, t);
                    kick();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPress);
                boolean tap = !moved && !dragging && downIdx >= 0 && e.getActionMasked() == MotionEvent.ACTION_UP
                        && downIdx == sel;
                boolean wasDrag = dragging;
                dragging = false;
                if (wasDrag) grabbed = false;
                int idx = downIdx;
                downIdx = -1;
                if (tap && idx >= 0) activate();
                kick();
                return true;
            }
            default:
                return true;
        }
    }
}
