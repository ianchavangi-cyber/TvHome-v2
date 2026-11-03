package com.tvhome.launcher;

import android.content.Context;
import android.view.View;

/** View with a frame loop that only runs while something is animating. */
abstract class AnimView extends View {
    private boolean running;
    private long last;
    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            float dt = Math.min(0.033f, Math.max(0.001f, (now - last) / 1e9f));
            last = now;
            boolean more = tick(dt);
            invalidate();
            if (more && isAttachedToWindow()) postOnAnimation(this);
            else running = false;
        }
    };

    AnimView(Context c) {
        super(c);
    }

    /** Advance animation by dt seconds; return true to keep running. */
    abstract boolean tick(float dt);

    final void kick() {
        if (running) return;
        running = true;
        last = System.nanoTime();
        postOnAnimation(loop);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        removeCallbacks(loop);
    }
}
