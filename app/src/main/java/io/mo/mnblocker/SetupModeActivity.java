package io.mo.mnblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First-launch mode picker: root (Xposed hook, silent block) vs. root-free
 * (NotificationListenerService, notification briefly flashes then is
 * cancelled). See docs/rootfree-mode-plan.md §5.
 *
 * 100% programmatic views (no XML / AndroidX / Material / RecyclerView),
 * matching the other screens' style ({@code cardLayout()}-shaped cards,
 * {@code baseButton()}-shaped buttons) but self-contained like
 * BlockedNotificationsActivity — this activity has no access to
 * MainActivity's private helpers.
 */
public final class SetupModeActivity extends Activity
{
    private static final int COLOR_BG = 0xFFF6F7FB;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_SUB = 0xFF6D7484;
    private static final int COLOR_LINE = 0xFFE7EAF0;
    private static final int COLOR_PRIMARY = 0xFF3F6DF6;

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
        root.setPadding(dp(16), dp(24), dp(16), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(getString(R.string.setup_title));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(getString(R.string.setup_subtitle));
        subtitle.setTextSize(13);
        subtitle.setTextColor(COLOR_SUB);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle);

        root.addView(modeCard(
                getString(R.string.setup_root_title),
                getString(R.string.setup_root_desc),
                getString(R.string.setup_choose_root),
                RegexConfig.MODE_ROOT));

        root.addView(modeCard(
                getString(R.string.setup_rootfree_title),
                getString(R.string.setup_rootfree_desc),
                getString(R.string.setup_choose_rootfree),
                RegexConfig.MODE_ROOTFREE));

        setContentView(scroll);
        SystemBars.edgeToEdge(this, scroll, root, root);
    }

    private LinearLayout modeCard(String title, String desc, String buttonText, String mode)
    {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundBg(COLOR_CARD, dp(20)));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(14);
        card.setLayoutParams(cardLp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(COLOR_TEXT);
        card.addView(t);

        View divider = new View(this);
        divider.setBackgroundColor(COLOR_LINE);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dividerLp.topMargin = dp(10);
        dividerLp.bottomMargin = dp(10);
        card.addView(divider, dividerLp);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(13);
        d.setTextColor(COLOR_SUB);
        d.setLineSpacing(dp(2), 1.0f);
        card.addView(d);

        Button button = new Button(this);
        button.setText(buttonText);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(0xFFFFFFFF);
        button.setBackground(roundBg(COLOR_PRIMARY, dp(14)));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setOnClickListener(v -> selectMode(mode));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        btnLp.topMargin = dp(14);
        card.addView(button, btnLp);

        return card;
    }

    private void selectMode(String mode)
    {
        final SharedPreferences sp = getSharedPreferences(RegexConfig.PREFS_NAME, MODE_PRIVATE);
        sp.edit().putString(RegexConfig.KEY_OPERATING_MODE, mode).apply();

        // Best-effort background push so a hook, if one is installed, picks up
        // the mode without needing to know whether this device even has root
        // (plan §9.3: rootfree-only devices have no hook to notify, so a su
        // failure here must stay silent — no toast, nothing shown to the user).
        Bg.run(() -> ConfigFileStore.writeFromApp(
                sp.getBoolean(RegexConfig.KEY_MASTER_ENABLED, true),
                sp.getBoolean(RegexConfig.KEY_MATCH_DESC, true),
                sp.getString(RegexConfig.KEY_RULES, ""),
                sp.getString(RegexConfig.KEY_ALLOW_RULES, ""),
                sp.getString(RegexConfig.KEY_OVERRIDES, ""),
                sp.getBoolean(RegexConfig.KEY_CONTENT_ENABLED, false),
                sp.getString(RegexConfig.KEY_CONTENT_RULES, ""),
                sp.getString(RegexConfig.KEY_APP_WHITELIST, ""),
                mode));

        if (RegexConfig.MODE_ROOTFREE.equals(mode))
        {
            promptKeepAlive();
            return;
        }
        enterApp();
    }

    /**
     * Root-free interception dies with this process, and an aggressive ROM will
     * kill it by default — so the survival settings get raised once, here, at the
     * moment the user opts into that trade-off.
     *
     * Advisory only: both buttons end up in the app, and the cancel path (back /
     * outside tap) does too. Nothing here gates entry.
     */
    private void promptKeepAlive()
    {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_keepalive_prompt_title)
                .setMessage(R.string.setup_keepalive_prompt_msg)
                .setPositiveButton(R.string.setup_keepalive_prompt_go, (d, w) ->
                {
                    // MainActivity first, so backing out of the permissions screen
                    // lands in the app rather than back on this picker.
                    startActivity(new Intent(this, MainActivity.class));
                    startActivity(new Intent(this, PermissionsActivity.class));
                    finish();
                })
                .setNegativeButton(R.string.setup_keepalive_prompt_skip, (d, w) -> enterApp())
                .setOnCancelListener(d -> enterApp())
                .show();
    }

    private void enterApp()
    {
        startActivity(new Intent(this, MainActivity.class));
        finish();
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
