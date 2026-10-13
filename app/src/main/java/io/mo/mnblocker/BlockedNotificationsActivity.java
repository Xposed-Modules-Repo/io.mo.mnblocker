package io.mo.mnblocker;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
 * Built from {@link Ui} like every other screen.
 */
public final class BlockedNotificationsActivity extends Activity
{
    /** Intent extra: the package name whose blocked notifications to show. */
    static final String EXTRA_PKG = "io.mo.mnblocker.extra.PKG";

    /** Skeleton rows shown while the (su-backed) read is in flight. */
    private static final int SKELETON_ROWS = 4;

    private Ui ui;
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
        ui = new Ui(this);

        Ui.Page page = ui.page();
        LinearLayout root = page.content;

        ImageView icon = new ImageView(this);
        AppIconCache.bindIcon(this, icon, pkg);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setLayoutParams(ui.margins(ui.size(Ui.APP_ICON, Ui.APP_ICON), 0, 0, Ui.S3, 0));
        root.addView(ui.topBar(AppIconCache.label(this, pkg),
                getString(R.string.blocked_notifications_subtitle), icon));

        listContainer = ui.column();
        root.addView(listContainer);

        // Skeleton while the (su-backed) read lands — the shape of what is coming,
        // not a spinner.
        for (int i = 0; i < SKELETON_ROWS; i++)
        {
            listContainer.addView(ui.skeletonRow());
        }

        setContentView(page.scroll);
        SystemBars.edgeToEdge(this, page.scroll, root, root);

        load();
    }

    private void load()
    {
        final String p = pkg;
        if (rootFree())
        {
            Bg.load(this, () -> RootFreeBlockLogStore.readForApp(this, p), this::render);
        }
        else
        {
            Bg.load(this, () -> ContentBlockLogStore.readForApp(p), this::render);
        }
    }

    /** See MainActivity#rootFree() — same pref, same "never spawns su" contract. */
    private boolean rootFree()
    {
        return RegexConfig.MODE_ROOTFREE.equals(
                getSharedPreferences(RegexConfig.PREFS_NAME, MODE_PRIVATE)
                        .getString(RegexConfig.KEY_OPERATING_MODE, RegexConfig.MODE_ROOT));
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
            listContainer.addView(ui.emptyState(R.drawable.ic_inbox,
                    getString(R.string.blocked_empty), ui.p.surface));
            return;
        }

        listContainer.addView(ui.sectionLabel(
                getString(R.string.blocked_count_fmt, entries.size())));

        for (ContentBlockLogStore.Entry e : entries)
        {
            listContainer.addView(entryCard(e));
        }
    }

    // ------------------------------------------------------------------
    // views
    // ------------------------------------------------------------------

    private View entryCard(ContentBlockLogStore.Entry e)
    {
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(Ui.S4), ui.dp(Ui.S3), ui.dp(Ui.S4), ui.dp(Ui.S3));
        card.setBackground(ui.shape(ui.p.surface, Ui.R_MEDIUM));
        card.setLayoutParams(ui.margins(Ui.matchWrap(), 0, 0, 0, Ui.S2));

        card.addView(ui.text(formatTime(e.time), Ui.Type.CAPTION, ui.p.onSurfaceVariant));

        if (!TextUtils.isEmpty(e.title))
        {
            card.addView(ui.text(e.title, Ui.Type.TITLE_SMALL, ui.p.onSurface),
                    ui.margins(Ui.matchWrap(), 0, Ui.S1, 0, 0));
        }

        if (!TextUtils.isEmpty(e.text))
        {
            card.addView(ui.text(e.text, Ui.Type.BODY, ui.p.onSurface),
                    ui.margins(Ui.matchWrap(), 0, Ui.S_HALF, 0, 0));
        }

        if (TextUtils.isEmpty(e.title) && TextUtils.isEmpty(e.text))
        {
            card.addView(ui.text(getString(R.string.blocked_no_text),
                    Ui.Type.BODY, ui.p.onSurfaceVariant),
                    ui.margins(Ui.matchWrap(), 0, Ui.S1, 0, 0));
        }

        if (!TextUtils.isEmpty(e.rule))
        {
            TextView chip = ui.chip(getString(R.string.blocked_rule_fmt, e.rule),
                    ui.p.onPrimaryContainer, ui.p.primaryContainer);
            card.addView(chip, ui.margins(Ui.wrapWrap(), 0, Ui.S2, 0, 0));
        }

        return card;
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
}
