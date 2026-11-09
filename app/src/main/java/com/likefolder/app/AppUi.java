package com.likefolder.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared native styling; all controls keep Android accessibility and touch behavior. */
final class AppUi {
    static final int BACKGROUND = Color.rgb(12, 16, 26);
    static final int CARD = Color.rgb(24, 29, 43);
    static final int MUTED = Color.rgb(159, 170, 191);
    static final int ACCENT = Color.rgb(244, 206, 99);
    static final int CORAL = Color.rgb(255, 119, 151);
    static final int BORDER = Color.rgb(49, 57, 77);

    static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable rounded(Context c, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(c, radius));
        return shape;
    }

    static RippleDrawable ripple(Context c, int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF),
                rounded(c, color, radius), rounded(c, Color.WHITE, radius));
    }

    static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView view = new TextView(c);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setLineSpacing(dp(c, 2), 1f);
        return view;
    }

    static Button button(Context c, String value, boolean primary) {
        Button view = new Button(c);
        view.setText(value); view.setAllCaps(false); view.setTextSize(14);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(primary ? BACKGROUND : Color.WHITE);
        view.setBackground(ripple(c, primary ? ACCENT : BORDER, 14));
        view.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        view.setMinHeight(dp(c, 50)); view.setMinimumHeight(dp(c, 50));
        return view;
    }

    static ImageButton iconButton(Context c, int drawable, String description) {
        ImageButton view = new ImageButton(c);
        view.setImageResource(drawable);
        view.setImageTintList(ColorStateList.valueOf(ACCENT));
        view.setContentDescription(description);
        view.setBackground(ripple(c, Color.TRANSPARENT, 12));
        view.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        view.setMinimumWidth(dp(c, 48)); view.setMinimumHeight(dp(c, 48));
        return view;
    }

    static TextView link(Context c, String value) {
        TextView view = text(c, value, 14, Color.WHITE, false);
        view.setAlpha(.68f); view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(c, 48));
        view.setPadding(dp(c, 8), dp(c, 12), dp(c, 8), dp(c, 12));
        view.setBackground(ripple(c, Color.TRANSPARENT, 12));
        view.setClickable(true); view.setFocusable(true);
        return view;
    }

    static LinearLayout card(Context c) {
        LinearLayout view = new LinearLayout(c); view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18));
        view.setBackground(rounded(c, CARD, 22));
        return view;
    }

    static void prepareWindow(Activity activity, View content) {
        activity.getWindow().setStatusBarColor(BACKGROUND);
        activity.getWindow().setNavigationBarColor(BACKGROUND);
        content.setBackgroundColor(BACKGROUND);
        final int left = content.getPaddingLeft(), top = content.getPaddingTop();
        final int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int insetTop, insetBottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                insetTop = bars.top; insetBottom = bars.bottom;
            } else {
                insetTop = insets.getSystemWindowInsetTop(); insetBottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top + insetTop, right, bottom + insetBottom);
            return insets;
        });
        content.requestApplyInsets();
    }

    private AppUi() { }
}
