package io.mo.mnblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration UI.
 *
 * Modernized programmatic layout.
 */
public final class MainActivity extends Activity
{
    private static final int SORT_BY_APP = 0;
    private static final int SORT_BY_ALPHA = 1;
    private static final String KEY_UI_ONLY_MATCHED = "ui_only_matched";
    private static final String KEY_UI_TAB = "ui_tab";

    private static final int TAB_MAIN = 0;
    private static final int TAB_STATS = 1;
    private static final int TAB_MATCHED = 2;

    /** Page crossfade timings and travel — see {@link Springs}. */
    private static final long PAGE_IN_MS = 200;
    private static final long PAGE_OUT_MS = 180;
    private static final long TINT_MS = 180;

    private static final int COLOR_BG = 0xFFF6F7FB;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_SUB = 0xFF6D7484;
    private static final int COLOR_LINE = 0xFFE7EAF0;
    private static final int COLOR_PRIMARY = 0xFF3F6DF6;
    private static final int COLOR_PRIMARY_DARK = 0xFF254FD8;
    private static final int COLOR_DANGER = 0xFFC62828;
    private static final int COLOR_SUCCESS = 0xFF2E7D32;
    private static final int COLOR_WARN_BG = 0xFFFFF5DD;
    private static final int COLOR_WARN_TEXT = 0xFF9A5B00;

    private EditText rulesInput;
    private Switch contentEnabledSwitch;
    private EditText contentRulesInput;
    private Switch masterSwitch;
    private Switch matchDescSwitch;
    private TextView statusView;
    private Button clearSafeModeButton;
    private TextView listHeader;
    private TextView batchHint;
    private Switch onlyMatchedSwitch;
    private LinearLayout listContainer;
    private Button sortButton;
    private Button multiSelectButton;
    private Button selectAllButton;

    // Bottom-tab pages and controls. Only pageMain is built up front; the other
    // two are inflated the first time their tab is selected (see pageFor).
    private FrameLayout contentFrame;
    private View pageMain;
    private View pageStats;
    private View pageMatched;
    private TextView[] tabViews;
    private ImageView[] tabIcons;
    private int currentTab = -1;
    private LinearLayout statsTilesContainer;
    private LinearLayout rankingContainer;

    // Whitelist state is edited on WhitelistActivity; the main screen holds
    // read-only copies (refreshed in onResume) so the matcher / list stay correct
    // and config writes here don't clobber them.
    private String allowRulesText = "";
    private final Set<String> appWhitelist = new LinkedHashSet<>();

    private final List<ChannelRecord> channels = new ArrayList<>();
    private final Map<String, Boolean> overrides = new HashMap<>();
    // Row / header views survive a re-render: renderList() detaches them and
    // re-attaches the same instances rather than inflating fresh trees. Bounded
    // by DetectedChannelsStore.MAX_ENTRIES.
    private final Map<String, LinearLayout> rowPool = new HashMap<>();
    private final Map<String, View> headerPool = new HashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private int sortMode = SORT_BY_APP;
    private boolean multiSelectMode;
    // Safe-mode state is read via su (the app uid cannot stat /data/system
    // directly), so it is cached here and refreshed on load / manual refresh
    // rather than probed on every refreshStatus() call.
    private boolean safeModeCached;

    private final Collator zhCollator = Collator.getInstance(Locale.CHINA);
    private FrameLayout rootFrame;

    // Shared block/allow engine, cached and rebuilt only when the rule text
    // changes so per-row rendering does not recompile regex repeatedly.
    private RuleMatcher cachedMatcher;
    private boolean matcherDirty = true;
    /** Fingerprint of what the stats page last drew, so an unchanged visit is free. */
    private String lastStatsSignature;
    /** channel key -> regex verdict, memoised; cleared whenever the rules change. */
    private final Map<String, Boolean> regexCache = new HashMap<>();

