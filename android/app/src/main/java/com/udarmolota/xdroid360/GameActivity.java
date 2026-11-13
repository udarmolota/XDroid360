package com.udarmolota.xdroid360;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The game's activity: SDL's, with the game library (libgame.so, the runtime and the
 * translated game code) as the main one. It runs one instance (Instance, chosen in the
 * launcher; without one, the development build's game in {@code files/dao/}), with that
 * instance's files ({@code game/}, {@code userdata/}, {@code driver/}) and settings. What's
 * missing is logged to logcat (tag XDroid360) and the instance's userdata/dao.log.
 */
public class GameActivity extends SDLActivity {
    private static final String TAG = "XDroid360";
    /** The built-in Turnip (Mesa, MIT): Turnip Gen8 V38 for the Adreno 8xx, in the APK's libraries. */
    static final String BUILTIN_DRIVER = "libvulkan_turnip_gen8.so";

    /** The instance being played; set from the intent before SDL starts. */
    private static Instance current;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "game" };
    }

    // The game library (libgame.so: the runtime, Xenia and the translated game code). A
    // game package built on the PC puts it at dao/libgame.so; it is copied into the app's
    // internal files (code can't be mapped from the shared storage) and loaded from there.
    // Without one, the copy built into this APK is used (the development build); a launcher
    // build has none and asks for a package.
    //
    // The version of the interface between this Java side and the library (JNI methods, SDL,
    // command-line options). Keep in step with DAO_ABI_VERSION in cpp/dao_abi.h.
    static final int DAO_ABI = 2;
    private String gameLibrary;

    private static native int nativeAbiVersion();

    @Override
    public void loadLibraries() {
        System.loadLibrary("SDL3");
        File packaged = new File(installRoot(this), "libgame.so");
        if (current != null && current.library != null) {
            if (!current.library.isFile()) {
                throw new UnsatisfiedLinkError("This game's library is missing: add the game package again");
            }
            System.load(current.library.getAbsolutePath());
            gameLibrary = current.library.getAbsolutePath();
            Log.i(TAG, "Game library: instance " + current.id);
        } else if (packaged.isFile()) {
            File internal = copyToInternal(packaged, "game");
            if (internal == null) {
                throw new UnsatisfiedLinkError("Couldn't copy the game library " + packaged);
            }
            System.load(internal.getAbsolutePath());
            gameLibrary = internal.getAbsolutePath();
            Log.i(TAG, "Game library: from the game package (" + packaged + ")");
        } else {
            try {
                System.loadLibrary("game");
            } catch (UnsatisfiedLinkError e) {
                throw new UnsatisfiedLinkError("No game package: put the one you built on your PC into "
                        + installRoot(this) + " (see the README)");
            }
            gameLibrary = getApplicationInfo().nativeLibraryDir + "/libgame.so";
            Log.i(TAG, "Game library: the one built into the APK");
        }
        int abi = nativeAbiVersion();
        if (abi != DAO_ABI) {
            throw new UnsatisfiedLinkError("The game package was built for launcher version " + abi
                    + ", this launcher is version " + DAO_ABI + ": build the package again with the matching tools");
        }
    }

    @Override
    protected String getMainSharedObject() {
        return gameLibrary != null ? gameLibrary : super.getMainSharedObject();
    }

    @Override
    protected String[] getArguments() {
        List<String> args = new ArrayList<>();
        File install = installRoot(this);
        applyInstanceSettings();
        applyEnvFile(new File(new File(install, "userdata"), "env.txt")); // env.txt wins
        args.add(install.getAbsolutePath());
        File driver = chosenDriver(install);
        if (driver != null) {
            args.add("--vulkan-driver=" + driver.getAbsolutePath());
            args.add("--native-lib-dir=" + getApplicationInfo().nativeLibraryDir);
            Log.i(TAG, "Vulkan driver: " + driver.getAbsolutePath());
        } else {
            Log.i(TAG, "Vulkan driver: the phone's own (no .so in " + new File(install, "driver") + ")");
        }
        return args.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String id = getIntent() != null ? getIntent().getStringExtra(InstanceActivity.EXTRA_ID) : null;
        current = id != null ? Instance.open(this, id)
                : (Instance.apkHasGameLibrary(this) ? Instance.open(this, Instance.DEV_ID) : null);
        File install = installRoot(this);
        File game = new File(install, "game");
        if (!new File(game, "default.xex").isFile()) {
            Log.e(TAG, "No game at " + game + ": copy DAORecomp's install/game there (see docs/PHASE5_runbook.md)");
        }
        applyFullScreenWindow();
        super.onCreate(savedInstanceState);
        hideSystemBars();
        // The on-screen gamepad over SDL's surface (mLayout holds the surface).
        if (mLayout != null) {
            VirtualPadView pad = new VirtualPadView(this, current != null ? current.id : null);
            pad.setVibrateOnTouch(current != null && current.vibrateOnTouch);
            pad.setControlMode(current != null && Instance.CONTROLS_TOUCH.equals(current.controlMode));
            mLayout.addView(pad, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            setUpPerfOverlay();
        }
    }

    // Full screen, set by the activity itself before the window is laid out (as in ValDroid).
    // SDL only switches to full screen later, when the game creates its window, and with the
    // deprecated system-UI flags; it also writes the cutout mode into getAttributes() without
    // setAttributes(). Some firmware (TECNO's HiOS, seen on a Mali-G57 phone) then keeps the
    // navigation bar's strip for itself: a black band on the right over the on-screen gamepad.
    private void applyFullScreenWindow() {
        android.view.Window window = getWindow();
        android.view.WindowManager.LayoutParams lp = window.getAttributes();
        lp.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30
                ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        window.setAttributes(lp);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        }
    }

    // Hides both the status bar and the navigation bar; a swipe shows them for a moment.
    // Android 10 keeps SDL's own system-UI flags.
    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(android.view.WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars(); // again after dialogs and coming back to the game
    }

    // The game's keyboard (XamShowKeyboardUI, e.g. the character's name): an Android dialog
    // with a text field. Called from the game's thread (keyboard_android.cpp), which waits
    // until nativeKeyboardDone gives the text, or null when the player cancels.
    private static native void nativeKeyboardDone(String text);

    @SuppressWarnings("unused") // from native code
    public void showKeyboard(final String title, final String description, final String text, final int maxLength) {
        runOnUiThread(() -> {
            final android.widget.EditText field = new android.widget.EditText(this);
            field.setSingleLine(true);
            field.setText(text);
            field.setSelectAllOnFocus(true);
            field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            if (maxLength > 0) {
                field.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(maxLength) });
            }
            float dp = getResources().getDisplayMetrics().density;
            android.widget.FrameLayout box = new android.widget.FrameLayout(this);
            int pad = Math.round(20 * dp);
            box.setPadding(pad, Math.round(8 * dp), pad, 0);
            box.addView(field);

            final boolean[] answered = { false };
            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title == null || title.isEmpty() ? "Dragon Age" : title)
                    .setMessage(description == null || description.isEmpty() ? null : description)
                    .setView(box)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        answered[0] = true;
                        nativeKeyboardDone(field.getText().toString());
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        answered[0] = true;
                        nativeKeyboardDone(null);
                    })
                    .create();
            dialog.setOnDismissListener(d -> {
                if (!answered[0]) {
                    answered[0] = true;
                    nativeKeyboardDone(null);
                }
            });
            field.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE && !answered[0]) {
                    answered[0] = true;
                    nativeKeyboardDone(field.getText().toString());
                    dialog.dismiss();
                    return true;
                }
                return false;
            });
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            dialog.show();
            field.requestFocus();
        });
    }

    // The in-game display (Instance.hudMode, as in Zomdroid): the "FPS: XX" counter, or the
    // performance bar (fps, CPU, GPU, RAM, thermal) from the launchers. Both count the frames the
    // game presented. userdata/env.txt DAO_PERF_OVERLAY=0 hides either.
    private static native long nativeGetPresentedFrames();

    private android.widget.TextView fpsText;
    private long fpsLastCount = -1, fpsLastTimeMs;
    private PerfOverlayView perfView;
    private PerfSampler perfSampler;
    private HandlerThread perfThread;
    private Handler perfHandler;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable fpsTick = new Runnable() {
        @Override public void run() {
            if (fpsText == null || perfHandler == null) return;
            long frames = nativeGetPresentedFrames(), now = android.os.SystemClock.elapsedRealtime();
            if (fpsLastCount >= 0 && now > fpsLastTimeMs) {
                final long fps = Math.round((frames - fpsLastCount) * 1000.0 / (now - fpsLastTimeMs));
                uiHandler.post(() -> { if (fpsText != null) fpsText.setText("FPS: " + fps); });
            }
            fpsLastCount = frames;
            fpsLastTimeMs = now;
            perfHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable perfTick = new Runnable() {
        @Override public void run() {
            if (perfView == null || perfSampler == null || perfHandler == null) return;
            final PerfSampler.Stats stats = perfSampler.sample(nativeGetPresentedFrames());
            uiHandler.post(() -> { if (perfView != null) perfView.setStats(stats); });
            perfHandler.postDelayed(this, 1000);
        }
    };

    private void setUpPerfOverlay() {
        // getArguments (the game's env) runs later, on SDL's thread: read env.txt here too.
        applyEnvFile(new File(new File(installRoot(this), "userdata"), "env.txt"));
        int mode = current != null ? current.hudMode : Instance.HUD_FULL;
        if ("0".equals(envOverrides.get("DAO_PERF_OVERLAY")) || mode == Instance.HUD_OFF) {
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        perfThread = new HandlerThread("PerfOverlay");
        perfThread.start();
        perfHandler = new Handler(perfThread.getLooper());
        if (mode == Instance.HUD_FPS) {
            fpsText = new android.widget.TextView(this);
            fpsText.setTextColor(0xFFFFFFFF);
            fpsText.setTextSize(14);
            fpsText.setShadowLayer(3 * dp, 0, 0, 0xFF000000);
            android.widget.RelativeLayout.LayoutParams fl = new android.widget.RelativeLayout.LayoutParams(
                    android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
            fl.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
            fl.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT);
            fl.setMargins(Math.round(8 * dp), Math.round(4 * dp), 0, 0);
            mLayout.addView(fpsText, fl); // not clickable: touches reach the pad underneath
            perfHandler.postDelayed(fpsTick, 1000);
            return;
        }
        perfView = new PerfOverlayView(this, "VK");
        // SDL's root layout is a RelativeLayout: centre by its rule, not by gravity.
        android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT);
        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
        lp.setMargins(0, Math.round(4 * dp), 0, 0);
        mLayout.addView(perfView, lp);   // not clickable: touches reach the pad underneath
        perfSampler = new PerfSampler(this);
        perfHandler.postDelayed(perfTick, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (perfThread != null) {
            perfThread.quitSafely();
            perfThread = null;
            perfHandler = null;
        }
    }

    /**
     * userdata/env.txt: KEY=VALUE lines set in the game's environment before it starts (the
     * DAO_* switches the headless build takes: DAO_CAPTURE_EVERY, DAO_DUMP_AFTER, DAO_GPU, ...).
     */
    private static final java.util.Map<String, String> envOverrides = new java.util.HashMap<>();

    private static void applyEnvFile(File file) {
        envOverrides.clear();
        if (!file.isFile()) {
            return;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq <= 0) {
                    continue;
                }
                nativeSetenv(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                envOverrides.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                Log.i(TAG, "env: " + line);
            }
        } catch (java.io.IOException e) {
            Log.w(TAG, "env.txt: " + e);
        }
    }

    /** The instance's folder (game/, userdata/, driver/), or {@code files/dao} without one. */
    static File installRoot(Context context) {
        File root = current != null ? current.root : Instance.daoRoot(context);
        //noinspection ResultOfMethodCallIgnored
        new File(root, "userdata").mkdirs();
        return root;
    }

    /** The instance's settings into the game's environment and its config.toml. */
    private void applyInstanceSettings() {
        if (current == null) return;
        switch (current.sysmem) {
            case Instance.SYSMEM_ON: nativeSetenv("TU_DEBUG", "sysmem"); break;
            case Instance.SYSMEM_OFF: nativeSetenv("TU_DEBUG", ""); break; // set: the library's Adreno 8xx default stays off
            default: break; // the library decides from the GPU
        }
        // Diagnostics for a bug report: the GPU's measurements in the game's log.
        if (current.diagnostics) {
            nativeSetenv("DAO_GPU_STATS", "1");
            nativeSetenv("DAO_GPU_TIMING", "1");
        }
        File config = new File(current.userdata(), "config.toml");
        try {
            XeniaConfig.set(config, "dao_texture_lod_bias", String.valueOf(current.textureLodToUse()));
            // The game's text language, read at its start (both games' runtimes: [game] language).
            XeniaConfig.set(config, "game", "language", "\"" + current.languageToUse() + "\"");
            // Always: it saves GPU work, and the plain scaling it uses looks the same on a phone.
            XeniaConfig.set(config, "dao_present_prerotate", "true");
            // The renderer the settings chose, for the games that have the native one.
            if (current.hasNativeRenderer()) {
                XeniaConfig.set(config, "dao_gpu_renderer", "\"" + current.rendererToUse() + "\"");
            }
            org.json.JSONObject frameRate = current.option("frame_rate");
            String[] key = frameRate != null ? frameRate.optString("config").split("\\.", 2) : new String[0];
            if (key.length == 2 && current.frameRateToUse() > 0) {
                XeniaConfig.set(config, key[0], key[1], String.valueOf(current.frameRateToUse()));
            }
            applyWeakGpuDefaults(config);
        } catch (java.io.IOException e) {
            Log.w(TAG, "config.toml: " + e);
        }
        try {
            InstanceActions.applyPreset(current);
        } catch (java.io.IOException e) {
            Log.w(TAG, "preset: " + e);
        }
        Log.i(TAG, "Instance " + current.id + ": driver " + current.driver + ", sysmem " + current.sysmem
                + ", texture LOD " + current.textureLodToUse() + ", display " + current.hudMode
                + ", language " + current.languageToUse()
                + (current.option("frame_rate") != null ? ", frame rate " + current.frameRateToUse() : "")
                + (current.option("preset") != null ? ", preset " + (current.preset.isEmpty() ? "game's own" : current.preset) : ""));
    }

    /**
     * On a GPU short of an Adreno 8xx (Mali, an older Adreno), a game's first start runs at 30 fps
     * with its heavier effects off: half the GPU's work, so less heat, which on a phone soon costs
     * more speed than anything else. Only before the game first writes its own frame rate into
     * config.toml, so a game already played, and what the player then picks in the game's own
     * Graphics tab, stay as they are. Dragon Age: Origins' keys; its shadows and post-processing
     * are already off on Android.
     */
    private void applyWeakGpuDefaults(File config) throws java.io.IOException {
        if (!Instance.DAO_TITLE_ID.equals(current.titleId())) return;
        String gpu = gpuModel();
        if (gpu.startsWith("Adreno8")) return;
        String text = config.isFile()
                ? new String(java.nio.file.Files.readAllBytes(config.toPath()), java.nio.charset.StandardCharsets.UTF_8) : "";
        if (java.util.regex.Pattern.compile("(?m)^\\s*frame_rate\\s*=").matcher(text).find()) return;
        XeniaConfig.set(config, "video", "frame_rate", "30");
        XeniaConfig.set(config, "video", "depth_of_field", "false");
        Log.i(TAG, "GPU '" + gpu + "' isn't an Adreno 8xx: first start at 30 fps, depth of field off");
    }

    /**
     * The Vulkan driver the instance chose, copied into the app's internal files when it comes
     * from the shared storage (code can't be mapped from there), or null for the phone's own.
     */
    File chosenDriver(File install) {
        return chosenDriver(this, current, install);
    }

    /** The Adreno model from the kernel's GPU driver ("Adreno830v2"), or "" on other GPUs. */
    static String gpuModel() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/sys/class/kgsl/kgsl-3d0/gpu_model"))) {
            String s = r.readLine();
            return s != null ? s.trim() : "";
        } catch (java.io.IOException | SecurityException e) {
            return "";
        }
    }

    /** As above, for an instance (null: the dev entry). */
    static File chosenDriver(Context context, Instance current, File install) {
        String choice = current != null ? current.driver : Instance.DRIVER_CUSTOM;
        if (Instance.DRIVER_AUTO.equals(choice)) {
            // Turnip Gen8 is for Adreno 8xx only: any other GPU (Mali, Xclipse, an older Adreno)
            // gets the phone's own driver unless the player installs one.
            String gpu = gpuModel();
            choice = gpu.startsWith("Adreno8") ? Instance.DRIVER_BUILTIN : Instance.DRIVER_SYSTEM;
            Log.i(TAG, "Driver auto: GPU '" + gpu + "' -> " + choice);
        }
        if (Instance.DRIVER_SYSTEM.equals(choice)) {
            return null;
        }
        if (Instance.DRIVER_BUILTIN.equals(choice)) {
            File builtin = new File(context.getApplicationInfo().nativeLibraryDir, BUILTIN_DRIVER);
            if (builtin.isFile()) return builtin;
            Log.w(TAG, "No built-in driver in this APK; using the phone's own");
            return null;
        }
        File[] files = new File(install, "driver").listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".so")) {
                return copyToInternal(context, file, current != null && !current.isDev() ? "driver/" + current.id : "driver");
            }
        }
        return null;
    }

    /** A copy of file in the app's internal files/dir (refreshed when the original changes). */
    File copyToInternal(File file, String dir) {
        return copyToInternal(this, file, dir);
    }

    static File copyToInternal(Context context, File file, String dir) {
        File internal = new File(new File(context.getFilesDir(), dir), file.getName());
        if (!internal.isFile() || internal.length() != file.length() || internal.lastModified() < file.lastModified()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                internal.getParentFile().mkdirs();
                java.nio.file.Files.copy(file.toPath(), internal.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                Log.e(TAG, "Copying " + file + " failed", e);
                return null;
            }
        }
        return internal;
    }
}
