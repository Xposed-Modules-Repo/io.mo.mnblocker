package io.mo.mnblocker;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.Toast;

/**
 * Hidden debug screen, entered by tapping the app icon / name 5 times on the
 * About page.
 *
 * Controls:
 *  1. "输出日志" switch — creates / deletes a flag file that HookLogger checks.
 *     Default OFF: hook.log is not written unless explicitly enabled.
 *  2. "导出 Hook 日志" button — copies hook.log to /sdcard/ via su.
 *     If hook.log does not exist, a snackbar says so.
 *  3. "禁用安全模式" switch — bypasses automatic crash-loop protection.
 */
public final class DebugActivity extends Activity
{
    private static final String[] LEVEL_LABELS = {"ALL", "DEBUG", "INFO", "WARN", "ERROR"};
    private static final int[] LEVELS = {
            HookLogger.LEVEL_ALL,
            HookLogger.LEVEL_DEBUG,
            HookLogger.LEVEL_INFO,
            HookLogger.LEVEL_WARN,
            HookLogger.LEVEL_ERROR
    };

    private Ui ui;
    private Switch logSwitch;
    private Switch disableXposedLogSwitch;
    private Switch disableSafeModeSwitch;
    private boolean updatingSafeModeSwitch;
    private Ui.Segmented levelSelector;
    private int currentLevel = HookLogger.LEVEL_ERROR;
    private FrameLayout rootFrame;

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

        rootFrame = new FrameLayout(this);

        Ui.Page page = ui.page();
        LinearLayout root = page.content;

        root.addView(ui.topBar(getString(R.string.debug_title),
                getString(R.string.debug_subtitle), null));
        root.addView(ui.notice(getString(R.string.debug_warning)),
                ui.margins(Ui.matchWrap(), 0, 0, 0, Ui.S3));
        root.addView(safetyCard());
        root.addView(loggingCard());
        root.addView(exportCard());

        rootFrame.addView(page.scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
        SystemBars.edgeToEdge(this, rootFrame, root, root);

        // Read current flag states in background.
        new Thread(() -> {
            boolean logEnabled = ShellUtils.isDebugLogging();
            boolean xposedDisabled = ShellUtils.isDisableXposedLog();
            boolean safeModeDisabled = ShellUtils.isDisableSafeMode();
            int level = ShellUtils.getXposedLogLevel();
            runOnUiThread(() -> {
                setCheckedSilently(logEnabled);
                setXposedDisabledSilently(xposedDisabled);
                setSafeModeDisabledSilently(safeModeDisabled);
                Ui.setEnabledLook(disableSafeModeSwitch, true);
                currentLevel = level;
                levelSelector.select(indexOfLevel(level), false);
                levelSelector.setEnabled(!xposedDisabled);
            });
        }).start();
    }

    // ---- UI sections -------------------------------------------------------