    @Override
    protected void attachBaseContext(Context newBase)
    {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        // Repair dir ownership + check root in one background thread.
        new Thread(() -> {
            boolean hasRoot = ShellUtils.fixDirPermissions();
            if (!hasRoot) {
                runOnUiThread(() -> showFadeHint(getString(R.string.main_no_root_hint)));
            }
        }).start();

        rootFrame = new FrameLayout(this);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(COLOR_BG);

        contentFrame = new FrameLayout(this);

        // Only the landing page is built here — the stats and matched pages cost
        // a full view tree each and most launches never open them.
        pageMain = buildMainPage();
        contentFrame.addView(pageMain, matchFrame());

        View tabBar = buildTabBar();
        outer.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        rootFrame.addView(outer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);

        // Status-bar inset goes on the page stack, nav-bar inset on the tab bar —
        // so the tab bar's card background is what fills the gesture-pill area.
        SystemBars.edgeToEdge(this, rootFrame, outer, tabBar);

        loadSwitchesAndRules();
        reloadChannelsAndOverrides();

        // Restore the tab last shown (rotation) without playing the transition.
        // Done after the data loads, so a lazily-built page renders fully populated.
        setTabImmediate(savedInstanceState == null
                ? TAB_MAIN
                : savedInstanceState.getInt(KEY_UI_TAB, TAB_MAIN));

        // Build the other two pages once the first frame is on screen. Deferring
        // their construction keeps the cold start cheap, but building one lazily
        // on the tap that opens it put a whole view-tree inflation inside the
        // transition's first frame — which is what made a quick tab tap stutter.
        rootFrame.post(() ->
        {
            pageFor(TAB_STATS);
            pageFor(TAB_MATCHED);
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out)
    {
        super.onSaveInstanceState(out);
        out.putInt(KEY_UI_TAB, currentTab);
    }

    private FrameLayout.LayoutParams matchFrame()
    {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private View buildMainPage()
    {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(heroCard());
        root.addView(globalCard());
        root.addView(whitelistEntryCard());
        root.addView(rulesCard());
        root.addView(mainActionRow());
        return scroll;
    }

    /** Tappable entry that opens the dedicated whitelist settings screen. */
    private View whitelistEntryCard()
    {
        LinearLayout card = cardLayout();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClickable(true);
        card.setOnClickListener(v ->
                startActivity(new Intent(this, WhitelistActivity.class)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(getString(R.string.whitelist_title));
        title.setTextSize(17);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(title);

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.whitelist_entry_desc));
        desc.setTextSize(12);
        desc.setTextColor(COLOR_SUB);
        desc.setPadding(0, dp(4), 0, 0);
        text.addView(desc);
        card.addView(text);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(24);
        arrow.setTextColor(COLOR_SUB);
        arrow.setPadding(dp(10), 0, dp(4), 0);
        card.addView(arrow);
        return card;
    }

    private View buildMatchedPage()
    {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(channelCard());
        return scroll;
    }

    private View buildStatsPage()
    {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout tilesCard = cardLayout();
        tilesCard.addView(sectionTitle(getString(R.string.stats_title), null));
        statsTilesContainer = new LinearLayout(this);
        statsTilesContainer.setOrientation(LinearLayout.VERTICAL);
        tilesCard.addView(statsTilesContainer);
        root.addView(tilesCard);

        LinearLayout rankCard = cardLayout();
        rankCard.addView(sectionTitle(getString(R.string.stats_ranking_title), null));
        rankingContainer = new LinearLayout(this);
        rankingContainer.setOrientation(LinearLayout.VERTICAL);
        rankCard.addView(rankingContainer);
        root.addView(rankCard);

        Button clear = softButton(getString(R.string.action_clear_count));
        clear.setOnClickListener(v -> onResetContentStats());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        clp.topMargin = dp(2);
        root.addView(clear, clp);
        return scroll;
    }

    private View mainActionRow()
    {
        LinearLayout row = rowLayout();

        Button save = primaryButton(getString(R.string.action_save_rules));
        save.setOnClickListener(v -> onSaveRules());
        row.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(2);
        row.setLayoutParams(rowLp);
        return row;
    }

    private View buildTabBar()
    {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(COLOR_CARD);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setElevation(dp(10));

        String[] labels = {getString(R.string.tab_home), getString(R.string.stats_title),
                getString(R.string.tab_matched)};
        int[] icons = {R.drawable.ic_tab_home, R.drawable.ic_tab_stats, R.drawable.ic_tab_matched};
        tabViews = new TextView[labels.length];
        tabIcons = new ImageView[labels.length];
        for (int i = 0; i < labels.length; i++)
        {
            final int idx = i;

            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(4), dp(8), dp(4), dp(6));
            tab.setOnClickListener(v -> showTab(idx));
            // Draws only; returns false so the click listener above still fires.
            tab.setOnTouchListener(Springs.pressFeedback(tab));

            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            tab.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(11);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(0, dp(4), 0, 0);
            tab.addView(t);

            tabIcons[i] = icon;
            tabViews[i] = t;
            bar.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    /** The page for a tab, or {@code null} if it has not been built yet. */
    private View builtPage(int i)
    {
        if (i == TAB_STATS)
        {
            return pageStats;
        }
        if (i == TAB_MATCHED)
        {
            return pageMatched;
        }
        return pageMain;
    }

    /**
     * The page for a tab, building it on first use. A page built here is
     * populated straight away: by the time any tab can be tapped, onCreate has
     * already loaded the channel / override data it renders from.
     */
    private View pageFor(int i)
    {
        if (i == TAB_STATS && pageStats == null)
        {
            pageStats = buildStatsPage();
            pageStats.setVisibility(View.GONE);
            contentFrame.addView(pageStats, matchFrame());
        }
        else if (i == TAB_MATCHED && pageMatched == null)
        {
            pageMatched = buildMatchedPage();
            pageMatched.setVisibility(View.GONE);
            contentFrame.addView(pageMatched, matchFrame());
            renderList();
        }
        return builtPage(i);
    }

    /** Select a tab with no transition — first layout and rotation restore. */
    private void setTabImmediate(int i)
    {
        View to = pageFor(i);
        settlePages(null, to);
        to.setVisibility(View.VISIBLE);
        to.setAlpha(1f);
        to.setTranslationX(0f);

        applyTabColors(i, false);
        currentTab = i;
        onTabSettled(i); // no transition to stay out of the way of
    }

    private void showTab(int i)
    {
        if (i == currentTab)
        {
            popTabIcon(i); // re-tapping the active tab still acknowledges the touch
            return;
        }

        final View from = builtPage(currentTab);
        View to = pageFor(i);

        if (from == null || from == to)
        {
            setTabImmediate(i);
            popTabIcon(i);
            return;
        }

        settlePages(from, to);

        // Travel direction follows the tab order, so the pages feel laid out
        // side by side rather than stacked.
        int dx = dp(16) * (i > currentTab ? 1 : -1);

        final int target = i;

        // withLayer(): fading a whole ScrollView subtree without promoting it to a
        // hardware layer re-renders every child each frame, which is most of what
        // made rapid tab taps stutter.
        to.setVisibility(View.VISIBLE);
        to.setAlpha(0f);
        to.setTranslationX(dx);
        to.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(PAGE_IN_MS)
                .setInterpolator(Springs.EASE_OUT)
                .withLayer()
                .withEndAction(() -> onTabSettled(target))
                .start();

        from.animate()
                .alpha(0f)
                .translationX(-dx)
                .setDuration(PAGE_OUT_MS)
                .setInterpolator(Springs.EASE_OUT)
                .withLayer()
                .withEndAction(() ->
                {
                    from.setVisibility(View.GONE);
                    from.setAlpha(1f);
                    from.setTranslationX(0f);
                })
                .start();

        applyTabColors(i, true); // reads currentTab as the previous index
        popTabIcon(i);
        currentTab = i;
    }

    /**
     * Work deferred until a tab transition has finished.
     *
     * Refreshing the stats page rebuilds its tiles and the whole app ranking. Doing
     * that while the pages are still animating meant every tap paid for a view
     * rebuild during the transition's own frames. If a transition is cancelled by
     * another tap this never runs — but the tap that finally settles will run it.
     */
    private void onTabSettled(int i)
    {
        if (i == TAB_STATS)
        {
            refreshStats();
        }
    }

    /**
     * Cancel any in-flight page animation and settle every page that is neither
     * leaving nor entering.
     *
     * withEndAction does NOT run when an animation is cancelled, so a page that
     * gets interrupted mid-fade — which is exactly what rapid tab tapping does —
     * would otherwise stay half-transparent and stuck on top of the new page.
     */
    private void settlePages(View from, View to)
    {
        for (int k = TAB_MAIN; k <= TAB_MATCHED; k++)
        {
            View p = builtPage(k);
            if (p == null)
            {
                continue;
            }
            p.animate().cancel();
            if (p != from && p != to)
            {
                p.setVisibility(View.GONE);
                p.setAlpha(1f);
                p.setTranslationX(0f);
            }
        }
    }

    private void popTabIcon(int i)
    {
        if (tabIcons != null && i >= 0 && i < tabIcons.length)
        {
            Springs.popIcon(tabIcons[i]);
        }
    }

    /** Move the selected tint to tab {@code i}, crossfading when animating. */
    private void applyTabColors(int i, boolean animate)
    {
        if (tabViews == null || tabIcons == null)
        {
            return;
        }
        for (int k = 0; k < tabViews.length; k++)
        {
            int to = (k == i) ? COLOR_PRIMARY : COLOR_SUB;
            if (!animate)
            {
                tabIcons[k].setColorFilter(to);
                tabViews[k].setTextColor(to);
                continue;
            }
            int from = (k == currentTab) ? COLOR_PRIMARY : COLOR_SUB;
            if (from != to)
            {
                Springs.tint(tabIcons[k], tabViews[k], from, to, TINT_MS);
            }
        }
    }

    private View heroCard()
    {
        LinearLayout card = cardLayout();
        card.setBackground(roundBg(COLOR_PRIMARY, dp(22)));
        card.setPadding(dp(18), dp(18), dp(18), dp(16));

        LinearLayout header = rowLayout();
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText("LSPosed Module");
        badge.setTextColor(0xFFEFF3FF);
        badge.setTextSize(11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        badge.setBackground(roundBg(0x33FFFFFF, dp(999)));
        header.addView(badge, wrapParams());

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.mipmap.ic_launcher);
        appIcon.setBackground(roundBg(0x33FFFFFF, dp(999)));
        appIcon.setContentDescription(getString(R.string.nav_about));
        appIcon.setPadding(dp(6), dp(6), dp(6), dp(6));
        appIcon.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        header.addView(appIcon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        card.addView(header);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(12), 0, dp(4));
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.hero_desc));
        desc.setTextColor(0xFFE7ECFF);
        desc.setTextSize(13);
        desc.setLineSpacing(dp(2), 1.0f);
        card.addView(desc);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(Color.WHITE);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusView.setBackground(roundBg(0x22FFFFFF, dp(14)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        card.addView(statusView, lp);

        clearSafeModeButton = baseButton(getString(R.string.safe_mode_close_button));
        clearSafeModeButton.setTextColor(Color.WHITE);
        clearSafeModeButton.setBackground(roundBg(COLOR_WARN_TEXT, dp(14)));
        clearSafeModeButton.setVisibility(View.GONE);
        clearSafeModeButton.setOnClickListener(v -> onClearSafeMode());

        LinearLayout.LayoutParams safeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46));
        safeLp.topMargin = dp(10);
        card.addView(clearSafeModeButton, safeLp);

        return card;
    }

    private void onClearSafeMode()
    {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.safe_mode_close_button))
                .setMessage(getString(R.string.safe_mode_dialog_message))
                .setPositiveButton(getString(R.string.safe_mode_confirm), (d, w) -> new Thread(() ->
                {
                    boolean ok = ShellUtils.clearSafeMode();
                    runOnUiThread(() ->
                    {
                        if (!ok)
                        {
                            Toast.makeText(this,
                                    getString(R.string.safe_mode_clear_failed),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        safeModeCached = false;
                        refreshStatus();
                        Toast.makeText(this, getString(R.string.safe_mode_cleared_toast),
                                Toast.LENGTH_LONG).show();
                    });
                }).start())
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private View globalCard()
    {
        LinearLayout card = cardLayout();
        card.addView(sectionTitle(getString(R.string.global_switch_title), null));

        masterSwitch = cleanSwitch(getString(R.string.switch_master_enable), null);
        masterSwitch.setOnCheckedChangeListener((b, v) -> persistSwitches());
        card.addView(masterSwitch);

        card.addView(divider());

        matchDescSwitch = cleanSwitch(getString(R.string.switch_match_desc_title),
                getString(R.string.switch_match_desc_sub));
        matchDescSwitch.setOnCheckedChangeListener((b, v) -> persistSwitches());
        card.addView(matchDescSwitch);

        return card;
    }

    private View rulesCard()
    {
        LinearLayout card = cardLayout();
        card.addView(sectionTitle(getString(R.string.rules_card_title), null));

        rulesInput = new EditText(this);
        rulesInput.setMinLines(5);
        rulesInput.setGravity(Gravity.TOP | Gravity.START);
        rulesInput.setTextSize(13);
        rulesInput.setTextColor(COLOR_TEXT);
        rulesInput.setHint(getString(R.string.rules_hint));
        rulesInput.setHintTextColor(0xFFB0B6C3);
        rulesInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        rulesInput.setBackground(roundStrokeBg(Color.WHITE, dp(14), COLOR_LINE, 1));
        // matcher() no longer re-reads this field on every call, so the edits have
        // to announce themselves.
        rulesInput.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s)
            {
                invalidateMatcher();
            }
        });

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(12);
        card.addView(rulesInput, inputLp);

        card.addView(divider());

        TextView contentTitle = new TextView(this);
        contentTitle.setText(getString(R.string.content_section_title));
        contentTitle.setTextColor(COLOR_TEXT);
        contentTitle.setTextSize(14);
        contentTitle.setTypeface(Typeface.DEFAULT_BOLD);
        contentTitle.setPadding(0, dp(10), 0, dp(2));
        card.addView(contentTitle);

        TextView contentDesc = new TextView(this);
        contentDesc.setText(getString(R.string.content_section_desc));
        contentDesc.setTextColor(COLOR_SUB);
        contentDesc.setTextSize(12);
        contentDesc.setPadding(0, 0, 0, dp(2));
        card.addView(contentDesc);

        contentEnabledSwitch = cleanSwitch(getString(R.string.switch_content_enable_title),
                getString(R.string.switch_content_enable_sub));
        contentEnabledSwitch.setOnCheckedChangeListener((b, v) -> persistSwitches());
        card.addView(contentEnabledSwitch);

        contentRulesInput = new EditText(this);
        contentRulesInput.setMinLines(3);
        contentRulesInput.setGravity(Gravity.TOP | Gravity.START);
        contentRulesInput.setTextSize(13);
        contentRulesInput.setTextColor(COLOR_TEXT);
        contentRulesInput.setHint(getString(R.string.content_rules_hint));
        contentRulesInput.setHintTextColor(0xFFB0B6C3);
        contentRulesInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        contentRulesInput.setBackground(roundStrokeBg(Color.WHITE, dp(14), COLOR_LINE, 1));

        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        contentLp.topMargin = dp(8);
        card.addView(contentRulesInput, contentLp);

        return card;
    }

