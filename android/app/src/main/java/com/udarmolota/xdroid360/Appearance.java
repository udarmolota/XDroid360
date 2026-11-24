package com.udarmolota.xdroid360;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The launcher's look: light, dark or the system's, and for the dark one of five colours (the
 * themes of values/themes_dark.xml: red, orange, yellow, blue, green). The choice is kept in the
 * launcher's preferences; {@link #apply} puts it on a screen, {@link #showDialog} changes it.
 */
final class Appearance {
    static final int SYSTEM = 0, LIGHT = 1, DARK = 2;

    private static final int[] ACCENT_NAMES = {
            R.string.theme_red, R.string.theme_orange, R.string.theme_yellow, R.string.theme_blue, R.string.theme_green };
    private static final int[] ACCENT_STYLES = {
            R.style.ThemeOverlay_Dao_Red, R.style.ThemeOverlay_Dao_Orange, R.style.ThemeOverlay_Dao_Yellow,
            R.style.ThemeOverlay_Dao_Blue, R.style.ThemeOverlay_Dao_Green };

    private Appearance() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    private static int mode(Context context) {
        return prefs(context).getInt("theme_mode", SYSTEM);
    }

    private static int accent(Context context) {
        int accent = prefs(context).getInt("dark_accent", 0);
        return accent >= 0 && accent < ACCENT_STYLES.length ? accent : 0;
    }

    private static boolean isNight(Context context, int mode) {
        return mode == DARK || (mode == SYSTEM
                && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
    }

    /** Every launcher screen, first thing in its onCreate (before super.onCreate). */
    static void apply(Activity activity) {
        int mode = mode(activity);
        AppCompatDelegate.setDefaultNightMode(mode == LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == DARK ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (isNight(activity, mode)) {
            activity.getTheme().applyStyle(ACCENT_STYLES[accent(activity)], true);
        }
    }

    /** The dialog of the menu's Theme entry: the mode, the dark colour, and a preview of them. */
    static void showDialog(Activity activity) {
        final int[] mode = { mode(activity) };
        final int[] accent = { accent(activity) };
        final float dp = activity.getResources().getDisplayMetrics().density;
        final int pad = Math.round(20 * dp);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, Math.round(8 * dp), pad, 0);

        final LinearLayout preview = new LinearLayout(activity);
        final TextView title = new TextView(activity);
        final TextView sample = new TextView(activity);
        final TextView button = new TextView(activity);
        Runnable refresh = () -> showPreview(activity, preview, title, sample, button, mode[0], accent[0]);

        column.addView(heading(activity, R.string.theme_mode, dp));
        RadioGroup modes = new RadioGroup(activity);
        int[] modeNames = { R.string.theme_system, R.string.theme_light, R.string.theme_dark };
        for (int i = 0; i < modeNames.length; i++) {
            RadioButton b = new RadioButton(activity);
            b.setId(View.generateViewId());
            b.setText(modeNames[i]);
            b.setTag(i);
            modes.addView(b);
            if (i == mode[0]) modes.check(b.getId());
        }

        column.addView(modes);
        column.addView(heading(activity, R.string.theme_accent, dp));
        RadioGroup accents = new RadioGroup(activity);
        for (int i = 0; i < ACCENT_NAMES.length; i++) {
            RadioButton b = new RadioButton(activity);
            b.setId(View.generateViewId());
            b.setText(ACCENT_NAMES[i]);
            b.setTag(i);
            // The button in the colour of its theme: the list is a palette.
            b.setButtonTintList(ColorStateList.valueOf(colorOf(activity, true, i, com.google.android.material.R.attr.colorPrimary)));
            accents.addView(b);
            if (i == accent[0]) accents.check(b.getId());
        }
        column.addView(accents);

        column.addView(heading(activity, R.string.theme_preview, dp));
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(pad, Math.round(12 * dp), pad, Math.round(12 * dp));
        title.setTextSize(16);
        sample.setText(R.string.theme_sample);
        sample.setTextSize(13);
        button.setText(R.string.theme_sample_button);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Math.round(20 * dp), Math.round(8 * dp), Math.round(20 * dp), Math.round(8 * dp));
        preview.addView(title);
        preview.addView(sample);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = Math.round(8 * dp);
        preview.addView(button, buttonParams);
        column.addView(preview);

        modes.setOnCheckedChangeListener((g, id) -> {
            mode[0] = (Integer) g.findViewById(id).getTag();
            refresh.run();
        });
        accents.setOnCheckedChangeListener((g, id) -> {
            accent[0] = (Integer) g.findViewById(id).getTag();
            refresh.run();
        });
        refresh.run();

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(column);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.nav_theme)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    prefs(activity).edit().putInt("theme_mode", mode[0]).putInt("dark_accent", accent[0]).apply();
                    // Sets the mode (the screens that are open follow it) and puts the colours on this one.
                    activity.recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static TextView heading(Activity activity, int text, float dp) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setPadding(0, Math.round(14 * dp), 0, Math.round(4 * dp));
        return view;
    }

    /** A colour of the light theme (accent -1) or of a dark one, read from the theme itself. */
    private static int colorOf(Activity activity, boolean night, int accent, int attr) {
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        ContextThemeWrapper themed = new ContextThemeWrapper(activity.createConfigurationContext(config), R.style.LauncherTheme);
        if (night) {
            themed.getTheme().applyStyle(ACCENT_STYLES[accent], true);
        }
        return MaterialColors.getColor(themed, attr, 0xFF808080);
    }

    private static void showPreview(Activity activity, LinearLayout preview, TextView title, TextView sample,
                                    TextView button, int mode, int accent) {
        boolean night = mode == DARK || (mode == SYSTEM && isNight(activity, SYSTEM));
        int background = colorOf(activity, night, accent, com.google.android.material.R.attr.colorSurface);
        int primary = colorOf(activity, night, accent, com.google.android.material.R.attr.colorPrimary);
        int onPrimary = colorOf(activity, night, accent, com.google.android.material.R.attr.colorOnPrimary);
        int onSurface = colorOf(activity, night, accent, com.google.android.material.R.attr.colorOnSurface);
        GradientDrawable card = new GradientDrawable();
        card.setColor(background);
        card.setCornerRadius(16 * activity.getResources().getDisplayMetrics().density);
        card.setStroke(Math.round(activity.getResources().getDisplayMetrics().density),
                colorOf(activity, night, accent, com.google.android.material.R.attr.colorOutline));
        preview.setBackground(card);
        title.setText(night ? ACCENT_NAMES[accent] : R.string.theme_light);
        title.setTextColor(primary);
        sample.setTextColor(onSurface);
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(primary);
        chip.setCornerRadius(100 * activity.getResources().getDisplayMetrics().density);
        button.setBackground(chip);
        button.setTextColor(onPrimary);
    }
}
