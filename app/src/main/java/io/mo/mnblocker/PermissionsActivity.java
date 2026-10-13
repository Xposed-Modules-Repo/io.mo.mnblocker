package io.mo.mnblocker;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
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
 * Built from {@link Ui} like every other screen.
 */
public final class PermissionsActivity extends Activity
{
    private Ui ui;
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
        ui = new Ui(this);

        Ui.Page page = ui.page();
        LinearLayout root = page.content;
        root.addView(ui.topBar(getString(R.string.perm_page_title), null, null));

        container = ui.column();
        root.addView(container);

        setContentView(page.scroll);
        SystemBars.edgeToEdge(this, page.scroll, root, root);
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
        container.addView(ui.sectionLabel(getString(R.string.perm_required_title)));
        container.addView(requiredCard());
        container.addView(ui.sectionLabel(getString(R.string.perm_keepalive_title)));
        container.addView(keepAliveCard());
    }

    // ------------------------------------------------------------------
    // cards
    // ------------------------------------------------------------------

    private View requiredCard()
    {
        LinearLayout card = ui.card();

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
        LinearLayout card = ui.card();

        // The one keep-alive setting whose state we can actually read.
        card.addView(stateRow(
                getString(R.string.perm_battery_title),
                getString(R.string.perm_battery_desc),
                KeepAliveUtils.isIgnoringBatteryOptimizations(this),
                getString(R.string.perm_action_grant),
                v -> KeepAliveUtils.requestIgnoreBatteryOptimizations(this)));

        card.addView(ui.divider());

        card.addView(guidanceRow(
                getString(R.string.perm_power_policy_title),
                getString(R.string.perm_power_policy_desc),
                getString(R.string.perm_action_open),
                v -> KeepAliveUtils.openPowerPolicySettings(this)));

        card.addView(ui.divider());

        card.addView(guidanceRow(
                getString(R.string.perm_autostart_title),
                getString(R.string.perm_autostart_desc),
                getString(R.string.perm_action_open),
                v -> KeepAliveUtils.openAutoStartSettings(this)));

        card.addView(ui.divider());

        // No API exists to set or read this — instructions only, no button.
        card.addView(guidanceRow(
                getString(R.string.perm_lock_recents_title),
                getString(R.string.perm_lock_recents_desc),
                null, null));

        card.addView(ui.notice(getString(R.string.perm_keepalive_notice)),
                ui.margins(Ui.matchWrap(), 0, Ui.S3, 0, 0));

        return card;
    }

    // ------------------------------------------------------------------
    // rows
    // ------------------------------------------------------------------

    /** A row with real, readable state, shown as a status chip under the title. */
    private View stateRow(String title, String desc, boolean granted,
                          String actionText, View.OnClickListener action)
    {
        LinearLayout row = rowShell(title, desc);

        TextView state = granted
                ? ui.chip(getString(R.string.perm_state_granted),
                        ui.p.onSuccessContainer, ui.p.successContainer)
                : ui.chip(getString(R.string.perm_state_missing),
                        ui.p.onWarnContainer, ui.p.warnContainer);
        ((LinearLayout) row.getChildAt(0)).addView(state,
                ui.margins(Ui.wrapWrap(), 0, Ui.S2, 0, 0));

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
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(Ui.ROW_MIN));
        row.setPadding(0, ui.dp(Ui.S2), 0, ui.dp(Ui.S2));

        LinearLayout text = ui.column();
        text.addView(ui.text(title, Ui.Type.TITLE_SMALL, ui.p.onSurface));
        text.addView(ui.text(desc, Ui.Type.BODY_SMALL, ui.p.onSurfaceVariant),
                ui.margins(Ui.matchWrap(), 0, Ui.S_HALF, 0, 0));
        row.addView(text, Ui.fillRow());
        return row;
    }

    private Button actionButton(String text, View.OnClickListener action)
    {
        Button b = ui.button(Ui.BTN_TONAL, text);
        b.setOnClickListener(action);
        b.setLayoutParams(ui.margins(Ui.wrapWrap(), Ui.S3, 0, 0, 0));
        return b;
    }
}