    private View channelCard()
    {
        LinearLayout card = cardLayout();
        card.addView(sectionTitle(getString(R.string.channel_card_title), null));

        listHeader = new TextView(this);
        listHeader.setTextSize(12);
        listHeader.setTextColor(COLOR_SUB);
        listHeader.setPadding(0, 0, 0, dp(8));
        card.addView(listHeader);

        onlyMatchedSwitch = cleanSwitch(getString(R.string.switch_only_matched_title),
                getString(R.string.switch_only_matched_sub));
        // This page is built lazily, i.e. after loadSwitchesAndRules() has run, so
        // it restores its own persisted state rather than being fed it from there.
        onlyMatchedSwitch.setChecked(prefs().getBoolean(KEY_UI_ONLY_MATCHED, true));
        onlyMatchedSwitch.setOnCheckedChangeListener((b, v) ->
        {
            prefs().edit().putBoolean(KEY_UI_ONLY_MATCHED, v).apply();
            selected.clear();
            renderList();
        });
        card.addView(onlyMatchedSwitch);

        LinearLayout toolbar1 = rowLayout();
        toolbar1.setPadding(0, dp(10), 0, 0);

        Button refreshButton = softButton(getString(R.string.action_refresh));
        refreshButton.setOnClickListener(v ->
        {
            reloadChannelsAndOverrides();
            renderList();
            Toast.makeText(this, getString(R.string.toast_refreshed), Toast.LENGTH_SHORT).show();
        });
        toolbar1.addView(refreshButton, equalWeightWithGap(true));

        sortButton = softButton(getString(R.string.sort_by_app));
        sortButton.setOnClickListener(v ->
        {
            sortMode = (sortMode == SORT_BY_APP) ? SORT_BY_ALPHA : SORT_BY_APP;
            renderList();
        });
        toolbar1.addView(sortButton, equalWeightWithGap(true));

        multiSelectButton = softButton(getString(R.string.action_multi_select));
        multiSelectButton.setOnClickListener(v ->
        {
            multiSelectMode = !multiSelectMode;
            selected.clear();
            renderList();
        });
        toolbar1.addView(multiSelectButton, equalWeightWithGap(false));
        card.addView(toolbar1);

        LinearLayout toolbar2 = rowLayout();
        toolbar2.setPadding(0, dp(8), 0, 0);

        Button blockAll = dangerButton(getString(R.string.action_block_all));
        blockAll.setOnClickListener(v -> batchSet(true));
        toolbar2.addView(blockAll, equalWeightWithGap(true));

        Button allowAll = successButton(getString(R.string.action_allow_all));
        allowAll.setOnClickListener(v -> batchSet(false));
        toolbar2.addView(allowAll, equalWeightWithGap(true));

        selectAllButton = softButton(getString(R.string.action_select_all_clear));
        selectAllButton.setOnClickListener(v ->
        {
            List<ChannelRecord> visible = visibleChannels();
            if (selected.size() < visible.size())
            {
                for (ChannelRecord r : visible)
                {
                    selected.add(r.key());
                }
            }
            else
            {
                selected.clear();
            }
            renderList();
        });
        toolbar2.addView(selectAllButton, equalWeightWithGap(false));
        card.addView(toolbar2);

        batchHint = new TextView(this);
        batchHint.setTextSize(12);
        batchHint.setTextColor(COLOR_SUB);
        batchHint.setPadding(dp(12), dp(8), dp(12), dp(8));
        batchHint.setBackground(roundBg(0xFFF8FAFF, dp(12)));

        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(10);
        card.addView(batchHint, hintLp);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(8);
        card.addView(listContainer, listLp);

        return card;
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences prefs()
    {
        try
        {
            return getSharedPreferences(RegexConfig.PREFS_NAME, Context.MODE_WORLD_READABLE);
        }
        catch (SecurityException e)
        {
            return getSharedPreferences(RegexConfig.PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    /** The config bridge may need su, so the disk read is backgrounded. */
    private void loadSwitchesAndRules()
    {
        SharedPreferences sp = prefs();
        final boolean prefMaster = sp.getBoolean(RegexConfig.KEY_MASTER_ENABLED, true);
        final boolean prefMatchDesc = sp.getBoolean(RegexConfig.KEY_MATCH_DESC, true);
        final String prefRules = sp.getString(RegexConfig.KEY_RULES, "");
        final boolean prefContentOn = sp.getBoolean(RegexConfig.KEY_CONTENT_ENABLED, false);
        final String prefContentRules = sp.getString(RegexConfig.KEY_CONTENT_RULES, "");

        Bg.load(this, ConfigFileStore::readForApp, disk ->
        {
            setCheckedSilently(masterSwitch,
                    disk.hasValue ? disk.masterEnabled : prefMaster);
            setCheckedSilently(matchDescSwitch,
                    disk.hasValue ? disk.matchDescription : prefMatchDesc);
            setCheckedSilently(contentEnabledSwitch,
                    disk.hasValue ? disk.contentEnabled : prefContentOn);
            rulesInput.setText(disk.hasValue ? disk.rules : prefRules);
            contentRulesInput.setText(disk.hasValue ? disk.contentRules : prefContentRules);

            invalidateMatcher(); // rule text just changed
            loadWhitelistState();
            refreshStatus();
        });
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        // The whitelist screen may have changed allow rules / app whitelist.
        loadWhitelistState();
        refreshStatus();
        renderList();
        refreshStats();
    }

    /**
     * Refresh the read-only whitelist copies the main screen relies on. The
     * config bridge may only be readable via su, so the read is backgrounded and
     * the list re-rendered once the values arrive.
     */
    private void loadWhitelistState()
    {
        SharedPreferences sp = prefs();
        final String prefAllow = sp.getString(RegexConfig.KEY_ALLOW_RULES, "");
        final String prefApps = sp.getString(RegexConfig.KEY_APP_WHITELIST, "");

        Bg.load(this, ConfigFileStore::readForApp, disk ->
        {
            String allow = disk.hasValue ? disk.allowRules : prefAllow;
            String appList = disk.hasValue ? disk.appWhitelist : prefApps;

            allowRulesText = allow == null ? "" : allow;
            appWhitelist.clear();
            if (!TextUtils.isEmpty(appList))
            {
                for (String line : appList.split("\\r?\\n"))
                {
                    String t = line.trim();
                    if (!t.isEmpty())
                    {
                        appWhitelist.add(t);
                    }
                }
            }
            invalidateMatcher(); // allow rules may have changed
            renderList();
            refreshStats();
        });
    }

    private void persistSwitches()
    {
        // matchDescSwitch decides which fields candidates() tests, so any switch
        // change invalidates the cached verdicts. (This is also the listener that
        // setCheckedSilently reinstalls, so it must live here, not at the call site.)
        invalidateMatcher();
        prefs().edit()
                .putBoolean(RegexConfig.KEY_MASTER_ENABLED, masterSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_MATCH_DESC, matchDescSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_CONTENT_ENABLED,
                        contentEnabledSwitch != null && contentEnabledSwitch.isChecked())
                .apply();
        persistConfigFile(false, null);
        refreshStatus();
    }

    private void onSaveRules()
    {
        String rules = rulesInput.getText().toString();
        String contentRules = contentRulesInput.getText().toString();
        String bad = firstInvalidRegex(rules);
        if (bad == null)
        {
            bad = firstInvalidRegex(contentRules);
        }
        if (bad != null)
        {
            Toast.makeText(this, getString(R.string.toast_regex_invalid_fmt, bad), Toast.LENGTH_LONG).show();
            return;
        }

        // apply(), not commit(): commit() writes the XML synchronously on the
        // main thread. The bridge write below is the one that can actually fail
        // (it needs root), so it alone decides the toast.
        prefs().edit()
                .putBoolean(RegexConfig.KEY_MASTER_ENABLED, masterSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_MATCH_DESC, matchDescSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_CONTENT_ENABLED, contentEnabledSwitch.isChecked())
                .putString(RegexConfig.KEY_RULES, rules)
                .putString(RegexConfig.KEY_CONTENT_RULES, contentRules)
                .apply();

        persistConfigFile(false, ok -> Toast.makeText(this,
                ok ? getString(R.string.toast_saved_synced)
                        : getString(R.string.toast_saved_sync_failed),
                Toast.LENGTH_LONG).show());

        refreshStatus();
        renderList();
        refreshStats();
    }

    /**
     * Reload the detected channels, the per-channel overrides and the safe-mode
     * flag. All three live under /data/system and may need su, so they are read
     * together on one background pass and applied when they land.
     */
    private void reloadChannelsAndOverrides()
    {
        final String prefOverrides = prefs().getString(RegexConfig.KEY_OVERRIDES, "");

        Bg.load(this, () ->
        {
            ChannelSnapshot s = new ChannelSnapshot();
            s.channels = DetectedChannelsStore.readAllFromDiskForApp();
            ConfigFileStore.ConfigSnapshot disk = ConfigFileStore.readForApp();
            s.overridesJson = disk.hasValue ? disk.overrides : prefOverrides;
            s.safeMode = ShellUtils.isSafeModeTripped();
            return s;
        }, s ->
        {
            channels.clear();
            channels.addAll(s.channels);
            regexCache.clear(); // the records themselves may have changed

            overrides.clear();
            if (!TextUtils.isEmpty(s.overridesJson))
            {
                try
                {
                    JSONObject o = new JSONObject(s.overridesJson);
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); )
                    {
                        String k = it.next();
                        overrides.put(k, o.optBoolean(k, false));
                    }
                }
                catch (Throwable ignored)
                {
                }
            }

            safeModeCached = s.safeMode;
            selected.retainAll(keySet());
            refreshStatus();
            renderList();
            refreshStats();
        });
    }

    /** What {@link #reloadChannelsAndOverrides} gathers off the main thread. */
    private static final class ChannelSnapshot
    {
        List<ChannelRecord> channels;
        String overridesJson;
        boolean safeMode;
    }

    /**
     * Rebuild the stats page: tiles + the combined per-app block ranking.
     *
     * The counters live under /data/system and may only be reachable via su, so
     * they are fetched off the main thread — this runs on every visit to the
     * stats tab, and a blocking read here would stall the tab transition.
     */
    private void refreshStats()
    {
        if (statsTilesContainer == null || rankingContainer == null)
        {
            return;
        }
        Bg.load(this, ContentStatsStore::readForApp, this::renderStats);
    }

    private void renderStats(ContentStatsStore.Snapshot cs)
    {
        if (statsTilesContainer == null || rankingContainer == null)
        {
            return;
        }

        Map<String, Integer> blockedByApp = blockedChannelsByApp();
        int totalBlockedChannels = 0;
        for (int v : blockedByApp.values())
        {
            totalBlockedChannels += v;
        }

        // Tearing down and rebuilding the tiles and the whole app ranking costs a
        // dropped frame, and this runs on every visit to the tab — but the numbers
        // usually have not moved since the last one. Rebuild only when they have.
        String signature = cs.count + "|" + cs.lastBlocked + "|" + cs.perApp
                + "|" + blockedByApp + "|" + rulesText();
        if (signature.equals(lastStatsSignature))
        {
            return;
        }
        lastStatsSignature = signature;
        Set<String> apps = new LinkedHashSet<>(blockedByApp.keySet());
        apps.addAll(cs.perApp.keySet());
        int ruleCount = countRules(rulesInput == null ? "" : rulesInput.getText().toString()) + 1;

        String when = cs.lastBlocked > 0
                ? DateUtils.getRelativeTimeSpanString(cs.lastBlocked,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                : "—";

        statsTilesContainer.removeAllViews();
        statsTilesContainer.addView(tileRow(
                statTile(getString(R.string.stat_tile_content_blocks), String.valueOf(cs.count)),
                statTile(getString(R.string.stat_tile_channel_blocks), String.valueOf(totalBlockedChannels))));
        statsTilesContainer.addView(tileRow(
                statTile(getString(R.string.stat_tile_apps), String.valueOf(apps.size())),
                statTile(getString(R.string.stat_tile_rules), String.valueOf(ruleCount))));

        TextView lastLine = new TextView(this);
        lastLine.setTextSize(12);
        lastLine.setTextColor(COLOR_SUB);
        lastLine.setText(getString(R.string.stats_last_blocked_fmt, when));
        lastLine.setPadding(dp(2), dp(4), 0, 0);
        statsTilesContainer.addView(lastLine);

        // ---- combined ranking (channel blocks + content blocks), desc ----
        final Map<String, Integer> chan = blockedByApp;
        final Map<String, Long> cont = cs.perApp;
        List<String> rank = new ArrayList<>();
        for (String pkg : apps)
        {
            if (score(chan, cont, pkg) > 0)
            {
                rank.add(pkg);
            }
        }
        Collections.sort(rank, (a, b) ->
        {
            long sa = score(chan, cont, a);
            long sb = score(chan, cont, b);
            if (sa != sb)
            {
                return Long.compare(sb, sa);
            }
            return appLabel(a).compareToIgnoreCase(appLabel(b));
        });

        rankingContainer.removeAllViews();
        if (rank.isEmpty())
        {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.stats_empty));
            empty.setTextSize(12);
            empty.setTextColor(COLOR_SUB);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(14), dp(20), dp(14), dp(20));
            empty.setBackground(roundStrokeBg(0xFFF8FAFF, dp(16), COLOR_LINE, 1));
            rankingContainer.addView(empty);
            return;
        }
        int i = 1;
        for (String pkg : rank)
        {
            int ch = chan.containsKey(pkg) ? chan.get(pkg) : 0;
            long ct = cont.containsKey(pkg) ? cont.get(pkg) : 0L;
            rankingContainer.addView(rankingRow(i++, pkg, ch, ct));
        }
    }

