package io.mo.mnblocker;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Hidden debug screen, entered by tapping the app icon / name 5 times on the
 * About page.
 *
 * Two controls:
 *  1. "输出日志" switch — creates / deletes a flag file that HookLogger checks.
 *     Default OFF: hook.log is not written unless explicitly enabled.
 *  2. "导出 Hook 日志" button — copies hook.log to /sdcard/ via su.
 *     If hook.log does not exist, a bottom-anchored hint fades in and out.
 */
public final class DebugActivity extends Activity
{
    private static final int COLOR_BG      = 0xFFF6F7FB;
    private static final int COLOR_CARD    = 0xFFFFFFFF;
    private static final int COLOR_TEXT    = 0xFF172033;
    private static final int COLOR_SUB     = 0xFF6D7484;
    private static final int COLOR_PRIMARY = 0xFF3F6DF6;
    private static final int COLOR_LINE    = 0xFFE7EAF0;
    private static final int COLOR_WARN_BG   = 0xFFFFF5DD;
    private static final int COLOR_WARN_TEXT = 0xFF9A5B00;

    private Switch logSwitch;
    private FrameLayout rootFrame;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        rootFrame = new FrameLayout(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(26), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(titleSection());
        root.addView(warningCard());
        root.addView(loggingCard());
        root.addView(exportCard());

        rootFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);

        // Read current flag state in background.
        new Thread(() -> {
            boolean enabled = ShellUtils.isDebugLogging();
            runOnUiThread(() -> setCheckedSilently(enabled));
        }).start();
    }

    // ---- UI sections -------------------------------------------------------

    private View titleSection()
    {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(16));

        TextView title = new TextView(this);
        title.setText("\uD83D\uDEE0 调试模式");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("以下选项仅用于排查问题，日常使用无需开启。");
        sub.setTextColor(COLOR_SUB);
        sub.setTextSize(13);
        sub.setPadding(0, dp(6), 0, 0);
        box.addView(sub);

        return box;
    }

    private View warningCard()
    {
        LinearLayout card = cardLayout();
        card.setBackground(roundBg(COLOR_WARN_BG, dp(22)));

        TextView warn = new TextView(this);
        warn.setText("\u26A0 开启日志输出会在每次通知类别事件时写入文件，"
                + "可能对性能有轻微影响。排查完毕后请关闭。");
        warn.setTextColor(COLOR_WARN_TEXT);
        warn.setTextSize(12);
        warn.setLineSpacing(dp(2), 1.0f);
        card.addView(warn);

        return card;
    }

    private View loggingCard()
    {
        LinearLayout card = cardLayout();

        TextView title = new TextView(this);
        title.setText("日志控制");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        // ---- switch row ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(4));

        LinearLayout labelCol = new LinearLayout(this);
        labelCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelCol.setLayoutParams(labelLp);

        TextView label = new TextView(this);
        label.setText("输出日志");
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(15);
        labelCol.addView(label);

        TextView desc = new TextView(this);
        desc.setText("开启后 Hook 事件会写入 hook.log，默认关闭。");
        desc.setTextColor(COLOR_SUB);
        desc.setTextSize(11);
        desc.setPadding(0, dp(2), 0, 0);
        labelCol.addView(desc);

        row.addView(labelCol);

        logSwitch = new Switch(this);
        logSwitch.setChecked(false);
        logSwitch.setOnCheckedChangeListener((btn, checked) -> {
            new Thread(() -> {
                boolean ok = ShellUtils.setDebugLogging(checked);
                if (!ok) {
                    runOnUiThread(() -> {
                        setCheckedSilently(!checked);
                        Toast.makeText(this,
                                "操作失败，请检查 root 权限",
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });
        row.addView(logSwitch);

        card.addView(row);
        return card;
    }

    private View exportCard()
    {
        LinearLayout card = cardLayout();

        TextView title = new TextView(this);
        title.setText("日志导出");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText("将 hook.log 复制到 /sdcard/ 以便分享。");
        desc.setTextColor(COLOR_SUB);
        desc.setTextSize(12);
        desc.setPadding(0, dp(6), 0, dp(14));
        card.addView(desc);

        Button exportBtn = new Button(this);
        exportBtn.setText("导出 Hook 日志");
        exportBtn.setTextColor(Color.WHITE);
        exportBtn.setTextSize(14);
        exportBtn.setTypeface(Typeface.DEFAULT_BOLD);
        exportBtn.setBackground(roundBg(COLOR_PRIMARY, dp(14)));
        exportBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        exportBtn.setAllCaps(false);
        exportBtn.setOnClickListener(v -> onExport());

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        card.addView(exportBtn, btnLp);

        return card;
    }

    // ---- actions -----------------------------------------------------------

    private void onExport()
    {
        new Thread(() -> {
            boolean exists = ShellUtils.hookLogExists();
            if (!exists) {
                runOnUiThread(() -> showFadeHint("无日志"));
                return;
            }
            boolean ok = ShellUtils.exportHookLog();
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this,
                            "已导出到 /sdcard/hook.log",
                            Toast.LENGTH_SHORT).show();
                } else {
                    showFadeHint("导出失败，请检查 root 权限");
                }
            });
        }).start();
    }

    /**
     * Show a centered hint near the bottom of the screen that fades out
     * after 2 seconds.
     */
    private void showFadeHint(String text)
    {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(24), dp(12), dp(24), dp(12));
        hint.setBackground(roundBg(0xCC333333, dp(24)));
        hint.setAlpha(1f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(80);

        rootFrame.addView(hint, lp);

        new Handler(Looper.getMainLooper()).postDelayed(() ->
                hint.animate()
                        .alpha(0f)
                        .setDuration(800)
                        .withEndAction(() -> rootFrame.removeView(hint))
                        .start(),
                2000);
    }

    // ---- helpers (suppress listener during programmatic change) -------------

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
                        Toast.makeText(this,
                                "操作失败，请检查 root 权限",
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });
    }

    // ---- card / drawing helpers (same style as AboutActivity) ---------------

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
