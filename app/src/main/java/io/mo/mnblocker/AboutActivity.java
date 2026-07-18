package io.mo.mnblocker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
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
    private static final int COLOR_WARN_BG = 0xFFFFF5DD;
    private static final int COLOR_WARN_TEXT = 0xFF9A5B00;
    /** Popup width; also the offset that right-aligns it with its row. */
    private static final int POPUP_WIDTH_DP = 200;
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
        root.addView(settingsCard());
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

    /** Called with the picked value once the popup closes. */
    private interface PickListener
    {
        void onPick(String value);
    }

    /**
     * The two settings this screen owns — operating mode and language — as
     * dropdown rows: label on the left, current value on the right, choices in a
     * popup anchored under it. They used to be a pair of always-visible buttons
     * each, which spent a whole row per setting to display one bit of state.
     */
    private View settingsCard()
    {
        LinearLayout card = cardLayout();
        boolean rootFree = isRootFree();

        card.addView(pickerRow(
                getString(R.string.about_mode_title),
                new String[]{getString(R.string.setup_root_title),
                        getString(R.string.setup_rootfree_title)},
                new String[]{RegexConfig.MODE_ROOT, RegexConfig.MODE_ROOTFREE},
                rootFree ? RegexConfig.MODE_ROOTFREE : RegexConfig.MODE_ROOT,
                this::onModeSelected));

        // Only warn about the trade-offs of the mode actually in use.
        TextView notice = new TextView(this);
        notice.setText(getString(R.string.rootfree_limitation_notice));
        notice.setTextSize(11);
        notice.setTextColor(COLOR_WARN_TEXT);
        notice.setBackground(roundBg(COLOR_WARN_BG, dp(10)));
        notice.setPadding(dp(10), dp(8), dp(10), dp(8));
        notice.setLineSpacing(dp(2), 1.0f);
        notice.setVisibility(rootFree ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams noticeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noticeLp.topMargin = dp(10);
        card.addView(notice, noticeLp);

        // Keep-alive only matters when interception rides on this app's process.
        // The root hook lives in system_server, so the whole page is noise there.
        if (rootFree)
        {
            card.addView(divider());
            card.addView(navRow(
                    getString(R.string.perm_entry_title),
                    getString(R.string.perm_entry_desc),
                    v -> startActivity(new Intent(this, PermissionsActivity.class))));
        }

        card.addView(divider());

        String lang = LocaleManager.getLanguage(this);
        card.addView(pickerRow(
                getString(R.string.about_language_title),
                new String[]{getString(R.string.lang_zh_label), getString(R.string.lang_en_label)},
                new String[]{LocaleManager.LANG_ZH, LocaleManager.LANG_EN},
                lang,
                this::onLanguageSelected));

        return card;
    }

    /** Title + description that opens another screen, with a chevron affordance. */
    private View navRow(String title, String desc, View.OnClickListener action)
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setPadding(0, dp(4), 0, dp(4));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(COLOR_SUB);
        d.setPadding(0, dp(4), 0, 0);
        d.setLineSpacing(dp(2), 1.0f);
        text.addView(d);

        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(22);
        chevron.setTextColor(COLOR_SUB);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chevLp.leftMargin = dp(10);
        row.addView(chevron, chevLp);

        row.setOnClickListener(action);
        return row;
    }

    /**
     * One dropdown row. {@code values} are the stored representations and
     * {@code labels} their display text, index-aligned; {@code current} is
     * matched against {@code values}. The picked value is shown on the right, so
     * the row carries no description — what it does is what it says.
     */
    private View pickerRow(String title, String[] labels, String[] values,
                           String current, PickListener listener)
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setText(labelFor(labels, values, current));
        value.setTextSize(14);
        value.setTextColor(COLOR_SUB);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.leftMargin = dp(12);
        row.addView(value, valueLp);

        // Stacked arrowheads, matching the "opens a list" affordance. Text rather
        // than a drawable, like the ‹ back arrows elsewhere in this app.
        TextView chevron = new TextView(this);
        chevron.setText("⌃\n⌄");
        chevron.setTextSize(9);
        chevron.setTextColor(COLOR_SUB);
        chevron.setGravity(Gravity.CENTER);
        chevron.setLineSpacing(0, 0.9f);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chevLp.leftMargin = dp(6);
        row.addView(chevron, chevLp);

        row.setOnClickListener(v -> showPicker(v, labels, values, current, listener));
        return row;
    }

    private String labelFor(String[] labels, String[] values, String current)
    {
        for (int i = 0; i < values.length; i++)
        {
            if (values[i].equals(current))
            {
                return labels[i];
            }
        }
        return labels.length == 0 ? "" : labels[0];
    }

    /** Option list anchored to the row; the current pick is accented and ticked. */
    private void showPicker(View anchor, String[] labels, String[] values,
                            String current, PickListener listener)
    {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), 0, dp(6));
        content.setBackground(roundBg(COLOR_CARD, dp(18)));

        final PopupWindow popup = new PopupWindow(content,
                dp(POPUP_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        // A non-null background is what makes outside taps dismiss the popup.
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(10));

        for (int i = 0; i < labels.length; i++)
        {
            final String value = values[i];
            boolean selected = value.equals(current);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setClickable(true);
            item.setPadding(dp(18), dp(14), dp(18), dp(14));

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextSize(16);
            label.setTextColor(selected ? COLOR_PRIMARY : COLOR_TEXT);
            item.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (selected)
            {
                TextView tick = new TextView(this);
                tick.setText("✓");
                tick.setTextSize(16);
                tick.setTextColor(COLOR_PRIMARY);
                item.addView(tick);
            }

            item.setOnClickListener(v ->
            {
                popup.dismiss();
                listener.onPick(value);
            });
            content.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Right-aligned with the row, so it drops out of the value it replaces.
        popup.showAsDropDown(anchor, anchor.getWidth() - dp(POPUP_WIDTH_DP), dp(2));
    }

    private View divider()
    {
        View line = new View(this);
        line.setBackgroundColor(COLOR_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(14);
        line.setLayoutParams(lp);
        return line;
    }

    private boolean isRootFree()
    {
        return RegexConfig.MODE_ROOTFREE.equals(
                getSharedPreferences(RegexConfig.PREFS_NAME, MODE_PRIVATE)
                        .getString(RegexConfig.KEY_OPERATING_MODE, RegexConfig.MODE_ROOT));
    }

    /**
     * Persist the mode and push one best-effort config sync so a rooted+LSPosed
     * device's hook learns to stand down (silently ignored on a device with no
     * hook to notify). Values come from the prefs rather than the main screen's
     * editors — this activity has no views to read, and the saved state is the
     * right thing to mirror anyway.
     *
     * Recreates the screen so the buttons restyle; MainActivity re-routes its own
     * data path when it next resumes.
     */
    private void onModeSelected(String mode)
    {
        if (mode.equals(getSharedPreferences(RegexConfig.PREFS_NAME, MODE_PRIVATE)
                .getString(RegexConfig.KEY_OPERATING_MODE, RegexConfig.MODE_ROOT)))
        {
            return;
        }

        SharedPreferences sp = getSharedPreferences(RegexConfig.PREFS_NAME, MODE_PRIVATE);
        sp.edit().putString(RegexConfig.KEY_OPERATING_MODE, mode).apply();

        final boolean master = sp.getBoolean(RegexConfig.KEY_MASTER_ENABLED, true);
        final boolean matchDesc = sp.getBoolean(RegexConfig.KEY_MATCH_DESC, true);
        final String rules = sp.getString(RegexConfig.KEY_RULES, "");
        final String allow = sp.getString(RegexConfig.KEY_ALLOW_RULES, "");
        final String ovr = sp.getString(RegexConfig.KEY_OVERRIDES, "");
        final boolean contentOn = sp.getBoolean(RegexConfig.KEY_CONTENT_ENABLED, false);
        final String contentRules = sp.getString(RegexConfig.KEY_CONTENT_RULES, "");
        final String apps = sp.getString(RegexConfig.KEY_APP_WHITELIST, "");
        Bg.run(() -> ConfigFileStore.writeFromApp(master, matchDesc, rules, allow, ovr,
                contentOn, contentRules, apps, mode));

        if (RegexConfig.MODE_ROOTFREE.equals(mode)
                && !NotificationAccessUtils.isListenerAccessGranted(this))
        {
            NotificationAccessUtils.openListenerSettings(this);
        }

        recreate();
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

    private int dp(int v)
    {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
