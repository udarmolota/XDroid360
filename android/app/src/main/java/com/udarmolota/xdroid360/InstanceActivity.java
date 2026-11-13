package com.udarmolota.xdroid360;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.io.IOException;

/** One game's settings: its details, Play, the driver and graphics switches, saves, the log, Delete. */
public class InstanceActivity extends AppCompatActivity {
    static final String EXTRA_ID = "instance";
    private static final String[] DRIVERS = { Instance.DRIVER_AUTO, Instance.DRIVER_BUILTIN, Instance.DRIVER_SYSTEM, Instance.DRIVER_CUSTOM };
    private static final String[] SYSMEM = { Instance.SYSMEM_AUTO, Instance.SYSMEM_ON, Instance.SYSMEM_OFF };

    private Instance instance;
    private Spinner driver;

    private final ActivityResultLauncher<String[]> pickDriver =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    InstanceActions.installDriver(this, instance, uri);
                    driver.setSelection(indexOf(DRIVERS, instance.driver), false);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_instance);
        instance = Instance.open(this, getIntent().getStringExtra(EXTRA_ID));

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.instance_settings);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());

        ImageView banner = findViewById(R.id.banner);
        new Thread(() -> {
            Bitmap b = Banner.load(this, instance);
            if (b != null) runOnUiThread(() -> banner.setImageBitmap(b));
        }).start();

        StringBuilder d = new StringBuilder(instance.buildLabel());
        if (!instance.isDev()) {
            d.append("\n").append(Formatter.formatFileSize(this, instance.manifest.optLong("game_bytes", 0)));
            d.append(", ").append(instance.manifest.optInt("game_files", 0)).append(" files");
            org.json.JSONObject b = instance.manifest.optJSONObject("build");
            if (b != null) d.append("\nDAORecomp ").append(shortCommit(b.optString("daorecomp"))).append(", ").append(b.optString("created"));
        }
        ((TextView) findViewById(R.id.name)).setText(instance.name);
        ((TextView) findViewById(R.id.details)).setText(d);

        driver = findViewById(R.id.driver);
        View driverPick = findViewById(R.id.driver_pick);
        driverPick.setVisibility(Instance.DRIVER_CUSTOM.equals(instance.driver) ? View.VISIBLE : View.GONE);
        bindSpinner(driver, R.array.drivers, indexOf(DRIVERS, instance.driver), i -> {
            instance.driver = DRIVERS[i];
            // The file to pick only matters for a custom driver.
            driverPick.setVisibility(Instance.DRIVER_CUSTOM.equals(instance.driver) ? View.VISIBLE : View.GONE);
        });
        driverPick.setOnClickListener(v -> pickDriver.launch(new String[] { "*/*" }));
        bindSpinner(findViewById(R.id.sysmem), R.array.sysmem, indexOf(SYSMEM, instance.sysmem), i -> instance.sysmem = SYSMEM[i]);
        bindOptions();
        bindTextureLod();
        View advanced = findViewById(R.id.advanced);
        View chevron = findViewById(R.id.advanced_chevron);
        findViewById(R.id.advanced_header).setOnClickListener(v -> {
            boolean open = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.setRotation(open ? 180 : 0);
        });
        android.widget.RadioGroup hud = findViewById(R.id.hud);
        int[] hudIds = { R.id.hud_off, R.id.hud_fps, R.id.hud_full };
        hud.check(hudIds[Math.max(0, Math.min(2, instance.hudMode))]);
        hud.setOnCheckedChangeListener((g, checked) -> {
            for (int i = 0; i < hudIds.length; i++) if (hudIds[i] == checked) instance.hudMode = i;
            save();
        });
        MaterialSwitch diagnostics = findViewById(R.id.diagnostics);
        diagnostics.setChecked(instance.diagnostics);
        diagnostics.setOnCheckedChangeListener((b, on) -> { instance.diagnostics = on; save(); });
        bindTouchControls();
        MaterialSwitch vibrate = findViewById(R.id.vibrate);
        vibrate.setChecked(instance.vibrateOnTouch);
        vibrate.setOnCheckedChangeListener((b, on) -> { instance.vibrateOnTouch = on; save(); });

        env = findViewById(R.id.env);
        env.setText(readEnv());
    }

    private void bindTouchControls() {
        android.widget.RadioGroup group = findViewById(R.id.control_mode);
        group.check(Instance.CONTROLS_TOUCH.equals(instance.controlMode)
                ? R.id.control_touch : R.id.control_gamepad);
        group.setOnCheckedChangeListener((g, checked) -> {
            instance.controlMode = checked == R.id.control_touch
                    ? Instance.CONTROLS_TOUCH : Instance.CONTROLS_GAMEPAD;
            save();
        });
    }

    private com.google.android.material.textfield.TextInputEditText env;

    /**
     * Texture detail: the one picked, or while none is, the renderer's default (so it follows a
     * change of renderer). Only a tap is a pick; the check set here isn't.
     */
    private void bindTextureLod() {
        android.widget.RadioGroup lod = findViewById(R.id.lod);
        int[] lodIds = { R.id.lod_full, R.id.lod_low, R.id.lod_ultra_low };
        lod.setOnCheckedChangeListener(null);
        lod.check(lodIds[instance.textureLodToUse()]);
        lod.setOnCheckedChangeListener((g, checked) -> {
            for (int i = 0; i < lodIds.length; i++) if (lodIds[i] == checked) instance.textureLodBias = i;
            save();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindFrameRateCap(); // again on return: the game's own Graphics tab may have changed it
    }

    @Override
    protected void onPause() {
        super.onPause();
        writeEnv();
    }

    /**
     * The frame rate limit (30, 60, none), shown from the game's config.toml and written there at
     * once, never at start: the game's own Graphics settings keep the same value, so neither side
     * overrides the other. Any other rate the game was set to leaves no button checked. A package
     * with its own frame rate option (options.frame_rate, applied at every start) keeps that one.
     * State of Decay offers 30 and 60 only: unlimited, its menus run at hundreds of fps and flicker.
     */
    private void bindFrameRateCap() {
        if (instance.option("frame_rate") != null) return;
        findViewById(R.id.fps_cap_card).setVisibility(View.VISIBLE);
        findViewById(R.id.fps_cap_off).setVisibility(
                Instance.SOD_TITLE_ID.equals(instance.titleId()) ? View.GONE : View.VISIBLE);
        android.widget.RadioGroup group = findViewById(R.id.fps_cap);
        int[] ids = { R.id.fps_cap_30, R.id.fps_cap_60, R.id.fps_cap_off };
        int[] rates = { 30, 60, 0 };
        group.setOnCheckedChangeListener(null);
        group.clearCheck();
        int cap = instance.frameRateCap();
        for (int i = 0; i < rates.length; i++) if (rates[i] == cap) group.check(ids[i]);
        group.setOnCheckedChangeListener((g, checked) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != checked) continue;
                try {
                    instance.setFrameRateCap(rates[i]);
                } catch (IOException e) {
                    Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** userdata/env.txt: KEY=VALUE lines the game starts with (GameActivity.applyEnvFile). */
    private File envFile() {
        return new File(instance.userdata(), "env.txt");
    }

    private String readEnv() {
        try {
            return envFile().isFile() ? new String(java.nio.file.Files.readAllBytes(envFile().toPath()),
                    java.nio.charset.StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void writeEnv() {
        if (env == null || env.getText() == null) return;
        String text = env.getText().toString().replace("\r", "").trim();
        try {
            if (text.isEmpty()) {
                //noinspection ResultOfMethodCallIgnored
                envFile().delete();
            } else {
                //noinspection ResultOfMethodCallIgnored
                instance.userdata().mkdirs();
                java.nio.file.Files.write(envFile().toPath(), (text + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
        }
    }

    /** The settings only some games have, offered by their package (manifest "options"). */
    private void bindOptions() {
        java.util.List<String> presets = instance.presets();
        if (!presets.isEmpty()) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            java.util.List<CharSequence> labels = new java.util.ArrayList<>();
            ids.add("");
            labels.add(getString(R.string.preset_default));
            for (String id : presets) {
                ids.add(id);
                labels.add(presetLabel(id));
            }
            int at = Math.max(0, ids.indexOf(instance.preset));
            showSpinner(R.id.preset_label, R.id.preset, labels, at, i -> instance.preset = ids.get(i));
        }
        // The game's language, its own card: the phone's by default.
        java.util.List<String> languageIds = new java.util.ArrayList<>();
        java.util.List<CharSequence> languageLabels = new java.util.ArrayList<>();
        languageIds.add("");
        languageLabels.add(getString(R.string.language_auto));
        for (String l : instance.languages()) {
            languageIds.add(l);
            languageLabels.add(Instance.languageName(l));
        }
        findViewById(R.id.language_card).setVisibility(View.VISIBLE);
        bindRadios(findViewById(R.id.language), languageLabels, Math.max(0, languageIds.indexOf(instance.language)),
                i -> instance.language = languageIds.get(i));
        // The renderer, for the games that have the native one.
        if (instance.hasNativeRenderer()) {
            findViewById(R.id.renderer_card).setVisibility(View.VISIBLE);
            // "Native" with a small green "NEW" tag after it.
            String label = getString(R.string.renderer_native);
            String tag = " " + getString(R.string.renderer_new) + " ";
            android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(label + "  " + tag);
            int start = label.length() + 2, end = text.length();
            text.setSpan(new android.text.style.BackgroundColorSpan(0xFF2E7D32), start, end, 0);
            text.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), start, end, 0);
            text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, 0);
            text.setSpan(new android.text.style.RelativeSizeSpan(0.75f), start, end, 0);
            ((TextView) findViewById(R.id.renderer_native)).setText(text);
            android.widget.RadioGroup renderer = findViewById(R.id.renderer);
            renderer.check(Instance.RENDERER_NATIVE.equals(instance.rendererToUse()) ? R.id.renderer_native : R.id.renderer_xenia);
            renderer.setOnCheckedChangeListener((g, checked) -> {
                instance.renderer = checked == R.id.renderer_native ? Instance.RENDERER_NATIVE : Instance.RENDERER_XENIA;
                save();
                bindTextureLod(); // its default depends on the renderer
            });
        }
        org.json.JSONObject frameRate = instance.option("frame_rate");
        org.json.JSONArray choices = frameRate != null ? frameRate.optJSONArray("choices") : null;
        if (choices != null && choices.length() > 0) {
            java.util.List<Integer> rates = new java.util.ArrayList<>();
            java.util.List<CharSequence> labels = new java.util.ArrayList<>();
            for (int i = 0; i < choices.length(); i++) {
                rates.add(choices.optInt(i));
                labels.add(getString(R.string.frame_rate_value, choices.optInt(i)));
            }
            int at = Math.max(0, rates.indexOf(instance.frameRateToUse()));
            showSpinner(R.id.frame_rate_label, R.id.frame_rate, labels, at, i -> instance.frameRate = rates.get(i));
        }
    }

    /** A preset's name in the player's language for the common ids, else the id itself. */
    private CharSequence presetLabel(String id) {
        switch (id) {
            case "medium": return getString(R.string.preset_medium);
            case "balanced": return getString(R.string.preset_balanced);
            case "low": return getString(R.string.preset_low);
            case "very_low": return getString(R.string.preset_very_low);
            default: return id;
        }
    }

    private void showSpinner(int labelId, int spinnerId, java.util.List<CharSequence> labels, int selected, OnPick onPick) {
        findViewById(labelId).setVisibility(View.VISIBLE);
        Spinner spinner = findViewById(spinnerId);
        spinner.setVisibility(View.VISIBLE);
        ArrayAdapter<CharSequence> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        listen(spinner, a, selected, onPick);
    }

    private interface OnPick { void picked(int index); }

    /** A radio button per label in group, the selected one checked; a pick saves at once. */
    private void bindRadios(android.widget.RadioGroup group, java.util.List<CharSequence> labels, int selected, OnPick onPick) {
        group.removeAllViews();
        int[] ids = new int[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            com.google.android.material.radiobutton.MaterialRadioButton b =
                    new com.google.android.material.radiobutton.MaterialRadioButton(this);
            ids[i] = View.generateViewId();
            b.setId(ids[i]);
            b.setText(labels.get(i));
            group.addView(b, new android.widget.RadioGroup.LayoutParams(
                    android.widget.RadioGroup.LayoutParams.MATCH_PARENT, android.widget.RadioGroup.LayoutParams.WRAP_CONTENT));
        }
        group.check(ids[Math.max(0, Math.min(ids.length - 1, selected))]);
        group.setOnCheckedChangeListener((g, checked) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checked) {
                    onPick.picked(i);
                    save();
                }
            }
        });
    }

    private void bindSpinner(Spinner spinner, int array, int selected, OnPick onPick) {
        ArrayAdapter<CharSequence> a = ArrayAdapter.createFromResource(this, array, android.R.layout.simple_spinner_item);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        listen(spinner, a, selected, onPick);
    }

    private void listen(Spinner spinner, ArrayAdapter<CharSequence> a, int selected, OnPick onPick) {
        spinner.setAdapter(a);
        spinner.setSelection(Math.max(0, selected), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onPick.picked(position);
                save();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void save() {
        try {
            instance.save();
        } catch (IOException e) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private static int indexOf(String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return 0;
    }

    private static String shortCommit(String c) {
        return c != null && c.length() > 8 ? c.substring(0, 8) : c;
    }
}
