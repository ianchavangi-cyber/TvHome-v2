package com.tvhome.launcher;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

final class Txt {
    private static Typeface tf;

    static Typeface face(Context c) {
        if (tf == null) {
            try {
                tf = c.getResources().getFont(R.font.outfit);
            } catch (Throwable t) {
                tf = Typeface.DEFAULT;
            }
        }
        return tf;
    }

    static Paint paint(Context c, float size, int wght, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face(c));
        p.setFontVariationSettings("'wght' " + wght);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }
}
