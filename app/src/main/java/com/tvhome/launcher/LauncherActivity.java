package com.tvhome.launcher;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LauncherActivity extends android.app.Activity {
    private enum Zone {PILLS, ROW, DOCK}

    private final Handler main = new Handler(Looper.getMainLooper());
    private Cfg cfg;
    private AppRepo repo;
    private Gallery gallery;
    private BlurSource blur;
    private int W, H;
    private float u;

    private FrameLayout root, ui;
    private WallpaperLayer wall;
    private GlassDockView dock;
    private ContentRowView row;
    private AppGridView grid;
    private GalleryView gv;
    private LinearLayout topLeft, pillBar;
    private TextView greeting, clock, date, wifiPill, saverClock, saverCredit;
    private View saverBox;
    private final List<TextView> pills = new ArrayList<>();
    private final ArrayDeque<Overlay> stack = new ArrayDeque<>();

    private Zone zone = Zone.DOCK;
    private int pillSel = 2;
    private boolean okLong, saver, resumed, wantPhoto, entered;
    private int wpGen, failCount;
    private long lastRotate;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Point p = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(p);
        W = Math.max(p.x, p.y);
        H = Math.min(p.x, p.y);
        u = H / 1080f;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        cfg = new Cfg(this);
        repo = new AppRepo(this, cfg, getResources().getStringArray(R.array.dock_apps));
        repo.load();
        gallery = Gallery.get(this);
        gallery.setScreenWidth(W);
        blur = new BlurSource(W, H);

        root = new FrameLayout(this);
        wall = new WallpaperLayer(this, cfg, W, H);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        ui = new FrameLayout(this);
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        buildTop();
        buildRowAndDock();
        buildSaver();
        buildOverlays();
        setContentView(root);

        dock.setApps(repo.pinnedApps());
        String last = cfg.sp.getString("last_pkg", null);
        if (last != null) dock.selectPackage(last);
        row.refresh();
        layoutUi();
        setZone(Zone.DOCK);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        // wallpaper: show the last one right away, then let the catalogue fill in
        Gallery.Wp cur = gallery.current();
        wantPhoto = cur == null;
        if (cur == null) cur = Gallery.abstractWp(0);
        applyWallpaper(cur);
        gallery.refresh(this::onCatalog);
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            repo.load();
            dock.setApps(repo.pinnedApps());
            layoutUi();
            row.refresh();
            if (grid != null && stack.contains(grid)) grid.rebuild(null);
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Exception ignored) {
        }
        main.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening");
        updateWifi();
        updateClock();
        dock.resetLaunch();
        row.refresh();
        layoutUi();
        if (zone == Zone.ROW && !row.hasItems()) setZone(Zone.DOCK);
        if (!entered) {
            entered = true;
            enterAnimation();
        }
        main.removeCallbacks(clockTask);
        main.postDelayed(clockTask, 15000);
        if (!gallery.online) gallery.refresh(this::onCatalog); // retry after being offline
        if (cfg.on(Cfg.ROTATE) && System.currentTimeMillis() - lastRotate > cfg.rotateMs() && lastRotate != 0 && stack.isEmpty()) rotateNow();
        else scheduleRotation();
        resetIdle();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(rotateTask);
        main.removeCallbacks(idleTask);
        main.removeCallbacks(clockTask);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed: close everything and come back to the dock
        if (saver) exitSaver();
        while (!stack.isEmpty()) {
            Overlay o = stack.pop();
            root.removeView(o);
        }
        ui.setAlpha(1f);
        if (dock.isEditing()) dock.setEdit(false);
        dock.setApps(repo.pinnedApps());
        row.refresh();
        layoutUi();
        setZone(Zone.DOCK);
    }

    @Override
    public void onBackPressed() {
        // A launcher never exits on Back (dispatchKeyEvent normally handles it first)
    }

    // ------------------------------------------------------------------ UI construction

    private int px(float v) {
        return Math.round(v * u);
    }

    private TextView text(float size, int wght, int color) {
        TextView t = new TextView(this);
        t.setTypeface(Txt.face(this));
        t.setFontVariationSettings("'wght' " + wght);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTextColor(color);
        t.setShadowLayer(8 * u, 0, 2 * u, 0xAA000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    private float margin() {
        return Math.max(0.05f * W, 48 * u);
    }

    private void buildTop() {
        topLeft = new LinearLayout(this);
        topLeft.setOrientation(LinearLayout.VERTICAL);
        greeting = text(22, 300, 0xCCFFFFFF);
        clock = text(92, 200, Color.WHITE);
        date = text(23, 300, 0xCCFFFFFF);
        topLeft.addView(greeting);
        topLeft.addView(clock);
        topLeft.addView(date);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.leftMargin = (int) margin();
        tl.topMargin = px(48);
        ui.addView(topLeft, tl);

        pillBar = new LinearLayout(this);
        pillBar.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Inputs", "Wi-Fi", "Wallpapers", "Customize"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(21, 400, Color.WHITE);
            t.setText(names[i]);
            t.setPadding(px(26), px(13), px(26), px(13));
            t.setClickable(true);
            t.setOnClickListener(v -> {
                pillSel = idx;
                setZone(Zone.PILLS);
                activatePill(idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = px(14);
            pillBar.addView(t, lp);
            pills.add(t);
        }
        wifiPill = pills.get(1);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        pl.rightMargin = (int) margin();
        pl.topMargin = px(54);
        ui.addView(pillBar, pl);
    }

    private void buildRowAndDock() {
        row = new ContentRowView(this, cfg, repo, W, u, new ContentRowView.Host() {
            @Override
            public void launch(AppRepo.App a) {
                launchApp(a);
            }

            @Override
            public void focusUp() {
                setZone(Zone.PILLS);
            }

            @Override
            public void focusDown() {
                setZone(Zone.DOCK);
            }

            @Override
            public void focusRow() {
                setZone(Zone.ROW);
            }
        });
        ui.addView(row, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        dock = new GlassDockView(this, cfg, repo, blur, W, u, new GlassDockView.Host() {
            @Override
            public void launch(AppRepo.App a) {
                launchApp(a);
            }

            @Override
            public void openAll() {
                openAllApps(null);
            }

            @Override
            public void pinsChanged(List<String> pkgs) {
                repo.savePins(pkgs);
            }

            @Override
            public void focusUp() {
                setZone(row.hasItems() ? Zone.ROW : Zone.PILLS);
            }

            @Override
            public void focusDock() {
                setZone(Zone.DOCK);
            }

            @Override
            public void editChanged(boolean on) {
                row.animate().setStartDelay(0).alpha(on ? 0.15f : 1f).setDuration(200).start();
                if (!on) {
                    dock.setApps(repo.pinnedApps());
                    layoutUi();
                }
            }
        });
        ui.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private void buildSaver() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        saverClock = text(120, 200, Color.WHITE);
        saverCredit = text(21, 300, 0xCCFFFFFF);
        box.addView(saverClock);
        box.addView(saverCredit);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = (int) margin();
        lp.bottomMargin = px(70);
        box.setAlpha(0f);
        box.setVisibility(View.GONE);
        saverBox = box;
        root.addView(box, lp);
    }

    private void buildOverlays() {
        grid = new AppGridView(this, cfg, repo, blur, W, H, u, getResources().getInteger(R.integer.columns), new AppGridView.Host() {
            @Override
            public void launch(AppRepo.App a) {
                launchApp(a);
            }

            @Override
            public void appMenu(AppRepo.App a) {
                showAppMenu(a);
            }
        });
        gv = new GalleryView(this, cfg, gallery, blur, W, H, u, new GalleryView.Host() {
            @Override
            public void setWallpaper(Gallery.Wp w) {
                gallery.setCurrent(w);
                applyWallpaper(w);
                gv.currentChanged(w);
                scheduleRotation();
                toast("Wallpaper set");
            }

            @Override
            public void toast(String s) {
                LauncherActivity.this.toast(s);
            }
        });
    }

    private void enterAnimation() {
        dock.setTranslationY(px(110));
        dock.setAlpha(0f);
        dock.animate().translationY(0).alpha(1f).setDuration(750).setInterpolator(new OvershootInterpolator(0.9f)).start();
        for (View v : new View[]{topLeft, pillBar, row}) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setStartDelay(200).setDuration(600).start();
        }
    }

    private void layoutUi() {
        float gapBottom = new float[]{40, 72, 170}[cfg.get(Cfg.POS)] * u;
        FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) dock.getLayoutParams();
        dl.gravity = Gravity.BOTTOM;
        dl.bottomMargin = (int) (gapBottom - 44 * u);
        dock.setLayoutParams(dl);
        dock.applySettings();
        FrameLayout.LayoutParams rl = (FrameLayout.LayoutParams) row.getLayoutParams();
        rl.height = Math.max(1, row.wantedHeight());
        rl.bottomMargin = (int) (gapBottom - 44 * u + dock.viewHeight() - dock.headroom() * 0.5f);
        row.setLayoutParams(rl);
        row.setVisibility(row.hasItems() ? View.VISIBLE : View.GONE);
        wall.applySettings();
    }

    // ------------------------------------------------------------------ top bar helpers

    private final Runnable clockTask = new Runnable() {
        @Override
        public void run() {
            updateClock();
            main.postDelayed(this, 15000);
        }
    };

    private void updateClock() {
        Date now = new Date();
        String f = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        CharSequence t = DateFormat.format(f, now);
        clock.setText(t);
        saverClock.setText(t);
        date.setText(DateFormat.format("EEEE, d MMMM", now));
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            @SuppressWarnings("deprecation") WifiInfo wi = wm.getConnectionInfo();
            String s = wi != null ? wi.getSSID() : null;
            if (s != null && !s.contains("unknown ssid") && !s.equals("0x")) name = s.replace("\"", "");
        } catch (Exception ignored) {
        }
        wifiPill.setText(name);
    }

    private void stylePills() {
        for (int i = 0; i < pills.size(); i++) {
            boolean f = zone == Zone.PILLS && i == pillSel;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(40));
            g.setColor(f ? 0x4DFFFFFF : 0x2E10131A);
            g.setStroke(Math.max(1, px(f ? 2.2f : 1.2f)), f ? ((cfg.accent() & 0xFFFFFF) | 0xE6000000) : 0x33FFFFFF);
            pills.get(i).setBackground(g);
            pills.get(i).animate().scaleX(f ? 1.07f : 1f).scaleY(f ? 1.07f : 1f).setDuration(160).start();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ focus zones

    private void setZone(Zone z) {
        if (z == Zone.ROW && !row.hasItems()) z = Zone.PILLS;
        zone = z;
        dock.setActive(z == Zone.DOCK);
        row.setActive(z == Zone.ROW);
        stylePills();
    }

    private void activatePill(int i) {
        try {
            if (i == 0) showInputs();
            else if (i == 1) startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            else if (i == 2) openGallery();
            else showCustomize();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (saver) {
            if (down) exitSaver();
            return true;
        }
        resetIdle();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok) {
            if (down) {
                if (e.getRepeatCount() == 0) okLong = false;
                else if (e.getRepeatCount() == 1 && !okLong) {
                    okLong = true;
                    handleLongOk();
                }
            } else {
                if (!okLong) handleOk();
                okLong = false;
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) handleBack();
            return true;
        }
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MENU;
        if (nav) {
            if (down) handleNav(k);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (saver) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) exitSaver();
            return true;
        }
        resetIdle();
        return super.dispatchTouchEvent(ev);
    }

    private void handleNav(int k) {
        if (!stack.isEmpty()) {
            stack.peek().nav(k);
            return;
        }
        switch (zone) {
            case DOCK:
                dock.onKey(k);
                break;
            case ROW:
                if (!row.onKey(k) && k == KeyEvent.KEYCODE_MENU) setZone(Zone.DOCK);
                break;
            default:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) pillSel = Math.max(0, pillSel - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) pillSel = Math.min(pills.size() - 1, pillSel + 1);
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    setZone(row.hasItems() ? Zone.ROW : Zone.DOCK);
                    return;
                }
                stylePills();
        }
    }

    private void handleOk() {
        if (!stack.isEmpty()) {
            stack.peek().ok();
            return;
        }
        if (zone == Zone.DOCK) dock.onKey(KeyEvent.KEYCODE_DPAD_CENTER);
        else if (zone == Zone.ROW) row.onKey(KeyEvent.KEYCODE_DPAD_CENTER);
        else activatePill(pillSel);
    }

    private void handleLongOk() {
        if (!stack.isEmpty()) {
            stack.peek().longOk();
            return;
        }
        if (zone == Zone.DOCK) dock.longPressOk();
    }

    private void handleBack() {
        if (!stack.isEmpty()) {
            stack.peek().back();
            return;
        }
        if (dock.isEditing()) dock.setEdit(false);
        else if (zone != Zone.DOCK) setZone(Zone.DOCK);
    }

    // ------------------------------------------------------------------ overlays

    private void showOverlay(final Overlay o, boolean hideUi) {
        if (o.getParent() != null) return;
        root.addView(o, new FrameLayout.LayoutParams(-1, -1));
        stack.push(o);
        o.onClose = () -> {
            root.removeView(o);
            stack.remove(o);
            if (stack.isEmpty()) {
                ui.animate().alpha(1f).setDuration(200).start();
                dock.setApps(repo.pinnedApps());
                row.refresh();
                layoutUi();
                setZone(zone);
                scheduleRotation();
                resetIdle();
            } else stack.peek().invalidate();
        };
        if (hideUi) ui.animate().alpha(0f).setDuration(200).start();
        o.open();
    }

    private void closeAllOverlays() {
        for (Overlay o : new ArrayList<>(stack)) o.close();
    }

    private void openAllApps(String focusPkg) {
        grid.showHidden = false;
        grid.rebuild(focusPkg);
        showOverlay(grid, true);
    }

    private void openGallery() {
        gv.openFor(gallery.current());
        showOverlay(gv, true);
    }

    private MenuView newMenu() {
        return new MenuView(this, cfg, blur, W, H, u);
    }

    private void showAppMenu(final AppRepo.App a) {
        final MenuView m = newMenu();
        List<MenuView.Row> rows = new ArrayList<>();
        rows.add(MenuView.Row.action("Open", () -> {
            closeAllOverlays();
            launchApp(a);
        }));
        final boolean pinned = repo.isPinned(a.pkg);
        rows.add(MenuView.Row.action(pinned ? "Unpin from dock" : "Pin to dock", () -> {
            if (pinned) repo.unpin(a.pkg);
            else if (!repo.pin(a.pkg)) toast("The dock is full (" + AppRepo.MAX_PINS + " apps). Unpin one first.");
            m.close();
        }));
        final boolean hidden = repo.isHidden(a.pkg);
        rows.add(MenuView.Row.action(hidden ? "Unhide app" : "Hide from All Apps", () -> {
            repo.setHidden(a.pkg, !hidden);
            m.close();
            grid.rebuild(hidden ? a.pkg : null);
        }));
        rows.add(MenuView.Row.action("Hidden apps", () -> grid.showHidden ? "Shown" : "Not shown", () -> {
            grid.showHidden = !grid.showHidden;
            m.close();
            grid.rebuild(a.pkg);
        }));
        showOverlay(m.setup(a.label, "App options", rows), false);
    }

    private void showCustomize() {
        final MenuView m = newMenu();
        List<MenuView.Row> rows = new ArrayList<>();
        rows.add(MenuView.Row.header("Dock"));
        Runnable dockChanged = () -> {
            dock.applySettings();
            layoutUi();
            row.refresh();
            layoutUi();
            stylePills();
        };
        for (Cfg.Opt o : Cfg.DOCK_OPTS) rows.add(MenuView.Row.choice(cfg, o, dockChanged));
        rows.add(MenuView.Row.action("Edit dock layout…", () -> {
            closeAllOverlays();
            main.postDelayed(() -> {
                setZone(Zone.DOCK);
                dock.setEdit(true);
            }, 250);
        }));
        rows.add(MenuView.Row.action("Reset dock to defaults", () -> {
            for (Cfg.Opt o : Cfg.DOCK_OPTS) cfg.set(o, o.def);
            cfg.sp.edit().remove("pins").apply();
            dock.setApps(repo.pinnedApps());
            dockChanged.run();
            toast("Dock reset");
            m.close();
        }));
        rows.add(MenuView.Row.header("Wallpapers & screensaver"));
        Runnable wallChanged = () -> {
            wall.applySettings();
            scheduleRotation();
            resetIdle();
        };
        for (Cfg.Opt o : Cfg.WALL_OPTS) rows.add(MenuView.Row.choice(cfg, o, wallChanged));
        rows.add(MenuView.Row.action("Open Ambient Gallery…", () -> {
            m.close();
            main.postDelayed(this::openGallery, 260);
        }));
        rows.add(MenuView.Row.action("Next wallpaper now", () -> rotateNow()));
        rows.add(MenuView.Row.action("Start screensaver now", () -> {
            closeAllOverlays();
            main.postDelayed(this::enterSaver, 350);
        }));
        rows.add(MenuView.Row.header("Apps"));
        rows.add(MenuView.Row.action("Show hidden apps…", () -> {
            m.close();
            main.postDelayed(() -> {
                grid.showHidden = true;
                grid.rebuild(null);
                showOverlay(grid, true);
            }, 260);
        }));
        showOverlay(m.setup("Customize", "Dock appearance, wallpapers and screensaver", rows), false);
    }

    private void showInputs() {
        final MenuView m = newMenu();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<MenuView.Row> rows = new ArrayList<>();
        List<TvInputInfo> list = tim != null ? tim.getTvInputList() : new ArrayList<TvInputInfo>();
        for (final TvInputInfo info : list) {
            final boolean tuner = info.getType() == TvInputInfo.TYPE_TUNER;
            if (!info.isPassthroughInput() && !tuner) continue;
            CharSequence label = info.loadCustomLabel(this);
            if (label == null || label.length() == 0) label = info.loadLabel(this);
            if (tuner) label = "Live TV";
            rows.add(MenuView.Row.action(String.valueOf(label), () -> {
                closeAllOverlays();
                switchInput(info, tuner);
            }));
        }
        if (rows.isEmpty()) rows.add(MenuView.Row.header("No inputs found"));
        showOverlay(m.setup("Inputs", "Switch what the TV is showing", rows), false);
    }

    private void switchInput(TvInputInfo info, boolean tuner) {
        try {
            if (tuner) {
                Intent li = getPackageManager().getLeanbackLaunchIntentForPackage("com.mediatek.wwtv.tvcenter");
                if (li != null) {
                    startActivity(li);
                    return;
                }
            }
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ launching

    private void launchApp(final AppRepo.App a) {
        if (a == null || a.intent == null) return;
        repo.recordLaunch(a);
        cfg.sp.edit().putString("last_pkg", a.pkg).apply();
        final Runnable go = () -> {
            try {
                startActivity(a.intent);
            } catch (Exception e) {
                toast("Can't open " + a.label);
                dock.resetLaunch();
            }
        };
        if (stack.isEmpty() && zone == Zone.DOCK && !cfg.reduced()) dock.launchAnim(go);
        else {
            go.run();
            if (!stack.isEmpty()) closeAllOverlays();
        }
    }

    // ------------------------------------------------------------------ wallpapers

    private void applyWallpaper(final Gallery.Wp w) {
        final int gen = ++wpGen;
        gallery.loadFull(w, W, H, bmp -> {
            if (gen != wpGen) return;
            if (bmp == null) {
                // image failed (offline?): try another, then fall back to a generated one
                if (++failCount <= 2) {
                    Gallery.Wp alt = gallery.pickNext(false);
                    if (alt != null && alt.abs < 0) {
                        gallery.setCurrent(alt);
                        applyWallpaper(alt);
                        return;
                    }
                }
                failCount = 0;
                if (w.abs < 0) {
                    Gallery.Wp fb = Gallery.abstractWp((int) (Math.random() * Procedural.NAMES.length));
                    gallery.setCurrent(fb);
                    applyWallpaper(fb);
                }
                return;
            }
            failCount = 0;
            lastRotate = System.currentTimeMillis();
            wall.show(bmp);
            saverCredit.setText(Gallery.credit(w));
            final android.graphics.Bitmap srcBmp = bmp;
            gallery.cpu.execute(() -> {
                try {
                    final android.graphics.Bitmap[] b = Procedural.blurs(srcBmp, W, H);
                    main.post(() -> {
                        if (gen == wpGen) blur.set(b);
                    });
                } catch (Throwable ignored) {
                }
            });
        });
    }

    private void onCatalog() {
        gv.catalogUpdated();
        if (wantPhoto) {
            for (int i = 0; i < 10; i++) {
                Gallery.Wp w = gallery.pickNext(false);
                if (w != null && w.abs < 0) {
                    wantPhoto = false;
                    gallery.setCurrent(w);
                    applyWallpaper(w);
                    scheduleRotation();
                    return;
                }
            }
        }
    }

    private final Runnable rotateTask = new Runnable() {
        @Override
        public void run() {
            boolean busy = stack.contains(gv) || !resumed;
            if (busy) scheduleRotation();
            else rotateNow();
        }
    };

    private void scheduleRotation() {
        main.removeCallbacks(rotateTask);
        if (!resumed) return;
        if (!saver && !cfg.on(Cfg.ROTATE)) return;
        main.postDelayed(rotateTask, saver ? 45000L : cfg.rotateMs());
    }

    private void rotateNow() {
        Gallery.Wp w = gallery.pickNext(cfg.on(Cfg.FAVONLY));
        if (w != null) {
            gallery.setCurrent(w);
            applyWallpaper(w);
            if (stack.contains(gv)) gv.currentChanged(w);
        } else toast("No wallpapers available yet");
        scheduleRotation();
    }

    // ------------------------------------------------------------------ screensaver

    private final Runnable idleTask = new Runnable() {
        @Override
        public void run() {
            if (resumed && stack.isEmpty() && !dock.isEditing()) enterSaver();
            else resetIdle();
        }
    };

    private void resetIdle() {
        main.removeCallbacks(idleTask);
        long d = cfg.saverMs();
        if (d > 0 && resumed && !saver) main.postDelayed(idleTask, d);
    }

    private void enterSaver() {
        if (saver || !resumed) return;
        saver = true;
        main.removeCallbacks(idleTask);
        ui.animate().alpha(0f).setDuration(900).start();
        dock.animate().translationY(px(90)).setDuration(900).start();
        saverBox.setVisibility(View.VISIBLE);
        saverBox.animate().alpha(1f).setStartDelay(500).setDuration(900).start();
        scheduleRotation();
    }

    private void exitSaver() {
        if (!saver) return;
        saver = false;
        saverBox.animate().cancel();
        saverBox.animate().alpha(0f).setStartDelay(0).setDuration(300).withEndAction(() -> saverBox.setVisibility(View.GONE)).start();
        ui.animate().alpha(1f).setDuration(450).start();
        dock.animate().translationY(0).setDuration(450).start();
        scheduleRotation();
        resetIdle();
    }
}
