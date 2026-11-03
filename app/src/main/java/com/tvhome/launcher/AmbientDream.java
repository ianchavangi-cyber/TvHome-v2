package com.tvhome.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/** System screensaver (Settings > Device Preferences > Screen saver) that reuses the Ambient Gallery. */
public class AmbientDream extends DreamService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private WallpaperLayer wall;
    private TextView clock, credit;
    private Gallery gallery;
    private int W, H, gen;
    private boolean running;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        Point p = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(p);
        W = Math.max(p.x, p.y);
        H = Math.min(p.x, p.y);
        float u = H / 1080f;
        Cfg cfg = new Cfg(this);
        gallery = Gallery.get(this);
        gallery.setScreenWidth(W);
        FrameLayout root = new FrameLayout(this);
        wall = new WallpaperLayer(this, cfg, W, H);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        clock = new TextView(this);
        clock.setTypeface(Txt.face(this));
        clock.setFontVariationSettings("'wght' 200");
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 120 * u);
        clock.setTextColor(Color.WHITE);
        clock.setShadowLayer(10 * u, 0, 2 * u, 0xAA000000);
        credit = new TextView(this);
        credit.setTypeface(Txt.face(this));
        credit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 21 * u);
        credit.setTextColor(0xCCFFFFFF);
        credit.setShadowLayer(8 * u, 0, 2 * u, 0xAA000000);
        box.addView(clock);
        box.addView(credit);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = (int) Math.max(0.05f * W, 48 * u);
        lp.bottomMargin = (int) (70 * u);
        root.addView(box, lp);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        gallery.refresh(() -> { });
        next();
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        main.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    private void tickClock() {
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", new Date()));
    }

    private void next() {
        if (!running) return;
        tickClock();
        Gallery.Wp w = gallery.current();
        Gallery.Wp pick = gallery.pickNext(new Cfg(this).on(Cfg.FAVONLY));
        if (pick != null) w = pick;
        if (w == null) w = Gallery.abstractWp(0);
        final Gallery.Wp fw = w;
        final int g = ++gen;
        gallery.loadFull(fw, W, H, (Bitmap b) -> {
            if (g != gen || !running) return;
            if (b != null) {
                wall.show(b);
                credit.setText(Gallery.credit(fw));
            }
        });
        main.postDelayed(this::next, 45000);
    }
}
