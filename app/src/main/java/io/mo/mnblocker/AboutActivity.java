package io.mo.mnblocker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class AboutActivity extends Activity
{
    private static final int COLOR_BG = 0xFFF6F7FB;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_SUB = 0xFF6D7484;
    private static final int COLOR_LINE = 0xFFE7EAF0;
    private static final int COLOR_LINK = 0xFF254FD8;
    private static final int COLOR_PRIMARY = 0xFF3F6DF6;
    private static final String SOURCE_URL = "https://github.com/lm060719/io.mo.mnblocker";

    /** Hidden debug entrance: tap the icon or name 5 times. */
    private int tapCount;
    private long lastTapTime;

    @Override
    protected void attachBaseContext(Context newBase)
    {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(26), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(appInfoBlock());
        root.addView(descriptionCard());
        root.addView(languageCard());
        root.addView(sourceCard());

        setContentView(scroll);
        SystemBars.edgeToEdge(this, scroll, root, root);
    }

    private View appInfoBlock()
    {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(10), 0, dp(22));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.app_icon);
        icon.setContentDescription(getString(R.string.app_name));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setOnClickListener(v -> onSecretTap());
        box.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView name = new TextView(this);
        name.setText(getString(R.string.app_name));
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(24);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        name.setOnClickListener(v -> onSecretTap());
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(14);
        box.addView(name, nameLp);

        TextView version = new TextView(this);
        version.setText(getString(R.string.about_version_fmt, versionName()));
        version.setTextColor(COLOR_SUB);
        version.setTextSize(13);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        versionLp.topMargin = dp(4);
        box.addView(version, versionLp);

        return box;
    }

    private void onSecretTap()
    {
        long now = System.currentTimeMillis();
        if (now - lastTapTime > 2000) {
            tapCount = 0;
        }
        lastTapTime = now;
        tapCount++;

        if (tapCount >= 5) {
            tapCount = 0;
            startActivity(new Intent(this, DebugActivity.class));
        }
    }

    private View descriptionCard()
    {
        LinearLayout card = cardLayout();

        TextView description = new TextView(this);
        description.setText(getString(R.string.about_description, HookLogger.DIR));
        description.setTextSize(12);
        description.setTextColor(COLOR_SUB);
        description.setLineSpacing(dp(3), 1.0f);
        card.addView(description);

        return card;
    }

    private View languageCard()
    {
        LinearLayout card = cardLayout();

        TextView title = new TextView(this);
        title.setText(getString(R.string.about_language_title));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.about_language_desc));
        desc.setTextSize(12);
        desc.setTextColor(COLOR_SUB);
        desc.setPadding(0, dp(4), 0, dp(12));
        card.addView(desc);

        String current = LocaleManager.getLanguage(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button zh = languageButton(getString(R.string.lang_zh_label),
                LocaleManager.LANG_ZH.equals(current));
        zh.setOnClickListener(v -> onLanguageSelected(LocaleManager.LANG_ZH));
        LinearLayout.LayoutParams zhLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        zhLp.rightMargin = dp(8);
        row.addView(zh, zhLp);

        Button en = languageButton(getString(R.string.lang_en_label),
                LocaleManager.LANG_EN.equals(current));
        en.setOnClickListener(v -> onLanguageSelected(LocaleManager.LANG_EN));
        row.addView(en, new LinearLayout.LayoutParams(0, dp(44), 1f));

        card.addView(row);
        return card;
    }

    private Button languageButton(String text, boolean selected)
    {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        if (selected)
        {
            b.setTextColor(Color.WHITE);
            b.setBackground(roundBg(COLOR_PRIMARY, dp(12)));
        }
        else
        {
            b.setTextColor(COLOR_TEXT);
            b.setBackground(roundStrokeBg(COLOR_CARD, dp(12), COLOR_LINE, 1));
        }
        return b;
    }

    /** Persists the pick and restarts the whole task so every screen re-reads
     *  its strings against the new locale (attachBaseContext only runs once,
     *  at activity creation). */
    private void onLanguageSelected(String lang)
    {
        if (lang.equals(LocaleManager.getLanguage(this)))
        {
            return;
        }
        LocaleManager.setLanguage(this, lang);
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private View sourceCard()
    {
        LinearLayout card = cardLayout();

        TextView title = new TextView(this);
        title.setText(getString(R.string.about_source_title));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView source = new TextView(this);
        source.setText(SOURCE_URL);
        source.setTextColor(COLOR_LINK);
        source.setTextSize(13);
        source.setTextIsSelectable(true);
        source.setOnClickListener(v -> startActivity(new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(SOURCE_URL))));
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceLp.topMargin = dp(8);
        card.addView(source, sourceLp);

        return card;
    }

    private String versionName()
    {
        try
        {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null)
            {
                return info.versionName;
            }
        }
        catch (PackageManager.NameNotFoundException ignored)
        {
        }
        return "";
    }

    private LinearLayout cardLayout()
    {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundBg(COLOR_CARD, dp(22)));
        card.setElevation(dp(2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private GradientDrawable roundBg(int color, int radius)
    {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    private GradientDrawable roundStrokeBg(int color, int radius, int strokeColor, int strokeWidth)
    {
        GradientDrawable gd = roundBg(color, radius);
        gd.setStroke(strokeWidth, strokeColor);
        return gd;
    }

    private int dp(int v)
    {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