    private static long score(Map<String, Integer> chan, Map<String, Long> cont, String pkg)
    {
        long c = chan.containsKey(pkg) ? chan.get(pkg) : 0;
        long t = cont.containsKey(pkg) ? cont.get(pkg) : 0L;
        return c + t;
    }

    private Map<String, Integer> blockedChannelsByApp()
    {
        Map<String, Integer> m = new HashMap<>();
        for (ChannelRecord r : channels)
        {
            if (effectiveBlocked(r))
            {
                Integer prev = m.get(r.pkg);
                m.put(r.pkg, (prev == null ? 0 : prev) + 1);
            }
        }
        return m;
    }

    private View tileRow(View a, View b)
    {
        LinearLayout row = rowLayout();
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(10);
        row.setLayoutParams(rowLp);

        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpA.rightMargin = dp(10);
        row.addView(a, lpA);
        row.addView(b, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View statTile(String label, String value)
    {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.setBackground(roundStrokeBg(0xFFF8FAFF, dp(16), COLOR_LINE, 1));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(COLOR_SUB);
        t.addView(l);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(24);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(COLOR_PRIMARY_DARK);
        v.setPadding(0, dp(6), 0, 0);
        t.addView(v);
        return t;
    }

    private View rankingRow(int rank, String pkg, int channelBlocks, long contentBlocks)
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(roundStrokeBg(Color.WHITE, dp(16), COLOR_LINE, 1));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        row.setLayoutParams(rowLp);

        ImageView iconView = new ImageView(this);
        AppIconCache.bindIcon(this, iconView, pkg);
        row.addView(iconView, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(6), 0, dp(8), 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(tlp);

        TextView name = new TextView(this);
        name.setText(appLabel(pkg));
        name.setTextSize(14);
        name.setTextColor(COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(name);

        TextView meta = new TextView(this);
        meta.setText(getString(R.string.ranking_meta_fmt, channelBlocks, contentBlocks, pkg));
        meta.setTextSize(10);
        meta.setTextColor(COLOR_SUB);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(3), 0, 0);
        text.addView(meta);
        row.addView(text);

        TextView total = new TextView(this);
        total.setText(String.valueOf(channelBlocks + contentBlocks));
        total.setTextSize(18);
        total.setTypeface(Typeface.DEFAULT_BOLD);
        total.setTextColor(COLOR_DANGER);
        row.addView(total);
        return row;
    }

    private String appLabel(String pkg)
    {
        return AppIconCache.label(this, pkg);
    }

    private void onResetContentStats()
    {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_reset_stats_title))
                .setMessage(getString(R.string.confirm_reset_stats_msg))
                .setPositiveButton(getString(R.string.action_clear), (d, w) -> new Thread(() ->
                {
                    boolean ok = ContentStatsStore.resetFromApp();
                    runOnUiThread(() ->
                    {
                        Toast.makeText(this,
                                ok ? getString(R.string.toast_cleared) : getString(R.string.toast_clear_failed),
                                Toast.LENGTH_SHORT).show();
                        refreshStats();
                    });
                }).start())
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void persistOverrides()
    {
        JSONObject o = new JSONObject();
        try
        {
            for (Map.Entry<String, Boolean> e : overrides.entrySet())
            {
                o.put(e.getKey(), e.getValue());
            }
        }
        catch (Throwable ignored)
        {
        }
        prefs().edit().putString(RegexConfig.KEY_OVERRIDES, o.toString()).apply();
        persistConfigFile(false, null);
    }

    /**
     * Push the whole config to the root-writable JSON bridge.
     *
     * The write goes through su, so the UI state is snapshotted here on the main
     * thread and the actual write happens on {@link Bg}. {@code then} (may be
     * null) receives the outcome back on the main thread.
     */
    private void persistConfigFile(final boolean showToast, final Bg.Consumer<Boolean> then)
    {
        final boolean master = masterSwitch != null && masterSwitch.isChecked();
        final boolean matchDesc = matchDescSwitch == null || matchDescSwitch.isChecked();
        final String rules = rulesInput == null ? "" : rulesInput.getText().toString();
        final String allow = allowRulesText;
        final String ovr = overridesJson();
        final boolean contentOn = contentEnabledSwitch != null && contentEnabledSwitch.isChecked();
        final String contentRules = contentRulesInput == null
                ? "" : contentRulesInput.getText().toString();
        final String apps = TextUtils.join("\n", appWhitelist);

        Bg.load(this,
                () -> ConfigFileStore.writeFromApp(master, matchDesc, rules, allow, ovr,
                        contentOn, contentRules, apps),
                ok ->
                {
                    if (!ok && showToast)
                    {
                        Toast.makeText(this,
                                getString(R.string.toast_config_sync_failed_fmt,
                                        ConfigFileStore.CONFIG_FILE),
                                Toast.LENGTH_LONG).show();
                    }
                    if (then != null)
                    {
                        then.accept(ok);
                    }
                });
    }

    private String overridesJson()
    {
        JSONObject o = new JSONObject();
        try
        {
            for (Map.Entry<String, Boolean> e : overrides.entrySet())
            {
                o.put(e.getKey(), e.getValue());
            }
        }
        catch (Throwable ignored)
        {
        }
        return o.toString();
    }

    private void renderList()
    {
        // The matched page is built lazily; nothing to render until it exists.
        // pageFor() calls this itself once the page is created.
        if (listContainer == null)
        {
            return;
        }

        sortButton.setText(sortMode == SORT_BY_APP
                ? getString(R.string.sort_by_app) : getString(R.string.sort_by_alpha));
        multiSelectButton.setText(multiSelectMode
                ? getString(R.string.multi_select_active) : getString(R.string.action_multi_select));
        selectAllButton.setEnabled(multiSelectMode);
        selectAllButton.setAlpha(multiSelectMode ? 1.0f : 0.45f);

        List<ChannelRecord> visible = visibleChannels();

        if (multiSelectMode)
        {
            batchHint.setText(getString(R.string.batch_hint_multi_fmt, selected.size()));
        }
        else
        {
            batchHint.setText(getString(R.string.batch_hint_normal_fmt, visible.size()));
        }

        // Detaches the children only — they stay alive in the pools below and are
        // re-attached and rebound. renderList() runs on every toggle / sort /
        // multi-select tap, and rebuilding up to 1000 row trees each time is what
        // used to make those taps stutter.
        listContainer.removeAllViews();

        if (visible.isEmpty())
        {
            TextView empty = new TextView(this);
            empty.setText(channels.isEmpty()
                    ? getString(R.string.empty_no_data)
                    : getString(R.string.empty_no_regex_match));
            empty.setTextSize(12);
            empty.setTextColor(COLOR_SUB);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(14), dp(22), dp(14), dp(22));
            empty.setBackground(roundStrokeBg(0xFFF8FAFF, dp(16), COLOR_LINE, 1));
            listContainer.addView(empty);
            listHeader.setText(getString(R.string.list_header_zero_fmt, channels.size()));
            return;
        }

        List<ChannelRecord> sorted = new ArrayList<>(visible);
        Collections.sort(sorted, comparator());

        listHeader.setText(getString(R.string.list_header_fmt,
                sorted.size(), channels.size(), countBlocked(sorted)));

        String lastApp = null;
        for (ChannelRecord r : sorted)
        {
            if (sortMode == SORT_BY_APP && !r.pkg.equals(lastApp))
            {
                lastApp = r.pkg;
                listContainer.addView(headerFor(r.pkg));
            }
            listContainer.addView(rowFor(r));
        }
    }

