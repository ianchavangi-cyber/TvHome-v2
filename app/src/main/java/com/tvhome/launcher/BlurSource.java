package com.tvhome.launcher;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds pre-blurred, screen-aligned copies of the current wallpaper (3 strengths) so glass
 * surfaces can draw a real "backdrop blur" cheaply with a BitmapShader.
 */
final class BlurSource {
    Bitmap[] cur, prev;
    float mix = 1f;
    final int screenW, screenH;
    private final List<View> watchers = new ArrayList<>();
    private ValueAnimator anim;

    BlurSource(int w, int h) {
        screenW = w;
        screenH = h;
    }

    void watch(View v) {
        if (!watchers.contains(v)) watchers.add(v);
    }

    void unwatch(View v) {
        watchers.remove(v);
    }

    void set(Bitmap[] next) {
        prev = cur;
        cur = next;
        mix = prev == null ? 1f : 0f;
        if (anim != null) anim.cancel();
        if (prev == null) {
            poke();
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1400);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            mix = (float) a.getAnimatedValue();
            poke();
        });
        anim.start();
    }

    private void poke() {
        for (View v : watchers) v.invalidate();
    }
}
