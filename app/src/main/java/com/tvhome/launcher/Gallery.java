package com.tvhome.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ambient wallpaper library.
 *
 * Photos come from Wikimedia Commons "featured picture" search results, discovered at runtime
 * through the public MediaWiki API (no hard-coded image URLs, nothing fabricated). Every image
 * is freely licensed; author + licence are kept for on-screen credit. Abstract wallpapers are
 * generated on-device so the launcher always has something to show, even offline.
 */
final class Gallery {
    static final class Cat {
        final String id, title;
        final String[] queries;

        Cat(String id, String title, String... queries) {
            this.id = id;
            this.title = title;
            this.queries = queries;
        }
    }

    static final class Wp {
        String id, cat, title = "", artist = "", license = "", page = "", orig = "", thumb = "";
        int w, h, abs = -1;

        JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id).put("cat", cat).put("title", title).put("artist", artist).put("license", license)
                        .put("page", page).put("orig", orig).put("thumb", thumb).put("w", w).put("h", h).put("abs", abs);
                return o;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        static Wp fromJson(JSONObject o) {
            Wp w = new Wp();
            w.id = o.optString("id");
            w.cat = o.optString("cat");
            w.title = o.optString("title");
            w.artist = o.optString("artist");
            w.license = o.optString("license");
            w.page = o.optString("page");
            w.orig = o.optString("orig");
            w.thumb = o.optString("thumb");
            w.w = o.optInt("w");
            w.h = o.optInt("h");
            w.abs = o.optInt("abs", -1);
            return w;
        }
    }

    interface Cb {
        void done(Bitmap b);
    }

    static final String FAV = "fav", ABSTRACT = "abstract";
    static final Cat[] CATS = {
            new Cat("beach", "Tropical Beaches", "tropical beach", "beach palm trees sea"),
            new Cat("snow", "Snowy Mountains", "snow mountain", "snowy peaks alps", "glacier winter mountains"),
            new Cat("city", "Luxury Cityscapes", "city skyline night", "skyline skyscrapers dusk", "cityscape"),
            new Cat("africa", "African Wildlife & Savannah", "savanna", "Serengeti OR Masai Mara OR Amboseli OR Kruger", "giraffe OR elephant OR lion Africa"),
            new Cat("forest", "Forests & Waterfalls", "waterfall", "forest"),
            new Cat("sea", "Underwater", "underwater", "coral reef"),
            new Cat("space", "Space & Galaxies", "galaxy", "nebula", "Milky Way night sky"),
            new Cat("arch", "Modern Architecture", "modern architecture", "architecture interior", "glass building"),
            new Cat(ABSTRACT, "Minimalist Abstract"),
            new Cat("aerial", "Aerial & Drone", "aerial view", "drone aerial", "aerial photograph landscape")};

    private static final String UA = "TvHomeLauncher/1.0 (personal Android TV launcher; Wikimedia Commons API client)";
    private static final String[] FILTERS = {" haswbstatement:P6731=Q63348049", " incategory:\"Featured pictures on Wikimedia Commons\"", ""};
    private static final long STALE_MS = 7L * 24 * 3600 * 1000;
    private static final long CACHE_MAX = 600L << 20, CACHE_KEEP = 450L << 20;
    private static Gallery inst;

    static synchronized Gallery get(Context c) {
        if (inst == null) inst = new Gallery(c.getApplicationContext());
        return inst;
    }

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService net = Executors.newFixedThreadPool(2), thumbs = Executors.newFixedThreadPool(2),
            cpu = Executors.newSingleThreadExecutor();
    private final Context ctx;
    private final SharedPreferences sp;
    private final File imgDir, thDir, listDir;
    private final Map<String, List<Wp>> lists = new HashMap<>();
    private final List<Wp> favs = new ArrayList<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Random rnd = new Random();
    private int screenW = 1920;
    volatile boolean online = true;

    private Gallery(Context c) {
        ctx = c;
        sp = new Cfg(c).sp;
        File root = new File(c.getCacheDir(), "wp");
        imgDir = new File(root, "img");
        thDir = new File(root, "th");
        listDir = new File(root, "lists");
        imgDir.mkdirs();
        thDir.mkdirs();
        listDir.mkdirs();
        try {
            JSONArray a = new JSONArray(sp.getString("favs", "[]"));
            for (int i = 0; i < a.length(); i++) favs.add(Wp.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
    }

    void setScreenWidth(int w) {
        screenW = w;
    }

    // ------------------------------------------------------------------ catalog

    static Wp abstractWp(int i) {
        Wp w = new Wp();
        w.id = "abs" + i;
        w.cat = ABSTRACT;
        w.title = Procedural.NAMES[i];
        w.artist = "Generated on device";
        w.license = "No attribution needed";
        w.abs = i;
        return w;
    }

    List<Wp> listFor(String catId) {
        if (FAV.equals(catId)) return new ArrayList<>(favs);
        if (ABSTRACT.equals(catId)) {
            List<Wp> l = new ArrayList<>();
            for (int i = 0; i < Procedural.NAMES.length; i++) l.add(abstractWp(i));
            return l;
        }
        synchronized (lists) {
            List<Wp> l = lists.get(catId);
            return l == null ? new ArrayList<Wp>() : new ArrayList<>(l);
        }
    }

    /** Loads cached catalogs, then refreshes stale or missing ones from the Commons API. */
    void refresh(final Runnable onProgress) {
        if (!refreshing.compareAndSet(false, true)) return;
        net.execute(() -> {
            try {
                for (Cat c : CATS) {
                    if (c.queries.length == 0) continue;
                    File f = new File(listDir, c.id + ".json");
                    List<Wp> cached = readList(f);
                    if (cached != null) {
                        synchronized (lists) {
                            lists.put(c.id, cached);
                        }
                        main.post(onProgress);
                    }
                    if (cached != null && System.currentTimeMillis() - f.lastModified() < STALE_MS) continue;
                    List<Wp> fresh = fetchCat(c);
                    if (fresh != null && !fresh.isEmpty()) {
                        writeList(f, fresh);
                        synchronized (lists) {
                            lists.put(c.id, fresh);
                        }
                        online = true;
                        main.post(onProgress);
                    }
                }
            } finally {
                refreshing.set(false);
            }
        });
    }

    private List<Wp> readList(File f) {
        if (!f.exists()) return null;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
            r.close();
            JSONArray a = new JSONArray(sb.toString());
            List<Wp> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(Wp.fromJson(a.getJSONObject(i)));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeList(File f, List<Wp> l) {
        try {
            JSONArray a = new JSONArray();
            for (Wp w : l) a.put(w.toJson());
            Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(a.toString());
            w.close();
        } catch (Exception ignored) {
        }
    }

    private List<Wp> fetchCat(Cat c) {
        LinkedHashMap<String, Wp> out = new LinkedHashMap<>();
        for (String q : c.queries) {
            for (String filter : FILTERS) {
                List<Wp> r = search(c, q + filter);
                if (r == null) {
                    online = false;
                    return out.isEmpty() ? null : new ArrayList<>(out.values());
                }
                if (!r.isEmpty()) {
                    for (Wp w : r) out.put(w.id, w);
                    break;
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** @return null on network error, otherwise (possibly empty) list of usable landscape JPEGs */
    private List<Wp> search(Cat c, String query) {
        try {
            String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2"
                    + "&generator=search&gsrnamespace=6&gsrlimit=50&gsrsearch=" + URLEncoder.encode(query + " filetype:bitmap", "UTF-8")
                    + "&prop=imageinfo&iiprop=" + URLEncoder.encode("url|size|mime|extmetadata", "UTF-8")
                    + "&iiextmetadatafilter=" + URLEncoder.encode("Artist|LicenseShortName|ObjectName", "UTF-8")
                    + "&iiurlwidth=3840";
            String body = httpString(url);
            if (body == null) return null;
            JSONObject root = new JSONObject(body);
            List<Wp> out = new ArrayList<>();
            JSONObject q = root.optJSONObject("query");
            if (q == null) return out;
            JSONArray pages = q.optJSONArray("pages");
            if (pages == null) return out;
            for (int i = 0; i < pages.length(); i++) {
                JSONObject p = pages.getJSONObject(i);
                JSONArray ii = p.optJSONArray("imageinfo");
                if (ii == null || ii.length() == 0) continue;
                JSONObject info = ii.getJSONObject(0);
                int w = info.optInt("width"), h = info.optInt("height");
                if (h <= 0 || !"image/jpeg".equals(info.optString("mime"))) continue;
                float ratio = w / (float) h;
                if (w < 3000 || ratio < 1.45f || ratio > 2.4f) continue;
                Wp wp = new Wp();
                String title = p.optString("title");
                wp.id = md5(title);
                wp.cat = c.id;
                wp.w = w;
                wp.h = h;
                wp.orig = info.optString("url");
                wp.thumb = info.optString("thumburl", wp.orig);
                wp.page = info.optString("descriptionurl");
                JSONObject meta = info.optJSONObject("extmetadata");
                String name = title.replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z]+$", "");
                if (meta != null) {
                    String on = metaVal(meta, "ObjectName");
                    if (!on.isEmpty()) name = on;
                    wp.artist = metaVal(meta, "Artist");
                    wp.license = metaVal(meta, "LicenseShortName");
                }
                wp.title = name.length() > 90 ? name.substring(0, 90) + "…" : name;
                if (wp.artist.length() > 60) wp.artist = wp.artist.substring(0, 60) + "…";
                if (wp.orig.isEmpty()) continue;
                out.add(wp);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static String metaVal(JSONObject meta, String key) {
        JSONObject o = meta.optJSONObject(key);
        if (o == null) return "";
        String v = o.optString("value", "");
        return Html.fromHtml(v, Html.FROM_HTML_MODE_LEGACY).toString().replace('\n', ' ').trim();
    }

    // ------------------------------------------------------------------ http / cache

    private String httpString(String url) {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "application/json");
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                int code = c.getResponseCode();
                if (code == 429 || code >= 500) {
                    Thread.sleep(1500);
                    continue;
                }
                if (code != 200) return null;
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                r.close();
                return sb.toString();
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    private boolean download(String url, File dest) {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            File tmp = new File(dest.getPath() + ".part");
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", UA);
                c.setConnectTimeout(15000);
                c.setReadTimeout(45000);
                int code = c.getResponseCode();
                if (code == 429 || code >= 500) {
                    Thread.sleep(2000);
                    continue;
                }
                if (code != 200) return false;
                InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(tmp);
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                if (tmp.length() < 1024) {
                    tmp.delete();
                    return false;
                }
                return tmp.renameTo(dest);
            } catch (Exception e) {
                tmp.delete();
                return false;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
    }

    private static final Pattern PX = Pattern.compile("/(\\d+)px-");

    private static String urlAtWidth(Wp w, int width) {
        Matcher m = PX.matcher(w.thumb);
        if (m.find()) return w.thumb.substring(0, m.start()) + "/" + width + "px-" + w.thumb.substring(m.end());
        return w.thumb;
    }

    private File ensureFull(Wp w) {
        boolean uhd = screenW >= 2560;
        int width = uhd && w.w >= 3840 ? 3840 : 1920;
        File f = new File(imgDir, w.id + "_" + (uhd && w.w < 3840 ? "o" : width) + ".jpg");
        if (f.exists() && f.length() > 1024) {
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        String url = (uhd && w.w < 3840) ? w.orig : urlAtWidth(w, width);
        if (!download(url, f) && !url.equals(w.orig)) download(w.orig, f);
        if (!f.exists()) return null;
        trim();
        return f;
    }

    private void trim() {
        File[] fs = imgDir.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total < CACHE_MAX) return;
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : fs) {
            if (total < CACHE_KEEP) break;
            total -= f.length();
            f.delete();
        }
    }

    private static Bitmap decode(File f, int targetW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (o.outWidth / (s * 2) >= targetW) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // ------------------------------------------------------------------ images

    /** Full-screen wallpaper (decoded near screen size). Callback on the main thread; null on failure. */
    void loadFull(final Wp w, final int sw, final int sh, final Cb cb) {
        net.execute(() -> {
            Bitmap b = null;
            try {
                if (w.abs >= 0) b = Procedural.abstractWp(w.abs, Math.min(sw, 1280), Math.min(sh, 720));
                else {
                    File f = ensureFull(w);
                    if (f != null) b = decode(f, sw);
                }
            } catch (Throwable ignored) {
            }
            final Bitmap fb = b;
            main.post(() -> cb.done(fb));
        });
    }

    void loadThumb(final Wp w, final Cb cb) {
        thumbs.execute(() -> {
            Bitmap b = null;
            try {
                if (w.abs >= 0) b = Procedural.abstractWp(w.abs, 480, 270);
                else {
                    File f = new File(thDir, w.id + ".jpg");
                    if (!f.exists()) download(urlAtWidth(w, 500), f);
                    if (f.exists()) b = decode(f, 480);
                }
            } catch (Throwable ignored) {
            }
            final Bitmap fb = b;
            main.post(() -> cb.done(fb));
        });
    }

    static String credit(Wp w) {
        if (w == null) return "";
        if (w.abs >= 0) return w.title + "  ·  generated on device";
        StringBuilder sb = new StringBuilder(w.title);
        if (!w.artist.isEmpty()) sb.append("  ·  ").append(w.artist);
        if (!w.license.isEmpty()) sb.append("  ·  ").append(w.license);
        sb.append("  ·  Wikimedia Commons");
        return sb.toString();
    }

    // ------------------------------------------------------------------ state

    Wp current() {
        try {
            String s = sp.getString("wp_cur", null);
            if (s != null) return Wp.fromJson(new JSONObject(s));
        } catch (Exception ignored) {
        }
        return null;
    }

    void setCurrent(Wp w) {
        sp.edit().putString("wp_cur", w.toJson().toString()).apply();
        recent.addLast(w.id);
        while (recent.size() > 12) recent.removeFirst();
    }

    boolean isFav(Wp w) {
        for (Wp f : favs) if (f.id.equals(w.id)) return true;
        return false;
    }

    /** @return new favourite state */
    boolean toggleFav(Wp w) {
        for (Iterator<Wp> it = favs.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(w.id)) {
                it.remove();
                saveFavs();
                return false;
            }
        }
        favs.add(w);
        saveFavs();
        return true;
    }

    private void saveFavs() {
        JSONArray a = new JSONArray();
        for (Wp w : favs) a.put(w.toJson());
        sp.edit().putString("favs", a.toString()).apply();
    }

    boolean catEnabled(String id) {
        return !sp.getString("cat_off", "").contains("|" + id + "|");
    }

    void setCatEnabled(String id, boolean on) {
        String s = sp.getString("cat_off", "").replace("|" + id + "|", "");
        if (!on) s += "|" + id + "|";
        sp.edit().putString("cat_off", s).apply();
    }

    /** Next wallpaper for rotation, or null if nothing is available. */
    Wp pickNext(boolean favoritesOnly) {
        if (favoritesOnly && !favs.isEmpty()) return favs.get(rnd.nextInt(favs.size()));
        List<String> cats = new ArrayList<>();
        for (Cat c : CATS) if (catEnabled(c.id) && !listFor(c.id).isEmpty()) cats.add(c.id);
        if (cats.isEmpty()) return null;
        for (int tries = 0; tries < 14; tries++) {
            List<Wp> l = listFor(cats.get(rnd.nextInt(cats.size())));
            Wp w = l.get(rnd.nextInt(l.size()));
            if (!recent.contains(w.id)) return w;
        }
        List<Wp> l = listFor(cats.get(rnd.nextInt(cats.size())));
        return l.get(rnd.nextInt(l.size()));
    }
}