    /** A group header for {@code pkg}, created once and reused across renders. */
    private View headerFor(String pkg)
    {
        View v = headerPool.get(pkg);
        if (v == null)
        {
            v = appGroupHeader(pkg);
            headerPool.put(pkg, v);
        }
        return v;
    }

    /** The row for a channel, created once and rebound on every render. */
    private View rowFor(ChannelRecord r)
    {
        String key = r.key();
        LinearLayout row = rowPool.get(key);
        if (row == null)
        {
            row = createChannelRow();
            rowPool.put(key, row);
        }
        bindChannelRow(row, r);
        return row;
    }

    private Comparator<ChannelRecord> comparator()
    {
        if (SORT_BY_APP == sortMode)
        {
            return (a, b) ->
            {
                // Group by app, ordered by the label the header shows. Falling back
                // to the package keeps each app's channels contiguous even when two
                // apps happen to share a label.
                int c = zhCollator.compare(appLabel(a.pkg), appLabel(b.pkg));
                if (c == 0)
                {
                    c = a.pkg.compareToIgnoreCase(b.pkg);
                }
                if (c != 0)
                {
                    return c;
                }
                return zhCollator.compare(displayName(a), displayName(b));
            };
        }

        return (a, b) ->
        {
            int c = zhCollator.compare(displayName(a), displayName(b));
            if (c != 0)
            {
                return c;
            }
            return a.pkg.compareToIgnoreCase(b.pkg);
        };
    }

