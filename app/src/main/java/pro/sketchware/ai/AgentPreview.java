package pro.sketchware.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.cardview.widget.CardView;

import com.besome.sketch.beans.BlockBean;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ViewBean;
import com.besome.sketch.editor.view.ItemView;
import com.besome.sketch.editor.view.ViewPane;
import com.besome.sketch.editor.view.item.ItemHorizontalScrollView;
import com.besome.sketch.editor.view.item.ItemVerticalScrollView;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import a.a.a.jC;
import a.a.a.wq;
import mod.bobur.VectorDrawableLoader;
import pro.sketchware.R;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileUtil;

/**
 * Offscreen {@link ViewPane} layout renderer and geometry auditor for
 * {@code preview_screen}.
 *
 * <p>Inflates a copy of any Activity or Custom View on the main UI thread
 * using Sketchware Pro's real {@link ViewPane} + {@code Theme.SketchwarePro.ProjectPreview},
 * synchronously resolves vector/SVG icons, custom {@code <shape>} drawables,
 * and static {@code onCreate_initializeLogic} visual styling blocks
 * (corner radii, strokes, gradients, card colours, tinting), renders a PNG
 * into {@link AiPaths#previewsDir()}, and audits measured bounds for 0dp
 * collapsed views, offscreen overflow, and clipped text.
 */
public final class AgentPreview {

    private static final int DEFAULT_WIDTH_DP = 360;
    private static final int DEFAULT_HEIGHT_DP = 800;

    private AgentPreview() {
    }

