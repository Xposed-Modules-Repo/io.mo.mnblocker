package io.mo.mnblocker;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Root-free keep-alive setup, reached from AboutActivity.
 *
 * Root-free interception only runs while this app's process is alive and its
 * listener is bound, and aggressive ROMs (HyperOS especially) kill background
 * apps by default — so the module goes quiet with nothing to show for it. This
 * screen collects the settings that prevent that.
 *
 * Rows come in two kinds and are deliberately styled differently, because
 * pretending we know more than we do is what made the old status banner lie:
 *
 *  - Checkable rows have real, readable state (notification access, battery
 *    optimisation) and show 已授权 / 未授权.
 *  - Guidance rows are vendor settings with no readable state (MIUI power
 *    policy, autostart) or no API at all (lock in recents). They show no state
 *    claim — only a button that best-effort deep-links, or plain instructions.
 *
 * 100% programmatic views, self-contained like the other secondary screens.
 */
public final class PermissionsActivity extends Activity
{
    private static final int COLOR_BG = 0xFFF6F7FB;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_SUB = 0xFF6D7484;
    private static final int COLOR_LINE = 0xFFE7EAF0;
    private static final int COLOR_PRIMARY = 0xFF3F6DF6;
    private static final int COLOR_OK = 0xFF1E874B;
    private static final int COLOR_WARN_BG = 0xFFFFF5DD;
    private static final int COLOR_WARN_TEXT = 0xFF9A5B00;

    private LinearLayout container;

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
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header());

        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        root.addView(container);

        setContentView(scroll);
        SystemBars.edgeToEdge(this, scroll, root, root);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        // Every row here sends the user into system settings and back, so the
        // state is stale by definition on return — rebuild rather than refresh.
        render();
    }

    private void render()
    {
        container.removeAllViews();
        container.addView(sectionLabel(getString(R.string.perm_required_title)));
        container.addView(requiredCard());
        container.addView(sectionLabel(getString(R.string.perm_keepalive_title)));
        container.addView(keepAliveCard());
    }

    // ------------------------------------------------------------------
    // cards
    // ------------------------------------------------------------------

    private View requiredCard()
    {
        LinearLayout card = cardLayout();

        boolean granted = NotificationAccessUtils.isListenerAccessGranted(this);
        card.addView(stateRow(
                getString(R.string.perm_listener_title),
                getString(R.string.perm_listener_desc),
                granted,
                getString(R.string.perm_action_grant),
                v -> NotificationAccessUtils.openListenerSettings(this)));

        return card;
    }

    private View keepAliveCard()
    {
        LinearLayout card = cardLayout();

        // The one keep-alive setting whose state we can actually read.
        card.addView(stateRow(
                getString(R.string.perm_battery_title),
                getString(R.string.perm_battery_desc),
                KeepAliveUtils.isIgnoringBatteryOptimizations(this),
                getString(R.string.perm_action_grant),
                v -> KeepAliveUtils.requestIgnoreBatteryOptimizations(this)));

        card.addView(divider());

        card.addView(guidanceRow(
                getString(R.string.perm_power_policy_title),
                getString(R.string.perm_power_policy_desc),
                getString(R.string.perm_action_open),
                v -> KeepAliveUtils.openPowerPolicySettings(this)));

        card.addView(divider());

        card.addView(guidanceRow(
                getString(R.string.perm_autostart_title),
                getString(R.string.perm_autostart_desc),
                getString(R.string.perm_action_open),
                v -> KeepAliveUtils.openAutoStartSettings(this)));

        card.addView(divider());

        // No API exists to set or read this — instructions only, no button.
        card.addView(guidanceRow(
                getString(R.string.perm_lock_recents_title),
                getString(R.string.perm_lock_recents_desc),
                null, null));

        TextView notice = new TextView(this);
        notice.setText(getString(R.string.perm_keepalive_notice));
        notice.setTextSize(11);
        notice.setTextColor(COLOR_WARN_TEXT);
        notice.setBackground(roundBg(COLOR_WARN_BG, dp(10)));
        notice.setPadding(dp(10), dp(8), dp(10), dp(8));
        notice.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams noticeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noticeLp.topMargin = dp(14);
        card.addView(notice, noticeLp);

        return card;
    }

    // ------------------------------------------------------------------
    // rows
    // ------------------------------------------------------------------

    /** A row with real, readable state. */
    private View stateRow(String title, String desc, boolean granted,
                          String actionText, View.OnClickListener action)
    {
        LinearLayout row = rowShell(title, desc);

        TextView state = new TextView(this);
        state.setText(granted
                ? getString(R.string.perm_state_granted)
                : getString(R.string.perm_state_missing));
        state.setTextSize(12);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        state.setTextColor(granted ? COLOR_OK : COLOR_WARN_TEXT);
        state.setPadding(0, dp(6), 0, 0);
        ((LinearLayout) row.getChildAt(0)).addView(state);

        if (!granted)
        {
            row.addView(actionButton(actionText, action));
        }
        return row;
    }

    /**
     * A row for a setting we cannot read. Shows no state — only a way in, or
     * nothing but text when even that has no API.
     */
    private View guidanceRow(String title, String desc, String actionText,
                             View.OnClickListener action)
    {
        LinearLayout row = rowShell(title, desc);
        if (!TextUtils.isEmpty(actionText) && action != null)
        {
            row.addView(actionButton(actionText, action));
        }
        return row;
    }

    /** Title + description on the left, room for a button on the right. */
    private LinearLayout rowShell(String title, String desc)
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(COLOR_TEXT);
        text.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(COLOR_SUB);
        d.setPadding(0, dp(3), 0, 0);
        d.setLineSpacing(dp(2), 1.0f);
        text.addView(d);

        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button actionButton(String text, View.OnClickListener action)
    {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(COLOR_PRIMARY);
        b.setBackground(roundBg(0xFFEDF1FF, dp(12)));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setOnClickListener(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.leftMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    // ------------------------------------------------------------------
    // chrome
    // ------------------------------------------------------------------

    private View header()
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(16));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(30);
        back.setTextColor(COLOR_TEXT);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.nav_back));
        back.setClickable(true);
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText(getString(R.string.perm_page_title));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        row.addView(title, lp);

        return row;
    }

    /**
     * A section label, sitting outside and above its card. Kept deliberately
     * smaller and greyer than the permission names inside the card: a heading
     * that competes with its own contents for weight reads as just another row.
     */
    private TextView sectionLabel(String text)
    {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(COLOR_SUB);
        t.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private View divider()
    {
        View line = new View(this);
        line.setBackgroundColor(COLOR_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        line.setLayoutParams(lp);
        return line;
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