    private View appGroupHeader(String pkg)
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(14), dp(2), dp(6));

        ImageView icon = new ImageView(this);
        AppIconCache.bindIcon(this, icon, pkg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        iconLp.rightMargin = dp(8);
        row.addView(icon, iconLp);

        TextView name = new TextView(this);
        name.setText(appLabel(pkg));
        name.setTextSize(12);
        name.setTextColor(COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name);

        // Keep the package visible — two apps can share a label, and it is what
        // the per-channel override is actually keyed on.
        TextView pkgView = new TextView(this);
        pkgView.setText(pkg);
        pkgView.setTextSize(10);
        pkgView.setTextColor(COLOR_SUB);
        pkgView.setSingleLine(true);
        pkgView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pkgLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pkgLp.leftMargin = dp(8);
        row.addView(pkgView, pkgLp);

        return row;
    }

    /**
     * The channel-row skeleton, created once per channel and then rebound.
     *
     * The checkbox is always present and merely hidden outside multi-select mode:
     * keeping the child indices stable is what lets a row survive a mode flip as
     * a rebind instead of a rebuild.
     */
    private LinearLayout createChannelRow()
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        row.setBackground(roundStrokeBg(Color.WHITE, dp(16), COLOR_LINE, 1));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        row.setLayoutParams(rowLp);

        row.addView(new CheckBox(this)); // child 0

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameView = new TextView(this);
        nameView.setTextSize(14);
        nameView.setTextColor(COLOR_TEXT);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(nameView); // text 0

        TextView metaView = new TextView(this);
        metaView.setTextSize(10);
        metaView.setTextColor(COLOR_SUB);
        metaView.setSingleLine(true);
        metaView.setEllipsize(TextUtils.TruncateAt.END);
        metaView.setPadding(0, dp(3), 0, dp(3));
        text.addView(metaView); // text 1

        TextView statusLine = new TextView(this);
        statusLine.setTextSize(10);
        statusLine.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(statusLine); // text 2

        row.addView(text);            // child 1
        row.addView(new Switch(this)); // child 2
        return row;
    }

    private void bindChannelRow(LinearLayout row, ChannelRecord r)
    {
        final String key = r.key();
        final boolean blocked = effectiveBlocked(r);

        CheckBox cb = (CheckBox) row.getChildAt(0);
        LinearLayout text = (LinearLayout) row.getChildAt(1);
        TextView nameView = (TextView) text.getChildAt(0);
        TextView metaView = (TextView) text.getChildAt(1);
        final TextView statusLine = (TextView) text.getChildAt(2);
        Switch sw = (Switch) row.getChildAt(2);

        // Detach the listener before setChecked — on a rebind the view still
        // carries the previous channel's listener, and setting the state would
        // otherwise fire it and write an override for the wrong channel.
        cb.setOnCheckedChangeListener(null);
        cb.setVisibility(multiSelectMode ? View.VISIBLE : View.GONE);
        cb.setChecked(selected.contains(key));
        cb.setOnCheckedChangeListener((b, checked) ->
        {
            if (checked)
            {
                selected.add(key);
            }
            else
            {
                selected.remove(key);
            }
            batchHint.setText(getString(R.string.batch_hint_multi_fmt, selected.size()));
        });

        text.setPadding(multiSelectMode ? 0 : dp(2), 0, dp(8), 0);
        nameView.setText(displayName(r));
        metaView.setText(r.pkg + "  ·  id=" + (TextUtils.isEmpty(r.id) ? "—" : r.id));

        statusLine.setText(getString(R.string.status_line_fmt, verdictSource(r),
                getString(blocked ? R.string.status_blocked : R.string.status_allowed)));
        statusLine.setTextColor(blocked ? COLOR_DANGER : COLOR_SUCCESS);

        sw.setOnCheckedChangeListener(null);
        sw.setChecked(blocked);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
        {
            overrides.put(key, checked);
            persistOverrides();
            statusLine.setText(getString(R.string.status_line_fmt, getString(R.string.source_override),
                    getString(checked ? R.string.status_blocked : R.string.status_allowed)));
            statusLine.setTextColor(checked ? COLOR_DANGER : COLOR_SUCCESS);
            List<ChannelRecord> visible = visibleChannels();
            listHeader.setText(getString(R.string.list_header_fmt,
                    visible.size(), channels.size(), countBlocked(visible)));
        });

        if (multiSelectMode)
        {
            row.setOnClickListener(v ->
            {
                if (!selected.remove(key))
                {
                    selected.add(key);
                }
                renderList();
            });
        }
        else
        {
            row.setOnClickListener(null);
            row.setClickable(false);
        }
    }

    /** Which rule decided this channel's verdict — shown on the row's status line. */
    private String verdictSource(ChannelRecord r)
    {
        if (overrides.get(r.key()) != null)
        {
            return getString(R.string.source_override);
        }
        if (appWhitelist.contains(r.pkg))
        {
            return getString(R.string.source_app_whitelist);
        }
        RuleMatcher m = matcher();
        String[] cand = candidates(r);
        if (m.firstAllowMatch(cand) != null)
        {
            return getString(R.string.source_allow_whitelist);
        }
        if (m.firstBlockMatch(cand) != null)
        {
            return getString(R.string.source_regex_matched);
        }
        return getString(R.string.source_regex_not_matched);
    }

    private void batchSet(boolean block)
    {
        Set<String> targets;
        if (multiSelectMode)
        {
            if (selected.isEmpty())
            {
                Toast.makeText(this, getString(R.string.toast_select_first), Toast.LENGTH_SHORT).show();
                return;
            }
            targets = new LinkedHashSet<>(selected);
        }
        else
        {
            targets = visibleKeySet();
            if (targets.isEmpty())
            {
                Toast.makeText(this, getString(R.string.toast_list_empty), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        for (String k : targets)
        {
            overrides.put(k, block);
        }

        persistOverrides();
        renderList();
        Toast.makeText(this,
                getString(block ? R.string.toast_blocked_n_fmt : R.string.toast_allowed_n_fmt, targets.size()),
                Toast.LENGTH_SHORT).show();
    }

    private boolean effectiveBlocked(ChannelRecord r)
    {
        Boolean ov = overrides.get(r.key());
        if (ov != null)
        {
            return ov;
        }
        if (appWhitelist.contains(r.pkg))
        {
            return false;
        }
        return uiRegexMatched(r);
    }

    private int countBlocked(List<ChannelRecord> list)
    {
        int n = 0;
        for (ChannelRecord r : list)
        {
            if (effectiveBlocked(r))
            {
                n++;
            }
        }
        return n;
    }

    private List<ChannelRecord> visibleChannels()
    {
        if (onlyMatchedSwitch == null || !onlyMatchedSwitch.isChecked())
        {
            return new ArrayList<>(channels);
        }

        List<ChannelRecord> out = new ArrayList<>();
        for (ChannelRecord r : channels)
        {
            if (uiRegexMatched(r))
            {
                out.add(r);
            }
        }
        return out;
    }

    private Set<String> visibleKeySet()
    {
        Set<String> s = new LinkedHashSet<>();
        for (ChannelRecord r : visibleChannels())
        {
            s.add(r.key());
        }
        return s;
    }

    private Set<String> keySet()
    {
        Set<String> s = new LinkedHashSet<>();
        for (ChannelRecord r : channels)
        {
            s.add(r.key());
        }
        return s;
    }

    /**
     * Net regex verdict for a channel using the SAME engine as the hook, so the
     * UI never disagrees with what actually gets blocked. Reflects the allow
     * whitelist (allow beats block) and the "匹配描述文本" toggle.
     */
    /**
     * Whether the regex rules match this channel, memoised per channel.
     *
     * Callers run this over the whole channel list — the stats page does it for
     * every record on every visit — so without the cache a single tab switch
     * could execute the rule set a thousand times mid-animation.
     */
    private boolean uiRegexMatched(ChannelRecord r)
    {
        String key = r.key();
        Boolean cached = regexCache.get(key);
        if (cached != null)
        {
            return cached;
        }
        boolean matched = matcher().shouldBlock(candidates(r));
        regexCache.put(key, matched);
        return matched;
    }

    /**
     * Shared engine built from the current rule text.
     *
     * Rebuilt only when something actually invalidates it. It used to re-read the
     * EditText and rebuild a cache key string on EVERY call — i.e. once per
     * channel per render — which is exactly the work this is supposed to avoid.
     */
    private RuleMatcher matcher()
    {
        if (cachedMatcher == null || matcherDirty)
        {
            String block = rulesInput == null ? "" : rulesInput.getText().toString();
            String allow = allowRulesText == null ? "" : allowRulesText;
            cachedMatcher = RuleMatcher.compile(splitLines(block), splitLines(allow));
            matcherDirty = false;
            regexCache.clear();
        }
        return cachedMatcher;
    }

    /** Rule text / match-description / allow list changed: verdicts must be recomputed. */
    private void invalidateMatcher()
    {
        matcherDirty = true;
        regexCache.clear();
        lastStatsSignature = null; // the rule count and the verdicts both feed the stats
    }

    private String rulesText()
    {
        return rulesInput == null ? "" : rulesInput.getText().toString();
    }

    /** Candidate strings to test: id + name, plus description when the toggle is on. */
    private String[] candidates(ChannelRecord r)
    {
        boolean useDesc = matchDescSwitch != null && matchDescSwitch.isChecked()
                && !TextUtils.isEmpty(r.desc);
        return useDesc
                ? new String[]{r.id, r.name, r.desc}
                : new String[]{r.id, r.name};
    }

    private static List<String> splitLines(String blob)
    {
        List<String> out = new ArrayList<>();
        if (blob == null)
        {
            return out;
        }
        for (String line : blob.split("\\r?\\n"))
        {
            out.add(line);
        }
        return out;
    }

    private static int countRules(String blob)
    {
        int n = 0;
        if (!TextUtils.isEmpty(blob))
        {
            for (String l : blob.split("\\r?\\n"))
            {
                String t = l.trim();
                if (!t.isEmpty() && !t.startsWith("#"))
                {
                    n++;
                }
            }
        }
        return n;
    }

    private String firstInvalidRegex(String blob)
    {
        if (blob == null)
        {
            return null;
        }
        for (String line : blob.split("\\r?\\n"))
        {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#"))
            {
                continue;
            }
            try
            {
                Pattern.compile(t);
            }
            catch (Exception e)
            {
                return t;
            }
        }
        return null;
    }

    private String displayName(ChannelRecord r)
    {
        if (!TextUtils.isEmpty(r.name))
        {
            return r.name;
        }
        if (!TextUtils.isEmpty(r.id))
        {
            return r.id;
        }
        return getString(R.string.unknown_name);
    }

    private void refreshStatus()
    {
        if (statusView == null)
        {
            return;
        }

        boolean safeMode = safeModeCached;
        String state = safeMode
                ? getString(R.string.state_safe_mode)
                : (masterSwitch != null && masterSwitch.isChecked()
                        ? getString(R.string.state_running) : getString(R.string.state_master_off));

        int count = countRules(rulesInput == null
                ? prefs().getString(RegexConfig.KEY_RULES, "")
                : rulesInput.getText().toString());
        int allowCount = countRules(allowRulesText);
        int contentCount = countRules(contentRulesInput == null
                ? prefs().getString(RegexConfig.KEY_CONTENT_RULES, "")
                : contentRulesInput.getText().toString());
        boolean contentOn = contentEnabledSwitch != null && contentEnabledSwitch.isChecked();

        statusView.setText(getString(R.string.status_text_fmt, state, count, allowCount,
                appWhitelist.size(), overrides.size(),
                contentOn ? getString(R.string.content_on) : getString(R.string.content_off),
                contentCount));

        if (safeMode)
        {
            statusView.setTextColor(COLOR_WARN_TEXT);
            statusView.setBackground(roundBg(COLOR_WARN_BG, dp(14)));
        }
        else
        {
            statusView.setTextColor(Color.WHITE);
            statusView.setBackground(roundBg(0x22FFFFFF, dp(14)));
        }

        if (clearSafeModeButton != null)
        {
            clearSafeModeButton.setVisibility(safeMode ? View.VISIBLE : View.GONE);
        }
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

    private View sectionTitle(String title, String desc)
    {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(12));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(17);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(titleView);

        if (!TextUtils.isEmpty(desc))
        {
            TextView descView = new TextView(this);
            descView.setText(desc);
            descView.setTextSize(12);
            descView.setTextColor(COLOR_SUB);
            descView.setPadding(0, dp(4), 0, 0);
            box.addView(descView);
        }

        return box;
    }

    private Switch cleanSwitch(String title, String sub)
    {
        Switch sw = new Switch(this);
        sw.setText(TextUtils.isEmpty(sub) ? title : title + "\n" + sub);
        sw.setTextSize(13);
        sw.setTextColor(COLOR_TEXT);
        sw.setPadding(0, dp(8), 0, dp(8));
        return sw;
    }

    private View divider()
    {
        View v = new View(this);
        v.setBackgroundColor(COLOR_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    private Button primaryButton(String text)
    {
        Button b = baseButton(text);
        b.setTextColor(Color.WHITE);
        b.setBackground(roundBg(COLOR_PRIMARY, dp(14)));
        return b;
    }

    private Button softButton(String text)
    {
        Button b = baseButton(text);
        b.setTextColor(COLOR_PRIMARY_DARK);
        b.setBackground(roundBg(0xFFEFF3FF, dp(14)));
        return b;
    }

    private Button dangerButton(String text)
    {
        Button b = baseButton(text);
        b.setTextColor(Color.WHITE);
        b.setBackground(roundBg(0xFFE53935, dp(14)));
        return b;
    }

    private Button successButton(String text)
    {
        Button b = baseButton(text);
        b.setTextColor(Color.WHITE);
        b.setBackground(roundBg(0xFF43A047, dp(14)));
        return b;
    }

    private Button baseButton(String text)
    {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private LinearLayout rowLayout()
    {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams equalWeightWithGap(boolean hasRightGap)
    {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0,
                dp(42),
                1f);
        if (hasRightGap)
        {
            lp.rightMargin = dp(8);
        }
        return lp;
    }

    private LinearLayout.LayoutParams wrapParams()
    {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

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

    private void setCheckedSilently(Switch sw, boolean checked)
    {
        // Switches on the stats / matched pages do not exist until their tab is
        // first opened; those pages read their own state when they are built.
        if (sw == null)
        {
            return;
        }
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        if (sw == onlyMatchedSwitch)
        {
            sw.setOnCheckedChangeListener((b, v) ->
            {
                prefs().edit().putBoolean(KEY_UI_ONLY_MATCHED, v).apply();
                selected.clear();
                renderList();
            });
        }
        else
        {
            sw.setOnCheckedChangeListener((b, v) -> persistSwitches());
        }
    }

    /**
     * Show a hint anchored near the bottom of the screen that fades out
     * after 2 seconds.
     */
    private void showFadeHint(String text)
    {
        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText(text);
        hint.setTextColor(android.graphics.Color.WHITE);
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

    private int dp(int v)
    {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
