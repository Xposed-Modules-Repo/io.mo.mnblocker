package io.mo.mnblocker;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Design tokens and the shared component kit for every settings screen.
 *
 * The UI is programmatic framework views on purpose (no XML layouts, no
 * AndroidX / Material). Before this class each Activity carried its own copy of
 * the colours and of {@code cardLayout()} / {@code roundBg()}, and the copies had
 * drifted. Screens now only compose what is here, and never write a colour,
 * radius, spacing or animation literal of their own:
 *
 *  - colour  → {@link Palette} (dynamic on 12+, light / dark, contrast-checked)
 *  - spacing → {@code S*} (4 dp grid)
 *  - radius  → {@code R_*}; medium and up are G2 {@link SmoothCornerDrawable}s
 *  - type    → {@link Type}, all in sp so the user's font scale applies
 *  - motion  → {@link Motion} springs; touch feedback → {@link Haptics}
 *
 * Text containers never get a fixed height (min-heights only), so 200 % font
 * scale grows rows rather than clipping them.
 */
final class Ui {

    // ---- spacing (dp), 4 dp grid -------------------------------------
    /** Half step, only for the gap between a title and its own subtitle. */
    static final int S_HALF = 2;
    static final int S1 = 4;
    static final int S2 = 8;
    static final int S3 = 12;
    static final int S4 = 16;
    static final int S5 = 20;
    static final int S6 = 24;
    static final int S8 = 32;

    // ---- radius (dp): the circular radius each shape should LOOK like ----
    static final int R_SMALL = 8;
    static final int R_MEDIUM = 16;
    static final int R_LARGE = 28;

    // ---- sizes (dp) ----------------------------------------------------
    /** Minimum touch target. */
    static final int TOUCH = 48;
    /** Height of a row that holds a title + subtitle + control. */
    static final int ROW_MIN = 56;
    static final int ICON = 24;
    static final int ICON_SMALL = 20;
    static final int APP_ICON = 40;
    static final int APP_ICON_SMALL = 20;

    // ---- type scale ----------------------------------------------------
    enum Type {
        DISPLAY(28, Weight.BOLD),
        HEADLINE(22, Weight.BOLD),
        TITLE(17, Weight.MEDIUM),
        TITLE_SMALL(15, Weight.MEDIUM),
        BODY(14, Weight.REGULAR),
        BODY_SMALL(13, Weight.REGULAR),
        LABEL(13, Weight.MEDIUM),
        CAPTION(12, Weight.REGULAR),
        NUMBER(26, Weight.BOLD),
        NUMBER_SMALL(18, Weight.BOLD);

        final int sp;
        final Weight weight;

        Type(int sp, Weight weight) {
            this.sp = sp;
            this.weight = weight;
        }
    }

    enum Weight { REGULAR, MEDIUM, BOLD }

    /** Multiplier for wrapped text; CJK paragraphs crowd at 1.0. */
    private static final float LINE_SPACING = 1.15f;
    /** Opacity of a disabled control (M3). */
    static final float DISABLED_ALPHA = 0.38f;

    // ---- button kinds --------------------------------------------------
    static final int BTN_PRIMARY = 0;
    static final int BTN_TONAL = 1;
    static final int BTN_DANGER = 2;
    static final int BTN_SUCCESS = 3;
    static final int BTN_WARN = 4;
    static final int BTN_OUTLINE = 5;

    private static final int BUTTON_SP = 14;
    private static final long SNACKBAR_MS = 2400;
    private static final long SKELETON_PULSE_MS = 900;

    final Activity a;
    final Palette p;
    private final float density;
    private static Typeface medium;

