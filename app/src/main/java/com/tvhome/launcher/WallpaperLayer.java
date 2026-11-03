package com.tvhome.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Random;

/** Cross-fading full-screen wallpaper with slow ambient (Ken Burns) motion and readability scrims. */
final class WallpaperLayer extends FrameLayout {
    private final ImageView a, b;
    private ImageView front;
    private final Scrim scrim;
    private final Cfg cfg;
    private final Random rnd = new Random();
    private final int sw, sh;

    private static final class Scrim extends View {
        private final Paint top = new Paint(), bottom = new Paint(), flat = new Paint();
        private float dim;

        Scrim(Context c) {
            super(c);
        }

        void setDim(float d) {
            dim = d;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            top.setShader(new LinearGradient(0, 0, 0, h * 0.34f, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP));
            bottom.setShader(new LinearGradient(0, h * 0.42f, 0, h, 0x00000000, 0xB8000000, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            flat.setColor(Color.argb((int) (dim * 255), 0, 0, 0));
            c.drawRect(0, 0, getWidth(), getHeight(), flat);
            c.drawRect(0, 0, getWidth(), getHeight() * 0.34f, top);
            c.drawRect(0, getHeight() * 0.42f, getWidth(), getHeight(), bottom);
        }
    }

    WallpaperLayer(Context c, Cfg cfg, int sw, int sh) {
        super(c);
        this.cfg = cfg;
        this.sw = sw;
        this.sh = sh;
        setBackgroundColor(0xFF000000);
        a = new ImageView(c);
        b = new ImageView(c);
        for (ImageView v : new ImageView[]{a, b}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            addView(v, new LayoutParams(-1, -1));
        }
        front = a;
        scrim = new Scrim(c);
        addView(scrim, new LayoutParams(-1, -1));
        applySettings();
    }

    void applySettings() {
        scrim.setDim(cfg.dim());
    }

    void show(Bitmap bmp) {
        final ImageView back = front == a ? b : a;
        final ImageView old = front;
        back.animate().cancel();
        back.setImageBitmap(bmp);
        back.setAlpha(0f);
        reset(back);
        back.bringToFront();
        scrim.bringToFront();
        ObjectAnimator fade = ObjectAnimator.ofFloat(back, View.ALPHA, 0f, 1f);
        fade.setDuration(1400);
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                if (front != back) return;
                old.animate().cancel();
                old.setImageDrawable(null);
                old.setAlpha(0f);
                reset(old);
            }
        });
        fade.start();
        motion(back);
        front = back;
    }

    private void reset(ImageView v) {
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
    }

    private void motion(ImageView v) {
        if (cfg.reduced() || !cfg.on(Cfg.MOTION)) return;
        float dx = (rnd.nextBoolean() ? 1 : -1) * sw * 0.018f;
        float dy = (rnd.nextBoolean() ? 1 : -1) * sh * 0.014f;
        ViewPropertyAnimator p = v.animate();
        p.scaleX(1.075f).scaleY(1.075f).translationX(dx).translationY(dy)
                .setDuration(45000).setInterpolator(new LinearInterpolator());
        p.start();
    }
}
