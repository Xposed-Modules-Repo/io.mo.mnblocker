package io.mo.mnblocker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

public final class AboutActivity extends Activity
{
    /** Popup width; also the offset that right-aligns it with its row. */
    private static final int POPUP_WIDTH_DP = 200;
    private static final int POPUP_ELEVATION_DP = 8;
    private static final int APP_ICON_DP = 88;
    private static final String SOURCE_URL = "https://github.com/lm060719/io.mo.mnblocker";

    /** Hidden debug entrance: tap the icon or name 5 times. */
    private int tapCount;
    private long lastTapTime;
    private Ui ui;

    @Override
    protected void attachBaseContext(Context newBase)
    {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);

        Ui.Page page = ui.page();
        LinearLayout root = page.content;

        root.addView(ui.topBar("", null, null));
        root.addView(appInfoBlock());
        root.addView(ui.sectionLabel(getString(R.string.about_settings_label)));
        root.addView(settingsCard());
        root.addView(sourceCard());

        setContentView(page.scroll);
        SystemBars.edgeToEdge(this, page.scroll, root, root);
    }

    private View appInfoBlock()
    {
        LinearLayout box = ui.column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, 0, 0, ui.dp(Ui.S6));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.app_icon);
        icon.setContentDescription(getString(R.string.app_name));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setOnClickListener(v -> onSecretTap());
        Motion.pressable(icon, Motion.PRESS_SCALE);
        box.addView(icon, ui.size(APP_ICON_DP, APP_ICON_DP));

        TextView name = ui.text(getString(R.string.app_name), Ui.Type.HEADLINE, ui.p.onSurface);
        name.setGravity(Gravity.CENTER);
        name.setOnClickListener(v -> onSecretTap());
        box.addView(name, ui.margins(Ui.matchWrap(), 0, Ui.S4, 0, 0));

        TextView version = ui.chip(getString(R.string.about_version_fmt, versionName()),
                ui.p.onSurfaceVariant, ui.p.surfaceVariant);
        box.addView(version, ui.margins(Ui.wrapWrap(), 0, Ui.S2, 0, 0));

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
            Haptics.confirm(getWindow().getDecorView());
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
        LinearLayout card = ui.card();
        card.setPadding(card.getPaddingLeft(), ui.dp(Ui.S2),
                card.getPaddingRight(), ui.dp(Ui.S2));
        boolean rootFree = isRootFree();

        card.addView(pickerRow(
                getString(R.string.about_mode_title),
                new String[]{getString(R.string.setup_root_title),
                        getString(R.string.setup_rootfree_title)},
                new String[]{RegexConfig.MODE_ROOT, RegexConfig.MODE_ROOTFREE},
                rootFree ? RegexConfig.MODE_ROOTFREE : RegexConfig.MODE_ROOT,
                this::onModeSelected));

        // Only warn about the trade-offs of the mode actually in use.
        if (rootFree)
        {
            card.addView(ui.notice(getString(R.string.rootfree_limitation_notice)),
                    ui.margins(Ui.matchWrap(), 0, Ui.S1, 0, Ui.S2));
        }

        // Keep-alive only matters when interception rides on this app's process.
        // The root hook lives in system_server, so the whole page is noise there.
        if (rootFree)
        {
            card.addView(ui.divider());
            card.addView(ui.navRow(
                    getString(R.string.perm_entry_title),
                    getString(R.string.perm_entry_desc),
                    v -> startActivity(new Intent(this, PermissionsActivity.class))));
        }

        card.addView(ui.divider());

        String lang = LocaleManager.getLanguage(this);
        card.addView(pickerRow(
                getString(R.string.about_language_title),
                new String[]{getString(R.string.lang_zh_label), getString(R.string.lang_en_label)},
                new String[]{LocaleManager.LANG_ZH, LocaleManager.LANG_EN},
                lang,
                this::onLanguageSelected));

        return card;
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
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(Ui.ROW_MIN));
        row.setPadding(0, ui.dp(Ui.S2), 0, ui.dp(Ui.S2));

        row.addView(ui.text(title, Ui.Type.TITLE_SMALL, ui.p.onSurface), Ui.fillRow());

        TextView value = ui.text(labelFor(labels, values, current),
                Ui.Type.BODY, ui.p.onSurfaceVariant);
        row.addView(value, ui.margins(Ui.wrapWrap(), Ui.S3, 0, 0, 0));

        // "Opens a list" affordance.
        row.addView(ui.icon(R.drawable.ic_unfold, ui.p.onSurfaceVariant),
                ui.margins(ui.size(Ui.ICON_SMALL, Ui.ICON_SMALL), Ui.S1, 0, 0, 0));

        row.setBackground(ui.ripple(ui.shape(Color.TRANSPARENT, Ui.R_SMALL), Ui.R_SMALL));
        row.setClickable(true);
        row.setContentDescription(title + ", " + value.getText());
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
        LinearLayout content = ui.column();
        content.setPadding(0, ui.dp(Ui.S2), 0, ui.dp(Ui.S2));
        content.setBackground(ui.shape(ui.p.surfaceHigh, Ui.R_MEDIUM));

        final PopupWindow popup = new PopupWindow(content,
                ui.dp(POPUP_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        // A non-null background is what makes outside taps dismiss the popup.
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(ui.dp(POPUP_ELEVATION_DP));

        for (int i = 0; i < labels.length; i++)
        {
            final String value = values[i];
            boolean selected = value.equals(current);

            LinearLayout item = ui.row();
            item.setMinimumHeight(ui.dp(Ui.TOUCH));
            item.setPadding(ui.dp(Ui.S4), ui.dp(Ui.S3), ui.dp(Ui.S4), ui.dp(Ui.S3));
            item.setBackground(ui.ripple(ui.shape(Color.TRANSPARENT, Ui.R_SMALL), Ui.R_SMALL));
            item.setClickable(true);

            TextView label = ui.text(labels[i], Ui.Type.BODY,
                    selected ? ui.p.primary : ui.p.onSurface);
            if (selected)
            {
                label.setTypeface(Ui.typeface(Ui.Weight.MEDIUM));
            }
            item.addView(label, Ui.fillRow());

            if (selected)
            {
                item.addView(ui.icon(R.drawable.ic_check, ui.p.primary),
                        ui.size(Ui.ICON_SMALL, Ui.ICON_SMALL));
            }

            item.setOnClickListener(v ->
            {
                Haptics.tick(v);
                popup.dismiss();
                listener.onPick(value);
            });
            content.addView(item, Ui.matchWrap());
        }

        // Right-aligned with the row, so it drops out of the value it replaces.
        popup.showAsDropDown(anchor, anchor.getWidth() - ui.dp(POPUP_WIDTH_DP),
                ui.dp(Ui.S_HALF));
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
     * Recreates the screen so the rows restyle; MainActivity re-routes its own
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
        LinearLayout card = ui.card();
        card.setPadding(card.getPaddingLeft(), ui.dp(Ui.S2),
                card.getPaddingRight(), ui.dp(Ui.S2));
        card.addView(ui.navRow(getString(R.string.about_source_title), SOURCE_URL,
                v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))));
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
}
