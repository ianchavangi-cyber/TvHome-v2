package com.tvhome.launcher;

import android.content.Context;
import android.graphics.*;

import java.util.ArrayList;
import java.util.List;

/** "All Apps" overlay. Hold OK on an app for pin / hide options. */
final class AppGridView extends GridBase {
    interface Host {
        void launch(AppRepo.App a);

        void appMenu(AppRepo.App a);
    }

    private final AppRepo repo;
    private final Host host;
    private final List<AppRepo.App> items = new ArrayList<>();
    private final Paint cardP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleP, subP, labelP, hintP;
    boolean showHidden;

    AppGridView(Context c, Cfg cfg, AppRepo repo, BlurSource blur, int sw, int sh, float u, int cols, Host host) {
        super(c, cfg, blur, sw, sh, u);
        this.repo = repo;
        this.host = host;
        this.cols = Math.max(4, Math.min(7, cols));
        titleP = Txt.paint(c, 40 * u, 600, 0xFFFFFFFF);
        subP = Txt.paint(c, 21 * u, 400, 0x99FFFFFF);
        labelP = Txt.paint(c, 25 * u, 500, 0xFFFFFFFF);
        labelP.setTextAlign(Paint.Align.CENTER);
        labelP.setShadowLayer(8 * u, 0, 2 * u, 0xCC000000);
        hintP = Txt.paint(c, 19 * u, 400, 0x88FFFFFF);
        hintP.setTextAlign(Paint.Align.CENTER);
    }

    void rebuild(String focusPkg) {
        items.clear();
        for (AppRepo.App a : repo.all) if (showHidden || !repo.isHidden(a.pkg)) items.add(a);
        int idx = 0;
        if (focusPkg != null) for (int i = 0; i < items.size(); i++) if (items.get(i).pkg.equals(focusPkg)) idx = i;
        placedReset(idx);
    }

    private void placedReset(int idx) {
        reset();
        if (idx > 0) snapTo(idx);
    }

    @Override
    int count() {
        return items.size();
    }

    AppRepo.App selected() {
        return sel < items.size() ? items.get(sel) : null;
    }

    @Override
    void drawTile(Canvas c, int i, RectF r, boolean focused) {
        AppRepo.App a = items.get(i);
        repo.ensureCard(a);
        boolean hid = repo.isHidden(a.pkg);
        cardP.setAlpha(hid ? 110 : 255);
        c.drawBitmap(a.card, null, r, cardP);
        float rad = r.height() * 0.128f;
        strokeP.setStrokeWidth(1.2f * u);
        strokeP.setColor(0x33FFFFFF);
        c.drawRoundRect(r, rad, rad, strokeP);
        if (repo.isPinned(a.pkg) && !hid) {
            fillP.setColor(0xE6FFFFFF);
            c.drawCircle(r.right - 16 * u, r.top + 16 * u, 6 * u, fillP);
        }
        if (focused) c.drawText(a.label, r.centerX(), r.bottom + 40 * u, labelP);
    }

    @Override
    void pick(int i) {
        host.launch(items.get(i));
    }

    @Override
    void menu(int i) {
        host.appMenu(items.get(i));
    }

    @Override
    void drawHeader(Canvas c) {
        float l = left();
        c.drawText("All Apps", l, 92 * u, titleP);
        int hidden = repo.hiddenSet().size();
        c.drawText(items.size() + " apps" + (showHidden ? "  ·  showing hidden apps" : hidden > 0 ? "  ·  " + hidden + " hidden" : "")
                + "   ·   ● pinned to dock", l, 126 * u, subP);
        c.drawText("Hold OK for pin / hide options", sw / 2f, sh - 40 * u, hintP);
    }

    @Override
    float gridTop() {
        return 188 * u;
    }
}
