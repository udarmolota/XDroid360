package com.udarmolota.xdroid360;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * One installed game: a game built on the phone from the player's disc (BuildService), an
 * older game package (docs/PACKAGE_FORMAT.md), or the development build's own game (the library
 * inside the APK, the game in the old dao/ folder). Its files: {@code root} (shared storage:
 * game/, userdata/, driver/, instance.json) and the game library in the app's internal files
 * (code can't be mapped from shared storage).
 */
final class Instance {
    static final String DEV_ID = "dev";
    static final String DAO_TITLE_ID = "454108C0";
    static final String SOD_TITLE_ID = "584111E8";
    static final String ME1_TITLE_ID = "4D5307E8";

    // Settings, kept in instance.json.
    /** auto: the built-in Turnip on an Adreno 8xx (what it is made for) or 7xx, else the phone's own. */
    static final String DRIVER_AUTO = "auto", DRIVER_BUILTIN = "builtin", DRIVER_SYSTEM = "system", DRIVER_CUSTOM = "custom";
    static final String SYSMEM_AUTO = "auto", SYSMEM_ON = "on", SYSMEM_OFF = "off";

    final String id;
    final File root;
    /** The game library to load, or null for the one inside the APK. */
    final File library;
    String name;
    JSONObject manifest; // the package's manifest.json (empty for the development build)
    String driver = DRIVER_AUTO;
    String sysmem = SYSMEM_AUTO;
    /** Texture detail (dao_texture_lod_bias: 0 full, 1 low, 2 ultra low) picked, -1 for the default. */
    int textureLodBias = -1;
    /**
     * Widescreen (Dragon Age: Origins only): the game is told a display as wide as the screen
     * (DAO_FILL_SCREEN, see GameActivity.applyInstanceSettings) instead of 16:9 with black bars.
     */
    boolean widescreen = false;

    /** Whether the Widescreen setting exists for this game. */
    boolean hasWidescreen() {
        return DAO_TITLE_ID.equals(titleId());
    }

    /**
     * The texture detail to start with: the one picked, else the renderer's default. Xenia starts
     * at ultra low (a phone's memory and bandwidth, not the console's); the native renderer, which
     * spends far less of both, at low.
     */
    int textureLodToUse() {
        if (textureLodBias >= 0 && textureLodBias <= 2) return textureLodBias;
        return hasNativeRenderer() && RENDERER_NATIVE.equals(rendererToUse()) ? 1 : 2;
    }
    /** The in-game display: nothing, the FPS counter, or the performance bar (as in Zomdroid). */
    static final int HUD_OFF = 0, HUD_FPS = 1, HUD_FULL = 2;
    int hudMode = HUD_OFF;
    /** The game's language (config.toml [game] language), "" for the phone's. */
    String language = "";
    /** The GPU's measurements in the game's log, for a bug report (DAO_GPU_STATS, DAO_GPU_TIMING). */
    boolean diagnostics = false;
    /** The languages each disc has text for ([game] language, read by both games' runtimes). */
    private static final String[] DAO_LANGUAGES = { "english", "italian", "spanish" };
    private static final String[] SOD_LANGUAGES = { "english", "french", "german", "italian", "spanish" };
    /** Dragon Age's languages and the talk table each needs (core_<locale>.tlk). */
    private static final String[][] DAO_TALK_TABLES = {
            { "english", "en-us" }, { "italian", "it-it" }, { "spanish", "es-es" }, { "russian", "ru-ru" },
            { "german", "de-de" } };

    /**
     * This game's languages, for the settings' language card. Dragon Age offers the ones its disc has
     * text for: English, Italian and Spanish on the usual disc, Russian on the Russian one, German
     * on the German one.
     */
    String[] languages() {
        if (SOD_TITLE_ID.equals(titleId())) return SOD_LANGUAGES;
        File talk = new File(game(), "packages/core/data/talktables");
        java.util.List<String> has = new java.util.ArrayList<>();
        for (String[] l : DAO_TALK_TABLES) {
            if (new File(talk, "core_" + l[1] + ".tlk").isFile()) has.add(l[0]);
        }
        return has.isEmpty() ? DAO_LANGUAGES : has.toArray(new String[0]);
    }

    /** A language's name in that language, as the card shows it. */
    static String languageName(String id) {
        switch (id) {
            case "english": return "English";
            case "french": return "Français";
            case "german": return "Deutsch";
            case "italian": return "Italiano";
            case "spanish": return "Español";
            case "russian": return "Русский";
            default: return id;
        }
    }

    /**
     * State of Decay's Russian text, made on the phone from a Russian title update (the recipe's
     * russian-text step). SoD's runtime shows it in place of French, so "french" plays in Russian
     * when it is there.
     */
    boolean hasRussianText() {
        return SOD_TITLE_ID.equals(titleId()) && new File(root, "russian/gamedata.pak").isFile();
    }
    boolean vibrateOnTouch = false;
    static final String CONTROLS_GAMEPAD = "gamepad", CONTROLS_TOUCH = "touch";
    /** On-screen control mode; old installs retain the original two-stick mode. */
    String controlMode = CONTROLS_GAMEPAD;
    /** The frame rate picked, 0 for the package's default (options.frame_rate). */
    int frameRate = 0;
    /** The graphics preset picked (options.preset), "" for the game's own settings. */
    String preset = "";
    /** Whether the launcher wrote the preset's target file (so going back to "" removes it). */
    boolean presetWritten = false;
    /** The renderer (config.toml [xenia] dao_gpu_renderer): Xenia's emulation, or the native one. */
    static final String RENDERER_XENIA = "xenia", RENDERER_NATIVE = "native";
    /** The player's choice, "" for the game's default (rendererToUse). */
    String renderer = "";
    /**
     * The stamp of the profile in the APK the player chose to play past ("Play anyway" when the
     * launcher offered its update), so the question isn't asked again until a newer one comes.
     */
    String updateSkipped = "";

    /**
     * Whether the game has the native renderer: the launcher writes the key, and the settings offer
     * the choice unless it is the only one (nativeRendererOnly). DAO and SoD read the same key and
     * fall back to Xenia by themselves while the shader library is missing.
     */
    boolean hasNativeRenderer() {
        String id = titleId();
        return DAO_TITLE_ID.equals(id) || SOD_TITLE_ID.equals(id) || ME1_TITLE_ID.equals(id);
    }

    /**
     * Whether the native renderer is the game's only one, so the settings offer no choice and the
     * launcher always writes "native": Mass Effect, whose Xenia path never drew more than a black or
     * white screen, and whose runtime would start that path were the key unset.
     */
    boolean nativeRendererOnly() {
        return ME1_TITLE_ID.equals(titleId());
    }

    /** The renderer to start with: the chosen one, else native for a game that has it, else Xenia. */
    String rendererToUse() {
        if (nativeRendererOnly()) return RENDERER_NATIVE;
        if (RENDERER_NATIVE.equals(renderer) || RENDERER_XENIA.equals(renderer)) return renderer;
        return hasNativeRenderer() ? RENDERER_NATIVE : RENDERER_XENIA;
    }

    private Instance(String id, File root, File library) {
        this.id = id;
        this.root = root;
        this.library = library;
    }

    File game() { return new File(root, "game"); }
    File userdata() { return new File(root, "userdata"); }
    File driverDir() { return new File(root, "driver"); }
    File presetsDir() { return new File(root, "presets"); }
    boolean isDev() { return DEV_ID.equals(id); }

    /** The first .so in driver/ (a custom driver), or null. */
    File customDriver() {
        File[] files = driverDir().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".so")) return f;
            }
        }
        return null;
    }

    /**
     * The game's title ID (upper case): which game this is, for its saves folder and for sharing a
     * shader cache only between instances of the same game. State of Decay's format 1 packages
     * carry DAO's pins and their own ID in build.sod_title_id; that case goes once SoD writes
     * format 2.
     */
    String titleId() {
        JSONObject build = manifest.optJSONObject("build");
        String sod = build != null ? build.optString("sod_title_id") : "";
        if (!sod.isEmpty()) return sod.toUpperCase(java.util.Locale.ROOT);
        return manifest.optString("title_id", DAO_TITLE_ID).toUpperCase(java.util.Locale.ROOT);
    }

    /** An optional setting the package offers (manifest "options", format 2), or null. */
    JSONObject option(String name) {
        JSONObject options = manifest.optJSONObject("options");
        return options != null ? options.optJSONObject(name) : null;
    }

    /** The game's config.toml (userdata), which both games' runtimes and the launcher write. */
    File config() { return new File(userdata(), "config.toml"); }

    /**
     * The frame rate cap the game will run at ([video] frame_rate, 0 = no limit): the game's own
     * setting (Dragon Age's in-game Graphics tab writes the same key), else its default: 30 for
     * State of Decay, and for Dragon Age 60, or 30 on a GPU short of an Adreno 8xx (what its first
     * start writes, GameActivity.applyWeakGpuDefaults). State of Decay runs at 30 or 60 only: its
     * runtime clamps no limit (0) and anything above 60 to 60, so that is what is reported.
     */
    int frameRateCap() {
        String v = XeniaConfig.get(config(), "video", "frame_rate");
        if (v != null) {
            try {
                int fps = Integer.parseInt(v);
                if (SOD_TITLE_ID.equals(titleId()) && (fps <= 0 || fps > 60)) return 60;
                return fps;
            } catch (NumberFormatException ignored) {
            }
        }
        if (SOD_TITLE_ID.equals(titleId())) return 30;
        return GameActivity.gpuModel().startsWith("Adreno8") ? 60 : 30;
    }

    /** Sets the frame rate cap in the game's config.toml at once; the game reads it at start. */
    void setFrameRateCap(int fps) throws java.io.IOException {
        XeniaConfig.set(config(), "video", "frame_rate", String.valueOf(fps));
    }

    /** The frame rate to run at, or 0 when the package offers no choice. */
    int frameRateToUse() {
        JSONObject o = option("frame_rate");
        if (o == null) return 0;
        org.json.JSONArray choices = o.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return 0;
        for (int i = 0; i < choices.length(); i++) if (choices.optInt(i) == frameRate) return frameRate;
        return o.optInt("default", choices.optInt(0));
    }

    /** The package's preset ids that came with it (presets/<id>.cfg), in the manifest's order. */
    List<String> presets() {
        List<String> out = new ArrayList<>();
        JSONObject o = option("preset");
        org.json.JSONArray choices = o != null ? o.optJSONArray("choices") : null;
        if (choices == null) return out;
        for (int i = 0; i < choices.length(); i++) {
            String id = choices.optString(i);
            if (id.matches("[a-z0-9_]+") && new File(presetsDir(), id + ".cfg").isFile()) out.add(id);
        }
        return out;
    }

    String buildLabel() {
        if (isDev()) return "development build (game built into the APK)";
        JSONObject recipe = manifest.optJSONObject("recipe");
        if (recipe != null) return "built on this phone, profile " + recipe.optString("version", "?");
        JSONObject build = manifest.optJSONObject("build");
        String commit = build != null ? build.optString("daodroid", "?") : "?";
        if (commit.length() > 8) commit = commit.substring(0, 8);
        Object tu = manifest.opt("title_update");
        return "build " + commit + (tu instanceof Number ? ", Title Update " + tu : "");
    }

    /** The profile version this game was built from on the phone ("sod-9"), or "" when it wasn't. */
    String profileVersion() {
        JSONObject recipe = manifest.optJSONObject("recipe");
        return recipe != null ? recipe.optString("version") : "";
    }

    /** The profile this APK carries for this game (built on the phone), or null. */
    Recipe bundledProfile(Context context) {
        JSONObject built = manifest.optJSONObject("recipe");
        if (built == null || isDev()) return null;
        String game = built.optString("game");
        for (Recipe r : Toolchain.profiles(context)) if (r.gameId().equals(game)) return r;
        return null;
    }

    /**
     * The profile in this APK when this game was built from another one (another version, kit or
     * tools: "Update game" rebuilds it) and this APK's tools can build it; else null. Until then
     * the game keeps running the code of the profile it was built from.
     */
    Recipe newerProfile(Context context) {
        Recipe r = bundledProfile(context);
        if (r == null || r.stamp().toString().equals(manifest.optJSONObject("recipe").toString())) return null;
        return Toolchain.mismatch(context, r) == null ? r : null;
    }

    void save() throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("manifest", manifest);
            JSONObject s = new JSONObject();
            s.put("driver", driver);
            s.put("sysmem", sysmem);
            s.put("texture_lod", textureLodBias);                 // -1: the renderer's default
            s.put("texture_lod_bias", textureLodToUse());         // what an older launcher reads
            s.put("hud_mode", hudMode);
            s.put("widescreen", widescreen);
            s.put("language", language);
            s.put("diagnostics", diagnostics);
            s.put("vibrate_on_touch", vibrateOnTouch);
            s.put("control_mode", controlMode);
            s.put("frame_rate", frameRate);
            s.put("preset", preset);
            s.put("preset_written", presetWritten);
            s.put("renderer", renderer);
            s.put("update_skipped", updateSkipped);
            o.put("settings", s);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        String text;
        try {
            text = o.toString(2);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        Files.write(new File(root, "instance.json").toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private void load() {
        File f = new File(root, "instance.json");
        if (!f.isFile()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            name = o.optString("name", name);
            JSONObject m = o.optJSONObject("manifest");
            if (m != null) manifest = m;
            JSONObject s = o.optJSONObject("settings");
            if (s != null) {
                driver = s.optString("driver", driver);
                sysmem = s.optString("sysmem", sysmem);
                if (s.has("texture_lod")) {
                    textureLodBias = s.optInt("texture_lod", textureLodBias);
                } else {
                    // Before the per-renderer default: 2 was the default every instance saved,
                    // so it can't be told from a choice and counts as the default.
                    int old = s.optInt("texture_lod_bias", 2);
                    textureLodBias = old == 2 ? -1 : old;
                }
                // Before hud_mode: the performance bar was a switch, on unless turned off.
                language = s.optString("language", language);
                widescreen = s.optBoolean("widescreen", widescreen);
                diagnostics = s.optBoolean("diagnostics", diagnostics);
                hudMode = s.has("hud_mode") ? s.optInt("hud_mode", hudMode)
                        : s.optBoolean("perf_overlay", true) ? HUD_FULL : HUD_OFF;
                vibrateOnTouch = s.optBoolean("vibrate_on_touch", vibrateOnTouch);
                String mode = s.optString("control_mode", CONTROLS_GAMEPAD);
                controlMode = CONTROLS_TOUCH.equals(mode) ? CONTROLS_TOUCH : CONTROLS_GAMEPAD;
                frameRate = s.optInt("frame_rate", frameRate);
                preset = s.optString("preset", preset);
                presetWritten = s.optBoolean("preset_written", presetWritten);
                renderer = s.optString("renderer", renderer);
                updateSkipped = s.optString("update_skipped", updateSkipped);
            }
        } catch (IOException | JSONException e) {
            android.util.Log.w("XDroid360", "instance.json of " + id + ": " + e);
        }
    }

    // ---------------------------------------------------------------- the store

    /** The public folder the games' data lives in when "All files access" is granted. */
    static final String SHARED_DIR = "XDroid360/dao";

    private static boolean migrated;

    /**
     * Where the games' data lives: {@code /sdcard/XDroid360/dao} (the same for every build of
     * the app, whatever its package name) once "All files access" is granted, else the app's
     * own folder under Android/data, as before.
     */
    static File daoRoot(Context context) {
        File shared = sharedRoot();
        if (shared != null) {
            migrateLegacyRoot(context, shared);
            return shared;
        }
        return legacyRoot(context);
    }

    /** The app's own folder (Android/data/<package>/files/dao, or the private files/dao). */
    private static File legacyRoot(Context context) {
        File external = context.getExternalFilesDir(null);
        return new File(external != null ? external : context.getFilesDir(), "dao");
    }

    /** True if the data is in the public folder: all builds of the app see the same games. */
    static boolean usesSharedStorage() {
        return sharedRoot() != null;
    }

    /** The public folder, or null where it can't be used (Android 10, or access not granted). */
    private static File sharedRoot() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                || !android.os.Environment.isExternalStorageManager()) {
            return null;
        }
        File dir = new File(android.os.Environment.getExternalStorageDirectory(), SHARED_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir.isDirectory() && dir.canWrite() ? dir : null;
    }

    /**
     * The first time the public folder is used: what is in the app's own folder moves into it,
     * if the public one has no games yet (a rename within the same volume, so it takes no
     * time and no space). Nothing is overwritten.
     */
    private static synchronized void migrateLegacyRoot(Context context, File shared) {
        if (migrated) return;
        migrated = true;
        File legacy = legacyRoot(context);
        File[] entries = legacy.listFiles();
        if (entries == null || entries.length == 0 || new File(shared, "instances").exists()
                || new File(shared, "game").exists()) {
            return;
        }
        for (File entry : entries) {
            File target = new File(shared, entry.getName());
            if (!target.exists() && !entry.renameTo(target)) {
                android.util.Log.w("Instance", "Can't move " + entry + " to " + target);
            }
        }
    }

    // ------------------------------------------------ the game library in the public folder

    private static final java.util.concurrent.atomic.AtomicBoolean syncing =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The built game library (libgame.so) is kept twice when the data is in the public folder:
     * the copy the game loads stays in the app's internal files (Android doesn't map code from
     * shared storage), and a copy sits beside the game's other files in
     * {@code XDroid360/dao/instances/<id>/}, so a file manager reaches it and any other build
     * of the app finds the game already built. This brings the two in line, in the background:
     * a library only on one side is copied to the other, and of two different ones the newer
     * wins. {@code onChanged} runs on the main thread if a library was copied (a game may then
     * have become complete: the list needs another look).
     */
    static void syncLibraries(Context context, Runnable onChanged) {
        if (!usesSharedStorage() || !syncing.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean changed = false;
            try {
                java.util.Set<String> ids = new java.util.TreeSet<>();
                for (File root : new File[] { instancesRoot(app), new File(app.getFilesDir(), "instances") }) {
                    File[] dirs = root.listFiles();
                    if (dirs == null) continue;
                    for (File d : dirs) {
                        if (d.isDirectory()) ids.add(d.getName());
                    }
                }
                for (String id : ids) {
                    changed |= syncLibrary(app, id);
                }
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("Instance", "Library sync: " + e);
            } finally {
                syncing.set(false);
            }
            if (changed) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(onChanged);
            }
        }, "library-sync").start();
    }

    private static boolean syncLibrary(Context app, String id) throws IOException {
        File shared = new File(new File(instancesRoot(app), id), "libgame.so");
        // Only a complete game: its instance.json is in the public folder.
        if (!new File(shared.getParentFile(), "instance.json").isFile()) return false;
        File internal = libraryFor(app, id);
        if (internal.isFile() && !shared.isFile()) {
            copyLibrary(internal, shared);
            return true;
        }
        if (!internal.isFile() && shared.isFile()) {
            copyLibrary(shared, internal);
            return true;
        }
        if (!internal.isFile()) return false;
        if (internal.length() == shared.length() && Math.abs(internal.lastModified() - shared.lastModified()) < 2000) {
            return false;
        }
        if (shared.lastModified() > internal.lastModified()) {
            copyLibrary(shared, internal);
        } else {
            copyLibrary(internal, shared);
        }
        return true;
    }

    /** Copies through a temporary file, so a library is never half there; keeps its time. */
    private static void copyLibrary(File from, File to) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        to.getParentFile().mkdirs();
        File tmp = new File(to.getPath() + ".tmp");
        Files.copy(from.toPath(), tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        //noinspection ResultOfMethodCallIgnored
        tmp.setLastModified(from.lastModified());
        Files.move(tmp.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** instances/ under it: one folder per installed game. */
    static File instancesRoot(Context context) {
        return new File(daoRoot(context), "instances");
    }

    static File libraryFor(Context context, String id) {
        return new File(new File(new File(context.getFilesDir(), "instances"), id), "libgame.so");
    }

    /** Whether this APK holds the game library (the development build). */
    static boolean apkHasGameLibrary(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libgame.so").isFile();
    }

    static Instance open(Context context, String id) {
        Instance i;
        if (DEV_ID.equals(id)) {
            i = new Instance(id, daoRoot(context), null);
            i.name = "Dragon Age: Origins (development)";
            i.manifest = new JSONObject();
            i.driver = DRIVER_CUSTOM; // the dev build's driver/ folder, as before the launcher
        } else {
            i = new Instance(id, new File(instancesRoot(context), id), libraryFor(context, id));
            i.name = "Dragon Age: Origins";
            i.manifest = new JSONObject();
        }
        i.load();
        return i;
    }

    static List<Instance> list(Context context) {
        List<Instance> out = new ArrayList<>();
        if (apkHasGameLibrary(context) && new File(new File(daoRoot(context), "game"), "default.xex").isFile()) {
            out.add(open(context, DEV_ID));
        }
        File[] dirs = instancesRoot(context).listFiles();
        if (dirs != null) {
            java.util.Arrays.sort(dirs);
            for (File d : dirs) {
                // An instance is complete once its instance.json and library are in place.
                if (d.isDirectory() && new File(d, "instance.json").isFile()
                        && libraryFor(context, d.getName()).isFile()) {
                    out.add(open(context, d.getName()));
                }
            }
        }
        return out;
    }

    /** The instance a build on the phone made (BuildJob id), with the recipe's stamp as its manifest. */
    static Instance built(Context context, String id, String titleId, String title, JSONObject stamp) {
        Instance i = new Instance(id, new File(instancesRoot(context), id), libraryFor(context, id));
        i.manifest = new JSONObject();
        try {
            i.manifest.put("format", 2);
            i.manifest.put("title_id", titleId);
            i.manifest.put("title", title);
            i.manifest.put("recipe", stamp);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        i.name = title;
        return i;
    }

    /**
     * The language to start the game in: the chosen one if this game has it, else the phone's
     * when the game has it, else English, else the disc's first (the Russian disc has only
     * Russian, the German disc only German). A Russian phone gets State of Decay's Russian text (played as "french") only when
     * it was made on this phone.
     */
    String languageToUse() {
        java.util.List<String> has = java.util.Arrays.asList(languages());
        if (has.contains(language)) return language;
        String phone;
        switch (java.util.Locale.getDefault().getLanguage()) {
            case "fr": phone = "french"; break;
            case "de": phone = "german"; break;
            case "it": phone = "italian"; break;
            case "es": phone = "spanish"; break;
            case "ru": phone = hasRussianText() ? "french" : "russian"; break;
            default: phone = "english";
        }
        if (has.contains(phone)) return phone;
        return has.contains("english") ? "english" : has.get(0);
    }

    /** name, or "name 2", "name 3"... if another instance already has it. */
    static String uniqueName(java.util.List<Instance> all, String name) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Instance i : all) taken.add(i.name);
        String unique = name;
        for (int n = 2; taken.contains(unique); n++) unique = name + " " + n;
        return unique;
    }

    /** Removes the instance's files (its game, saves and library). */
    void delete(Context context) {
        if (isDev()) {
            // The development build's files share dao/ with the instances: only its own go.
            for (String name : new String[] { "game", "userdata", "driver", "title_update", "mods", "instance.json", "banner.jpg" }) {
                deleteTree(new File(root, name));
            }
            return;
        }
        deleteTree(root);
        if (library != null) deleteTree(library.getParentFile());
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteTree(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