    private View safetyCard()
    {
        LinearLayout card = ui.card();
        card.setPadding(card.getPaddingLeft(), ui.dp(Ui.S2),
                card.getPaddingRight(), ui.dp(Ui.S2));

        Ui.SwitchRow row = ui.switchRow(getString(R.string.debug_disable_safe_mode_title),
                getString(R.string.debug_disable_safe_mode_desc));
        disableSafeModeSwitch = row.sw;
        // Avoid overwriting a persisted setting before the background read finishes.
        Ui.setEnabledLook(disableSafeModeSwitch, false);
        disableSafeModeSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingSafeModeSwitch) {
                return;
            }
            Ui.setEnabledLook(disableSafeModeSwitch, false);
            new Thread(() -> {
                boolean ok = ShellUtils.setDisableSafeMode(checked);
                boolean actual = ok ? checked : ShellUtils.isDisableSafeMode();
                runOnUiThread(() -> {
                    setSafeModeDisabledSilently(actual);
                    Ui.setEnabledLook(disableSafeModeSwitch, true);
                    if (!ok) {
                        ui.snackbar(rootFrame, getString(R.string.toast_op_failed_root), true);
                    }
                });
            }).start();
        });
        card.addView(row.view);
        return card;
    }

    private View loggingCard()
    {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(getString(R.string.logging_card_title), null));

        Ui.SwitchRow logRow = ui.switchRow(getString(R.string.debug_output_log_title),
                getString(R.string.debug_output_log_desc));
        logSwitch = logRow.sw;
        setCheckedSilently(false);
        card.addView(logRow.view);

        card.addView(ui.divider());

        Ui.SwitchRow xpRow = ui.switchRow(getString(R.string.debug_disable_xposed_log_title),
                getString(R.string.debug_disable_xposed_log_desc));
        disableXposedLogSwitch = xpRow.sw;
        setXposedDisabledSilently(false);
        card.addView(xpRow.view);

        card.addView(ui.divider());

        // ---- log level selector ----
        LinearLayout lvlSection = ui.column();
        lvlSection.setPadding(0, ui.dp(Ui.S2), 0, ui.dp(Ui.S1));
        lvlSection.addView(ui.text(getString(R.string.debug_log_level_title),
                Ui.Type.TITLE_SMALL, ui.p.onSurface));
        lvlSection.addView(ui.text(getString(R.string.debug_log_level_desc),
                Ui.Type.BODY_SMALL, ui.p.onSurfaceVariant),
                ui.margins(Ui.matchWrap(), 0, Ui.S_HALF, 0, Ui.S3));

        levelSelector = ui.segmented(LEVEL_LABELS, indexOfLevel(HookLogger.LEVEL_ERROR), i -> {
            final int lvl = LEVELS[i];
            currentLevel = lvl;
            new Thread(() -> {
                boolean ok = ShellUtils.setXposedLogLevel(lvl);
                if (!ok) {
                    runOnUiThread(() -> ui.snackbar(rootFrame,
                            getString(R.string.toast_op_failed_root), true));
                }
            }).start();
        });
        lvlSection.addView(levelSelector.view, Ui.matchWrap());
        card.addView(lvlSection);

        return card;
    }

    private View exportCard()
    {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(getString(R.string.export_card_title),
                getString(R.string.export_card_desc)));

        Button exportBtn = ui.button(Ui.BTN_PRIMARY, getString(R.string.action_export_log));
        exportBtn.setOnClickListener(v -> onExport());
        card.addView(exportBtn, Ui.matchWrap());

        return card;
    }

    // ---- actions -----------------------------------------------------------

    private void onExport()
    {
        new Thread(() -> {
            boolean exists = ShellUtils.hookLogExists();
            if (!exists) {
                runOnUiThread(() -> ui.snackbar(rootFrame, getString(R.string.hint_no_log), true));
                return;
            }
            boolean ok = ShellUtils.exportHookLog();
            runOnUiThread(() -> {
                if (ok) {
                    Haptics.confirm(rootFrame);
                    Toast.makeText(this,
                            getString(R.string.toast_exported),
                            Toast.LENGTH_SHORT).show();
                } else {
                    ui.snackbar(rootFrame, getString(R.string.hint_export_failed), true);
                }
            });
        }).start();
    }

    // ---- helpers (suppress listener during programmatic change) -------------

    private static int indexOfLevel(int level)
    {
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i] == level) {
                return i;
            }
        }
        return LEVELS.length - 1;
    }

    private void setSafeModeDisabledSilently(boolean checked)
    {
        updatingSafeModeSwitch = true;
        disableSafeModeSwitch.setChecked(checked);
        updatingSafeModeSwitch = false;
    }

    private void setCheckedSilently(boolean checked)
    {
        logSwitch.setOnCheckedChangeListener(null);
        logSwitch.setChecked(checked);
        logSwitch.setOnCheckedChangeListener((btn, c) -> {
            new Thread(() -> {
                boolean ok = ShellUtils.setDebugLogging(c);
                if (!ok) {
                    runOnUiThread(() -> {
                        setCheckedSilently(!c);
                        ui.snackbar(rootFrame, getString(R.string.toast_op_failed_root), true);
                    });
                }
            }).start();
        });
    }

    private void setXposedDisabledSilently(boolean checked)
    {
        disableXposedLogSwitch.setOnCheckedChangeListener(null);
        disableXposedLogSwitch.setChecked(checked);
        disableXposedLogSwitch.setOnCheckedChangeListener((btn, c) -> {
            levelSelector.setEnabled(!c);
            new Thread(() -> {
                boolean ok = ShellUtils.setDisableXposedLog(c);
                if (!ok) {
                    runOnUiThread(() -> {
                        setXposedDisabledSilently(!c);
                        levelSelector.setEnabled(c);
                        ui.snackbar(rootFrame, getString(R.string.toast_op_failed_root), true);
                    });
                }
            }).start();
        });
    }
}
