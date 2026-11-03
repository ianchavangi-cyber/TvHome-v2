package com.tvhome.launcher;

import android.graphics.*;

import java.util.Random;

/** On-device generated imagery (abstract wallpapers) and blur helpers. */
final class Procedural {
    static final String[] NAMES = {"Midnight Bloom", "Dusk Ember", "Lagoon", "Forest Mist", "Rose Quartz",
            "Graphite", "Sandstone", "Polar", "Violet Haze", "Ocean Deep"};
    private static final int[][] PAL = {
            {0xFF0B1026, 0xFF1B1F4B, 0xFF5B6CFF, 0xFF8A4DFF, 0xFF2EC4FF},
            {0xFF1A0F1F, 0xFF3B1A2E, 0xFFFF6B4A, 0xFFFF9E5E, 0xFFB23A6B},
            {0xFF04222B, 0xFF0B4A54, 0xFF2BD9C5, 0xFF2A8CFF, 0xFF7BF0D1},
            {0xFF0C1A14, 0xFF1E3B2D, 0xFF4CAF7A, 0xFF9BD67E, 0xFF2F7F73},
            {0xFF1D1220, 0xFF3A2142, 0xFFFF7FAE, 0xFFB57BFF, 0xFFFFB3C7},
            {0xFF101113, 0xFF23252A, 0xFF4A4F5C, 0xFF6F7788, 0xFF2E3138},
            {0xFF21160F, 0xFF4A3322, 0xFFE0A15F, 0xFFC96B4B, 0xFFF2CF8E},
            {0xFF0B1620, 0xFF1F3B52, 0xFF7FC8FF, 0xFFBDE6FF, 0xFF4A8FD6},
            {0xFF120B24, 0xFF2A1B5C, 0xFFA66BFF, 0xFF5E7BFF, 0xFFD08BFF},
            {0xFF020B1A, 0xFF06304F, 0xFF1E8BE0, 0xFF00C2CC, 0xFF3A5BD9}};
    private static Bitmap grain;

    static Bitmap abstractWp(int idx, int w, int h) {
        int[] p = PAL[Math.floorMod(idx, PAL.length)];
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, w * 0.4f, h, p[0], p[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        Random r = new Random(idx * 7919L + 13);
        for (int i = 0; i < 4; i++) {
            float cx = w * (0.1f + 0.8f * r.nextFloat()), cy = h * (0.1f + 0.8f * r.nextFloat());
            float rad = w * (0.28f + 0.30f * r.nextFloat());
            int col = p[2 + i % 3];
            paint.setShader(new RadialGradient(cx, cy, rad, (col & 0x00FFFFFF) | 0x8C000000, col & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
        }
        paint.setShader(new RadialGradient(w / 2f, h / 2f, w * 0.75f, 0x00000000, 0x88000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(new BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        paint.setAlpha(18);
        c.drawRect(0, 0, w, h, paint);
        return bm;
    }

    private static synchronized Bitmap grain() {
        if (grain == null) {
            int s = 128;
            int[] px = new int[s * s];
            Random r = new Random(5);
            for (int i = 0; i < px.length; i++) {
                int v = r.nextInt(256);
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            grain = Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888);
        }
        return grain;
    }

    /** Light / medium / strong blurred copies, center-cropped to the screen aspect. */
    static Bitmap[] blurs(Bitmap src, int sw, int sh) {
        int[][] cfg = {{6, 2, 1}, {10, 3, 2}, {16, 4, 3}};
        Bitmap[] out = new Bitmap[3];
        // cheap anti-aliased pre-shrink
        Bitmap base = src;
        while (base.getWidth() > sw / 3 * 2 && base.getWidth() > 600) {
            Bitmap n = Bitmap.createScaledBitmap(base, base.getWidth() / 2, base.getHeight() / 2, true);
            if (base != src) base.recycle();
            base = n;
        }
        for (int i = 0; i < 3; i++) {
            int bw = Math.max(16, sw / cfg[i][0]);
            int bh = Math.max(9, bw * sh / sw);
            Bitmap s = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(s);
            float scale = Math.max(bw / (float) base.getWidth(), bh / (float) base.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((bw - base.getWidth() * scale) / 2f, (bh - base.getHeight() * scale) / 2f);
            c.drawBitmap(base, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] px = new int[bw * bh];
            s.getPixels(px, 0, bw, 0, 0, bw, bh);
            int[] tmp = new int[px.length];
            for (int pass = 0; pass < cfg[i][2]; pass++) {
                box(px, tmp, bw, bh, cfg[i][1], true);
                box(tmp, px, bw, bh, cfg[i][1], false);
            }
            s.setPixels(px, 0, bw, 0, 0, bw, bh);
            out[i] = s;
        }
        if (base != src) base.recycle();
        return out;
    }

    private static void box(int[] in, int[] out, int w, int h, int rad, boolean horiz) {
        int lines = horiz ? h : w, len = horiz ? w : h;
        int div = rad * 2 + 1;
        for (int l = 0; l < lines; l++) {
            int a = 0, r = 0, g = 0, b = 0;
            for (int k = -rad; k <= rad; k++) {
                int p = in[idx(l, Math.max(0, Math.min(len - 1, k)), w, horiz)];
                a += p >>> 24;
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                b += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[idx(l, i, w, horiz)] = ((a / div) << 24) | ((r / div) << 16) | ((g / div) << 8) | (b / div);
                int add = in[idx(l, Math.min(len - 1, i + rad + 1), w, horiz)];
                int sub = in[idx(l, Math.max(0, i - rad), w, horiz)];
                a += (add >>> 24) - (sub >>> 24);
                r += ((add >> 16) & 255) - ((sub >> 16) & 255);
                g += ((add >> 8) & 255) - ((sub >> 8) & 255);
                b += (add & 255) - (sub & 255);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horiz) {
        return horiz ? line * w + i : i * w + line;
    }
}
