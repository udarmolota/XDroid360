package com.udarmolota.xdroid360;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Adding a game: what the player picked (which game, whether it's a supported version, the
 * space it needs), then the build on the phone (BuildService) with its progress, cancel,
 * retry and continue. Opened with a pick (EXTRA_URI, EXTRA_PICK) or an existing build (EXTRA_JOB).
 */
public class BuildActivity extends AppCompatActivity {
    static final String EXTRA_URI = "uri", EXTRA_PICK = "pick", EXTRA_JOB = "job";
    private static final long MB = 1L << 20;
    /** Room for the unpacked toolchain kit and profile, beyond the recipe's own numbers. */
    private static final long TOOLCHAIN_MB = 200;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView title, details, step, message;
    private View nameLayout;
    private TextInputEditText name;
    private LinearProgressIndicator progress;
    private MaterialButton primary, secondary, log;
    private String jobId;
    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            showJob();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Appearance.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_build);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.build_title);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        title = findViewById(R.id.title);
        details = findViewById(R.id.details);
        step = findViewById(R.id.step);
        message = findViewById(R.id.message);
        nameLayout = findViewById(R.id.name_layout);
        name = findViewById(R.id.name);
        progress = findViewById(R.id.progress);
        primary = findViewById(R.id.primary);
        secondary = findViewById(R.id.secondary);
        log = findViewById(R.id.log);
        log.setOnClickListener(v -> showLog());

        jobId = savedInstanceState != null ? savedInstanceState.getString(EXTRA_JOB) : getIntent().getStringExtra(EXTRA_JOB);
        if (jobId == null) detect(getIntent().getParcelableExtra(EXTRA_URI), getIntent().getStringExtra(EXTRA_PICK));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(EXTRA_JOB, jobId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (jobId != null) showJob();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    // ---------------------------------------------------------------- the pick

    private void detect(Uri uri, String pick) {
        if (uri == null) {
            finish();
            return;
        }
        title.setText(R.string.build_reading);
        progress.setIndeterminate(true);
        new Thread(() -> {
            try {
                GameSource source = GameSource.detect(this, uri, pick);
                ui.post(() -> showCard(source));
            } catch (Recipe.Failure f) {
                android.util.Log.w("XDroid360", "detect " + uri + ": " + f.code + " " + f.getMessage());
                ui.post(() -> showRefused(f));
            }
        }, "detect").start();
    }

    private void showRefused(Recipe.Failure f) {
        title.setText(R.string.build_cannot);
        progress.setVisibility(View.GONE);
        setMessage(BuildErrors.text(this, f.code));
        button(primary, R.string.build_close, v -> finish());
    }

    private void showCard(GameSource source) {
        Recipe recipe = source.recipe;
        progress.setVisibility(View.GONE);
        title.setText(recipe.title());
        // A zipped game is inflated next to its files first (the disc image, or a package at a time).
        long gameNeed = Math.max(recipe.spaceMb("game_mb") * MB, source.gameBytes) + source.scratchBytes;
        long internalNeed = (recipe.spaceMb("work_mb") + recipe.spaceMb("install_mb") + TOOLCHAIN_MB) * MB;
        long gameFree = free(Instance.instancesRoot(this)), internalFree = free(getFilesDir());
        details.setText(getString(R.string.build_details, source.titleId,
                Formatter.formatShortFileSize(this, gameNeed), Formatter.formatShortFileSize(this, gameFree),
                Formatter.formatShortFileSize(this, internalNeed), Formatter.formatShortFileSize(this, internalFree)));
        if (Boolean.FALSE.equals(source.supportedVersion)) {
            setMessage(BuildErrors.text(this, "unsupported_game_version"));
            button(primary, R.string.build_close, v -> finish());
            return;
        }
        String mismatch = Toolchain.present(this) ? Toolchain.mismatch(this, recipe) : "no toolchain";
        if (mismatch != null) {
            android.util.Log.w("XDroid360", "can't build " + recipe.profileId + ": " + mismatch);
            setMessage(getString(R.string.build_no_toolchain));
            button(primary, R.string.build_close, v -> finish());
            return;
        }
        if (gameFree < gameNeed || internalFree < internalNeed) {
            setMessage(BuildErrors.text(this, "not_enough_space"));
            button(primary, R.string.build_close, v -> finish());
            return;
        }
        nameLayout.setVisibility(View.VISIBLE);
        name.setText(Instance.uniqueName(Instance.list(this), recipe.title()));
        step.setText(R.string.build_time);
        button(primary, R.string.build_start, v -> start(source));
        button(secondary, R.string.build_close, v -> finish());
    }

    private void start(GameSource source) {
        if (BuildService.busy()) {
            setMessage(getString(R.string.build_busy));
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(source.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // Without it the build still runs now; only continuing after a restart may lose the file.
            android.util.Log.w("XDroid360", "no lasting access to " + source.uri + ": " + e);
        }
        String n = name.getText() != null ? name.getText().toString().trim() : "";
        BuildJob job = BuildJob.create(source.recipe.profileId, source.uri, source.pick,
                n.isEmpty() ? source.recipe.title() : n);
        job.titleId = source.titleId;
        job.stamp = source.recipe.stamp();
        try {
            job.save(this);
        } catch (IOException e) {
            setMessage(BuildErrors.text(this, "not_enough_space"));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        jobId = job.id;
        nameLayout.setVisibility(View.GONE);
        BuildService.start(this, job.id);
        showJob();
    }

    // ---------------------------------------------------------------- the build

    private void showJob() {
        ui.removeCallbacks(poll);
        BuildJob job = BuildJob.load(this, jobId);
        if (job == null) {
            finish();
            return;
        }
        title.setText(job.name);
        details.setText("");
        hide(primary, secondary, log);
        message.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        BuildService.Status s = BuildService.status(job.id);
        if (BuildService.isRunning(job.id) || (s != null && !s.finished && BuildJob.RUNNING.equals(job.state))) {
            progress.setIndeterminate(s == null || s.overall <= 0);
            if (s != null) progress.setProgressCompat((int) (s.overall * 1000), true);
            step.setText(s == null || s.step.isEmpty() ? getString(R.string.build_preparing)
                    : s.step + (s.detail.isEmpty() ? "" : " · " + s.detail));
            button(secondary, R.string.build_cancel, v -> confirmCancel(job));
            ui.postDelayed(poll, 300);
            return;
        }
        progress.setIndeterminate(false);
        step.setText("");
        switch (job.state) {
            case BuildJob.DONE:
                progress.setProgressCompat(1000, false);
                step.setText(R.string.build_done);
                button(primary, R.string.build_done_button, v -> finish());
                break;
            case BuildJob.FAILED:
                progress.setVisibility(View.GONE);
                setMessage(BuildErrors.text(this, job.errorCode));
                button(primary, R.string.build_retry, v -> resume(job));
                button(secondary, R.string.build_delete, v -> confirmDelete(job));
                log.setVisibility(BuildService.newestLog(this, job.id) != null ? View.VISIBLE : View.GONE);
                break;
            default: // cancelled, or "running" with nothing running it: Android stopped the app
                progress.setVisibility(View.GONE);
                setMessage(getString(BuildJob.CANCELLED.equals(job.state) ? R.string.build_cancelled : R.string.build_interrupted));
                button(primary, R.string.build_continue, v -> resume(job));
                button(secondary, R.string.build_delete, v -> confirmDelete(job));
                break;
        }
    }

    private void resume(BuildJob job) {
        if (BuildService.isRunning(job.id)) { // already going: show it
            showJob();
            return;
        }
        if (BuildService.busy()) {
            setMessage(getString(R.string.build_busy));
            return;
        }
        BuildService.start(this, job.id);
        ui.postDelayed(poll, 300);
    }

    private void confirmCancel(BuildJob job) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.build_cancel_confirm)
                .setPositiveButton(R.string.build_cancel, (d, w) -> BuildService.cancel(this, job.id))
                .setNegativeButton(R.string.build_keep_going, null)
                .show();
    }

    private void confirmDelete(BuildJob job) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.build_delete_confirm)
                .setPositiveButton(R.string.build_delete, (d, w) -> new Thread(() -> {
                    job.delete(this);
                    ui.post(this::finish);
                }, "delete build").start())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The last 200 lines of the newest log (the failed step's). */
    private void showLog() {
        File f = BuildService.newestLog(this, jobId);
        if (f == null) return;
        String text;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long from = Math.max(0, r.length() - 32 * 1024);
            byte[] b = new byte[(int) (r.length() - from)];
            r.seek(from);
            r.readFully(b);
            text = new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = e.toString();
        }
        String[] lines = text.split("\n");
        StringBuilder tail = new StringBuilder();
        for (int i = Math.max(0, lines.length - 200); i < lines.length; i++) tail.append(lines[i]).append('\n');
        TextView view = new TextView(this);
        view.setTextIsSelectable(true);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextSize(11);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        view.setText(tail);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(view);
        new MaterialAlertDialogBuilder(this)
                .setTitle(f.getName())
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---------------------------------------------------------------- helpers

    private void setMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private static void button(MaterialButton b, int text, View.OnClickListener click) {
        b.setText(text);
        b.setOnClickListener(click);
        b.setVisibility(View.VISIBLE);
    }

    private static void hide(View... views) {
        for (View v : views) v.setVisibility(View.GONE);
    }

    private static long free(File dir) {
        File d = dir;
        while (d != null && !d.exists()) d = d.getParentFile();
        if (d == null) return 0;
        return new StatFs(d.getPath()).getAvailableBytes();
    }
}
