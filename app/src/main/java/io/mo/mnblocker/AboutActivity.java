package io.mo.mnblocker;

import android.app.Activity;
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
    private static final int COLOR_LINK = 0xFF254FD8;
    private static final String SOURCE_URL = "https://github.com/lm060719/io.mo.mnblocker";

    /** Hidden debug entrance: tap the icon or name 5 times. */
    private int tapCount;
    private long lastTapTime;

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
        root.addView(sourceCard());

        setContentView(scroll);
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
        version.setText("版本号 " + versionName());
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
        description.setText("说明：\n"
                + "• 开关 ON = 拦截该类别通知，OFF = 允许。\n"
                + "• 手动开关属于单独覆盖，优先级高于正则。\n"
                + "• Hook 日志：" + HookLogger.DIR + "/hook.log");
        description.setTextSize(12);
        description.setTextColor(COLOR_SUB);
        description.setLineSpacing(dp(3), 1.0f);
        card.addView(description);

        return card;
    }

    private View sourceCard()
    {
        LinearLayout card = cardLayout();

        TextView title = new TextView(this);
        title.setText("源码");
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