    Ui(Activity activity) {
        a = activity;
        p = Palette.of(activity);
        density = activity.getResources().getDisplayMetrics().density;
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------------
    // text
    // ------------------------------------------------------------------

    TextView text(CharSequence s, Type type, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        style(t, type, color);
        return t;
    }

    void style(TextView t, Type type, int color) {
        t.setTextSize(type.sp);
        t.setTextColor(color);
        t.setTypeface(typeface(type.weight));
        if (type == Type.BODY || type == Type.BODY_SMALL || type == Type.CAPTION) {
            t.setLineSpacing(0f, LINE_SPACING);
        }
    }

    static Typeface typeface(Weight w) {
        switch (w) {
            case BOLD:
                return Typeface.DEFAULT_BOLD;
            case MEDIUM:
                if (medium == null) {
                    medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
                }
                return medium;
            default:
                return Typeface.DEFAULT;
        }
    }

    // ------------------------------------------------------------------
    // layout
    // ------------------------------------------------------------------

    LinearLayout column() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Takes the remaining width of a horizontal row. */
    static LinearLayout.LayoutParams fillRow() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    LinearLayout.LayoutParams size(int wDp, int hDp) {
        return new LinearLayout.LayoutParams(dp(wDp), dp(hDp));
    }

    /** Margins in dp on any LinearLayout params; returns the same instance. */
    LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, int l, int t, int r, int b) {
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    View space(int hDp) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(hDp)));
        return v;
    }

    static final class Page {
        final ScrollView scroll;
        final LinearLayout content;

        Page(ScrollView scroll, LinearLayout content) {
            this.scroll = scroll;
            this.content = content;
        }
    }

    /** A scrolling page with the standard gutters. */
    Page page() {
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(p.bg);
        scroll.setClipToPadding(false);

        LinearLayout content = column();
        content.setPadding(dp(S4), dp(S2), dp(S4), dp(S6));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Page(scroll, content);
    }

    // ------------------------------------------------------------------
    // shapes & feedback
    // ------------------------------------------------------------------

    /** A filled shape at a token radius; medium and larger get the G2 corner. */
    Drawable shape(int fill, int radiusDp) {
        if (radiusDp >= R_MEDIUM) {
            return new SmoothCornerDrawable(fill, dp(radiusDp));
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    Drawable shape(int fill, int radiusDp, int strokeColor, int strokeDp) {
        if (radiusDp >= R_MEDIUM) {
            return new SmoothCornerDrawable(fill, dp(radiusDp)).stroke(strokeColor, dp(strokeDp));
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(strokeDp), strokeColor);
        return gd;
    }

    /** Fully rounded ends. */
    Drawable pill(int fill) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(999));
        return gd;
    }

    Drawable circle(int fill) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(fill);
        return gd;
    }

    /** {@code content} with a pressed ripple clipped to the same shape. */
    Drawable ripple(Drawable content, int radiusDp, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content,
                shape(Color.BLACK, radiusDp));
    }

    Drawable ripple(Drawable content, int radiusDp) {
        return ripple(content, radiusDp, p.ripple);
    }

    /** Enabled look + state together, so a disabled control can never look live. */
    static void setEnabledLook(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : DISABLED_ALPHA);
    }

    // ------------------------------------------------------------------
    // surfaces
    // ------------------------------------------------------------------

    LinearLayout card() {
        return card(p.surface);
    }

    LinearLayout card(int fill) {
        LinearLayout card = column();
        card.setPadding(dp(S4), dp(S4), dp(S4), dp(S4));
        card.setBackground(shape(fill, R_LARGE));
        card.setLayoutParams(margins(matchWrap(), 0, 0, 0, S3));
        return card;
    }

    /** Make a card / row tappable: ripple, press dip, and the click. */
    void tappable(View v, int fill, int radiusDp, View.OnClickListener onClick) {
        v.setBackground(ripple(shape(fill, radiusDp), radiusDp));
        v.setClickable(true);
        v.setOnClickListener(onClick);
        Motion.pressable(v, Motion.PRESS_SCALE_LARGE);
    }

    View divider() {
        View v = new View(a);
        v.setBackgroundColor(p.outlineVariant);
        v.setLayoutParams(margins(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))), 0, S1, 0, S1));
        return v;
    }

    // ------------------------------------------------------------------
    // headers
    // ------------------------------------------------------------------

    /** Large page title for top-level screens, with an optional trailing action. */
    View largeTitle(String title, String subtitle, View trailing) {
        LinearLayout row = row();
        row.setPadding(dp(S1), dp(S4), 0, dp(S4));

        LinearLayout text = column();
        text.addView(text(title, Type.DISPLAY, p.onSurface));
        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = text(subtitle, Type.BODY_SMALL, p.onSurfaceVariant);
            text.addView(sub, margins(matchWrap(), 0, S1, 0, 0));
        }
        row.addView(text, fillRow());
        if (trailing != null) {
            row.addView(trailing);
        }
        return row;
    }

    /** Sub-screen header: back button, optional leading view, title + subtitle. */
    View topBar(String title, String subtitle, View leading) {
        LinearLayout row = row();
        row.setPadding(0, dp(S2), 0, dp(S3));

        ImageView back = iconButton(R.drawable.ic_arrow_back, p.onSurface,
                a.getString(R.string.nav_back), v -> a.finish());
        row.addView(back, margins(size(TOUCH, TOUCH), -S1, 0, S1, 0));

        if (leading != null) {
            row.addView(leading);
        }

        LinearLayout text = column();
        TextView t = text(title, Type.HEADLINE, p.onSurface);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(t);
        if (!TextUtils.isEmpty(subtitle)) {
            text.addView(text(subtitle, Type.CAPTION, p.onSurfaceVariant));
        }
        row.addView(text, fillRow());
        return row;
    }

    View sectionTitle(String title, String desc) {
        LinearLayout box = column();
        box.setPadding(0, 0, 0, dp(S3));
        box.addView(text(title, Type.TITLE, p.onSurface));
        if (!TextUtils.isEmpty(desc)) {
            box.addView(text(desc, Type.BODY_SMALL, p.onSurfaceVariant),
                    margins(matchWrap(), 0, S1, 0, 0));
        }
        return box;
    }

    /** A label sitting on the page above a card or a list, not inside one. */
    TextView sectionLabel(String s) {
        TextView t = text(s, Type.LABEL, p.onSurfaceVariant);
        t.setLetterSpacing(0.04f);
        t.setLayoutParams(margins(matchWrap(), S1, S2, S1, S2));
        return t;
    }

    // ------------------------------------------------------------------
    // icons
    // ------------------------------------------------------------------

    ImageView icon(int res, int tint) {
        ImageView iv = new ImageView(a);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return iv;
    }

    /** 48 dp round icon button with an unbounded-looking circular ripple. */
    ImageView iconButton(int res, int tint, String description, View.OnClickListener onClick) {
        ImageView iv = icon(res, tint);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        iv.setContentDescription(description);
        int pad = (TOUCH - ICON) / 2;
        iv.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        iv.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), null,
                circle(Color.BLACK)));
        iv.setClickable(true);
        iv.setOnClickListener(onClick);
        Motion.pressable(iv, Motion.PRESS_SCALE);
        iv.setLayoutParams(size(TOUCH, TOUCH));
        return iv;
    }

    // ------------------------------------------------------------------
    // controls
    // ------------------------------------------------------------------

    Button button(int kind, String label) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(TOUCH));
        b.setMinimumHeight(dp(TOUCH));
        // The platform Button raises itself on press; the spring dip replaces that.
        b.setStateListAnimator(null);
        styleButton(b, kind);
        Motion.pressable(b, Motion.PRESS_SCALE);
        return b;
    }

    /** (Re)apply a button kind — e.g. a toggle button switching to its active look. */
    void styleButton(Button b, int kind) {
        int fill;
        int fg;
        switch (kind) {
            case BTN_PRIMARY:
                fill = p.primary;
                fg = p.onPrimary;
                break;
            case BTN_DANGER:
                fill = p.dangerContainer;
                fg = p.onDangerContainer;
                break;
            case BTN_SUCCESS:
                fill = p.successContainer;
                fg = p.onSuccessContainer;
                break;
            case BTN_WARN:
                fill = p.warnContainer;
                fg = p.onWarnContainer;
                break;
            case BTN_OUTLINE:
                fill = Color.TRANSPARENT;
                fg = p.primary;
                break;
            case BTN_TONAL:
            default:
                fill = p.primaryContainer;
                fg = p.onPrimaryContainer;
                break;
        }

        style(b, Type.LABEL, fg);
        b.setTextSize(BUTTON_SP);
        Drawable content = kind == BTN_OUTLINE
                ? shape(Color.TRANSPARENT, R_MEDIUM, p.outline, 1)
                : shape(fill, R_MEDIUM);
        b.setBackground(ripple(content, R_MEDIUM, Palette.withAlpha(fg, 0.16f)));
        b.setPadding(dp(S4), dp(S2), dp(S4), dp(S2));
    }

    Switch switchView() {
        Switch sw = new Switch(a);
        int[][] states = {
                {-android.R.attr.state_enabled},
                {android.R.attr.state_checked},
                {}};
        int offThumb = p.dark ? p.onSurfaceVariant : p.surface;
        sw.setThumbTintList(new ColorStateList(states, new int[]{
                Palette.withAlpha(offThumb, DISABLED_ALPHA), p.primary, offThumb}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{
                Palette.withAlpha(p.outline, 0.2f),
                Palette.withAlpha(p.primary, 0.5f),
                p.dark ? Palette.withAlpha(p.outline, 0.6f) : p.outline}));
        return sw;
    }

    CheckBox checkBox() {
        CheckBox cb = new CheckBox(a);
        cb.setButtonTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{p.primary, p.onSurfaceVariant}));
        return cb;
    }

    static final class SwitchRow {
        final LinearLayout view;
        final Switch sw;

        SwitchRow(LinearLayout view, Switch sw) {
            this.view = view;
            this.sw = sw;
        }
    }

    /**
     * Title / subtitle with a switch on the right. The whole row toggles it — a
     * bare Switch's hit area is only the thumb — and a user flip (never a
     * programmatic setChecked) gets the toggle haptic.
     */
    SwitchRow switchRow(String title, String sub) {
        LinearLayout row = row();
        row.setMinimumHeight(dp(ROW_MIN));
        row.setPadding(0, dp(S2), 0, dp(S2));

        LinearLayout text = column();
        text.addView(text(title, Type.TITLE_SMALL, p.onSurface));
        if (!TextUtils.isEmpty(sub)) {
            text.addView(text(sub.trim(), Type.BODY_SMALL, p.onSurfaceVariant),
                    margins(matchWrap(), 0, S_HALF, 0, 0));
        }
        row.addView(text, fillRow());

        final Switch sw = switchView();
        sw.setContentDescription(title);
        sw.setOnClickListener(v -> Haptics.toggle(v, sw.isChecked()));
        row.addView(sw, margins(wrapWrap(), S3, 0, 0, 0));

        row.setBackground(ripple(shape(Color.TRANSPARENT, R_SMALL), R_SMALL));
        row.setOnClickListener(v -> {
            if (sw.isEnabled()) {
                sw.performClick();
            }
        });
        return new SwitchRow(row, sw);
    }

    /** Title / description that opens another screen, with a chevron. */
    View navRow(String title, String desc, View.OnClickListener onClick) {
        LinearLayout row = row();
        row.setMinimumHeight(dp(ROW_MIN));
        row.setPadding(0, dp(S2), 0, dp(S2));

        LinearLayout text = column();
        text.addView(text(title, Type.TITLE_SMALL, p.onSurface));
        if (!TextUtils.isEmpty(desc)) {
            text.addView(text(desc, Type.BODY_SMALL, p.onSurfaceVariant),
                    margins(matchWrap(), 0, S_HALF, 0, 0));
        }
        row.addView(text, fillRow());
        row.addView(icon(R.drawable.ic_chevron_right, p.onSurfaceVariant),
                margins(size(ICON, ICON), S2, 0, 0, 0));

        row.setBackground(ripple(shape(Color.TRANSPARENT, R_SMALL), R_SMALL));
        row.setClickable(true);
        row.setOnClickListener(onClick);
        return row;
    }

    // ------------------------------------------------------------------
    // segmented control
    // ------------------------------------------------------------------

    interface IndexListener {
        void onSelect(int index);
    }

    /**
     * Single-choice segmented buttons. The selection colour moves with a MICRO
     * spring per segment, so a quick second tap redirects rather than waits.
     */
    final class Segmented {
        final LinearLayout view;
        private final TextView[] items;
        private final SmoothCornerDrawable[] fills;
        private final Motion.Spring[] springs;
        private int selected = -1;

        Segmented(String[] labels, int initial, IndexListener listener) {
            view = row();
            view.setPadding(dp(S1), dp(S1), dp(S1), dp(S1));
            view.setBackground(shape(p.surfaceVariant, R_MEDIUM));
            items = new TextView[labels.length];
            fills = new SmoothCornerDrawable[labels.length];
            springs = new Motion.Spring[labels.length];

            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                TextView t = text(labels[i], Type.LABEL, p.onSurfaceVariant);
                t.setGravity(Gravity.CENTER);
                t.setMinHeight(dp(TOUCH - 2 * S1));
                t.setPadding(dp(S2), dp(S1), dp(S2), dp(S1));
                SmoothCornerDrawable fill = new SmoothCornerDrawable(p.surfaceVariant, dp(R_MEDIUM - S1));
                t.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), fill, null));
                t.setClickable(true);
                t.setOnClickListener(v -> {
                    if (idx == selected) {
                        return;
                    }
                    Haptics.tick(v);
                    select(idx, true);
                    listener.onSelect(idx);
                });
                fills[i] = fill;
                springs[i] = new Motion.Spring(0f, 0.002f, value -> paint(idx, value));
                items[i] = t;
                view.addView(t, fillRow());
            }
            select(initial, false);
        }

        private void paint(int i, float v) {
            float c = Math.max(0f, Math.min(1f, v));
            fills[i].setFillColor(Palette.blend(p.surfaceVariant, p.primaryContainer, c));
            items[i].setTextColor(Palette.blend(p.onSurfaceVariant, p.onPrimaryContainer, c));
        }

        void select(int index, boolean animate) {
            selected = index;
            boolean motion = animate && !Motion.reduced(a);
            for (int i = 0; i < items.length; i++) {
                float target = i == index ? 1f : 0f;
                items[i].setSelected(i == index);
                if (motion) {
                    springs[i].animateTo(target, Motion.MICRO, null);
                } else {
                    springs[i].snapTo(target);
                }
            }
        }

        int selected() {
            return selected;
        }

        void setEnabled(boolean enabled) {
            for (TextView t : items) {
                t.setEnabled(enabled);
            }
            view.setAlpha(enabled ? 1f : DISABLED_ALPHA);
        }
    }

    Segmented segmented(String[] labels, int initial, IndexListener listener) {
        return new Segmented(labels, initial, listener);
    }

    // ------------------------------------------------------------------
    // text fields
    // ------------------------------------------------------------------

    /**
     * Filled field with a meaningful (>= 3:1) outline, thickening to the primary
     * colour on focus. {@code mono} for regex input, where column alignment and
     * telling {@code l} from {@code 1} matter.
     */
    EditText field(String hint, int minLines, boolean mono) {
        EditText e = new EditText(a);
        style(e, Type.BODY, p.onSurface);
        if (mono) {
            e.setTypeface(Typeface.MONOSPACE);
            e.setTextSize(Type.BODY_SMALL.sp);
        }
        e.setHint(hint);
        e.setHintTextColor(p.onSurfaceVariant);
        e.setHighlightColor(Palette.withAlpha(p.primary, 0.3f));
        if (minLines > 1) {
            // Explicitly multi-line: rules are one per line, and without the flag
            // the hint's own line breaks collapse into spaces. No suggestions —
            // autocorrect "fixing" a regex is never wanted.
            e.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setMinLines(minLines);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
            e.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }
        e.setMinHeight(dp(TOUCH));
        e.setBackground(fieldBackground(false));
        e.setPadding(dp(S3), dp(S3), dp(S3), dp(S3));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            GradientDrawable cursor = new GradientDrawable();
            cursor.setColor(p.primary);
            cursor.setSize(dp(2), 1);
            e.setTextCursorDrawable(cursor);
        }
        // Keep a focused multi-line field above the IME once it has opened.
        e.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.postDelayed(() -> v.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()), true), 150);
            }
        });
        return e;
    }

    EditText searchField(String hint) {
        EditText e = field(hint, 1, false);
        Drawable icon = a.getDrawable(R.drawable.ic_search);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(p.onSurfaceVariant);
            int s = dp(ICON_SMALL);
            icon.setBounds(0, 0, s, s);
            e.setCompoundDrawablesRelative(icon, null, null, null);
            e.setCompoundDrawablePadding(dp(S2));
        }
        return e;
    }

    private Drawable fieldBackground(boolean error) {
        int idle = error ? p.danger : p.outline;
        int focused = error ? p.danger : p.primary;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},
                shape(p.surfaceVariant, R_MEDIUM, focused, 2));
        s.addState(new int[]{}, shape(p.surfaceVariant, R_MEDIUM, idle, error ? 2 : 1));
        return s;
    }

    /**
     * Flag a field as wrong: danger outline, reject haptic, focus. Clears itself
     * on the next edit, so the error never outlives the thing it points at.
     */
    void markError(final EditText e) {
        int l = e.getPaddingLeft();
        int t = e.getPaddingTop();
        int r = e.getPaddingRight();
        int b = e.getPaddingBottom();
        e.setBackground(fieldBackground(true));
        e.setPadding(l, t, r, b);
        e.requestFocus();
        Haptics.reject(e);
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}

            @Override
            public void onTextChanged(CharSequence s, int st, int bf, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                e.removeTextChangedListener(this);
                e.setBackground(fieldBackground(false));
                e.setPadding(l, t, r, b);
            }
        });
    }

    // ------------------------------------------------------------------
    // small pieces
    // ------------------------------------------------------------------

    TextView chip(String s, int fg, int bg) {
        TextView t = text(s, Type.CAPTION, fg);
        t.setTypeface(typeface(Weight.MEDIUM));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(S2), dp(S_HALF), dp(S2), dp(S_HALF));
        t.setBackground(shape(bg, R_SMALL));
        return t;
    }

    /** Warning callout box. */
    TextView notice(String s) {
        TextView t = text(s, Type.BODY_SMALL, p.onWarnContainer);
        t.setPadding(dp(S3), dp(S3), dp(S3), dp(S3));
        t.setBackground(shape(p.warnContainer, R_MEDIUM));
        return t;
    }

    /**
     * Empty / error state: an icon in a soft circle over a short explanation.
     * {@code fill} 0 = transparent, for use inside a card.
     */
    View emptyState(int iconRes, String message, int fill) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(S6), dp(S6), dp(S6), dp(S6));
        if (fill != 0) {
            box.setBackground(shape(fill, R_LARGE));
        }

        ImageView icon = icon(iconRes, p.onSurfaceVariant);
        int pad = (56 - 28) / 2;
        icon.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        icon.setBackground(circle(fill == p.surfaceVariant ? p.surface : p.surfaceVariant));
        box.addView(icon, size(56, 56));

        TextView t = text(message, Type.BODY_SMALL, p.onSurfaceVariant);
        t.setGravity(Gravity.CENTER);
        box.addView(t, margins(matchWrap(), 0, S3, 0, 0));
        box.setLayoutParams(margins(matchWrap(), 0, 0, 0, S2));
        return box;
    }

    // ------------------------------------------------------------------
    // loading skeletons
    // ------------------------------------------------------------------

    /** Placeholder list row: icon disc + two text bars, pulsing gently. */
    View skeletonRow() {
        LinearLayout row = row();
        row.setPadding(dp(S3), dp(S3), dp(S3), dp(S3));
        row.setBackground(shape(p.surface, R_MEDIUM));
        row.setLayoutParams(margins(matchWrap(), 0, 0, 0, S2));

        View disc = new View(a);
        disc.setBackground(circle(p.surfaceVariant));
        row.addView(disc, margins(size(APP_ICON, APP_ICON), 0, 0, S3, 0));

        LinearLayout bars = column();
        bars.addView(bar(), new LinearLayout.LayoutParams(dp(160), dp(14)));
        bars.addView(bar(), margins(new LinearLayout.LayoutParams(dp(220), dp(10)), 0, S2, 0, 0));
        row.addView(bars, fillRow());

        row.setContentDescription(a.getString(R.string.a11y_loading));
        pulse(row);
        return row;
    }

    /** Placeholder block of a given height. */
    View skeletonBlock(int heightDp) {
        View v = new View(a);
        v.setBackground(shape(p.surfaceVariant, R_MEDIUM));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        // Also a minimum, for parents that re-lay it out as wrap_content.
        v.setMinimumHeight(dp(heightDp));
        v.setContentDescription(a.getString(R.string.a11y_loading));
        pulse(v);
        return v;
    }

    private View bar() {
        View v = new View(a);
        v.setBackground(shape(p.surfaceVariant, R_SMALL / 2));
        return v;
    }

    /**
     * Ambient opacity pulse for skeletons. Not an interaction, so not a spring;
     * runs only while attached, and not at all with animations turned off.
     */
    private void pulse(final View v) {
        if (Motion.reduced(a)) {
            return;
        }
        final ValueAnimator anim = ValueAnimator.ofFloat(1f, 0.55f);
        anim.setDuration(SKELETON_PULSE_MS);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.addUpdateListener(x -> v.setAlpha((Float) x.getAnimatedValue()));
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                anim.start();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                anim.cancel();
                view.setAlpha(1f);
            }
        });
    }

    // ------------------------------------------------------------------
    // snackbar
    // ------------------------------------------------------------------

    /**
     * Transient message over the bottom of {@code host}: springs up, waits,
     * fades. {@code error} adds the reject haptic. Also announced to TalkBack.
     *
     * Lifts itself clear of the gesture pill when {@code host} reaches down
     * behind it (edge-to-edge), and not otherwise.
     */
    @SuppressWarnings("deprecation")
    private static int navOverlap(View host) {
        android.view.WindowInsets in = host.getRootWindowInsets();
        if (in == null) {
            return 0;
        }
        int nav = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? in.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                : in.getSystemWindowInsetBottom();
        int[] loc = new int[2];
        host.getLocationInWindow(loc);
        int below = host.getRootView().getHeight() - (loc[1] + host.getHeight());
        return Math.max(0, nav - below);
    }

    void snackbar(final FrameLayout host, String message, boolean error) {
        final TextView t = text(message, Type.BODY, p.onInverseSurface);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(TOUCH));
        t.setPadding(dp(S4), dp(S3), dp(S4), dp(S3));
        t.setBackground(shape(p.inverseSurface, R_MEDIUM));
        t.setId(R.id.snackbar);

        // One at a time: a newer message replaces the one still showing.
        View previous = host.findViewById(R.id.snackbar);
        if (previous != null && previous.getParent() == host) {
            host.removeView(previous);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.setMargins(dp(S4), 0, dp(S4), dp(S4) + navOverlap(host));
        host.addView(t, lp);

        if (error) {
            Haptics.reject(host);
        }
        host.announceForAccessibility(message);

        t.setAlpha(0f);
        t.setTranslationY(dp(S6));
        Motion.animate(t, View.ALPHA, 1f, Motion.STANDARD);
        Motion.animate(t, View.TRANSLATION_Y, 0f, Motion.FLING);
        t.postDelayed(() -> Motion.animate(t, View.ALPHA, 0f, Motion.STANDARD,
                () -> host.removeView(t)), SNACKBAR_MS);
    }
}
