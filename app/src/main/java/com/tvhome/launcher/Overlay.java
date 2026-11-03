package com.tvhome.launcher;

import android.content.Context;

/** Full-screen layer that gets D-pad input from the activity while it is open. */
abstract class Overlay extends AnimView {
    Runnable onClose;
    private boolean closing;

    Overlay(Context c) {
        super(c);
        setAlpha(0f);
    }

    /** Direction keys and MENU. @return true if consumed. */
    abstract boolean nav(int keyCode);

    abstract void ok();

    void longOk() {
        ok();
    }

    void back() {
        close();
    }

    void open() {
        closing = false;
        animate().cancel();
        setAlpha(0f);
        animate().alpha(1f).setDuration(220).start();
        kick();
    }

    void close() {
        if (closing) return;
        closing = true;
        animate().alpha(0f).setDuration(170).withEndAction(() -> {
            if (onClose != null) onClose.run();
        }).start();
    }
}
