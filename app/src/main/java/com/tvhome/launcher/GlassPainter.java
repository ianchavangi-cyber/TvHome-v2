package com.tvhome.launcher;

import android.graphics.*;

/**
 * Draws the "dark glass" material: layered shadow, real backdrop blur (from BlurSource),
 * tint, top sheen, hairline border, inner highlight, soft outer glow and a moving specular spot.
 */
final class GlassPainter {
    private final Cfg cfg;
    private final BlurSource blur;
    private final float u;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rr = new RectF();
    private final Path path = new Path();
    private final Matrix m = new Matrix(), sm = new Matrix();
    private final RadialGradient specShader = new RadialGradient(0, 0, 100f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private Bitmap kC, kP;
    private BitmapShader sC, sP;
    private float cTop = -1, cBot = -1;
    private int cTint, cAlpha = -1;

    GlassPainter(Cfg cfg, BlurSource blur, float u) {
        this.cfg = cfg;
        this.blur = blur;
        this.u = u;
        borderP.setStyle(Paint.Style.STROKE);
        innerP.setStyle(Paint.Style.STROKE);
        glowP.setStyle(Paint.Style.STROKE);
    }

    private BitmapShader shader(Bitmap b, boolean isCur) {
        if (isCur) {
            if (kC != b) {
                kC = b;
                sC = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            return sC;
        }
        if (kP != b) {
            kP = b;
            sP = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        return sP;
    }

    private void blurFill(Canvas c, RectF r, float rad, Bitmap b, boolean isCur, int alpha, float ox, float oy) {
        BitmapShader s = shader(b, isCur);
        m.setScale(blur.screenW / (float) b.getWidth(), blur.screenH / (float) b.getHeight());
        m.postTranslate(ox, oy);
        s.setLocalMatrix(m);
        fill.setShader(s);
        fill.setAlpha(alpha);
        c.drawRoundRect(r, rad, rad, fill);
    }

    /**
     * @param r    glass rectangle in canvas coordinates
     * @param ox   canvas.x = screen.x + ox  (i.e. minus the view's on-screen left)
     * @param hlX  x of the moving specular highlight (canvas coords) or NaN for none
     */
    void draw(Canvas c, RectF r, float rad, float ox, float oy, float hlX) {
        boolean fx = !cfg.reduced();
        int level = cfg.get(Cfg.BLUR);
        int tintRgb = cfg.tint() & 0xFFFFFF;
        int ta = Cfg.ALPHA_V[cfg.get(Cfg.ALPHA)];
        boolean haveBlur = fx && level > 0 && blur != null && blur.cur != null;
        if (!haveBlur) ta = Math.min(255, ta + 0x38);
        boolean light = cfg.lightPreset();

        // layered soft shadow, only outside the glass
        if (fx) {
            c.save();
            path.rewind();
            path.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipOutPath(path);
            for (int i = 0; i < 6; i++) {
                float e = u * (3 + i * 5);
                shadow.setColor(Color.argb(15, 0, 0, 0));
                rr.set(r.left - e, r.top - e + u * 16, r.right + e, r.bottom + e + u * 16);
                c.drawRoundRect(rr, rad + e, rad + e, shadow);
            }
            c.restore();
        }

        if (haveBlur) {
            if (blur.prev != null && blur.mix < 1f) {
                blurFill(c, r, rad, blur.prev[level - 1], false, 255, ox, oy);
                blurFill(c, r, rad, blur.cur[level - 1], true, (int) (255 * blur.mix), ox, oy);
            } else {
                blurFill(c, r, rad, blur.cur[level - 1], true, 255, ox, oy);
            }
        }

        if (cTop != r.top || cBot != r.bottom || cTint != tintRgb || cAlpha != ta) {
            cTop = r.top;
            cBot = r.bottom;
            cTint = tintRgb;
            cAlpha = ta;
            tintP.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    (Math.min(255, ta) << 24) | tintRgb, (Math.min(255, (int) (ta * 1.28f)) << 24) | tintRgb, Shader.TileMode.CLAMP));
            int topA = light ? 0x55 : 0x34;
            sheenP.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    new int[]{(topA << 24) | 0xFFFFFF, 0x0DFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.42f, 0.8f}, Shader.TileMode.CLAMP));
            borderP.setStrokeWidth(u * 1.6f);
            borderP.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x99FFFFFF, 0x2EFFFFFF, Shader.TileMode.CLAMP));
            innerP.setStrokeWidth(u * 1.1f);
            innerP.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(r, rad, rad, tintP);
        c.drawRoundRect(r, rad, rad, sheenP);

        if (fx && !Float.isNaN(hlX)) {
            sm.setScale(r.width() * 0.13f / 100f, r.height() * 0.95f / 100f);
            sm.postTranslate(hlX, r.top + r.height() * 0.32f);
            specShader.setLocalMatrix(sm);
            specP.setShader(specShader);
            c.drawRoundRect(r, rad, rad, specP);
        }

        float h = u * 0.8f;
        rr.set(r.left + h, r.top + h, r.right - h, r.bottom - h);
        c.drawRoundRect(rr, rad - h, rad - h, borderP);
        float in = u * 3.2f;
        rr.set(r.left + in, r.top + in, r.right - in, r.bottom - in);
        c.drawRoundRect(rr, Math.max(0, rad - in), Math.max(0, rad - in), innerP);

        if (fx) {
            glowP.setStrokeWidth(u * 7f);
            glowP.setColor((cfg.accent() & 0xFFFFFF) | 0x0F000000);
            float g = u * 3.5f;
            rr.set(r.left - g, r.top - g, r.right + g, r.bottom + g);
            c.drawRoundRect(rr, rad + g, rad + g, glowP);
        }
    }
}
