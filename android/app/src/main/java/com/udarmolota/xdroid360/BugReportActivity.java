package com.udarmolota.xdroid360;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Report a bug", as in Zomdroid: choose the game, then send the report by email (the chooser
 * lists mail apps only), or save it to a file and send it from anywhere to the address shown.
 * The report always holds the game's log, Android's log of this app, the settings, crash
 * records, the device and the build. With more than one game the choice starts empty, so
 * nobody sends another game's logs by accident.
 */
public class BugReportActivity extends AppCompatActivity {
    private final List<Instance> instances = new ArrayList<>();
    /** Builds that failed or stopped: a game that didn't get installed can be reported too. */
    private final List<BuildJob> builds = new ArrayList<>();
    private Spinner instance;
    /** Whether the first row is "Choose the game" (more than one game). */
    private boolean chooseRow;
    private View email, saveButton;

    private final ActivityResultLauncher<String> save =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                if (uri == null) return;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    writeReport(out);
                    Toast.makeText(this, R.string.bug_report_saved, Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Appearance.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bug_report);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());

        instances.addAll(Instance.list(this));
        for (BuildJob j : BuildJob.unfinished(this)) if (!BuildService.isRunning(j.id)) builds.add(j);
        chooseRow = instances.size() + builds.size() > 1;
        List<String> names = new ArrayList<>();
        if (chooseRow) names.add(getString(R.string.bug_report_pick_game));
        for (Instance i : instances) names.add(i.name + " — " + i.buildLabel());
        for (BuildJob j : builds) names.add(j.name + " — " + getString(R.string.bug_report_build_failed));
        instance = findViewById(R.id.instance);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        instance.setAdapter(a);
        email = findViewById(R.id.email);
        saveButton = findViewById(R.id.save);
        instance.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateButtons();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                updateButtons();
            }
        });
        updateButtons();

        email.setOnClickListener(v -> sendEmail());
        saveButton.setOnClickListener(v -> save.launch("xdroid360_report_" + stamp() + ".zip"));
        findViewById(R.id.copy).setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("e-mail", getString(R.string.bug_report_email)));
            Toast.makeText(this, R.string.bug_report_copied, Toast.LENGTH_SHORT).show();
        });
    }

    private Instance selected() {
        int i = instance.getSelectedItemPosition() - (chooseRow ? 1 : 0);
        return i >= 0 && i < instances.size() ? instances.get(i) : null;
    }

    /** The failed build chosen instead of a game, or null. */
    private BuildJob selectedBuild() {
        int i = instance.getSelectedItemPosition() - (chooseRow ? 1 : 0) - instances.size();
        return i >= 0 && i < builds.size() ? builds.get(i) : null;
    }

    /** Sending waits for a game to be chosen (with no games at all, the report has the device only). */
    private void updateButtons() {
        boolean ready = !chooseRow || selected() != null || selectedBuild() != null;
        email.setEnabled(ready);
        saveButton.setEnabled(ready);
    }

    private void sendEmail() {
        try {
            File dir = new File(getCacheDir(), "logs");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File zip = new File(dir, "xdroid360_report_" + stamp() + ".zip");
            try (OutputStream out = new FileOutputStream(zip)) {
                writeReport(out);
            }
            Uri attach = FileProvider.getUriForFile(this, getPackageName() + ".files", zip);
            Instance game = selected();
            BuildJob build = selectedBuild();
            String subject = "XDroid360 bug report" + (game != null ? ": " + game.name
                    : build != null ? ": " + build.name + " (build)" : "");
            String body = (game != null ? "Game: " + game.name + " (" + game.buildLabel() + ")\n"
                    : build != null ? "Build: " + build.name + ", " + build.state + " at " + build.failedStep
                            + " (" + build.errorCode + ")\n" : "")
                    + deviceInfo() + "\n\nWhat happened:\n";
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_EMAIL, new String[] { getString(R.string.bug_report_email) })
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, body)
                    .putExtra(Intent.EXTRA_STREAM, attach)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            pickMailApp(send);
        } catch (IOException e) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Mail apps only, as in Zomdroid: the apps that handle mailto: and also take this ACTION_SEND,
     * each started by its screen, not its package. Mail.ru has two screens that take the mail:
     * one mails the player and ignores the address and the text, the other is a normal new
     * letter; started by package it opened the first. Screens that mail "yourself" are skipped.
     */
    private void pickMailApp(Intent send) {
        android.content.pm.PackageManager pm = getPackageManager();
        List<String> packages = new ArrayList<>();
        List<android.content.ComponentName> targets = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        List<android.graphics.drawable.Drawable> icons = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)) {
            String pkg = r.activityInfo.packageName;
            if (packages.contains(pkg)) continue;
            android.content.ComponentName target = null;
            for (ResolveInfo s : pm.queryIntentActivities(new Intent(send).setPackage(pkg), 0)) {
                if (s.activityInfo.name.toLowerCase(Locale.ROOT).contains("self")) continue;
                target = new android.content.ComponentName(pkg, s.activityInfo.name);
                break;
            }
            if (target == null) continue;
            packages.add(pkg);
            targets.add(target);
            labels.add(r.activityInfo.applicationInfo.loadLabel(pm));
            icons.add(r.activityInfo.applicationInfo.loadIcon(pm));
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.bug_report_no_mail_app, Toast.LENGTH_LONG).show();
            return;
        }
        if (targets.size() == 1) {
            launchMail(send, targets.get(0));
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        ArrayAdapter<CharSequence> rows = new ArrayAdapter<CharSequence>(this, android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int pos, View convert, android.view.ViewGroup parent) {
                android.widget.TextView tv = (android.widget.TextView) super.getView(pos, convert, parent);
                android.graphics.drawable.Drawable d = icons.get(pos);
                int size = Math.round(36 * dp);
                d.setBounds(0, 0, size, size);
                tv.setCompoundDrawablesRelative(d, null, null, null);
                tv.setCompoundDrawablePadding(Math.round(16 * dp));
                tv.setPaddingRelative(Math.round(24 * dp), tv.getPaddingTop(), tv.getPaddingEnd(), tv.getPaddingBottom());
                return tv;
            }
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bug_report_send_email)
                .setAdapter(rows, (d, w) -> launchMail(send, targets.get(w)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchMail(Intent send, android.content.ComponentName target) {
        try {
            startActivity(new Intent(send).setComponent(target));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.bug_report_no_mail_app, Toast.LENGTH_LONG).show();
        }
    }

    /** A file in the report: no bigger (a game log stays far below; a runaway one is left out). */
    private static final long MAX_FILE = 4L << 20;

    private void writeReport(OutputStream out) throws IOException {
        Instance i = selected();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "device.txt", deviceInfo().getBytes(StandardCharsets.UTF_8));
            if (i != null) {
                // The profile the game was built from beside the APK's, so a report from a game
                // not updated after installing a new launcher shows it at once.
                Recipe bundled = i.bundledProfile(this);
                String profiles = bundled == null ? ""
                        : "\nprofile " + i.profileVersion() + ", bundled " + bundled.version()
                        + (i.newerProfile(this) != null ? " (UPDATE NOT APPLIED)" : "");
                put(zip, "instance.txt", (i.name + "\n" + i.buildLabel() + profiles + "\ndriver " + i.driver + ", sysmem " + i.sysmem
                        + ", texture LOD " + i.textureLodToUse() + (i.textureLodBias < 0 ? " (default)" : "")
                        + ", display " + i.hudMode + "\n\n"
                        + i.manifest.toString()).getBytes(StandardCharsets.UTF_8));
                // The game's own log and settings, whatever it names them (dao.log, sod.log,
                // config.toml, the game's own .toml), and the launcher's env.txt.
                File[] files = i.userdata().listFiles();
                if (files != null) {
                    java.util.Arrays.sort(files);
                    for (File f : files) {
                        String n = f.getName().toLowerCase(Locale.ROOT);
                        if (f.isFile() && f.length() <= MAX_FILE
                                && (n.endsWith(".log") || n.endsWith(".toml") || n.equals("env.txt"))) {
                            putFile(zip, f.getName(), f);
                        }
                    }
                }
            }
            BuildJob build = selectedBuild();
            if (build != null) {
                // The build's job (state, failed step, error) and its logs: the failed step's,
                // and each compile's (the one that failed is the newest).
                putFile(zip, "build.json", new File(build.internal(this), "build.json"));
                File logs = new File(build.work(this), "logs");
                List<File> files = new ArrayList<>();
                collect(logs, files);
                files.sort((x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                long total = 0;
                for (File f : files) {
                    if (f.length() > MAX_FILE || (total += f.length()) > 4 * MAX_FILE) continue;
                    putFile(zip, "build-logs/" + logs.toURI().relativize(f.toURI()).getPath(), f);
                }
            }
            // Android gives an app only its own log; the newest lines are the ones about the bug.
            put(zip, "logcat.txt", run("logcat", "-d", "-v", "time", "-t", "5000"));
            put(zip, "crash.txt", run("logcat", "-d", "-b", "crash", "-v", "time"));
        }
    }

    private String deviceInfo() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        // The GPU as its own driver names it (Adreno's kernel file exists on Adreno only).
        String gpu = glRenderer();
        String adreno = GameActivity.gpuModel();
        if (!adreno.isEmpty()) gpu += " (" + adreno + ")";
        return "XDroid360 " + version + " (abi " + GameActivity.DAO_ABI + ")"
                + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nSoC: " + (Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : "?")
                + "\nGPU: " + gpu;
    }

    /** "Mali-G720-Immortalis MC12 (ARM, OpenGL ES 3.2 v1.r...)": from a throwaway GLES context. */
    private static String glRenderer() {
        android.opengl.EGLDisplay display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
        if (display == android.opengl.EGL14.EGL_NO_DISPLAY) return "?";
        int[] version = new int[2];
        if (!android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)) return "?";
        try {
            int[] attribs = { android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                              android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                              android.opengl.EGL14.EGL_NONE };
            android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
            int[] count = new int[1];
            if (!android.opengl.EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return "?";
            android.opengl.EGLContext context = android.opengl.EGL14.eglCreateContext(display, configs[0],
                    android.opengl.EGL14.EGL_NO_CONTEXT, new int[] { android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE }, 0);
            android.opengl.EGLSurface surface = android.opengl.EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[] { android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE }, 0);
            try {
                if (!android.opengl.EGL14.eglMakeCurrent(display, surface, surface, context)) return "?";
                return android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER) + " ("
                        + android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR) + ", "
                        + android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION) + ")";
            } finally {
                android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE,
                        android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
                android.opengl.EGL14.eglDestroySurface(display, surface);
                android.opengl.EGL14.eglDestroyContext(display, context);
            }
        } catch (RuntimeException e) {
            return "?";
        } finally {
            android.opengl.EGL14.eglTerminate(display);
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) collect(c, out);
            else out.add(c);
        }
    }

    private static byte[] run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] out = readAll(p.getInputStream());
            p.waitFor();
            return out;
        } catch (IOException | InterruptedException e) {
            return e.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static void putFile(ZipOutputStream zip, String name, File f) throws IOException {
        if (!f.isFile()) return;
        try (InputStream in = new FileInputStream(f)) {
            put(zip, name, readAll(in));
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toByteArray();
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
    }
}
