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

    // Settings, kept in instance.json.
    /** auto: the built-in Turnip on an Adreno 8xx (what it is made for), else the phone's own. */
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

    /** This game's languages, for the settings' language card. */
    String[] languages() {
        return SOD_TITLE_ID.equals(titleId()) ? SOD_LANGUAGES : DAO_LANGUAGES;
    }

    /** A language's name in that language, as the card shows it. */
    static String languageName(String id) {
        switch (id) {
            case "english": return "English";
            case "french": return "Français";
            case "german": return "Deutsch";
            case "italian": return "Italiano";
            case "spanish": return "Español";
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
     * Whether the game has the native renderer, so the settings offer the choice. Both games read
     * the same key and fall back to Xenia by themselves while the shader library is missing.
     */
    boolean hasNativeRenderer() {
        String id = titleId();
        return DAO_TITLE_ID.equals(id) || SOD_TITLE_ID.equals(id);
    }

    /** The renderer to start with: the chosen one, else native for a game that has it, else Xenia. */
    String rendererToUse() {
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
            s.put("language", language);
            s.put("diagnostics", diagnostics);
            s.put("vibrate_on_touch", vibrateOnTouch);
            s.put("control_mode", controlMode);
            s.put("frame_rate", frameRate);
            s.put("preset", preset);
            s.put("preset_written", presetWritten);
            s.put("renderer", renderer);
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
            }
        } catch (IOException | JSONException e) {
            android.util.Log.w("XDroid360", "instance.json of " + id + ": " + e);
        }
    }

    // ---------------------------------------------------------------- the store

    /** dao/ on the shared storage: the development build's game, and instances/. */
    static File daoRoot(Context context) {
        File external = context.getExternalFilesDir(null);
        return new File(external != null ? external : context.getFilesDir(), "dao");
    }

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
     * when the game has it, else English. A Russian phone gets State of Decay's Russian text
     * (played as "french") only when it was made on this phone.
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
            case "ru": phone = hasRussianText() ? "french" : "english"; break;
            default: phone = "english";
        }
        return has.contains(phone) ? phone : "english";
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
            for (String name : new String[] { "game", "userdata", "driver", "title_update", "instance.json", "banner.jpg" }) {
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
