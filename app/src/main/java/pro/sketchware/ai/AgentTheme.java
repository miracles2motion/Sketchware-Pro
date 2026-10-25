package pro.sketchware.ai;

import android.content.Context;
import android.util.TypedValue;

/**
 * The agent's own chrome — dialogs, cards, the board — dressed in the
 * SAME theme as the rest of Sketchware: every colour is resolved from
 * the app theme's Material attributes, so the agent follows whatever
 * the app looks like (light, dark, brand). The only hardcoded values
 * are last-resort fallbacks for themes that lack an attribute. The
 * blueprint SCREEN PREVIEW is deliberately NOT themed by this class
 * when a design system exists — that drawing previews the USER'S app
 * palette, not Sketchware's.
 */
public final class AgentTheme {

    private AgentTheme() {
    }

    /** The app's primary/brand colour. */
    public static int primary(Context ctx) {
        // The app's own R: this repo's proven namespace for
        // colorPrimary (besome code resolves it the same way).
        return resolve(ctx, pro.sketchware.R.attr.colorPrimary,
                0xFF008DCD);
    }

    /** Card and dialog backgrounds. */
    public static int surface(Context ctx) {
        return resolve(ctx, com.google.android.material.R.attr
                .colorSurface, 0xFFFFFFFF);
    }

    /** Softer card backgrounds (board rows, chips). */
    public static int surfaceLow(Context ctx) {
        return resolve(ctx, com.google.android.material.R.attr
                .colorSurfaceContainerLow, 0xFFF2F4F7);
    }

    /** Main text ink. */
    public static int ink(Context ctx) {
        return resolve(ctx, com.google.android.material.R.attr
                .colorOnSurface, 0xFF1B1F24);
    }

    /** Secondary text. */
    public static int faint(Context ctx) {
        return resolve(ctx, com.google.android.material.R.attr
                .colorOnSurfaceVariant, 0xFF6B7280);
    }

    /** Destructive / failure accents (stop buttons, failed states). */
    public static int error(Context ctx) {
        return resolve(ctx, pro.sketchware.R.attr.colorError,
                0xFFD32F2F);
    }

    /** Hairline borders. */
    public static int border(Context ctx) {
        return resolve(ctx, com.google.android.material.R.attr
                .colorOutline, 0xFFE3E6EA);
    }

    /** The proven technique: resolve any theme attribute safely. */
    private static int resolve(Context ctx, int attr, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (ctx != null && ctx.getTheme().resolveAttribute(attr, tv,
                    true)) {
                if (tv.resourceId != 0) {
                    try {
                        return androidx.core.content.ContextCompat
                                .getColor(ctx, tv.resourceId);
                    } catch (Exception ignored) {
                    }
                }
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
