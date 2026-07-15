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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Detail screen reached by tapping an app in the stats page's block ranking:
 * shows the individual notifications that content-level interception dropped for
 * that one app (title / text / time / matched rule).
 *
 * The data comes from {@link ContentBlockLogStore}, whose backing file is
 * PRIVATE (0600, system-owned) because it holds notification text — so the read
 * goes through {@code su} and MUST run off the main thread via {@link Bg}.
 *
 * Only content-level blocks appear here; channel-level blocking never
 * intercepts an individual post, so an app that was only ever blocked at the
 * channel level shows the empty state.
 *
 * 100% programmatic views (no XML / AndroidX), matching the other screens.
 */
public final class BlockedNotificationsActivity extends Activity
{
    /** Intent extra: the package name whose blocked notifications to show. */
    static final String EXTRA_PKG = "io.mo.mnblocker.extra.PKG";

    private static final int COLOR_BG = 0xFFF6F7FB;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_SUB = 0xFF6D7484;
    private static final int COLOR_LINE = 0xFFE7EAF0;
    private static final int COLOR_CHIP_BG = 0xFFF0F3FF;
    private static final int COLOR_CHIP_TEXT = 0xFF254FD8;

    private String pkg;
    private LinearLayout listContainer;

    @Override
    protected void attachBaseContext(Context newBase)
    {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        pkg = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PKG);
        if (TextUtils.isEmpty(pkg))
        {
            finish();
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header());

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        // Placeholder while the (su-backed) read lands.
        listContainer.addView(hintCard(getString(R.string.blocked_loading)));

        setContentView(scroll);
        SystemBars.edgeToEdge(this, scroll, root, root);

        load();
    }

    private void load()
    {
        final String p = pkg;
        Bg.load(this, () -> ContentBlockLogStore.readForApp(p), this::render);
    }

    private void render(List<ContentBlockLogStore.Entry> entries)
    {
        if (listContainer == null)
        {
            return;
        }
        listContainer.removeAllViews();

        if (entries == null || entries.isEmpty())
        {
            listContainer.addView(hintCard(getString(R.string.blocked_empty)));
            return;
        }

        TextView count = new TextView(this);
        count.setText(getString(R.string.blocked_count_fmt, entries.size()));
        count.setTextSize(12);
        count.setTextColor(COLOR_SUB);
        count.setPadding(dp(2), 0, 0, dp(10));
        listContainer.addView(count);

        for (ContentBlockLogStore.Entry e : entries)
        {
            listContainer.addView(entryCard(e));
        }
    }

    // ------------------------------------------------------------------
    // views
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

        ImageView icon = new ImageView(this);
        AppIconCache.bindIcon(this, icon, pkg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconLp.leftMargin = dp(4);
        iconLp.rightMargin = dp(10);
        row.addView(icon, iconLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(AppIconCache.label(this, pkg));
        title.setTextSize(20);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.blocked_notifications_subtitle));
        sub.setTextSize(11);
        sub.setTextColor(COLOR_SUB);
        text.addView(sub);
        row.addView(text);

        return row;
    }

    private View entryCard(ContentBlockLogStore.Entry e)
    {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundStrokeBg(COLOR_CARD, dp(16), COLOR_LINE, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        TextView time = new TextView(this);
        time.setText(formatTime(e.time));
        time.setTextSize(11);
        time.setTextColor(COLOR_SUB);
        card.addView(time);

        if (!TextUtils.isEmpty(e.title))
        {
            TextView title = new TextView(this);
            title.setText(e.title);
            title.setTextSize(15);
            title.setTextColor(COLOR_TEXT);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(0, dp(4), 0, 0);
            card.addView(title);
        }

        if (!TextUtils.isEmpty(e.text))
        {
            TextView body = new TextView(this);
            body.setText(e.text);
            body.setTextSize(13);
            body.setTextColor(COLOR_TEXT);
            body.setPadding(0, dp(3), 0, 0);
            card.addView(body);
        }

        if (TextUtils.isEmpty(e.title) && TextUtils.isEmpty(e.text))
        {
            TextView none = new TextView(this);
            none.setText(getString(R.string.blocked_no_text));
            none.setTextSize(13);
            none.setTextColor(COLOR_SUB);
            none.setPadding(0, dp(4), 0, 0);
            card.addView(none);
        }

        if (!TextUtils.isEmpty(e.rule))
        {
            TextView chip = new TextView(this);
            chip.setText(getString(R.string.blocked_rule_fmt, e.rule));
            chip.setTextSize(11);
            chip.setTextColor(COLOR_CHIP_TEXT);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setPadding(dp(8), dp(4), dp(8), dp(4));
            chip.setBackground(roundBg(COLOR_CHIP_BG, dp(8)));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(8);
            chip.setLayoutParams(clp);
            card.addView(chip);
        }

        return card;
    }

    private View hintCard(String message)
    {
        TextView t = new TextView(this);
        t.setText(message);
        t.setTextSize(12);
        t.setTextColor(COLOR_SUB);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(24), dp(14), dp(24));
        t.setBackground(roundStrokeBg(0xFFF8FAFF, dp(16), COLOR_LINE, 1));
        return t;
    }

    private String formatTime(long ms)
    {
        if (ms <= 0)
        {
            return "—";
        }
        try
        {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(ms));
        }
        catch (Throwable t)
        {
            return "—";
        }
    }

    // ------------------------------------------------------------------
    // style helpers (self-contained, same look as the other screens)
    // ------------------------------------------------------------------

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
