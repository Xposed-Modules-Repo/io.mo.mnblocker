package io.mo.mnblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * First-launch mode picker: root (Xposed hook, silent block) vs. root-free
 * (NotificationListenerService, notification briefly flashes then is
 * cancelled). See docs/rootfree-mode-plan.md §5.
 *
 * Built from {@link Ui} like every other screen.
 */
public final class SetupModeActivity extends Activity
{
    private static final int APP_ICON_DP = 64;

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
        root.setPadding(root.getPaddingLeft(), ui.dp(Ui.S6),
                root.getPaddingRight(), root.getPaddingBottom());

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.app_icon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(icon, ui.margins(ui.size(APP_ICON_DP, APP_ICON_DP), Ui.S1, Ui.S4, 0, 0));

        root.addView(ui.largeTitle(getString(R.string.setup_title),
                getString(R.string.setup_subtitle), null));

        // Root is the full-strength path (silent, complete), so it carries the
        // filled button; root-free is the fallback and gets the tonal one.
        root.addView(modeCard(
                getString(R.string.setup_root_title),
                getString(R.string.setup_root_desc),
                getString(R.string.setup_choose_root),
                RegexConfig.MODE_ROOT, Ui.BTN_PRIMARY));

        root.addView(modeCard(
                getString(R.string.setup_rootfree_title),
                getString(R.string.setup_rootfree_desc),
                getString(R.string.setup_choose_rootfree),
                RegexConfig.MODE_ROOTFREE, Ui.BTN_TONAL));

        setContentView(page.scroll);
        SystemBars.edgeToEdge(this, page.scroll, root, root);
    }

    private LinearLayout modeCard(String title, String desc, String buttonText,
                                  String mode, int buttonKind)
    {
        LinearLayout card = ui.card();
        card.setPadding(ui.dp(Ui.S5), ui.dp(Ui.S5), ui.dp(Ui.S5), ui.dp(Ui.S5));

        card.addView(ui.text(title, Ui.Type.TITLE, ui.p.onSurface));
        card.addView(ui.text(desc, Ui.Type.BODY_SMALL, ui.p.onSurfaceVariant),
                ui.margins(Ui.matchWrap(), 0, Ui.S2, 0, 0));

        Button button = ui.button(buttonKind, buttonText);
        button.setOnClickListener(v ->
        {
            Haptics.confirm(v);
            selectMode(mode);
        });
        card.addView(button, ui.margins(Ui.matchWrap(), 0, Ui.S4, 0, 0));
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
}