    /**
     * Renders {@code screenName} offscreen and returns measured dp geometry,
     * layout warnings, and (when {@code includeImage} is true) the marker
     * that instructs {@link AgentLoop} to attach the rendered PNG for vision
     * self-inspection.
     */
    public static JsonObject renderScreen(Context context,
                                          String scId,
                                          String screenName,
                                          int requestedWidthDp,
                                          int requestedHeightDp,
                                          boolean includeImage)
            throws AgentTools.ToolException {
        ProjectFileBean target = resolveScreenOrCustomView(scId, screenName);
        if (target == null) {
            throw new AgentTools.ToolException("E_NO_SCREEN",
                    "Screen or custom view \"" + screenName
                            + "\" does not exist in this project.");
        }

        ArrayList<ViewBean> sourceBeans =
                SketchwareProjects.screenBeans(scId, target);
        ArrayList<ViewBean> beans = AgentEdits.deepCopy(
                sourceBeans == null ? new ArrayList<>() : sourceBeans);
        boolean isCustomView = target.fileType
                != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY;

        ArrayList<BlockBean> initBlocks = new ArrayList<>();
        if (!isCustomView) {
            try {
                ArrayList<BlockBean> rawBlocks = jC.a(scId).a(
                        target.getJavaName(), "onCreate_initializeLogic");
                if (rawBlocks != null) {
                    initBlocks.addAll(rawBlocks);
                }
            } catch (Exception ignored) {
            }
        }

        int widthDp = Math.max(240, Math.min(
                requestedWidthDp <= 0 ? DEFAULT_WIDTH_DP : requestedWidthDp,
                600));
        int heightDp = Math.max(200, Math.min(
                requestedHeightDp <= 0
                        ? (isCustomView ? 320 : DEFAULT_HEIGHT_DP)
                        : requestedHeightDp,
                1400));

        AgentAssets.resetResourceManager(scId);
        // Pre-warm resource manager off the UI thread before posting to main looper
        jC.d(scId);

        final JsonObject[] resultHolder = new JsonObject[1];
        final Throwable[] errorHolder = new Throwable[1];

        Runnable uiTask = () -> {
            try {
                resultHolder[0] = renderOnMainThread(
                        context, scId, target, beans, initBlocks,
                        widthDp, heightDp, isCustomView, includeImage);
            } catch (Throwable t) {
                errorHolder[0] = t;
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            uiTask.run();
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                try {
                    uiTask.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                if (!latch.await(15, TimeUnit.SECONDS)) {
                    throw new AgentTools.ToolException("E_PREVIEW_TIMEOUT",
                            "Timed out rendering preview for \""
                                    + target.fileName + "\".");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentTools.ToolException("E_CANCELLED",
                        "Preview render interrupted.");
            }
        }

        if (errorHolder[0] != null) {
            if (errorHolder[0] instanceof AgentTools.ToolException te) {
                throw te;
            }
            throw new AgentTools.ToolException("E_PREVIEW_FAILED",
                    "Could not render preview of \"" + target.fileName
                            + "\": " + errorHolder[0].getMessage());
        }
        return resultHolder[0];
    }

    private static JsonObject renderOnMainThread(
            Context appContext,
            String scId,
            ProjectFileBean target,
            ArrayList<ViewBean> beans,
            List<BlockBean> initBlocks,
            int widthDp,
            int heightDp,
            boolean isCustomView,
            boolean includeImage) throws Exception {
        Context themedContext = new ContextThemeWrapper(
                appContext, R.style.Theme_SketchwarePro_ProjectPreview);
        float density = Math.min(
                2.0f,
                Math.max(1.0f,
                        themedContext.getResources()
                                .getDisplayMetrics().density));
        float realDensity = themedContext.getResources()
                .getDisplayMetrics().density;

        ViewPane pane = new ViewPane(themedContext);
        pane.initialize(scId, true);
        pane.setResourceManager(jC.d(scId));
        pane.updateRootLayout(scId, target.getXmlName());

        Map<String, View> viewsById = new LinkedHashMap<>();
        Map<String, ViewBean> beansById = new LinkedHashMap<>();

        for (int i = 0; i < beans.size(); i++) {
            ViewBean bean = beans.get(i);
            if (bean.parent == null || bean.parent.isEmpty()
                    || "root".equalsIgnoreCase(bean.parent)) {
                bean.parent = "root";
                if (bean.parentType < 0) {
                    bean.parentType = ViewBean.VIEW_TYPE_LAYOUT_LINEAR;
                }
            }
            if (bean.convert == null) {
                bean.convert = "";
            }
            View itemView = pane.createItemView(bean);
            pane.addViewAndUpdateIndex(itemView);
            if (itemView instanceof ItemView iv) {
                iv.setFixed(true);
            }
            viewsById.put(bean.id, itemView);
            beansById.put(bean.id, bean);
            hydrateCustomDrawablesSync(scId, itemView, bean, realDensity);
        }

        applyStaticInitBlocks(viewsById, initBlocks, realDensity);

        int widthPx = Math.round(widthDp * realDensity);
        int maxHeightPx = Math.round(heightDp * realDensity);
        pane.measure(
                View.MeasureSpec.makeMeasureSpec(
                        widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        maxHeightPx,
                        isCustomView
                                ? View.MeasureSpec.AT_MOST
                                : View.MeasureSpec.EXACTLY));
        int measuredW = Math.max(1, pane.getMeasuredWidth());
        int measuredH = Math.max(
                1,
                isCustomView
                        ? Math.min(maxHeightPx,
                                Math.max(Math.round(56 * realDensity),
                                        pane.getMeasuredHeight()))
                        : maxHeightPx);
        pane.layout(0, 0, measuredW, measuredH);

        // Render scaled bitmap so preview PNG stays sharp & compact (~360-720px wide)
        float bitmapScale = density / realDensity;
        int bmpW = Math.max(1, Math.round(measuredW * bitmapScale));
        int bmpH = Math.max(1, Math.round(measuredH * bitmapScale));
        Bitmap bitmap = Bitmap.createBitmap(
                bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        canvas.save();
        canvas.scale(bitmapScale, bitmapScale);
        pane.draw(canvas);
        canvas.restore();

        File previewFile = new File(
                AiPaths.previewsDir(),
                scId + "_" + target.getXmlName().replace(".xml", "") + ".png");
        try (FileOutputStream fos = new FileOutputStream(previewFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 92, fos);
            fos.flush();
        }
        bitmap.recycle();

        // Audit geometry & build structured report
        JsonArray widgetBounds = new JsonArray();
        JsonArray warnings = new JsonArray();
        int[] paneLoc = new int[2];

        for (Map.Entry<String, View> entry : viewsById.entrySet()) {
            String id = entry.getKey();
            View v = entry.getValue();
            ViewBean bean = beansById.get(id);
            int[] xy = relativeOffset(v, pane);
            int xDp = Math.round(xy[0] / realDensity);
            int yDp = Math.round(xy[1] / realDensity);
            int wDp = Math.round(v.getWidth() / realDensity);
            int hDp = Math.round(v.getHeight() / realDensity);

            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("type", wq.b(bean.type));
            row.addProperty("parent", bean.parent);
            row.addProperty("x_dp", xDp);
            row.addProperty("y_dp", yDp);
            row.addProperty("width_dp", wDp);
            row.addProperty("height_dp", hDp);
            if (v instanceof TextView tv && tv.getText() != null
                    && tv.getText().length() > 0) {
                String txt = tv.getText().toString();
                row.addProperty("text", txt.length() > 60
                        ? txt.substring(0, 60) + "…" : txt);
                if (tv.getLayout() != null) {
                    row.addProperty("lines", tv.getLayout().getLineCount());
                }
            }
            widgetBounds.add(row);

            if (v.getVisibility() == View.VISIBLE) {
                if (v.getWidth() == 0 || v.getHeight() == 0) {
                    warnings.add(id + " (" + wq.b(bean.type)
                            + ") collapsed to " + wDp + "x" + hDp
                            + "dp — check width/height/weight or sibling "
                            + "match_parent squeezing it out.");
                } else if (!isInsideScrollable(v, pane)) {
                    if (xy[0] + v.getWidth() > measuredW + Math.round(2 * realDensity)) {
                        warnings.add(id + " overflows the right edge ("
                                + (xDp + wDp) + "dp > " + widthDp + "dp).");
                    }
                    if (!isCustomView
                            && xy[1] + v.getHeight()
                                    > measuredH + Math.round(4 * realDensity)) {
                        warnings.add(id + " overflows below the "
                                + heightDp + "dp screen without a ScrollView ("
                                + (yDp + hDp) + "dp > " + heightDp + "dp).");
                    }
                }
                if (v instanceof TextView tv) {
                    Layout layout = tv.getLayout();
                    if (layout != null && layout.getLineCount() > 0) {
                        int lastLine = layout.getLineCount() - 1;
                        if (layout.getEllipsisCount(lastLine) > 0) {
                            warnings.add(id + " text is ellipsized/truncated "
                                    + "at " + wDp + "dp width.");
                        } else if ("button".equals(wq.b(bean.type))
                                || "materialbutton".equals(wq.b(bean.type))) {
                            if (layout.getLineCount() >= 3) {
                                warnings.add(id + " button label wrapped onto "
                                        + layout.getLineCount()
                                        + " lines (" + wDp
                                        + "dp wide) — widen button or shorten text.");
                            }
                        }
                    }
                }
            }
        }

        auditThemeAndAccessibility(scId, beans, beansById, viewsById,
                pane, isCustomView, warnings);

        JsonObject out = new JsonObject();
        out.addProperty("screen", target.fileName);
        out.addProperty("kind", isCustomView ? "custom_view" : "activity");
        out.addProperty("viewport_width_dp", widthDp);
        out.addProperty("viewport_height_dp",
                Math.round(measuredH / realDensity));
        out.addProperty("widget_count", beans.size());
        out.addProperty("warning_count", warnings.size());
        out.add("layout_warnings", warnings);
        out.add("widgets", widgetBounds);
        out.addProperty("preview_saved", previewFile.getName());
        if (includeImage && previewFile.isFile() && previewFile.length() > 0) {
            out.addProperty("_attach_preview_image",
                    previewFile.getAbsolutePath());
            out.addProperty("vision_attached", true);
        }
        return out;
    }

    private static void hydrateCustomDrawablesSync(String scId,
                                                   View view,
                                                   ViewBean bean,
                                                   float density) {
        try {
            if (view instanceof ImageView iv && bean.image != null
                    && bean.image.resName != null
                    && !"default_image".equals(bean.image.resName)) {
                String resName = bean.image.resName;
                File convertedSvg = new File(
                        new FilePathUtil().getSvgFullPath(scId, resName));
                if (convertedSvg.isFile()) {
                    Bitmap bmp = AgentAssets.renderSvgToBitmap(
                            convertedSvg, 96, null);
                    if (bmp != null) {
                        iv.setImageBitmap(bmp);
                    }
                } else {
                    File customVec = new File(
                            new FilePathUtil().getPathResource(scId)
                                    + File.separator + "drawable"
                                    + File.separator + resName + ".xml");
                    if (customVec.isFile()) {
                        new VectorDrawableLoader().setImageVectorFromFile(
                                iv, customVec.getAbsolutePath());
                    }
                }
            }
            if (bean.layout != null
                    && bean.layout.backgroundResource != null
                    && !bean.layout.backgroundResource.isEmpty()) {
                File customBg = new File(
                        new FilePathUtil().getPathResource(scId)
                                + File.separator + "drawable"
                                + File.separator
                                + bean.layout.backgroundResource + ".xml");
                if (customBg.isFile()) {
                    String rawXml = FileUtil.readFile(customBg.getAbsolutePath());
                    if (rawXml != null && rawXml.contains("<selector")) {
                        boolean isSelected = bean.inject != null
                                && bean.inject.contains("android:selected=\"true\"");
                        String targetDraw = resolveSelectorDrawableName(rawXml, isSelected);
                        if (!targetDraw.isEmpty()) {
                            File subBg = new File(
                                    new FilePathUtil().getPathResource(scId)
                                            + File.separator + "drawable"
                                            + File.separator + targetDraw + ".xml");
                            if (subBg.isFile()) {
                                rawXml = FileUtil.readFile(subBg.getAbsolutePath());
                            }
                        }
                    }
                    GradientDrawable shape = parseSimpleShapeXml(
                            scId, rawXml, density);
                    if (shape != null) {
                        view.setBackground(shape);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void applyStaticInitBlocks(Map<String, View> viewsById,
                                              List<BlockBean> blocks,
                                              float density) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        for (BlockBean b : blocks) {
            if (b == null || b.opCode == null || b.parameters == null
                    || b.parameters.isEmpty()) {
                continue;
            }
            String targetId = stripViewPrefix(b.parameters.get(0));
            View view = viewsById.get(targetId);
            if (view == null) {
                continue;
            }
            try {
                switch (b.opCode) {
                    case "setCornerRadiusView" -> {
                        if (b.parameters.size() >= 3) {
                            float r = parseFloatSafe(b.parameters.get(1), 0f)
                                    * density;
                            Integer c = parseColorSafe(b.parameters.get(2));
                            if (c != null) {
                                GradientDrawable gd = new GradientDrawable();
                                gd.setCornerRadius(r);
                                gd.setColor(c);
                                view.setBackground(gd);
                            }
                        }
                    }
                    case "setStrokeView" -> {
                        if (b.parameters.size() >= 4) {
                            int sw = Math.round(parseFloatSafe(
                                    b.parameters.get(1), 1f) * density);
                            Integer sc = parseColorSafe(b.parameters.get(2));
                            Integer bg = parseColorSafe(b.parameters.get(3));
                            if (sc != null && bg != null) {
                                GradientDrawable gd = new GradientDrawable();
                                gd.setStroke(sw, sc);
                                gd.setColor(bg);
                                view.setBackground(gd);
                            }
                        }
                    }
                    case "setRadiusAndStrokeView" -> {
                        if (b.parameters.size() >= 5) {
                            float r = parseFloatSafe(b.parameters.get(1), 0f)
                                    * density;
                            int sw = Math.round(parseFloatSafe(
                                    b.parameters.get(2), 1f) * density);
                            Integer sc = parseColorSafe(b.parameters.get(3));
                            Integer bg = parseColorSafe(b.parameters.get(4));
                            if (sc != null && bg != null) {
                                GradientDrawable gd = new GradientDrawable();
                                gd.setCornerRadius(r);
                                gd.setStroke(sw, sc);
                                gd.setColor(bg);
                                view.setBackground(gd);
                            }
                        }
                    }
                    case "setGradientBackground" -> {
                        if (b.parameters.size() >= 3) {
                            Integer c1 = parseColorSafe(b.parameters.get(1));
                            Integer c2 = parseColorSafe(b.parameters.get(2));
                            if (c1 != null && c2 != null) {
                                GradientDrawable gd = new GradientDrawable(
                                        GradientDrawable.Orientation.TOP_BOTTOM,
                                        new int[]{c1, c2});
                                view.setBackground(gd);
                            }
                        }
                    }
                    case "setElevation" -> {
                        if (b.parameters.size() >= 2) {
                            view.setElevation(parseFloatSafe(
                                    b.parameters.get(1), 0f) * density);
                        }
                    }
                    case "setColorFilter" -> {
                        if (view instanceof ImageView iv
                                && b.parameters.size() >= 2) {
                            Integer c = parseColorSafe(b.parameters.get(1));
                            if (c != null) {
                                iv.setColorFilter(c, PorterDuff.Mode.SRC_IN);
                            }
                        }
                    }
                    case "setCardBackgroundColor" -> {
                        if (view instanceof CardView cv
                                && b.parameters.size() >= 2) {
                            Integer c = parseColorSafe(b.parameters.get(1));
                            if (c != null) {
                                cv.setCardBackgroundColor(c);
                            }
                        }
                    }
                    case "setCardRadius" -> {
                        if (view instanceof CardView cv
                                && b.parameters.size() >= 2) {
                            cv.setRadius(parseFloatSafe(
                                    b.parameters.get(1), 0f) * density);
                        }
                    }
                    case "setCardElevation" -> {
                        if (view instanceof CardView cv
                                && b.parameters.size() >= 2) {
                            cv.setCardElevation(parseFloatSafe(
                                    b.parameters.get(1), 0f) * density);
                        }
                    }
                    default -> {
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void auditThemeAndAccessibility(
            String scId,
            List<ViewBean> beans,
            Map<String, ViewBean> beansById,
            Map<String, View> viewsById,
            View pane,
            boolean isCustomView,
            JsonArray warnings) {
        if (beans == null || beans.isEmpty()) {
            return;
        }
        OpenUiThemeEngine.ResolvedTheme theme =
                OpenUiThemeEngine.resolveTheme(AgentJournal.designSystem(scId));
        boolean isDayNight = "DayNight".equalsIgnoreCase(theme.themeMode);

        // Count direct children per CardView & unscrollable input fields
        Map<String, Integer> childCounts = new LinkedHashMap<>();
        int unscrollableInputs = 0;

        for (ViewBean bean : beans) {
            if (bean == null || bean.id == null) {
                continue;
            }
            if (bean.parent != null) {
                childCounts.put(bean.parent,
                        childCounts.getOrDefault(bean.parent, 0) + 1);
            }
            String tName = wq.b(bean.type);
            View v = viewsById.get(bean.id);
            if (!isCustomView && v != null && !isInsideScrollable(v, pane)
                    && ("edittext".equals(tName) || "spinner".equals(tName)
                            || "seekbar".equals(tName))) {
                unscrollableInputs++;
            }

            // 1. Material 3 <Button> custom drawable tint & compact padding check
            if (("button".equals(tName) || "materialbutton".equals(tName))
                    && bean.layout != null
                    && bean.layout.backgroundResource != null
                    && !bean.layout.backgroundResource.isEmpty()) {
                String inj = bean.inject == null ? "" : bean.inject;
                if (!inj.contains("backgroundTint=\"@null\"")
                        && !inj.contains("backgroundTint=@null")) {
                    warnings.add(bean.id + " uses custom drawable \""
                            + bean.layout.backgroundResource
                            + "\" on <Button> without app:backgroundTint=\"@null\" "
                            + "(Material 3 will paint solid colorPrimary over it).");
                }
                if (bean.layout.height > 0 && bean.layout.height <= 44
                        && (bean.layout.paddingTop + bean.layout.paddingBottom) >= 16) {
                    warnings.add(bean.id + " has compact height "
                            + bean.layout.height + "dp with "
                            + (bean.layout.paddingTop + bean.layout.paddingBottom)
                            + "dp vertical padding — text may clip vertically.");
                }
            }

            // 2. DayNight hardcoded hex & Light/Dark WCAG contrast check on text widgets
            if (bean.text != null && ("textview".equals(tName)
                    || "button".equals(tName) || "materialbutton".equals(tName)
                    || "edittext".equals(tName) || "checkbox".equals(tName)
                    || "switch".equals(tName) || "radiobutton".equals(tName))) {
                boolean hasResTc = bean.text.resTextColor != null
                        && !bean.text.resTextColor.isEmpty();
                if (isDayNight && !hasResTc && bean.text.textColor != 0) {
                    // Skip white text on solid accent buttons/swatches
                    if (bean.text.textColor != 0xFFFFFFFF) {
                        warnings.add(bean.id + " uses hardcoded hex text colour "
                                + String.format(Locale.ROOT, "#%06X",
                                        bean.text.textColor & 0xFFFFFF)
                                + " in DayNight mode — prefer @color/openui_text_primary "
                                + "or @color/openui_text_secondary so Dark mode stays readable.");
                    }
                }
                Integer lightFg = resolveModeTextColor(scId, bean, theme.lightColors);
                Integer darkFg = resolveModeTextColor(scId, bean, theme.darkColors);
                Integer lightBg = resolveAncestorSurfaceColor(
                        scId, bean, beansById, theme.lightColors, 0xFFFCFCFC);
                Integer darkBg = resolveAncestorSurfaceColor(
                        scId, bean, beansById, theme.darkColors, 0xFF121212);
                double minRatio = (bean.text.textSize >= 18) ? 3.0 : 4.2;
                if (lightFg != null && lightBg != null) {
                    double rL = OpenUiThemeEngine.contrastRatio(lightFg, lightBg);
                    if (rL < minRatio) {
                        warnings.add(String.format(Locale.US,
                                "%s has low Light-mode contrast (%.2f:1 < %.1f:1).",
                                bean.id, rL, minRatio));
                    }
                }
                if (isDayNight && darkFg != null && darkBg != null) {
                    double rD = OpenUiThemeEngine.contrastRatio(darkFg, darkBg);
                    if (rD < minRatio) {
                        warnings.add(String.format(Locale.US,
                                "%s has low Dark-mode contrast (%.2f:1 < %.1f:1).",
                                bean.id, rD, minRatio));
                    }
                }
            }
        }

        for (ViewBean bean : beans) {
            if (bean != null && "cardview".equals(wq.b(bean.type))
                    && childCounts.getOrDefault(bean.id, 0) >= 2) {
                warnings.add(bean.id + " (CardView) has "
                        + childCounts.get(bean.id)
                        + " direct children — CardView is a FrameLayout, so "
                        + "children overlap unless wrapped in one LinearLayout.");
            }
        }
        if (!isCustomView && unscrollableInputs >= 4) {
            warnings.add("Screen has " + unscrollableInputs
                    + " input controls outside a ScrollView — wrap multi-field "
                    + "forms in Form(...) or ScrollView so the keyboard never "
                    + "hides lower fields.");
        }
    }

    private static Integer resolveModeTextColor(String scId,
                                                ViewBean bean,
                                                Map<String, String> modeColors) {
        if (bean == null || bean.text == null) {
            return null;
        }
        String res = bean.text.resTextColor;
        if (res != null && res.startsWith("@color/")) {
            String key = res.substring("@color/".length()).trim();
            if ("openui_chip_text".equals(key)) {
                boolean sel = bean.inject != null
                        && bean.inject.contains("android:selected=\"true\"");
                key = sel ? "openui_text_on_accent" : "openui_text_primary";
            }
            if (modeColors != null && modeColors.containsKey(key)) {
                return parseColorSafe(modeColors.get(key));
            }
            return AgentResources.resolveColorInt(scId, key);
        }
        if (bean.text.textColor != 0) {
            return bean.text.textColor;
        }
        return modeColors != null
                ? parseColorSafe(modeColors.get("openui_text_primary")) : null;
    }

    private static Integer resolveAncestorSurfaceColor(
            String scId,
            ViewBean start,
            Map<String, ViewBean> beansById,
            Map<String, String> modeColors,
            int rootDefault) {
        ViewBean cur = start;
        int guard = 0;
        while (cur != null && guard++ < 32) {
            if (cur.layout != null) {
                String bgDraw = cur.layout.backgroundResource;
                if (bgDraw != null && !bgDraw.isEmpty()) {
                    Integer fromDraw = resolveDrawableSurfaceColor(
                            bgDraw, cur.inject, modeColors);
                    if (fromDraw != null) {
                        return fromDraw;
                    }
                }
                String resBg = cur.layout.backgroundResColor;
                if (resBg != null && resBg.startsWith("@color/")) {
                    String key = resBg.substring("@color/".length()).trim();
                    if (modeColors != null && modeColors.containsKey(key)) {
                        return parseColorSafe(modeColors.get(key));
                    }
                    Integer c = AgentResources.resolveColorInt(scId, key);
                    if (c != null && ((c >>> 24) & 0xFF) > 0) {
                        return c;
                    }
                }
                int rawBg = cur.layout.backgroundColor;
                if (rawBg != 0 && rawBg != 0x00FFFFFF
                        && ((rawBg >>> 24) & 0xFF) >= 0x80) {
                    return rawBg;
                }
            }
            if (cur.inject != null && cur.inject.contains("app:cardBackgroundColor=")) {
                for (String line : cur.inject.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("app:cardBackgroundColor=")) {
                        String val = t.substring("app:cardBackgroundColor=".length())
                                .replace("\"", "").trim();
                        if (val.startsWith("@color/")) {
                            String k = val.substring("@color/".length());
                            if (modeColors != null && modeColors.containsKey(k)) {
                                return parseColorSafe(modeColors.get(k));
                            }
                        }
                        Integer pc = parseColorSafe(val);
                        if (pc != null) {
                            return pc;
                        }
                    }
                }
            }
            String p = cur.parent;
            cur = (p == null || p.isEmpty() || "root".equalsIgnoreCase(p))
                    ? null : beansById.get(p);
        }
        if (modeColors != null && modeColors.containsKey("openui_bg")) {
            Integer bg = parseColorSafe(modeColors.get("openui_bg"));
            if (bg != null) {
                return bg;
            }
        }
        return rootDefault;
    }

    private static Integer resolveDrawableSurfaceColor(
            String bgDraw, String inject, Map<String, String> modeColors) {
        if (modeColors == null || bgDraw == null) {
            return null;
        }
        switch (bgDraw) {
            case "openui_btn_primary":
            case "openui_chip_selected":
                return parseColorSafe(modeColors.get("openui_accent"));
            case "openui_btn_destructive":
                return parseColorSafe(modeColors.get("openui_danger_accent"));
            case "openui_btn_secondary":
            case "openui_sunk_input":
            case "openui_card_sunk":
            case "openui_chip_unselected":
                return parseColorSafe(modeColors.get("openui_sunk"));
            case "openui_chip_selector": {
                boolean sel = inject != null
                        && inject.contains("android:selected=\"true\"");
                return parseColorSafe(modeColors.get(
                        sel ? "openui_accent" : "openui_sunk"));
            }
            case "openui_card":
                return parseColorSafe(modeColors.get("openui_fg"));
            case "openui_callout_info":
            case "openui_tag_info":
                return parseColorSafe(modeColors.get("openui_info_bg"));
            case "openui_callout_success":
            case "openui_tag_success":
                return parseColorSafe(modeColors.get("openui_success_bg"));
            case "openui_callout_warning":
            case "openui_tag_warning":
                return parseColorSafe(modeColors.get("openui_alert_bg"));
            case "openui_callout_danger":
            case "openui_tag_danger":
                return parseColorSafe(modeColors.get("openui_danger_bg"));
            default:
                if (bgDraw.startsWith("openui_swatch_")) {
                    String hex = bgDraw.replace("openui_swatch_sel_", "")
                            .replace("openui_swatch_", "");
                    if (hex.length() == 6) {
                        return parseColorSafe("#" + hex);
                    }
                }
                return null;
        }
    }

    private static String resolveSelectorDrawableName(String xml,
                                                      boolean selectedState) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));
            String fallbackDraw = "";
            int ev = parser.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                    String draw = attr(parser, "android:drawable")
                            .replace("@drawable/", "").trim();
                    String stateSel = attr(parser, "android:state_selected");
                    if (selectedState && "true".equalsIgnoreCase(stateSel) && !draw.isEmpty()) {
                        return draw;
                    }
                    if (!selectedState && stateSel.isEmpty() && !draw.isEmpty()) {
                        return draw;
                    }
                    if (fallbackDraw.isEmpty() && !draw.isEmpty()) {
                        fallbackDraw = draw;
                    }
                }
                ev = parser.next();
            }
            return fallbackDraw;
        } catch (Exception e) {
            return "";
        }
    }

    private static GradientDrawable parseSimpleShapeXml(String scId,
                                                        String xml,
                                                        float density) {
        if (xml == null || !xml.contains("<shape")) {
            return null;
        }
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));
            GradientDrawable gd = new GradientDrawable();
            boolean touched = false;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("shape".equals(tag)) {
                        String shape = attr(parser, "android:shape");
                        if ("oval".equals(shape)) {
                            gd.setShape(GradientDrawable.OVAL);
                        }
                        touched = true;
                    } else if ("solid".equals(tag)) {
                        Integer c = parseColorSafe(scId, attr(parser, "android:color"));
                        if (c != null) {
                            gd.setColor(c);
                            touched = true;
                        }
                    } else if ("corners".equals(tag)) {
                        String rad = attr(parser, "android:radius");
                        if (!rad.isEmpty()) {
                            gd.setCornerRadius(parseDp(rad, density));
                            touched = true;
                        }
                    } else if ("stroke".equals(tag)) {
                        int w = Math.round(parseDp(
                                attr(parser, "android:width"), density));
                        Integer c = parseColorSafe(scId, attr(parser, "android:color"));
                        if (w > 0 && c != null) {
                            gd.setStroke(w, c);
                            touched = true;
                        }
                    } else if ("gradient".equals(tag)) {
                        Integer s = parseColorSafe(scId,
                                attr(parser, "android:startColor"));
                        Integer e = parseColorSafe(scId,
                                attr(parser, "android:endColor"));
                        if (s != null && e != null) {
                            gd.setColors(new int[]{s, e});
                            touched = true;
                        }
                    }
                }
                event = parser.next();
            }
            return touched ? gd : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String attr(XmlPullParser parser, String name) {
        String v = parser.getAttributeValue(null, name);
        return v == null ? "" : v.trim();
    }

    private static float parseDp(String raw, float density) {
        if (raw == null || raw.isEmpty()) {
            return 0f;
        }
        String clean = raw.toLowerCase(Locale.ROOT)
                .replace("dip", "")
                .replace("dp", "")
                .replace("px", "")
                .trim();
        float v = parseFloatSafe(clean, 0f);
        return raw.toLowerCase(Locale.ROOT).endsWith("px") ? v : v * density;
    }

    private static String stripViewPrefix(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int dot = trimmed.lastIndexOf('.');
        return dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
    }

    private static float parseFloatSafe(String raw, float fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Integer parseColorSafe(String raw) {
        return parseColorSafe("", raw);
    }

    private static Integer parseColorSafe(String scId, String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("@color/") && scId != null && !scId.isEmpty()) {
            String name = s.substring("@color/".length()).trim();
            return AgentResources.resolveColorInt(scId, name);
        }
        if (s.startsWith("0x") || s.startsWith("0X")) {
            try {
                return (int) Long.parseLong(s.substring(2), 16);
            } catch (Exception e) {
                return null;
            }
        }
        if (s.startsWith("#")) {
            try {
                return Color.parseColor(s);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static int[] relativeOffset(View child, View root) {
        int x = 0;
        int y = 0;
        View cur = child;
        while (cur != null && cur != root) {
            x += cur.getLeft();
            y += cur.getTop();
            ViewParent p = cur.getParent();
            cur = p instanceof View pv ? pv : null;
        }
        return new int[]{x, y};
    }

    private static boolean isInsideScrollable(View child, View root) {
        ViewParent p = child.getParent();
        while (p instanceof View pv && pv != root) {
            if (pv instanceof ItemVerticalScrollView
                    || pv instanceof ItemHorizontalScrollView
                    || pv instanceof android.widget.ScrollView
                    || pv instanceof android.widget.HorizontalScrollView) {
                return true;
            }
            p = pv.getParent();
        }
        return false;
    }

    public static ProjectFileBean resolveScreenOrCustomView(String scId,
                                                            String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return null;
        }
        ProjectFileBean activity =
                SketchwareProjects.findActivity(scId, rawName);
        if (activity != null) {
            return activity;
        }
        String wanted = rawName.trim();
        if (wanted.endsWith(".xml")) {
            wanted = wanted.substring(0, wanted.length() - 4);
        }
        for (ProjectFileBean cv : SketchwareProjects.customViews(scId)) {
            if (cv.fileName.equalsIgnoreCase(wanted)
                    || cv.getXmlName().equalsIgnoreCase(rawName.trim())) {
                return cv;
            }
        }
        return null;
    }
}
