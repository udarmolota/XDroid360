package com.udarmolota.xdroid360;

import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;

/**
 * The short wiki from the drawer's link row: installing a game, the editions, DLC and title
 * updates, updating after a new launcher, the game settings, controls and saves, what to do when
 * something goes wrong, and the antivirus warnings. One page of HTML per launcher language
 * (R.string.wiki_text in values and values-ru).
 */
public class WikiActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Appearance.apply(this);
        super.onCreate(savedInstanceState);
        int pad = dp(16);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.wiki_title);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView text = new TextView(this);
        text.setPadding(pad, 0, pad, pad);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setLineSpacing(0, 1.15f);
        text.setText(Html.fromHtml(getString(R.string.wiki_text), Html.FROM_HTML_MODE_COMPACT));
        text.setMovementMethod(LinkMovementMethod.getInstance());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(toolbar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        // Edge to edge (targetSdk 35): keep the toolbar and the text off the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(root);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
