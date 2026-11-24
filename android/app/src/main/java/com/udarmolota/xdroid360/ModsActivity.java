package com.udarmolota.xdroid360;

import android.app.ProgressDialog;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A game's file-override mods: each folder under {@code <game>/mods/} mirrors the game's folder
 * (mods/MyMod/packages/core/override/foo.gda). Here they are switched on and off, put in load order
 * (a later mod wins over an earlier one) and added from a .zip. The order is kept in
 * mods/load_order.txt, which the game reads when it starts (mods_android.cpp).
 */
public class ModsActivity extends AppCompatActivity {
    private static final String ORDER_FILE = "load_order.txt";

    private static final class Mod {
        final String folder;
        boolean enabled;

        Mod(String folder, boolean enabled) {
            this.folder = folder;
            this.enabled = enabled;
        }
    }

    private Instance instance;
    private File modsDir;
    private final List<Mod> mods = new ArrayList<>();
    private LinearLayout list;
    private ActivityResultLauncher<String[]> picker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Appearance.apply(this);
        super.onCreate(savedInstanceState);
        instance = Instance.open(this, getIntent().getStringExtra(InstanceActivity.EXTRA_ID));
        if (instance == null) {
            finish();
            return;
        }
        modsDir = new File(instance.root, "mods");
        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) install(uri);
        });

        final float dp = getResources().getDisplayMetrics().density;
        final int pad = Math.round(16 * dp);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0));
        // Clear of the system bars (the launcher's screens draw under them).
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            //noinspection deprecation
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = new TextView(this);
        title.setText(getString(R.string.mods_title, instance.name));
        title.setTextSize(20);
        title.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0));
        title.setPadding(pad, pad, pad, Math.round(4 * dp));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText(R.string.mods_hint);
        hint.setTextSize(13);
        hint.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
        hint.setPadding(pad, 0, pad, Math.round(8 * dp));
        root.addView(hint);

        MaterialButton add = new MaterialButton(this);
        add.setText(R.string.mods_add);
        add.setOnClickListener(v -> picker.launch(new String[] { "application/zip",
                "application/x-zip-compressed", "application/octet-stream" }));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.setMargins(pad, 0, pad, Math.round(8 * dp));
        root.addView(add, addParams);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, 0, pad, pad);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        root.requestApplyInsets();

        load();
        render();
    }

    // ----------------------------------------------------------------- the list

    /** The folders under mods/ in the saved order; new ones are enabled, after the listed ones. */
    private void load() {
        mods.clear();
        TreeSet<String> onDisk = new TreeSet<>();
        File[] dirs = modsDir.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && !d.getName().startsWith(".")) onDisk.add(d.getName());
            }
        }
        File file = new File(modsDir, ORDER_FILE);
        if (file.isFile()) {
            try {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.length() > 1 && (line.charAt(0) == '+' || line.charAt(0) == '-')
                            && onDisk.remove(line.substring(1))) {
                        mods.add(new Mod(line.substring(1), line.charAt(0) == '+'));
                    }
                }
            } catch (IOException e) {
                android.util.Log.w("Mods", "Can't read " + file + ": " + e);
            }
        }
        for (String name : onDisk) mods.add(new Mod(name, true));
    }

    private void save() {
        StringBuilder text = new StringBuilder();
        for (Mod m : mods) text.append(m.enabled ? '+' : '-').append(m.folder).append('\n');
        try {
            //noinspection ResultOfMethodCallIgnored
            modsDir.mkdirs();
            File tmp = new File(modsDir, ORDER_FILE + ".tmp");
            Files.write(tmp.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), new File(modsDir, ORDER_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void render() {
        list.removeAllViews();
        final float dp = getResources().getDisplayMetrics().density;
        if (mods.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.mods_empty);
            empty.setPadding(0, Math.round(16 * dp), 0, 0);
            list.addView(empty);
            return;
        }
        for (int i = 0; i < mods.size(); i++) {
            final int index = i;
            final Mod mod = mods.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Math.round(8 * dp), 0, Math.round(8 * dp));

            MaterialSwitch toggle = new MaterialSwitch(this);
            toggle.setText(mod.folder);
            toggle.setChecked(mod.enabled);
            toggle.setOnCheckedChangeListener((b, checked) -> {
                mod.enabled = checked;
                save();
            });
            row.addView(toggle);

            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.addView(action(R.string.mods_up, index > 0, () -> move(index, index - 1)));
            buttons.addView(action(R.string.mods_down, index < mods.size() - 1, () -> move(index, index + 1)));
            buttons.addView(action(R.string.mods_delete, true, () -> confirmDelete(mod)));
            row.addView(buttons);
            list.addView(row);
        }
    }

    private View action(int text, boolean enabled, Runnable run) {
        MaterialButton b = new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setEnabled(enabled);
        b.setOnClickListener(v -> run.run());
        return b;
    }

    private void move(int from, int to) {
        mods.add(to, mods.remove(from));
        save();
        render();
    }

    private void confirmDelete(Mod mod) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.mods_delete_title, mod.folder))
                .setMessage(R.string.mods_delete_message)
                .setPositiveButton(R.string.mods_delete, (d, w) -> {
                    Instance.deleteTree(new File(modsDir, mod.folder));
                    mods.remove(mod);
                    save();
                    render();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------ adding a .zip

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return "mod.zip";
    }

    private void install(Uri uri) {
        String file = displayName(uri);
        String base = file.toLowerCase(Locale.ROOT).endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
        base = base.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (base.isEmpty() || base.startsWith(".")) base = "mod";
        final String name = base;
        final ProgressDialog progress = ProgressDialog.show(this, null, getString(R.string.mods_adding), true, false);
        new Thread(() -> {
            String result = null, error = null;
            try {
                result = extract(uri, name);
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            final String done = result, failed = error;
            runOnUiThread(() -> {
                progress.dismiss();
                if (failed != null) {
                    Toast.makeText(this, getString(R.string.mods_failed, failed), Toast.LENGTH_LONG).show();
                    return;
                }
                load();
                save();
                render();
                Toast.makeText(this, getString(R.string.mods_added, done), Toast.LENGTH_SHORT).show();
            });
        }, "mod-install").start();
    }

    /**
     * Puts a mod's files where the game finds them, as DAORecomp's launcher does: packages/, core/
     * and override/ folders keep their place, anything else goes to packages/core/override/<mod>/;
     * readmes and pictures stay at the top. Returns the mod's folder name.
     */
    private String extract(Uri uri, String name) throws IOException {
        File cache = new File(getCacheDir(), "mod-upload.zip");
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(cache)) {
            if (in == null) throw new IOException("can't read the file");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
        File finalDir = new File(modsDir, name);
        for (int n = 2; finalDir.exists(); n++) finalDir = new File(modsDir, name + " (" + n + ")");
        File work = new File(modsDir, ".adding-" + finalDir.getName());
        Instance.deleteTree(work);
        boolean any = false;
        try (ZipFile zip = new ZipFile(cache)) {
            // A single folder around everything is dropped (unless it's one of the game's own).
            String wrapper = null;
            boolean single = true;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                String[] parts = entry.getName().replace('\\', '/').split("/");
                if (parts.length < 2 || (wrapper != null && !wrapper.equals(parts[0]))) {
                    single = false;
                    break;
                }
                wrapper = parts[0];
            }
            if (single && wrapper != null && isGameFolder(wrapper)) single = false;

            final String canonicalWork = work.getCanonicalPath() + File.separator;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');
                if (single && wrapper != null) path = path.substring(wrapper.length() + 1);
                String mapped = map(path, finalDir.getName());
                if (mapped == null) continue;
                File target = new File(work, mapped);
                // A path may not leave the mod's folder.
                if (!target.getCanonicalPath().startsWith(canonicalWork)) continue;
                //noinspection ResultOfMethodCallIgnored
                target.getParentFile().mkdirs();
                try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[1 << 16];
                    for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                }
                if (mapped.startsWith("packages/")) any = true;
            }
        } catch (IOException | RuntimeException e) {
            Instance.deleteTree(work);
            throw e;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            cache.delete();
        }
        if (!any) {
            Instance.deleteTree(work);
            throw new IOException(getString(R.string.mods_no_files));
        }
        if (!work.renameTo(finalDir)) {
            Instance.deleteTree(work);
            throw new IOException("can't create " + finalDir.getName());
        }
        // (A folder not in the load order yet is enabled and last: load() puts it there.)
        return finalDir.getName();
    }

    private static boolean isGameFolder(String segment) {
        String s = segment.toLowerCase(Locale.ROOT);
        return s.equals("packages") || s.equals("core") || s.equals("override");
    }

    /** Where a file of the .zip goes in the mod's folder; null to leave it out. */
    private static String map(String path, String mod) {
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty()) return null;
        int slash = path.indexOf('/');
        String first = (slash < 0 ? path : path.substring(0, slash)).toLowerCase(Locale.ROOT);
        switch (first) {
            case "packages":
                return path;
            case "core":
                return "packages/" + path;
            case "override":
                return "packages/core/" + path;
            default:
                break;
        }
        if (slash < 0) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (String ext : new String[] { ".txt", ".md", ".pdf", ".png", ".jpg", ".jpeg", ".toml", ".xml", ".rtf", ".html" }) {
                if (lower.endsWith(ext)) return path;
            }
        }
        return "packages/core/override/" + mod + "/" + path;
    }
}
