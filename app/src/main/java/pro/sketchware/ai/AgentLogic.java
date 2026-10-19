package pro.sketchware.ai;

import android.content.Context;

import com.besome.sketch.beans.BlockBean;
import com.besome.sketch.beans.ComponentBean;
import com.besome.sketch.beans.EventBean;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ViewBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import mod.agus.jcoderz.beans.ViewBeans;

import a.a.a.Fx;
import a.a.a.eC;
import a.a.a.jC;
import a.a.a.lC;
import a.a.a.jq;
import a.a.a.kq;
import a.a.a.lq;
import a.a.a.oq;
import a.a.a.xB;

/**
 * The {@code set_event_handler} engine: turns the model's short block
 * list into real {@link BlockBean}s and appends them to one event of
 * one activity, through the same data-manager calls the logic editor
 * itself uses.
 *
 * <p>Everything load-bearing comes from the app's own tables, read at
 * runtime: the parameter slots of every block ({@code lq.a}), the
 * canonical spec text ({@code xB}), the palette colour ({@code kq}),
 * the events a widget class raises ({@code oq}). The model-facing
 * vocabulary is deliberately small — statement blocks with literal
 * values, plus variable assignment/arithmetic and variable values
 * fed into text and messages (the one reporter shape the editor
 * stores: a {@code getVar} bean whose spec is the variable name,
 * referenced from a slot as "@id"), appended after whatever the
 * event already does. No control flow, no raw code.
 *
 * <p>Stored shape, from the editor's load path and the code
 * generator: an event's blocks are one flat list keyed by
 * {@code targetId + "_" + eventName}; the first bean is the chain
 * head; {@code nextBlock}/{@code subStack1}/{@code subStack2} link
 * by integer id; {@code Fx} starts generating at the head. New block
 * ids continue from the largest id already present, exactly how the
 * canvas counter advances.
 */
public final class AgentLogic {

    /** At most this many blocks per handler call. */
    public static final int MAX_BLOCKS = 8;

    /** Deepest container nesting in one handler. */
    private static final int MAX_DEPTH = 3;

    /** Every statement in one handler, nested ones included. */
    private static final int MAX_TOTAL = 24;

    /** Slot token separators, for finding editable values. */
    private static final java.util.regex.Pattern SLOT_PATTERN =
            java.util.regex.Pattern.compile("%[a-zA-Z]+(?:\\.[a-zA-Z]+)?");

    /** Widget kinds whose generated field has a setText method. */
    private static final List<String> TEXT_TYPES = Arrays.asList(
            "TextView", "Button", "MaterialButton", "EditText",
            "AutoCompleteTextView", "CheckBox", "RadioButton", "Switch");

    /**
     * One widget-action block from the View palette, taught from the
     * app's own tables (see docs/STOCK_BLOCK_KNOWLEDGE.md): the model
     * kind, the editor opCode, the widget family the palette requires
     * ("" = any widget), the argument slots after the widget slot
     * (d number, s text, b flag, c colour, r drawable name, g gravity
     * constant, m settings constant, l list-of-text name, w child
     * widget), a fragment the generated code must contain, and the
     * approval sentence ({t} = widget, {1}.. = argument values).
     */
    private static final class WA {
        final String opCode;
        final String widget;
        final String args;
        final String expect;
        final String sentence;

        WA(String opCode, String widget, String args, String expect,
           String sentence) {
            this.opCode = opCode;
            this.widget = widget;
            this.args = args;
            this.expect = expect;
            this.sentence = sentence;
        }
    }

    private static final Map<String, WA> WIDGET_ACTIONS = new HashMap<>();

    static {
        WIDGET_ACTIONS.put("set_lines", new WA("EditTextLines",
                "edittext", "d", ".setLines(",
                "Set {t} to show {1} lines"));
        WIDGET_ACTIONS.put("select_all_text", new WA(
                "EditTextSelectAll", "edittext", "", ".selectAll(",
                "Select all the text in {t}"));
        WIDGET_ACTIONS.put("set_max_lines", new WA("EditTextSetMaxLines",
                "edittext", "d", ".setMaxLines(",
                "Set {t} to show at most {1} lines"));
        WIDGET_ACTIONS.put("set_selection", new WA("EditTextSetSelection",
                "edittext", "dd", ".setSelection(",
                "Select text in {t} from {1} to {2}"));
        WIDGET_ACTIONS.put("show_error", new WA("EditTextShowError",
                "edittext", "s", ".setError(",
                "Show the error “{1}” on {t}"));
        WIDGET_ACTIONS.put("set_single_line", new WA("EditTextSingleLine",
                "edittext", "b", ".setSingleLine(",
                "Set {t} single-line to {1}"));
        WIDGET_ACTIONS.put("disable_suggestions", new WA(
                "EditTextdiableSuggestion", "edittext", "",
                ".setInputType(", "Turn off text suggestions in {t}"));
        WIDGET_ACTIONS.put("set_text_size", new WA("setTextSize",
                "textview", "d", ".setTextSize(",
                "Set {t} text size to {1}"));
        WIDGET_ACTIONS.put("set_hint_color", new WA("setHintTextColor",
                "edittext", "c", ".setHintTextColor(",
                "Set {t} hint colour to {1}"));
        WIDGET_ACTIONS.put("set_background_color", new WA("setBgColor",
                "", "c", ".setBackgroundColor(",
                "Set {t} background colour to {1}"));
        WIDGET_ACTIONS.put("set_background_image", new WA("setBgDrawable",
                "", "r", ".setBackgroundDrawable(",
                "Set {t} background image to {1}"));
        WIDGET_ACTIONS.put("set_background_resource", new WA(
                "setBgResource", "", "r", ".setBackgroundResource(",
                "Set {t} background to the image {1}"));
        WIDGET_ACTIONS.put("set_image_resource", new WA(
                "setImageCustomRes", "imageview", "r",
                ".setImageResource(", "Set {t} image to {1}"));
        WIDGET_ACTIONS.put("set_image_by_name", new WA(
                "setImageIdentifier", "imageview", "s",
                ".setImageResource(", "Set {t} image to the one "
                + "named “{1}”"));
        WIDGET_ACTIONS.put("set_image_file", new WA("setImageFilePath",
                "imageview", "s", ".setImageBitmap(",
                "Set {t} image from the file at “{1}”"));
        WIDGET_ACTIONS.put("set_color_filter", new WA("setColorFilter",
                "imageview", "c", ".setColorFilter(",
                "Tint {t} with {1}"));
        WIDGET_ACTIONS.put("set_elevation", new WA("setElevation",
                "", "d", ".setElevation(", "Set {t} elevation to {1}"));
        WIDGET_ACTIONS.put("set_alpha", new WA("setAlpha",
                "", "d", ".setAlpha(", "Set {t} opacity to {1}"));
        WIDGET_ACTIONS.put("set_rotation", new WA("setRotate",
                "", "d", ".setRotation(", "Rotate {t} to {1} degrees"));
        WIDGET_ACTIONS.put("set_scale_x", new WA("setScaleX",
                "", "d", ".setScaleX(", "Set {t} horizontal scale "
                + "to {1}"));
        WIDGET_ACTIONS.put("set_scale_y", new WA("setScaleY",
                "", "d", ".setScaleY(", "Set {t} vertical scale "
                + "to {1}"));
        WIDGET_ACTIONS.put("set_translation_x", new WA("setTranslationX",
                "", "d", ".setTranslationX(",
                "Move {t} horizontally by {1}"));
        WIDGET_ACTIONS.put("set_translation_y", new WA("setTranslationY",
                "", "d", ".setTranslationY(",
                "Move {t} vertically by {1}"));
        WIDGET_ACTIONS.put("set_corner_radius", new WA(
                "setCornerRadiusView", "", "dc",
                ".setBackground(new GradientDrawable()",
                "Give {t} rounded corners of {1} in {2}"));
        WIDGET_ACTIONS.put("set_stroke", new WA("setStrokeView",
                "", "dcc", ".setBackground(new GradientDrawable()",
                "Give {t} a {1} outline in {2} on {3}"));
        WIDGET_ACTIONS.put("set_gradient", new WA("setGradientBackground",
                "", "cc", "GradientDrawable.Orientation",
                "Give {t} a gradient background from {1} to {2}"));
        WIDGET_ACTIONS.put("set_radius_and_stroke", new WA(
                "setRadiusAndStrokeView", "", "ddcc",
                ".setBackground(new GradientDrawable()",
                "Give {t} corners of {1} and a {2} outline in {3} "
                + "on {4}"));
        WIDGET_ACTIONS.put("set_gravity", new WA("setGravity",
                "", "gg", ".setGravity(Gravity.",
                "Set {t} gravity to {1} and {2}"));
        WIDGET_ACTIONS.put("request_focus", new WA("requestFocus",
                "", "", ".requestFocus(", "Move the focus to {t}"));
        WIDGET_ACTIONS.put("perform_click", new WA("performClick",
                "", "", ".performClick(", "Tap {t} from code"));
        WIDGET_ACTIONS.put("remove_all_views", new WA("removeViews",
                "", "", ".removeAllViews(",
                "Remove everything inside {t}"));
        WIDGET_ACTIONS.put("add_view", new WA("addView",
                "", "w", ".addView(", "Put {1} inside {t}"));
        WIDGET_ACTIONS.put("add_view_at", new WA("addViews",
                "", "wd", ".addView(", "Put {1} inside {t} at "
                + "position {2}"));
        WIDGET_ACTIONS.put("remove_view", new WA("removeView",
                "", "w", ".removeView(", "Take {1} out of {t}"));
        WIDGET_ACTIONS.put("set_checked", new WA("setChecked",
                "checkbox", "b", ".setChecked(",
                "Set {t} checked to {1}"));
        WIDGET_ACTIONS.put("set_max", new WA("seekBarSetMax",
                "seekbar", "d", ".setMax(", "Set {t} maximum to {1}"));
        WIDGET_ACTIONS.put("set_indeterminate", new WA(
                "progressBarSetIndeterminate", "progressbar", "b",
                ".setIndeterminate(", "Set {t} indeterminate to {1}"));
        WIDGET_ACTIONS.put("set_num_stars", new WA("setNumStars",
                "ratingbar", "d", ".setNumStars(",
                "Set {t} to have {1} stars"));
        WIDGET_ACTIONS.put("set_rating", new WA("setRating",
                "ratingbar", "d", ".setRating(",
                "Set {t} rating to {1}"));
        WIDGET_ACTIONS.put("set_step_size", new WA("setStepSize",
                "ratingbar", "d", ".setStepSize(",
                "Set {t} rating step to {1}"));
        WIDGET_ACTIONS.put("play_video", new WA("videoviewStart",
                "videoview", "", ".start(", "Start the video in {t}"));
        WIDGET_ACTIONS.put("pause_video", new WA("videoviewPause",
                "videoview", "", ".pause(", "Pause the video in {t}"));
        WIDGET_ACTIONS.put("stop_video", new WA("videoviewStop",
                "videoview", "", ".stopPlayback(",
                "Stop the video in {t}"));
        WIDGET_ACTIONS.put("set_video_url", new WA("videoviewSetVideoUri",
                "videoview", "s", ".setVideoURI(",
                "Load the video at “{1}” into {t}"));
        WIDGET_ACTIONS.put("web_go_back", new WA("webViewGoBack",
                "webview", "", ".goBack(", "Go back in {t}"));
        WIDGET_ACTIONS.put("web_go_forward", new WA("webViewGoForward",
                "webview", "", ".goForward(", "Go forward in {t}"));
        WIDGET_ACTIONS.put("web_clear_cache", new WA("webViewClearCache",
                "webview", "", ".clearCache(", "Clear the cache of "
                + "{t}"));
        WIDGET_ACTIONS.put("web_clear_history", new WA(
                "webViewClearHistory", "webview", "", ".clearHistory(",
                "Clear the history of {t}"));
        WIDGET_ACTIONS.put("web_stop_loading", new WA("webViewStopLoading",
                "webview", "", ".stopLoading(", "Stop {t} loading"));
        WIDGET_ACTIONS.put("web_zoom_in", new WA("webViewZoomIn",
                "webview", "", ".zoomIn(", "Zoom in on {t}"));
        WIDGET_ACTIONS.put("web_zoom_out", new WA("webViewZoomOut",
                "webview", "", ".zoomOut(", "Zoom out on {t}"));
        WIDGET_ACTIONS.put("set_cache_mode", new WA("webViewSetCacheMode",
                "webview", "m", "WebSettings.",
                "Set {t} cache mode to {1}"));
        WIDGET_ACTIONS.put("set_autocomplete_data", new WA("autoComSetData",
                "actv", "l", ".setAdapter(new ArrayAdapter",
                "Fill {t} suggestions from list “{1}”"));
        WIDGET_ACTIONS.put("set_multiautocomplete_data", new WA(
                "multiAutoComSetData", "mactv", "l",
                ".setAdapter(new ArrayAdapter",
                "Fill {t} suggestions from list “{1}”"));
        WIDGET_ACTIONS.put("set_threshold", new WA("setThreshold",
                "mactv", "d", ".setThreshold(",
                "Set {t} to suggest after {1} characters"));
        WIDGET_ACTIONS.put("set_tokenizer", new WA("setTokenizer",
                "mactv", "", ".setTokenizer(",
                "Set {t} to separate entries with commas"));
        // Widget-gated families (wave 9): each only offered when the
        // matching widget is on the screen, as in the palette.
        WIDGET_ACTIONS.put("refresh_list", new WA("listRefresh",
                "listview", "", ".getAdapter()).notifyDataSetChanged(",
                "Refresh the rows of {t}"));
        WIDGET_ACTIONS.put("refresh_list_views", new WA("refreshingList",
                "listview", "", ".invalidateViews(",
                "Redraw the rows of {t}"));
        WIDGET_ACTIONS.put("list_smooth_scroll", new WA(
                "listSmoothScrollTo", "listview", "d",
                ".smoothScrollToPosition(", "Scroll {t} smoothly to "
                + "item {1}"));
        WIDGET_ACTIONS.put("list_set_selection", new WA(
                "listViewSetSelection", "listview", "d", ".setSelection(",
                "Select item {1} in {t}"));
        WIDGET_ACTIONS.put("list_stack_from_bottom", new WA(
                "listSetStackFromBottom", "listview", "b",
                ".setStackFromBottom(", "Set {t} to stack from the "
                + "bottom: {1}"));
        WIDGET_ACTIONS.put("list_transcript_mode", new WA(
                "listSetTranscriptMode", "listview", "m",
                ".setTranscriptMode(ListView.",
                "Set {t} transcript mode to {1}"));
        WIDGET_ACTIONS.put("add_header", new WA("ListViewAddHeader",
                "listview", "wsb", ".addHeaderView(",
                "Add {1} as a header of {t}"));
        WIDGET_ACTIONS.put("add_footer", new WA("ListViewAddFooter",
                "listview", "wsb", ".addFooterView(",
                "Add {1} as a footer of {t}"));
        WIDGET_ACTIONS.put("remove_header", new WA("listViewRemoveHeader",
                "listview", "w", ".removeHeaderView(",
                "Remove the header {1} from {t}"));
        WIDGET_ACTIONS.put("remove_footer", new WA("listViewRemoveFooter",
                "listview", "w", ".removeFooterView(",
                "Remove the footer {1} from {t}"));
        WIDGET_ACTIONS.put("set_spinner_data", new WA("spnSetData",
                "spinner", "l", ".setAdapter(new ArrayAdapter",
                "Fill {t} choices from list “{1}”"));
        WIDGET_ACTIONS.put("refresh_spinner", new WA("spnRefresh",
                "spinner", "", ".getAdapter()).notifyDataSetChanged(",
                "Refresh the choices of {t}"));
        WIDGET_ACTIONS.put("set_spinner_selection", new WA(
                "spnSetSelection", "spinner", "d", ".setSelection(",
                "Select item {1} in {t}"));
        WIDGET_ACTIONS.put("set_pager_page", new WA("pagerSetCurrentItem",
                "viewpager", "d", ".setCurrentItem(",
                "Show page {1} in {t}"));
        WIDGET_ACTIONS.put("set_pager_limit", new WA(
                "pagerSetOffscreenPageLimit", "viewpager", "d",
                ".setOffscreenPageLimit(", "Set {t} to keep {1} pages "
                + "alive on each side"));
        WIDGET_ACTIONS.put("notify_pager_changed", new WA(
                "ViewPagerNotifyOnDtatChange", "viewpager", "",
                ".notifyDataSetChanged(", "Tell {t} its pages "
                + "changed"));
        WIDGET_ACTIONS.put("recycler_layout", new WA(
                "recyclerSetLayoutManager", "recyclerview", "",
                ".setLayoutManager(new LinearLayoutManager(this));",
                "Give {t} a vertical scrolling layout"));
        WIDGET_ACTIONS.put("recycler_layout_horizontal", new WA(
                "recyclerSetLayoutManagerHorizontal", "recyclerview",
                "", ".setLayoutManager(new LinearLayoutManager(this,",
                "Give {t} a horizontal scrolling layout"));
        WIDGET_ACTIONS.put("recycler_fixed_size", new WA(
                "recyclerSetHasFixedSize", "recyclerview", "b",
                ".setHasFixedSize(", "Set {t} fixed size to {1}"));
        WIDGET_ACTIONS.put("recycler_scroll_to", new WA(
                "recyclerSmoothScrollToPosition", "recyclerview", "d",
                ".smoothScrollToPosition(", "Scroll {t} smoothly to "
                + "item {1}"));
        WIDGET_ACTIONS.put("recycler_scroll_offset", new WA(
                "recyclerScrollToPositionWithOffset", "recyclerview",
                "dd", ".scrollToPositionWithOffset(",
                "Scroll {t} to item {1} with offset {2}"));
        WIDGET_ACTIONS.put("grid_columns", new WA("gridSetNumColumns",
                "gridview", "d", ".setNumColumns(",
                "Set {t} to {1} columns"));
        WIDGET_ACTIONS.put("grid_column_width", new WA(
                "gridSetColumnWidth", "gridview", "d",
                ".setColumnWidth(", "Set {t} column width to {1}"));
        WIDGET_ACTIONS.put("grid_spacing_h", new WA(
                "gridSetHorizontalSpacing", "gridview", "d",
                ".setHorizontalSpacing(", "Set {t} horizontal "
                + "spacing to {1}"));
        WIDGET_ACTIONS.put("grid_spacing_v", new WA(
                "gridSetVerticalSpacing", "gridview", "d",
                ".setVerticalSpacing(", "Set {t} vertical spacing "
                + "to {1}"));
        WIDGET_ACTIONS.put("grid_stretch_mode", new WA(
                "gridSetStretchMode", "gridview", "m",
                ".setStretchMode(GridView.", "Set {t} stretch mode "
                + "to {1}"));
        WIDGET_ACTIONS.put("add_tab", new WA("addTab",
                "tablayout", "s", ".addTab(",
                "Add a tab titled “{1}” to {t}"));
        WIDGET_ACTIONS.put("set_tab_colors", new WA("setTabTextColors",
                "tablayout", "cc", ".setTabTextColors(",
                "Set {t} tab text colours to {1} and {2}"));
        WIDGET_ACTIONS.put("set_tab_indicator_color", new WA(
                "setSelectedTabIndicatorColor", "tablayout", "c",
                ".setSelectedTabIndicatorColor(",
                "Set {t} selected-tab line colour to {1}"));
        WIDGET_ACTIONS.put("set_tab_indicator_height", new WA(
                "setSelectedTabIndicatorHeight", "tablayout", "d",
                ".setSelectedTabIndicatorHeight(",
                "Set {t} selected-tab line height to {1}"));
        WIDGET_ACTIONS.put("set_tab_ripple_color", new WA(
                "setTabRippleColor", "tablayout", "c",
                ".setTabRippleColor(", "Set {t} tab ripple colour "
                + "to {1}"));
        WIDGET_ACTIONS.put("set_inline_label", new WA("setInlineLabel",
                "tablayout", "b", ".setInlineLabel(",
                "Set {t} labels inline: {1}"));
        WIDGET_ACTIONS.put("setup_with_pager", new WA("setupWithViewPager",
                "tablayout", "w", ".setupWithViewPager(",
                "Link {t} with the pager {1}"));
        WIDGET_ACTIONS.put("set_card_color", new WA(
                "setCardBackgroundColor", "cardview", "c",
                ".setCardBackgroundColor(", "Set {t} card colour "
                + "to {1}"));
        WIDGET_ACTIONS.put("set_card_elevation", new WA("setCardElevation",
                "cardview", "d", ".setCardElevation(",
                "Set {t} card elevation to {1}"));
        WIDGET_ACTIONS.put("set_card_radius", new WA("setCardRadius",
                "cardview", "d", ".setRadius(",
                "Set {t} corner radius to {1}"));
        WIDGET_ACTIONS.put("set_prevent_overlap", new WA(
                "setPreventCornerOverlap", "cardview", "b",
                ".setPreventCornerOverlap(",
                "Set {t} corner-overlap prevention to {1}"));
        WIDGET_ACTIONS.put("set_compat_padding", new WA(
                "setUseCompatPadding", "cardview", "b",
                ".setUseCompatPadding(", "Set {t} compat padding "
                + "to {1}"));
        WIDGET_ACTIONS.put("set_picker_hour", new WA("timepickerSetHour",
                "timepicker", "d", ".setHour(",
                "Set {t} hour to {1}"));
        WIDGET_ACTIONS.put("set_picker_minute", new WA(
                "timepickerSetMinute", "timepicker", "d", ".setMinute(",
                "Set {t} minute to {1}"));
        WIDGET_ACTIONS.put("set_24hour", new WA("timepickerSetIs24Hour",
                "timepicker", "b", ".setIs24HourView(",
                "Set {t} to 24-hour view: {1}"));
        WIDGET_ACTIONS.put("set_calendar_date", new WA(
                "calendarViewSetDate", "calendarview", "d", ".setDate(",
                "Set {t} to the date at {1} milliseconds"));
        WIDGET_ACTIONS.put("set_calendar_min_date", new WA(
                "calendarViewSetMinDate", "calendarview", "d",
                ".setMinDate(", "Set {t} earliest date to {1} "
                + "milliseconds"));
        WIDGET_ACTIONS.put("set_calendar_max_date", new WA(
                "calnedarViewSetMaxDate", "calendarview", "d",
                ".setMaxDate(", "Set {t} latest date to {1} "
                + "milliseconds"));
        WIDGET_ACTIONS.put("til_box_color", new WA("tilSetBoxBgColor",
                "textinputlayout", "c", ".setBoxBackgroundColor(",
                "Set {t} box colour to {1}"));
        WIDGET_ACTIONS.put("til_box_stroke", new WA(
                "tilSetBoxStrokeColor", "textinputlayout", "c",
                ".setBoxStrokeColor(", "Set {t} box outline colour "
                + "to {1}"));
        WIDGET_ACTIONS.put("til_box_mode", new WA("tilSetBoxBgMode",
                "textinputlayout", "m",
                ".setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_",
                "Set {t} box style to {1}"));
        WIDGET_ACTIONS.put("til_corner_radii", new WA(
                "tilSetBoxCornerRadii", "textinputlayout", "dddd",
                ".setBoxCornerRadii(", "Set {t} box corners to {1}, "
                + "{2}, {3}, {4}"));
        WIDGET_ACTIONS.put("til_counter_enabled", new WA(
                "tilSetCounterEnabled", "textinputlayout", "b",
                ".setCounterEnabled(", "Set {t} character counter "
                + "to {1}"));
        WIDGET_ACTIONS.put("til_counter_max", new WA(
                "tilSetCounterMaxLength", "textinputlayout", "d",
                ".setCounterMaxLength(", "Set {t} character limit "
                + "to {1}"));
        WIDGET_ACTIONS.put("til_set_error", new WA("tilSetError",
                "textinputlayout", "s", ".setError(",
                "Show the error “{1}” on {t}"));
        WIDGET_ACTIONS.put("til_error_enabled", new WA(
                "tilSetErrorEnabled", "textinputlayout", "b",
                ".setErrorEnabled(", "Set {t} error display to {1}"));
        WIDGET_ACTIONS.put("set_badge_number", new WA("setBadgeNumber",
                "badgeview", "d", ".setBadgeCount(",
                "Set {t} badge number to {1}"));
        WIDGET_ACTIONS.put("set_badge_text", new WA("setBadgeString",
                "badgeview", "s", ".setBadgeCount(",
                "Set {t} badge text to “{1}”"));
        WIDGET_ACTIONS.put("set_badge_background", new WA(
                "setBadgeBackground", "badgeview", "c",
                ".setBadgeBackground(", "Set {t} badge colour to {1}"));
        WIDGET_ACTIONS.put("set_badge_text_color", new WA(
                "setBadgeTextColor", "badgeview", "c", ".setTextColor(",
                "Set {t} badge text colour to {1}"));
        WIDGET_ACTIONS.put("set_badge_text_size", new WA(
                "setBadgeTextSize", "badgeview", "d", ".setTextSize(",
                "Set {t} badge text size to {1}"));
        WIDGET_ACTIONS.put("clear_pattern", new WA("patternLockClear",
                "patternview", "", ".clearPattern(",
                "Clear the pattern on {t}"));
        WIDGET_ACTIONS.put("pattern_dot_count", new WA(
                "patternSetDotCount", "patternview", "d",
                ".setDotCount(", "Set {t} to {1} dots per side"));
        WIDGET_ACTIONS.put("pattern_correct_color", new WA(
                "patternSetCorrectStateColor", "patternview", "c",
                ".setCorrectStateColor(", "Set {t} correct-pattern "
                + "colour to {1}"));
        WIDGET_ACTIONS.put("pattern_normal_color", new WA(
                "patternSetNormalStateColor", "patternview", "c",
                ".setNormalStateColor(", "Set {t} pattern colour "
                + "to {1}"));
        WIDGET_ACTIONS.put("pattern_wrong_color", new WA(
                "patternSetWrongStateColor", "patternview", "c",
                ".setWrongStateColor(", "Set {t} wrong-pattern "
                + "colour to {1}"));
        WIDGET_ACTIONS.put("pattern_view_mode", new WA(
                "patternSetViewMode", "patternview", "m",
                ".setViewMode(PatternLockView.PatternViewMode.",
                "Set {t} pattern mode to {1}"));
        WIDGET_ACTIONS.put("set_otp_text", new WA("otpViewSetOTPText",
                "otpview", "s", ".setOTPText(",
                "Set the code in {t} to “{1}”"));
        WIDGET_ACTIONS.put("set_otp_field_count", new WA(
                "otpViewSetFieldCount", "otpview", "d",
                ".setFieldCount(", "Set {t} to {1} code boxes"));
        WIDGET_ACTIONS.put("set_lottie_asset", new WA(
                "lottieSetAnimationFromAsset", "lottie", "s",
                ".setAnimation(", "Play the animation file “{1}” "
                + "in {t}"));
        WIDGET_ACTIONS.put("set_lottie_json", new WA(
                "lottieSetAnimationFromJson", "lottie", "s",
                ".setAnimationFromJson(", "Play an animation from "
                + "JSON in {t}"));
        WIDGET_ACTIONS.put("set_lottie_url", new WA(
                "lottieSetAnimationFromUrl", "lottie", "s",
                ".setAnimationFromUrl(", "Play the animation at "
                + "“{1}” in {t}"));
        WIDGET_ACTIONS.put("set_lottie_repeat", new WA(
                "lottieSetRepeatCount", "lottie", "d",
                ".setRepeatCount(", "Set {t} to repeat {1} times"));
        WIDGET_ACTIONS.put("set_lottie_speed", new WA(
                "lottieSetSpeed", "lottie", "d", ".setSpeed(",
                "Set {t} animation speed to {1}"));
        WIDGET_ACTIONS.put("set_code_text", new WA("codeviewSetCode",
                "codeview", "s", ".setCode(",
                "Put code into {t}"));
        WIDGET_ACTIONS.put("apply_code", new WA("codeviewApply",
                "codeview", "", ".apply();", "Apply the code in {t}"));
        WIDGET_ACTIONS.put("set_code_language", new WA(
                "codeviewSetLanguage", "codeview", "m",
                ".setLanguage(Language.", "Set {t} language to {1}"));
        WIDGET_ACTIONS.put("set_code_theme", new WA("codeviewSetTheme",
                "codeview", "m", ".setTheme(Theme.",
                "Set {t} theme to {1}"));
        WIDGET_ACTIONS.put("map_zoom_in", new WA("mapViewZoomIn",
                "mapview", "", "_controller.zoomIn();",
                "Zoom in on {t}"));
        WIDGET_ACTIONS.put("map_zoom_out", new WA("mapViewZoomOut",
                "mapview", "", "_controller.zoomOut();",
                "Zoom out on {t}"));
        WIDGET_ACTIONS.put("map_zoom_to", new WA("mapViewZoomTo",
                "mapview", "d", "_controller.zoomTo(",
                "Zoom {t} to level {1}"));
        WIDGET_ACTIONS.put("map_move_camera", new WA("mapViewMoveCamera",
                "mapview", "dd", "_controller.moveCamera(",
                "Move {t} camera to {1}, {2}"));
        WIDGET_ACTIONS.put("map_add_marker", new WA("mapViewAddMarker",
                "mapview", "sdd", "_controller.addMarker(",
                "Add a marker titled “{1}” at {2}, {3} on {t}"));
        WIDGET_ACTIONS.put("map_set_type", new WA("mapViewSetMapType",
                "mapview", "m", "_controller.setMapType(GoogleMap.",
                "Set {t} map type to {1}"));
        WIDGET_ACTIONS.put("map_marker_position", new WA(
                "mapViewSetMarkerPosition", "mapview", "sdd",
                "_controller.setMarkerPosition(",
                "Move the marker “{1}” to {2}, {3} on {t}"));
        WIDGET_ACTIONS.put("map_marker_visible", new WA(
                "mapViewSetMarkerVisible", "mapview", "sb",
                "_controller.setMarkerVisible(",
                "Set the marker “{1}” visible to {2} on {t}"));
        WIDGET_ACTIONS.put("map_marker_info", new WA(
                "mapViewSetMarkerInfo", "mapview", "sss",
                "_controller.setMarkerInfo(",
                "Set the marker “{1}” info to “{2}” / “{3}” on {t}"));
        WIDGET_ACTIONS.put("set_refreshing", new WA("setRefreshing",
                "swiperefresh", "b", ".setRefreshing(",
                "Set {t} refreshing to {1}"));
        WIDGET_ACTIONS.put("bottom_add_item", new WA("bottomMenuAddItem",
                "bottomnavigation", "dsr", ".getMenu().add(",
                "Add the item “{2}” to {t}"));
        // Wave 11 widget leftovers, Fx-verified like the rest.
        WIDGET_ACTIONS.put("map_marker_color", new WA(
                "mapViewSetMarkerColor", "mapview", "smd",
                "_controller.setMarkerColor(",
                "Set marker “{1}” colour on {t} to {2}"));
        WIDGET_ACTIONS.put("map_marker_icon", new WA(
                "mapViewSetMarkerIcon", "mapview", "sr",
                "_controller.setMarkerIcon(",
                "Set marker “{1}” icon on {t} to “{2}”"));
        WIDGET_ACTIONS.put("bubble_arrow_height", new WA(
                "setBubbleArrowHeight", "bubblelayout", "d",
                ".setArrowHeight(", "Set {t} arrow height to {1}"));
        WIDGET_ACTIONS.put("bubble_arrow_position", new WA(
                "setBubbleArrowPosition", "bubblelayout", "d",
                ".setArrowPosition(",
                "Set {t} arrow position to {1}"));
        WIDGET_ACTIONS.put("bubble_arrow_width", new WA(
                "setBubbleArrowWidth", "bubblelayout", "d",
                ".setArrowWidth(", "Set {t} arrow width to {1}"));
        WIDGET_ACTIONS.put("bubble_color", new WA("setBubbleColor",
                "bubblelayout", "c", ".setBubbleColor(",
                "Set {t} bubble colour to {1}"));
        WIDGET_ACTIONS.put("bubble_corner_radius", new WA(
                "setBubbleCornerRadius", "bubblelayout", "d",
                ".setCornersRadius(",
                "Set {t} corner radius to {1}"));
        WIDGET_ACTIONS.put("bubble_stroke_color", new WA(
                "setBubbleStrokeColor", "bubblelayout", "c",
                ".setStrokeColor(", "Set {t} outline colour to {1}"));
        WIDGET_ACTIONS.put("bubble_stroke_width", new WA(
                "setBubbleStrokeWidth", "bubblelayout", "d",
                ".setStrokeWidth(", "Set {t} outline width to {1}"));
        WIDGET_ACTIONS.put("signin_color_scheme", new WA(
                "signInButtonSetColorScheme", "signinbutton", "m",
                ".setColorScheme(SignInButton.",
                "Set {t} colour scheme to {1}"));
        WIDGET_ACTIONS.put("signin_size", new WA(
                "signInButtonSetSize", "signinbutton", "m",
                ".setSize(SignInButton.", "Set {t} size to {1}"));
        WIDGET_ACTIONS.put("banner_load_ad", new WA(
                "bannerAdViewLoadAd", "adview", "", ".loadAd(",
                "Load an ad into {t}"));
        WIDGET_ACTIONS.put("set_background_color_filter", new WA(
                "setColorFilterView", "", "cm",
                ".getBackground().setColorFilter(",
                "Tint {t}'s background {1} ({2})"));
    }

    /**
     * One component-action block (wave 10), taught the same way:
     * model kind, editor opCode, the required ComponentBean type
     * (0 = the block takes no component), the argument slots after
     * the component slot (WA codes, plus v = a map variable and
     * A = this activity's class name), an expect fragment, and the
     * sentence ({c} = the component).
     */
    private static final class CA {
        final String opCode;
        final int componentType;
        final String args;
        final String expect;
        final String sentence;
        /** Allowed stored values for an 'm'/'M'/'F' slot, or null. */
        final String[] modes;

        CA(String opCode, int componentType, String args, String expect,
           String sentence) {
            this(opCode, componentType, args, expect, sentence, null);
        }

        CA(String opCode, int componentType, String args, String expect,
           String sentence, String[] modes) {
            this.opCode = opCode;
            this.componentType = componentType;
            this.args = args;
            this.expect = expect;
            this.sentence = sentence;
            this.modes = modes;
        }
    }

    private static final Map<String, CA> COMPONENT_ACTIONS =
            new HashMap<>();

    static {
        int intent = ComponentBean.COMPONENT_TYPE_INTENT;
        int prefs = ComponentBean.COMPONENT_TYPE_SHAREDPREF;
        int calendar = ComponentBean.COMPONENT_TYPE_CALENDAR;
        int vibrator = ComponentBean.COMPONENT_TYPE_VIBRATOR;
        int timer = ComponentBean.COMPONENT_TYPE_TIMERTASK;
        int firebase = ComponentBean.COMPONENT_TYPE_FIREBASE;
        int dialog = ComponentBean.COMPONENT_TYPE_DIALOG;
        int media = ComponentBean.COMPONENT_TYPE_MEDIAPLAYER;
        int pool = ComponentBean.COMPONENT_TYPE_SOUNDPOOL;
        int animator = ComponentBean.COMPONENT_TYPE_OBJECTANIMATOR;
        int gyro = ComponentBean.COMPONENT_TYPE_GYROSCOPE;
        int ad = ComponentBean.COMPONENT_TYPE_INTERSTITIAL_AD;
        int camera = ComponentBean.COMPONENT_TYPE_CAMERA;
        int picker = ComponentBean.COMPONENT_TYPE_FILE_PICKER;
        int network = ComponentBean.COMPONENT_TYPE_REQUEST_NETWORK;
        int tts = ComponentBean.COMPONENT_TYPE_TEXT_TO_SPEECH;
        int stt = ComponentBean.COMPONENT_TYPE_SPEECH_TO_TEXT;
        int bluetooth = ComponentBean.COMPONENT_TYPE_BLUETOOTH_CONNECT;
        int location = ComponentBean.COMPONENT_TYPE_LOCATION_MANAGER;
        int progress = ComponentBean.COMPONENT_TYPE_PROGRESS_DIALOG;

        COMPONENT_ACTIONS.put("set_intent_action", new CA(
                "intentSetAction", intent, "s", ".setAction(",
                "Set {c} action to “{1}”"));
        COMPONENT_ACTIONS.put("set_intent_data", new CA("intentSetData",
                intent, "s", ".setData(Uri.parse(",
                "Set {c} data to “{1}”"));
        COMPONENT_ACTIONS.put("set_intent_type", new CA("intentSetType",
                intent, "s", ".setType(", "Set {c} type to “{1}”"));
        COMPONENT_ACTIONS.put("put_extra", new CA("intentPutExtra",
                intent, "ss", ".putExtra(",
                "Put “{1}” = “{2}” into {c}"));
        COMPONENT_ACTIONS.put("remove_extra", new CA("intentRemoveExtra",
                intent, "s", ".removeExtra(",
                "Remove “{1}” from {c}"));
        COMPONENT_ACTIONS.put("launch_app", new CA("launchApp",
                intent, "s", ".getLaunchIntentForPackage(",
                "Point {c} at the app “{1}”"));
        COMPONENT_ACTIONS.put("start_with_chooser", new CA(
                "startActivityWithChooser", intent, "s",
                "Intent.createChooser(", "Open {c} with a chooser "
                + "titled “{1}”"));
        COMPONENT_ACTIONS.put("save_pref", new CA("fileSetData",
                prefs, "ss", ".edit().putString(",
                "In {c}, save “{2}” under “{1}”"));
        COMPONENT_ACTIONS.put("remove_pref", new CA("fileRemoveData",
                prefs, "s", ".edit().remove(",
                "In {c}, remove “{1}”"));
        COMPONENT_ACTIONS.put("calendar_now", new CA("calendarGetNow",
                calendar, "", " = Calendar.getInstance();",
                "Set {c} to now"));
        COMPONENT_ACTIONS.put("calendar_add", new CA("calendarAdd",
                calendar, "md", ".add(Calendar.",
                "Add {2} {1} to {c}"));
        COMPONENT_ACTIONS.put("calendar_set", new CA("calendarSet",
                calendar, "md", ".set(Calendar.",
                "Set {c}'s {1} to {2}"));
        COMPONENT_ACTIONS.put("calendar_set_time", new CA(
                "calendarSetTime", calendar, "d", ".setTimeInMillis(",
                "Set {c} to {1} milliseconds"));
        COMPONENT_ACTIONS.put("vibrate", new CA("vibratorAction",
                vibrator, "d", ".vibrate(", "Vibrate for {1} "
                + "milliseconds"));
        COMPONENT_ACTIONS.put("timer_cancel", new CA("timerCancel",
                timer, "", ".cancel();", "Cancel {c}"));
        COMPONENT_ACTIONS.put("dialog_title", new CA("dialogSetTitle",
                dialog, "s", ".setTitle(", "Set {c} title to “{1}”"));
        COMPONENT_ACTIONS.put("dialog_message", new CA("dialogSetMessage",
                dialog, "s", ".setMessage(",
                "Set {c} message to “{1}”"));
        COMPONENT_ACTIONS.put("dialog_show", new CA("dialogShow",
                dialog, "", ".create().show();", "Show {c}"));
        COMPONENT_ACTIONS.put("mp_create", new CA("mediaplayerCreate",
                media, "R", " = MediaPlayer.create(",
                "Load the sound “{1}” into {c}"));
        COMPONENT_ACTIONS.put("mp_start", new CA("mediaplayerStart",
                media, "", ".start();", "Play {c}"));
        COMPONENT_ACTIONS.put("mp_pause", new CA("mediaplayerPause",
                media, "", ".pause();", "Pause {c}"));
        COMPONENT_ACTIONS.put("mp_release", new CA("mediaplayerRelease",
                media, "", ".release();", "Release {c}"));
        COMPONENT_ACTIONS.put("mp_reset", new CA("mediaplayerReset",
                media, "", ".reset();", "Reset {c}"));
        COMPONENT_ACTIONS.put("mp_seek", new CA("mediaplayerSeek",
                media, "d", ".seekTo(", "Move {c} to {1} "
                + "milliseconds"));
        COMPONENT_ACTIONS.put("mp_looping", new CA("mediaplayerSetLooping",
                media, "b", ".setLooping(", "Set {c} looping to {1}"));
        COMPONENT_ACTIONS.put("sp_create", new CA("soundpoolCreate",
                pool, "d", " = new SoundPool(",
                "Set up {c} for {1} sounds at once"));
        COMPONENT_ACTIONS.put("sp_stop", new CA("soundpoolStreamStop",
                pool, "d", ".stop((int)(", "Stop sound {1} in {c}"));
        COMPONENT_ACTIONS.put("anim_start", new CA("objectanimatorStart",
                animator, "", ".start();", "Start {c}"));
        COMPONENT_ACTIONS.put("anim_cancel", new CA("objectanimatorCancel",
                animator, "", ".cancel();", "Cancel {c}"));
        COMPONENT_ACTIONS.put("anim_duration", new CA(
                "objectanimatorSetDuration", animator, "d",
                ".setDuration(", "Set {c} to run for {1} "
                + "milliseconds"));
        COMPONENT_ACTIONS.put("anim_repeat", new CA(
                "objectanimatorSetRepeatCount", animator, "d",
                ".setRepeatCount(", "Set {c} to repeat {1} times"));
        COMPONENT_ACTIONS.put("anim_from_to", new CA(
                "objectanimatorSetFromTo", animator, "dd",
                ".setFloatValues(", "Set {c} values from {1} to {2}"));
        COMPONENT_ACTIONS.put("anim_target", new CA(
                "objectanimatorSetTarget", animator, "w", ".setTarget(",
                "Set {c} to animate {1}"));
        COMPONENT_ACTIONS.put("anim_property", new CA(
                "objectanimatorSetProperty", animator, "s",
                ".setPropertyName(", "Set {c} to animate the “{1}” "
                + "property"));
        COMPONENT_ACTIONS.put("gyro_start", new CA("gyroscopeStartListen",
                gyro, "", ".registerListener(", "Start listening "
                + "to {c}"));
        COMPONENT_ACTIONS.put("gyro_stop", new CA("gyroscopeStopListen",
                gyro, "", ".unregisterListener(", "Stop listening "
                + "to {c}"));
        COMPONENT_ACTIONS.put("fb_set", new CA("firebaseAdd",
                firebase, "sv", ".child(",
                "In {c} at “{1}”, save the map {2}"));
        COMPONENT_ACTIONS.put("fb_delete", new CA("firebaseDelete",
                firebase, "s", ".child(", "In {c}, delete “{1}”"));
        COMPONENT_ACTIONS.put("fb_push", new CA("firebasePush",
                firebase, "v", ".push().updateChildren(",
                "Push the map {1} into {c}"));
        COMPONENT_ACTIONS.put("fb_listen", new CA("firebaseStartListen",
                firebase, "", ".addChildEventListener(",
                "Start listening to {c}"));
        COMPONENT_ACTIONS.put("fb_stop_listen", new CA(
                "firebaseStopListen", firebase, "",
                ".removeEventListener(", "Stop listening to {c}"));
        COMPONENT_ACTIONS.put("ad_load", new CA("interstitialAdLoad",
                ad, "A", "InterstitialAd.load(",
                "Load an ad into {c}"));
        COMPONENT_ACTIONS.put("ad_show", new CA("interstitialAdShow",
                ad, "A", ".show(", "Show the ad in {c}"));
        COMPONENT_ACTIONS.put("take_picture", new CA(
                "camerastarttakepicture", camera, "",
                "startActivityForResult(", "Take a picture with {c}"));
        COMPONENT_ACTIONS.put("pick_file", new CA(
                "filepickerstartpickfiles", picker, "",
                "startActivityForResult(", "Pick a file with {c}"));
        COMPONENT_ACTIONS.put("crop_image", new CA("imageCrop",
                0, "sd", "SketchwareUtil.CropImage(",
                "Crop the image at “{1}” to {2}"));
        COMPONENT_ACTIONS.put("net_headers", new CA(
                "requestnetworkSetHeaders", network, "v", ".setHeaders(",
                "Set {c} headers from the map {1}"));
        COMPONENT_ACTIONS.put("net_start", new CA(
                "requestnetworkStartRequestNetwork", network, "mss",
                ".startRequestNetwork(", "With {c}, send a {1} "
                + "request to “{2}” tagged “{3}”"));
        COMPONENT_ACTIONS.put("tts_speak", new CA("textToSpeechSpeak",
                tts, "s", ".speak(", "Speak “{1}” with {c}"));
        COMPONENT_ACTIONS.put("tts_pitch", new CA("textToSpeechSetPitch",
                tts, "d", ".setPitch(", "Set {c} pitch to {1}"));
        COMPONENT_ACTIONS.put("tts_rate", new CA("textToSpeechSetSpeechRate",
                tts, "d", ".setSpeechRate(", "Set {c} speed to {1}"));
        COMPONENT_ACTIONS.put("tts_stop", new CA("textToSpeechStop",
                tts, "", ".stop();", "Stop {c} speaking"));
        COMPONENT_ACTIONS.put("tts_shutdown", new CA("textToSpeechShutdown",
                tts, "", ".shutdown();", "Shut down {c}"));
        COMPONENT_ACTIONS.put("stt_start", new CA(
                "speechToTextStartListening", stt, "",
                ".startListening(_intent);", "Start listening "
                + "with {c}"));
        COMPONENT_ACTIONS.put("stt_stop", new CA(
                "speechToTextStopListening", stt, "",
                ".stopListening(", "Stop listening with {c}"));
        COMPONENT_ACTIONS.put("stt_shutdown", new CA(
                "speechToTextShutdown", stt, "", ".shutdown();",
                "Shut down {c}"));
        COMPONENT_ACTIONS.put("bt_enable", new CA(
                "bluetoothConnectActivateBluetooth", bluetooth, "",
                ".activateBluetooth();", "Turn on Bluetooth via {c}"));
        COMPONENT_ACTIONS.put("loc_request", new CA(
                "locationManagerRequestLocationUpdates", location,
                "mdd", ".requestLocationUpdates(",
                "Ask {c} for {1} updates every {2} ms / {3} m"));
        COMPONENT_ACTIONS.put("loc_remove", new CA(
                "locationManagerRemoveUpdates", location, "",
                ".removeUpdates(_", "Stop location updates from {c}"));
        COMPONENT_ACTIONS.put("progress_create", new CA(
                "progressdialogCreate", progress, "A",
                " = new ProgressDialog(", "Create {c}"));
        COMPONENT_ACTIONS.put("progress_show", new CA(
                "progressdialogShow", progress, "", ".show();",
                "Show {c}"));
        COMPONENT_ACTIONS.put("progress_dismiss", new CA(
                "progressdialogDismiss", progress, "", ".dismiss();",
                "Dismiss {c}"));
        COMPONENT_ACTIONS.put("progress_message", new CA(
                "progressdialogSetMessage", progress, "s",
                ".setMessage(", "Set {c} message to “{1}”"));
        COMPONENT_ACTIONS.put("progress_title", new CA(
                "progressdialogSetTitle", progress, "s", ".setTitle(",
                "Set {c} title to “{1}”"));
        COMPONENT_ACTIONS.put("progress_value", new CA(
                "progressdialogSetProgress", progress, "d",
                ".setProgress(", "Set {c} progress to {1}"));
        COMPONENT_ACTIONS.put("progress_max", new CA(
                "progressdialogSetMax", progress, "d", ".setMax(",
                "Set {c} maximum to {1}"));
        COMPONENT_ACTIONS.put("progress_cancelable", new CA(
                "progressdialogSetCancelable", progress, "b",
                ".setCancelable(", "Set {c} cancelable to {1}"));
        COMPONENT_ACTIONS.put("progress_outside", new CA(
                "progressdialogSetCanceledOutside", progress, "b",
                ".setCanceledOnTouchOutside(",
                "Set {c} to cancel on outside tap: {1}"));
        COMPONENT_ACTIONS.put("progress_style", new CA(
                "progressdialogSetStyle", progress, "m",
                ".setProgressStyle(ProgressDialog.",
                "Set {c} style to {1}"));

        // Wave 11: the verified leftovers. Every emission, stored
        // picker value and field dependency was read out of Fx, Lx,
        // BlocksHandler and uq before teaching (see the knowledge
        // doc's Wave 11 research notes). (This comment also serves
        // as the app-path change that re-triggers Android CI after
        // a transient runner failure; the bootstrap workflow's
        // build of the same code was already green.)
        int auth = ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH;
        int storage = ComponentBean.COMPONENT_TYPE_FIREBASE_STORAGE;
        int rewarded = ComponentBean.COMPONENT_TYPE_REWARDED_VIDEO_AD;

        COMPONENT_ACTIONS.put("set_intent_flag", new CA(
                "intentSetFlags", intent, "F",
                ".setFlags(Intent.FLAG_ACTIVITY_",
                "Set {c} flag to {1}"));
        COMPONENT_ACTIONS.put("anim_value", new CA(
                "objectanimatorSetValue", animator, "d",
                ".setFloatValues(", "Set {c} value to {1}"));
        COMPONENT_ACTIONS.put("anim_repeat_mode", new CA(
                "objectanimatorSetRepeatMode", animator, "m",
                ".setRepeatMode(ValueAnimator.",
                "Set {c} repeat mode to {1}",
                new String[]{"RESTART", "REVERSE"}));
        COMPONENT_ACTIONS.put("anim_interpolator", new CA(
                "objectanimatorSetInterpolator", animator, "M",
                ".setInterpolator(", "Set {c} easing to {1}",
                new String[]{"Linear", "Accelerate", "Decelerate",
                        "AccelerateDecelerate", "Bounce"}));
        COMPONENT_ACTIONS.put("dialog_icon", new CA(
                "Dialog SetIcon", dialog, "r", ".setIcon(R.drawable.",
                "Set {c} icon to “{1}”"));
        COMPONENT_ACTIONS.put("rewarded_load", new CA(
                "rewardedVideoAdLoad", rewarded, "A",
                "RewardedAd.load(", "Load a rewarded ad into {c}"));
        COMPONENT_ACTIONS.put("rewarded_show", new CA(
                "rewardedVideoAdShow", rewarded, "A", ".show(",
                "Show the rewarded ad in {c}"));
        COMPONENT_ACTIONS.put("auth_create_user", new CA(
                "firebaseauthCreateUser", auth, "ss",
                ".createUserWithEmailAndPassword(",
                "Create a user with email “{1}” in {c}"));
        COMPONENT_ACTIONS.put("auth_sign_in", new CA(
                "firebaseauthSignInUser", auth, "ss",
                ".signInWithEmailAndPassword(",
                "Sign in with email “{1}” in {c}"));
        COMPONENT_ACTIONS.put("auth_sign_in_anon", new CA(
                "firebaseauthSignInAnonymously", auth, "",
                ".signInAnonymously(", "Sign in anonymously"));
        COMPONENT_ACTIONS.put("auth_reset_password", new CA(
                "firebaseauthResetPassword", auth, "s",
                ".sendPasswordResetEmail(",
                "Send a password reset to “{1}”"));
        COMPONENT_ACTIONS.put("storage_upload", new CA(
                "firebasestorageUploadFile", storage, "ss",
                ".putFile(Uri.fromFile(new File(",
                "Upload the file “{1}” to “{2}” in {c}"));
        COMPONENT_ACTIONS.put("storage_download", new CA(
                "firebasestorageDownloadFile", storage, "ss",
                "getReferenceFromUrl(",
                "Download “{1}” to “{2}” in {c}"));
        COMPONENT_ACTIONS.put("storage_delete", new CA(
                "firebasestorageDelete", storage, "s",
                "getReferenceFromUrl(", "Delete “{1}” in {c}"));
        COMPONENT_ACTIONS.put("bt_ready", new CA(
                "bluetoothConnectReadyConnection", bluetooth, "s",
                ".readyConnection(",
                "Make {c} ready to receive as “{1}”"));
        COMPONENT_ACTIONS.put("bt_ready_uuid", new CA(
                "bluetoothConnectReadyConnectionToUuid", bluetooth,
                "ss", ".readyConnection(",
                "Make {c} ready to receive with ID “{1}” as “{2}”"));
        COMPONENT_ACTIONS.put("bt_connect", new CA(
                "bluetoothConnectStartConnection", bluetooth, "ss",
                ".startConnection(",
                "Connect {c} to “{1}” as “{2}”"));
        COMPONENT_ACTIONS.put("bt_connect_uuid", new CA(
                "bluetoothConnectStartConnectionToUuid", bluetooth,
                "sss", ".startConnection(",
                "Connect {c} to “{2}” via ID “{1}” as “{3}”"));
        COMPONENT_ACTIONS.put("bt_disconnect", new CA(
                "bluetoothConnectStopConnection", bluetooth, "s",
                ".stopConnection(", "Disconnect {c} (“{1}”)"));
        COMPONENT_ACTIONS.put("bt_send", new CA(
                "bluetoothConnectSendData", bluetooth, "ss",
                ".sendData(", "Send “{1}” via {c} (“{2}”)"));
        COMPONENT_ACTIONS.put("bt_paired", new CA(
                "bluetoothConnectGetPairedDevices", bluetooth, "L",
                ".getPairedDevices(",
                "Put paired devices into list {1}"));
        COMPONENT_ACTIONS.put("net_params", new CA(
                "requestnetworkSetParams", network, "vm",
                ".setParams(", "Set {c} parameters from map {1} as "
                + "{2}",
                new String[]{"REQUEST_PARAM", "REQUEST_BODY"}));
        COMPONENT_ACTIONS.put("send_broadcast", new CA(
                "sendBroadcast", intent, "", "sendBroadcast(",
                "Send {c} as a broadcast"));
    }

    private AgentLogic() {
    }

    /** One translated statement. */
    private static final class Stmt {
        String opCode;
        String type;
        final ArrayList<String> params = new ArrayList<>();
        String sentence;
        /** Fragment the generated code must contain if this ran. */
        String expectCode;
    }

    /** A validated handler change; also the frozen proposal payload. */
    public static final class Plan {
        public String scId;
        public ProjectFileBean activity;
        public String targetId;
        public String eventName;
        public boolean activityEvent;
        /** True when the target is a component, not a widget. */
        public boolean componentEvent;
        /** The component's editor type code (component targets). */
        public int componentType;
        /** Values this event passes, name to editor slot letter. */
        public final LinkedHashMap<String, Character> eventArgs =
                new LinkedHashMap<>();
        public boolean eventExists;
        public int existingCount;
        /** Intent component apply() must create first, or null. */
        public String newComponentId;
        public String header;
        public final ArrayList<String> lines = new ArrayList<>();
        final ArrayList<Stmt> statements = new ArrayList<>();
        final ArrayList<BlockBean> fresh = new ArrayList<>();
        /** Statement beans in chain order (what {@link #verify} walks). */
        final ArrayList<BlockBean> chain = new ArrayList<>();
        /** Reporter beans (e.g. a variable's value) referenced from a
         * statement's parameters as "@id"; they sit in the same stored
         * list but never on the statement chain. */
        final ArrayList<BlockBean> extras = new ArrayList<>();
    }

    /**
     * The values an event hands to its handler, name to editor slot
     * letter ('d' number, 's' text, 'b' true/false, 'a' a whole map
     * of values), read from the {@code final} locals the app's own
     * generators declare (Lx.getEventCode, ManageEvent.f,
     * EventsHandler.getEventCode, Lx.getOnActivityResultCode). A
     * value becomes a "getArg" reporter bean whose spec is the name;
     * Fx emits "_" + spec. Events that pass nothing usable (a raw
     * object, a list) simply have no row here.
     */
    private static final String[][] EVENT_ARG_TABLE = {
            {"onChildAdded", "childKey:s,childValue:a"},
            {"onChildChanged", "childKey:s,childValue:a"},
            {"onChildRemoved", "childKey:s,childValue:a"},
            {"onCancelled", "errorCode:d,errorMessage:s"},
            {"onSensorChanged", "x:d,y:d,z:d"},
            {"onCreateUserComplete", "success:b,errorMessage:s"},
            {"onSignInUserComplete", "success:b,errorMessage:s"},
            {"onGoogleSignIn", "success:b,errorMessage:s"},
            {"signInWithPhoneAuthComplete", "success:b,errorMessage:s"},
            {"onUpdateProfileComplete", "success:b,errorMessage:s"},
            {"onDeleteUserComplete", "success:b,errorMessage:s"},
            {"onUpdateEmailComplete", "success:b,errorMessage:s"},
            {"onUpdatePasswordComplete", "success:b,errorMessage:s"},
            {"onEmailVerificationSent", "success:b,errorMessage:s"},
            {"onResetPasswordEmailSent", "success:b"},
            {"onCodeSent", "verificationId:s"},
            {"onVerificationFailed", "exception:s"},
            {"onUploadProgress", "progressValue:d"},
            {"onDownloadProgress", "progressValue:d"},
            {"onUploadSuccess", "downloadUrl:s"},
            {"onDownloadSuccess", "totalByteCount:d"},
            {"onFailure", "message:s"},
            {"onResponse", "tag:s,response:s,responseHeaders:a"},
            {"onErrorResponse", "tag:s,message:s"},
            {"onSpeechResult", "result:s"},
            {"onSpeechError", "errorMessage:s"},
            {"onConnected", "tag:s,deviceData:a"},
            {"onDataReceived", "tag:s,data:s"},
            {"onDataSent", "tag:s,data:s"},
            {"onConnectionError",
                    "tag:s,connectionState:s,errorMessage:s"},
            {"onConnectionStopped", "tag:s"},
            {"onLocationChanged", "lat:d,lng:d,acc:d"},
            {"onMarkerClicked", "id:s"},
            {"onPictureTaken", "filePath:s"},
            {"onBindCustomView", "position:d"},
            {"onFragmentAdded", "position:d"},
            {"onTabAdded", "position:d"},
            {"doInBackground", "param:s"},
            {"onProgressUpdate", "value:d"},
            {"onPostExecute", "result:s"},
            {"onDateSet", "year:d,month:d,day:d"},
            {"onTimeSet", "hour:d,minute:d"},
            {"onActivityResult", "requestCode:d,resultCode:d"},
            {"onOptionsItemSelected", "id:d,title:s"},
            {"onTabLayoutNewTabAdded", "position:d"},
            {"onBannerAdFailedToLoad", "errorCode:d,errorMessage:s"},
            {"onInterstitialAdFailedToLoad",
                    "errorCode:d,errorMessage:s"},
            {"onRewardAdFailedToLoad", "errorCode:d,errorMessage:s"},
            {"onAdFailedToShowFullScreenContent",
                    "errorCode:d,errorMessage:s"},
    };

    /** The values {@code eventName} passes, in declaration order. */
    private static LinkedHashMap<String, Character> eventArgsOf(
            String eventName) {
        LinkedHashMap<String, Character> args = new LinkedHashMap<>();
        for (String[] row : EVENT_ARG_TABLE) {
            if (row[0].equals(eventName)) {
                for (String pair : row[1].split(",")) {
                    int colon = pair.lastIndexOf(':');
                    args.put(pair.substring(0, colon),
                            pair.charAt(colon + 1));
                }
            }
        }
        return args;
    }

    /** Component id to editor type code for one activity. */
    private static LinkedHashMap<String, Integer> componentsOf(
            eC store, String javaName) {
        LinkedHashMap<String, Integer> components =
                new LinkedHashMap<>();
        try {
            ArrayList<ComponentBean> stored = store.e(javaName);
            if (stored != null) {
                for (ComponentBean component : stored) {
                    if (component != null
                            && component.componentId != null) {
                        components.put(component.componentId,
                                component.type);
                    }
                }
            }
        } catch (Exception ignored) {
            // No components readable; component blocks will say so.
        }
        return components;
    }

    /**
     * Validates a handler request and builds the beans it would add.
     * Pure: reads the project, changes nothing.
     */
    public static Plan plan(Context context, String scId,
                            ProjectFileBean activity, String target,
                            String event, JsonArray blocks)
            throws AgentTools.ToolException {
        if (activity.fileType != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
            throw new AgentTools.ToolException("E_NOT_ACTIVITY",
                    "Custom views have no logic to wire. Pick an activity "
                            + "screen instead.");
        }
        if (blocks == null || blocks.isEmpty()
                || blocks.size() > MAX_BLOCKS) {
            throw new AgentTools.ToolException("E_BLOCKS_LIMIT",
                    "Give between 1 and " + MAX_BLOCKS
                            + " blocks per handler.");
        }
        String javaName = activity.getJavaName();
        eC store = jC.a(scId);

        Plan plan = new Plan();
        plan.scId = scId;
        plan.activity = activity;
        plan.eventName = event == null ? "" : event.trim();

        ArrayList<ViewBean> beans =
                SketchwareProjects.screenBeans(scId, activity);
        Map<String, ViewBean> byId = new LinkedHashMap<>();
        for (ViewBean bean : beans) {
            byId.put(bean.id, bean);
        }
        // The variables this activity declares (name to editor type
        // code), so variable blocks are checked before they run.
        LinkedHashMap<String, Integer> variables =
                variablesOf(store, javaName);
        // The components this activity already has (id to editor
        // type code): a component id is a valid event target, and
        // component blocks are checked against it.
        LinkedHashMap<String, Integer> components =
                componentsOf(store, javaName);

        if ("activity".equalsIgnoreCase(target)) {
            plan.activityEvent = true;
            plan.targetId = plan.eventName;
            if (!Arrays.asList(oq.getAllActivityEvents())
                    .contains(plan.eventName)) {
                // Models name screen events the Android way; accept
                // the near-miss instead of refusing it. "onCreate" is
                // this app's initializeLogic.
                String alias = activityEventAlias(plan.eventName);
                if (alias == null
                        || !Arrays.asList(oq.getAllActivityEvents())
                                .contains(alias)) {
                    throw new AgentTools.ToolException("E_BAD_EVENT",
                            "\"" + plan.eventName + "\" is not an activity "
                                    + "event. Activity events are: "
                                    + String.join(", ",
                                            oq.getAllActivityEvents())
                                    + ".");
                }
                plan.eventName = alias;
                plan.targetId = alias;
            }
            plan.header = "When " + SketchwareProjects.activityName(activity)
                    + " runs " + plan.eventName + ":";
        } else {
            ViewBean widget = findWidget(byId, target);
            if (widget == null) {
                String wanted = target == null ? "" : target.trim();
                if (Arrays.asList(oq.getAllActivityEvents())
                        .contains(wanted)) {
                    throw new AgentTools.ToolException("E_BAD_TARGET",
                            "\"" + wanted + "\" is an activity event, "
                                    + "not a widget. For screen-level "
                                    + "behaviour, call again with target "
                                    + "\"activity\" and event \"" + wanted
                                    + "\".");
                }
                Integer componentType = components.get(wanted);
                if (componentType == null) {
                    throw new AgentTools.ToolException("E_NO_WIDGET",
                            "There is no widget or component with id \""
                                    + target + "\" on this screen. Use "
                                    + "get_screen to see the widget ids.");
                }
                // A component target: its events come from the same
                // table the editor's Add Event screen lists, and it
                // registers exactly the way AddEventActivity does.
                plan.componentEvent = true;
                plan.componentType = componentType;
                plan.targetId = wanted;
                String[] events = oq.getComponentEventsForClass(
                        new ComponentBean(componentType).getClassInfo());
                if (events == null || !Arrays.asList(events)
                        .contains(plan.eventName)) {
                    throw new AgentTools.ToolException("E_BAD_EVENT",
                            wanted + " (" + ComponentBean
                                    .getComponentName(null, componentType)
                                    + ") has no \"" + plan.eventName
                                    + "\" event. Its events are: "
                                    + (events == null ? "none"
                                            : String.join(", ", events))
                                    + ".");
                }
                plan.header = "When " + wanted + " "
                        + plan.eventName + ":";
            } else {
                plan.targetId = widget.id;
                String[] events = oq.getEventsForClass(new ViewBean(
                        widget.id, widget.type).buildClassInfo(widget.type));
                if (events == null || !Arrays.asList(events)
                        .contains(plan.eventName)) {
                    throw new AgentTools.ToolException("E_BAD_EVENT",
                            widget.id + " (" + AgentEdits.typeName(widget.type)
                                    + ") has no \"" + plan.eventName
                                    + "\" event. Its events are: "
                                    + (events == null ? "none"
                                            : String.join(", ", events))
                                    + ".");
                }
                String widgetText = widget.text == null
                        ? "" : widget.text.text;
                String label = widgetText != null && !widgetText.isEmpty()
                        ? " (“" + widgetText + "”)" : "";
                plan.header = "When " + widget.id + label + " "
                        + plan.eventName + ":";
                if ("onBindCustomView".equals(plan.eventName)
                        && (widget.customView == null
                                || widget.customView.isEmpty()
                                || "none".equals(widget.customView))) {
                    throw new AgentTools.ToolException("E_BAD_EVENT",
                            widget.id + " has no item layout yet, so "
                                    + "onBindCustomView has no row to "
                                    + "fill. Give it one first with "
                                    + "edit_screen (op "
                                    + "set_list_item_view) — creating "
                                    + "the layout with create_custom_view "
                                    + "if the project has none.");
                }
            }
        }
        plan.eventArgs.putAll(eventArgsOf(plan.eventName));

        // The event this handler appends to, and what it already does.
        plan.eventExists = eventRegistered(store, javaName, plan);
        ArrayList<BlockBean> existing = readBlocks(store, javaName, plan);
        plan.existingCount = existing.size();
        int nextId = nextFreeId(existing);

        // Inside onBindCustomView, blocks address the item layout's
        // widgets (the editor swaps its widget menus to that layout);
        // the screen's own ids keep priority on a clash.
        Map<String, ViewBean> builderWidgets = byId;
        if ("onBindCustomView".equals(plan.eventName)
                && !plan.activityEvent && !plan.componentEvent) {
            ViewBean host = byId.get(plan.targetId);
            if (host != null && host.customView != null
                    && !host.customView.isEmpty()
                    && !"none".equals(host.customView)) {
                builderWidgets = new LinkedHashMap<>(byId);
                try {
                    ArrayList<ViewBean> itemViews = store.d(
                            ProjectFileBean.getXmlName(host.customView));
                    if (itemViews != null) {
                        for (ViewBean item : itemViews) {
                            if (item != null && item.id != null
                                    && !builderWidgets
                                            .containsKey(item.id)) {
                                builderWidgets.put(item.id, item);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // The screen's own widgets still resolve.
                }
            }
        }

        Builder builder = new Builder(context, builderWidgets,
                variables, nextId);
        builder.lists = listsOf(store, javaName);
        builder.components = components;
        builder.scId = scId;
        builder.activityName = SketchwareProjects.activityName(activity);
        builder.eventArgs.putAll(plan.eventArgs);
        builder.currentEvent = plan.eventName;
        for (JsonElement element : blocks) {
            if (!element.isJsonObject()) {
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        "Every block must be an object like "
                                + "{\"block\":\"toast\",\"message\":\"Hi\"}.");
            }
            try {
                plan.chain.add(builder.buildStatement(
                        element.getAsJsonObject(), 0));
            } catch (AgentTools.ToolException e) {
                // A missing variable is almost always the per-screen
                // rule: the name exists — on another screen. Say so,
                // instead of making the model guess again.
                if ("E_NO_VARIABLE".equals(e.code)) {
                    String hint = variablesByScreenHint(scId, activity);
                    if (!hint.isEmpty()) {
                        throw new AgentTools.ToolException(e.code,
                                e.getMessage() + " " + hint);
                    }
                }
                throw e;
            }
        }
        if (builder.componentToCreate != null) {
            plan.newComponentId = builder.componentToCreate;
            plan.lines.add(0, "Add an Intent component ("
                    + plan.newComponentId + ") to this screen");
        }
        plan.fresh.addAll(builder.created);
        plan.statements.addAll(builder.stmts);
        plan.lines.addAll(builder.lines);
        nextId = builder.nextId;
        // Resolve variable-value reporters: each "@var:" placeholder
        // in a bean's parameters becomes an "@id" reference to a
        // getVar bean (spec = the variable name, the shape the editor
        // stores) appended after the statement beans, unchained.
        // Every created bean is scanned, not just statements: a
        // variable inside a comparison lives in the comparison's own
        // reporter bean, and an unresolved "@var:" written to disk
        // makes the logic editor crash parsing it as a block id.
        for (BlockBean bean : builder.created) {
            for (int i = 0; i < bean.parameters.size(); i++) {
                String param = bean.parameters.get(i);
                if (!param.startsWith("@var:")) {
                    continue;
                }
                String name = param.substring("@var:".length());
                // A name the activity never declared can only be a
                // repeat_index loop variable in scope there; Fx
                // emits the getVar spec as the identifier itself.
                String reporterType = variables.containsKey(name)
                        ? variableTypeLetter(variables.get(name)) : "d";
                BlockBean reporter = new BlockBean(
                        String.valueOf(nextId), name,
                        reporterType, "",
                        "getVar");
                try {
                    reporter.color = kq.a("getVar", reporter.type);
                } catch (Exception ignored) {
                    // Colour is display-only.
                }
                plan.extras.add(reporter);
                plan.fresh.add(reporter);
                bean.parameters.set(i, "@" + nextId);
                nextId++;
            }
        }
        // No placeholder may reach disk unresolved.
        for (BlockBean bean : plan.fresh) {
            for (String param : bean.parameters) {
                if (param != null && param.startsWith("@var:")) {
                    throw new AgentTools.ToolException("E_INTERNAL",
                            "A variable reference did not resolve; "
                                    + "nothing was changed.");
                }
            }
        }
        // The editor-load gate: nothing we add may be a shape the
        // logic editor's loader would crash on.
        ArrayList<String> loadProblems = editorLoadProblems(plan.fresh);
        if (!loadProblems.isEmpty()) {
            throw new AgentTools.ToolException("E_INTERNAL",
                    "The new blocks would not open in the editor ("
                            + loadProblems.get(0) + "); nothing was "
                            + "changed.");
        }
        // Chain the fresh beans among themselves; the applier joins
        // them to the existing chain's tail.
        HashMap<String, BlockBean> freshById = new HashMap<>();
        for (BlockBean bean : plan.fresh) {
            freshById.put(bean.id, bean);
        }
        for (int i = 0; i + 1 < plan.chain.size(); i++) {
            // Link from the tail: a statement may itself be a short
            // chain (open_screen is two blocks).
            BlockBean tail = plan.chain.get(i);
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (tail.nextBlock >= 0 && seen.add(tail.id)
                    && freshById.containsKey(
                            String.valueOf(tail.nextBlock))) {
                tail = freshById.get(String.valueOf(tail.nextBlock));
            }
            tail.nextBlock = Integer.parseInt(plan.chain.get(i + 1).id);
        }

        // Prove with the app's own code generator that the combined
        // event still generates, and that each new statement lands.
        // The existing beans are the store's live objects, so the
        // dry run joins clones — a proposal must not touch anything.
        ArrayList<BlockBean> dryExisting = new ArrayList<>();
        for (BlockBean bean : existing) {
            dryExisting.add(bean.clone());
        }
        String combined = generate(context, scId, activity,
                joined(dryExisting, freshGenerationList(plan)));
        Stmt unproven = null;
        for (Stmt stmt : plan.statements) {
            if (combined == null || !combined.contains(stmt.expectCode)) {
                unproven = stmt;
                break;
            }
        }
        if (unproven != null) {
            // The combined run can fail on an older broken block in
            // this same event; prove the new blocks on their own
            // before blaming them.
            String freshCode = generate(context, scId, activity,
                    freshGenerationList(plan));
            boolean proven = freshCode != null;
            if (proven) {
                for (Stmt stmt : plan.statements) {
                    if (!freshCode.contains(stmt.expectCode)) {
                        proven = false;
                        unproven = stmt;
                        break;
                    }
                }
            }
            if (!proven) {
                throw new AgentTools.ToolException("E_GENERATE",
                        "The new blocks did not survive Sketchware's "
                                + "code generator (\"" + unproven.sentence
                                + "\"). Nothing was changed.");
            }
        }
        return plan;
    }

    /**
     * Builds the beans for one handler, recursively: container blocks
     * (if, if/else, repeat, forever) own sub-chains through
     * subStack1/subStack2 exactly as the editor stores them, and
     * value expressions become reporter beans referenced as "@id"
     * (variables keep the "@var:" placeholder the getVar pass below
     * resolves). Every opCode, slot and type letter comes from the
     * app's own tables via {@link #specFor} and {@code kq}; every
     * container shape was checked against Fx's generator cases.
     */
    private static final class Builder {
        final Context context;
        final Map<String, ViewBean> byId;
        final Map<String, Integer> variables;
        final ArrayList<BlockBean> created = new ArrayList<>();
        final ArrayList<BlockBean> allStatements = new ArrayList<>();
        final ArrayList<Stmt> stmts = new ArrayList<>();
        final ArrayList<String> lines = new ArrayList<>();
        int nextId;
        int total;
        /** List name to editor list type code (1/2/3). */
        Map<String, Integer> lists = new LinkedHashMap<>();
        /** Component id to editor type code, for Intent blocks. */
        Map<String, Integer> components = new LinkedHashMap<>();
        /** Project id, for resolving screens an Intent opens. */
        String scId;
        /** This activity's class name, for %m.activity slots. */
        String activityName;
        /** Loop-variable names in scope at this point of the build. */
        final java.util.Set<String> loopVars = new java.util.HashSet<>();
        /** Every loop-variable name used, for the getVar pass. */
        final java.util.Set<String> loopVarNames =
                new java.util.HashSet<>();
        /** InputStream names opened by open_asset in this build. */
        final java.util.Set<String> assetStreams =
                new java.util.HashSet<>();
        /** Intent component apply() must create, once per plan. */
        String componentToCreate;
        /** Values the current event passes, name to slot letter. */
        final LinkedHashMap<String, Character> eventArgs =
                new LinkedHashMap<>();
        /** The event being built (gates return/publish blocks). */
        String currentEvent = "";
        /** MenuItem names declared so far in this handler. */
        final java.util.Set<String> menuItemNames =
                new java.util.HashSet<>();
        /** SubMenu names declared so far in this handler. */
        final java.util.Set<String> submenuNames =
                new java.util.HashSet<>();

        Builder(Context context, Map<String, ViewBean> byId,
                Map<String, Integer> variables, int nextId) {
            this.context = context;
            this.byId = byId;
            this.variables = variables;
            this.nextId = nextId;
        }

        BlockBean newBean(String opCode, String type) {
            BlockBean bean = new BlockBean(String.valueOf(nextId),
                    specFor(context, opCode), type, "", opCode);
            try {
                bean.color = kq.a(opCode, type);
            } catch (Exception ignored) {
                // Colour is display-only; the palette default stands.
            }
            created.add(bean);
            nextId++;
            return bean;
        }

        /** A component argument of the required kind, by id. */
        String requireComponent(JsonObject op, int type, String label)
                throws AgentTools.ToolException {
            String componentId = stringOf(op, "component");
            Integer actual = components.get(componentId);
            if (actual == null) {
                // An empty name is the common case: the model knows it
                // wants a component but not which one. Naming the ones
                // that exist turns a dead end into a correction.
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        componentId.isEmpty()
                                ? "This block needs a component and none "
                                        + "was named. "
                                        + describe("This screen has: ",
                                                "It has no components yet "
                                                        + "— add one with "
                                                        + "create_component.",
                                                components.keySet())
                                : "There is no component called "
                                        + componentId + " on this screen. "
                                        + describe("This screen has: ",
                                                "It has no components yet "
                                                        + "— add one with "
                                                        + "create_component.",
                                                components.keySet()));
            }
            if (actual != type) {
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        componentId + " is a "
                                + ComponentBean.getComponentName(null,
                                        actual)
                                + " component, and this block needs a "
                                + label + " component.");
            }
            return componentId;
        }

        /**
         * The result kind ('d' or 's') of a switch value, decided
         * before building so the case blocks match the switch kind.
         * Anything undecidable is refused honestly rather than
         * guessed.
         */
        char switchValueKind(JsonElement element)
                throws AgentTools.ToolException {
            String what = "A switch needs a \"value\" that is a "
                    + "number or a piece of text (a variable, a "
                    + "number/text literal, or a simple function of "
                    + "those)";
            if (element == null || element.isJsonNull()) {
                throw new AgentTools.ToolException("E_BAD_BLOCK", what);
            }
            if (element.isJsonPrimitive()) {
                return element.getAsJsonPrimitive().isNumber()
                        ? 'd' : 's';
            }
            if (!element.isJsonObject()) {
                throw new AgentTools.ToolException("E_BAD_BLOCK", what);
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("arg")) {
                String name = stringOf(object, "arg");
                Character letter = eventArgs.get(name);
                if (letter == null || letter == 'a'
                        || letter == 'b') {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            what);
                }
                return letter.charValue();
            }
            if (object.has("var")) {
                String name = stringOf(object, "var");
                requireVariable(variables, name);
                int kind = variables.get(name);
                if (kind == 1) return 'd';
                if (kind == 2) return 's';
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        "A switch cannot switch on variable \"" + name
                                + "\"'s kind of value.");
            }
            if (object.has("number")) return 'd';
            if (object.has("text")) return 's';
            if (object.has("op")) {
                return 'd';
            }
            if (object.has("fn")) {
                String fn = stringOf(object, "fn")
                        .toLowerCase(Locale.ROOT);
                return switch (fn) {
                    case "concat", "upper", "lower", "trim",
                            "substring", "substring_from", "to_text",
                            "format_number", "reverse_text",
                            "last_segment", "external_dir", "app_dir" ->
                            's';
                    case "contains", "matches", "is_empty",
                            "starts_with", "ends_with" ->
                            throw new AgentTools.ToolException(
                                    "E_BAD_BLOCK", what);
                    default -> 'd';
                };
            }
            if (object.has("map_get") || object.has("text_of")
                    || object.has("json_of_map")
                    || object.has("json_of_list")
                    || object.has("read_file")) {
                return 's';
            }
            throw new AgentTools.ToolException("E_BAD_BLOCK", what);
        }

        /** Menu blocks live where the options menu itself exists. */
        private void requireOptionsMenu(String kind)
                throws AgentTools.ToolException {
            if (!"onCreateOptionsMenu".equals(currentEvent)) {
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        kind + " only works inside the activity's "
                                + "onCreateOptionsMenu event, where "
                                + "the menu exists.");
            }
        }

        /** A declared menu name: a fresh Java identifier. */
        private String newMenuName(JsonObject op, String field,
                                   java.util.Set<String> declared)
                throws AgentTools.ToolException {
            String name = stringOf(op, field);
            if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        "A menu name is one word of letters, digits "
                                + "or underscores starting with a "
                                + "letter, like \"saveItem\".");
            }
            if (declared.contains(name)) {
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        "The name \"" + name + "\" is already used "
                                + "in this menu. Pick another.");
            }
            return name;
        }

        BlockBean buildStatement(JsonObject op, int depth)
                throws AgentTools.ToolException {
            if (depth > MAX_DEPTH) {
                throw new AgentTools.ToolException("E_TOO_DEEP",
                        "Blocks can nest at most " + MAX_DEPTH
                                + " containers deep in one handler.");
            }
            if (++total > MAX_TOTAL) {
                throw new AgentTools.ToolException("E_BLOCKS_LIMIT",
                        "At most " + MAX_TOTAL + " blocks per handler, "
                                + "nested ones included.");
            }
            String kind = blockKindOf(op);
            if ("open_asset".equals(kind)) {
                // Remember the stream name before anything reads
                // it back with asset_text later in this handler.
                String streamName = stringOf(op, "name");
                if (!streamName.isEmpty()) {
                    assetStreams.add(streamName);
                }
            }
            switch (kind) {
                case "if", "if_else" -> {
                    boolean withElse = "if_else".equals(kind);
                    String condition = expr(op.get("condition"), 'b',
                            "An if needs a \"condition\"");
                    BlockBean bean = newBean(
                            withElse ? "ifElse" : "if",
                            withElse ? "e" : "c");
                    bean.parameters.add(condition);
                    allStatements.add(bean);
                    addExpect(withElse ? "} else {" : "if (",
                            (withElse ? "If " : "If ")
                                    + describeExpr(op.get("condition")));
                    lines.add(indent(depth) + "If "
                            + describeExpr(op.get("condition")) + ":");
                    ArrayList<BlockBean> then = buildList(
                            arrayOf(op, "then"), depth + 1, "then");
                    bean.subStack1 = Integer.parseInt(then.get(0).id);
                    if (withElse) {
                        lines.add(indent(depth) + "Otherwise:");
                        ArrayList<BlockBean> otherwise = buildList(
                                arrayOf(op, "else"), depth + 1, "else");
                        bean.subStack2 = Integer.parseInt(
                                otherwise.get(0).id);
                    }
                    return bean;
                }
                case "repeat" -> {
                    if (!op.has("times") || op.get("times").isJsonNull()) {
                        throw new AgentTools.ToolException("E_BAD_BLOCK",
                                "repeat needs \"times\": how many times "
                                        + "to run its blocks.");
                    }
                    String times = expr(op.get("times"), 'd',
                            "repeat needs a numeric \"times\"");
                    BlockBean bean = newBean("repeat", "c");
                    bean.parameters.add(times);
                    allStatements.add(bean);
                    addExpect("for(int _repeat",
                            "Repeat " + describeExpr(op.get("times"))
                                    + " times");
                    lines.add(indent(depth) + "Repeat "
                            + describeExpr(op.get("times")) + " times:");
                    ArrayList<BlockBean> body = buildList(
                            arrayOf(op, "blocks"), depth + 1, "blocks");
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    return bean;
                }
                case "forever" -> {
                    BlockBean bean = newBean("forever", "c");
                    allStatements.add(bean);
                    addExpect("while(true) {", "Repeat forever");
                    lines.add(indent(depth) + "Repeat forever:");
                    ArrayList<BlockBean> body = buildList(
                            arrayOf(op, "blocks"), depth + 1, "blocks");
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    return bean;
                }
                case "break" -> {
                    BlockBean bean = newBean("break", "f");
                    allStatements.add(bean);
                    addExpect("break;", "Break out of the loop");
                    lines.add(indent(depth) + "Break out of the loop");
                    return bean;
                }
                case "continue" -> {
                    BlockBean bean = newBean("continue", "f");
                    allStatements.add(bean);
                    addExpect("continue;", "Skip to the next loop round");
                    lines.add(indent(depth)
                            + "Skip to the next loop round");
                    return bean;
                }
                case "while" -> {
                    // The Pro whileLoop container: while %b with one
                    // sub-stack, emitted while(cond) { ... }.
                    String condition = expr(op.get("condition"), 'b',
                            "A while needs a \"condition\"");
                    BlockBean bean = newBean("whileLoop", "c");
                    bean.parameters.add(condition);
                    allStatements.add(bean);
                    addExpect("while(", "While "
                            + describeExpr(op.get("condition")));
                    lines.add(indent(depth) + "While "
                            + describeExpr(op.get("condition")) + ":");
                    ArrayList<BlockBean> body = buildList(
                            arrayOf(op, "blocks"), depth + 1, "blocks");
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    return bean;
                }
                case "try_catch" -> {
                    // The Pro tryCatch (type e): try sub-stack 1,
                    // catch sub-stack 2, no slots.
                    BlockBean bean = newBean("tryCatch", "e");
                    allStatements.add(bean);
                    addExpect("try {", "Try, and catch errors");
                    lines.add(indent(depth) + "Try:");
                    ArrayList<BlockBean> body = buildList(
                            arrayOf(op, "blocks"), depth + 1, "blocks");
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    JsonArray catchArray = arrayOf(op, "catch");
                    if (catchArray != null && !catchArray.isEmpty()) {
                        lines.add(indent(depth) + "If that fails:");
                        ArrayList<BlockBean> caught = buildList(
                                catchArray, depth + 1, "catch");
                        bean.subStack2 = Integer.parseInt(
                                caught.get(0).id);
                    }
                    return bean;
                }
                case "timer_after", "timer_every" -> {
                    // The Timer containers: the component's own
                    // TimerTask field runs the sub-stack after a
                    // delay (and every period, for timer_every).
                    String componentId = requireComponent(op,
                            ComponentBean.COMPONENT_TYPE_TIMERTASK,
                            "Timer");
                    boolean every = "timer_every".equals(kind);
                    String delay = expr(op.get("delay"), 'd',
                            "A timer needs a \"delay\" in "
                                    + "milliseconds");
                    BlockBean bean = newBean(
                            every ? "timerEvery" : "timerAfter", "c");
                    bean.parameters.add(componentId);
                    bean.parameters.add(delay);
                    String extra = "";
                    if (every) {
                        bean.parameters.add(expr(op.get("period"),
                                'd', "timer_every also needs a "
                                        + "\"period\" in milliseconds"));
                        extra = ", then every "
                                + describeExpr(op.get("period"))
                                + " ms";
                    }
                    allStatements.add(bean);
                    addExpect("runOnUiThread(new Runnable()",
                            "After " + describeExpr(op.get("delay"))
                                    + " ms" + extra + ", with "
                                    + componentId);
                    lines.add(indent(depth) + "After "
                            + describeExpr(op.get("delay")) + " ms"
                            + extra + ":");
                    ArrayList<BlockBean> body = buildList(
                            arrayOf(op, "blocks"), depth + 1, "blocks");
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    return bean;
                }
                case "dialog_ok_button", "dialog_negative_button",
                        "dialog_neutral_button" -> {
                    // The Dialog button containers: tapping the
                    // button runs the sub-stack (dialogOkButton /
                    // dialogCancelButton / dialogNeutralButton).
                    String componentId = requireComponent(op,
                            ComponentBean.COMPONENT_TYPE_DIALOG,
                            "Dialog");
                    String text = stringOf(op, "text");
                    if (text.isEmpty()) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "A dialog button needs its \"text\".");
                    }
                    String kindName = kind;
                    String opCode = "dialog_ok_button".equals(kindName)
                            ? "dialogOkButton"
                            : "dialog_negative_button".equals(kindName)
                            ? "dialogCancelButton" : "dialogNeutralButton";
                    String which = "dialog_ok_button".equals(kindName)
                            ? "OK" : "dialog_negative_button"
                            .equals(kindName) ? "negative" : "middle";
                    BlockBean bean = newBean(opCode, "c");
                    bean.parameters.add(componentId);
                    bean.parameters.add(text);
                    allStatements.add(bean);
                    addExpect("set" + ("dialog_ok_button"
                                    .equals(kindName) ? "PositiveButton"
                                    : "dialog_negative_button"
                                            .equals(kindName)
                                            ? "NegativeButton"
                                            : "NeutralButton") + "(",
                            "When " + componentId + "'s “" + text
                                    + "” button is tapped");
                    lines.add(indent(depth) + "When " + componentId
                            + "'s “" + text + "” (" + which
                            + ") button is tapped:");
                    ArrayList<BlockBean> body = buildList(
                            arrayOf(op, "blocks"), depth + 1, "blocks");
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    return bean;
                }
                case "switch" -> {
                    // The Pro switch blocks: a switchNum/switchStr
                    // container whose sub-stack is a chain of
                    // caseNum/caseStr containers (and a defaultSwitch
                    // last), each with its own sub-stack.
                    JsonElement valueEl = op.get("value");
                    char slot = switchValueKind(valueEl);
                    String value = expr(valueEl, slot,
                            "A switch needs a \"value\" to switch on");
                    BlockBean bean = newBean(
                            slot == 'd' ? "switchNum" : "switchStr",
                            "c");
                    bean.parameters.add(value);
                    allStatements.add(bean);
                    addExpect(slot == 'd' ? "switch((int)" : "switch(",
                            "Switch on " + describeExpr(valueEl));
                    lines.add(indent(depth) + "Switch on "
                            + describeExpr(valueEl) + ":");
                    JsonArray cases = arrayOf(op, "cases");
                    if (cases == null || cases.isEmpty()) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "A switch needs a \"cases\" list: "
                                        + "[{\"match\":...,\"blocks\":"
                                        + "[...]}].");
                    }
                    if (cases.size() > 12) {
                        throw new AgentTools.ToolException(
                                "E_BLOCKS_LIMIT",
                                "At most 12 cases in one switch.");
                    }
                    BlockBean previous = null;
                    BlockBean first = null;
                    for (JsonElement element : cases) {
                        if (!element.isJsonObject()) {
                            throw new AgentTools.ToolException(
                                    "E_BAD_BLOCK",
                                    "Every case must be an object "
                                            + "like {\"match\":1,"
                                            + "\"blocks\":[...]}.");
                        }
                        JsonObject caseOp = element.getAsJsonObject();
                        if (++total > MAX_TOTAL) {
                            throw new AgentTools.ToolException(
                                    "E_BLOCKS_LIMIT",
                                    "At most " + MAX_TOTAL
                                            + " blocks per handler.");
                        }
                        BlockBean caseBean = newBean(
                                slot == 'd' ? "caseNum" : "caseStr",
                                "c");
                        caseBean.parameters.add(expr(
                                caseOp.get("match"), slot,
                                "A case needs a \"match\" value"));
                        allStatements.add(caseBean);
                        lines.add(indent(depth + 1) + "When it is "
                                + describeExpr(caseOp.get("match"))
                                + ":");
                        JsonArray body = arrayOf(caseOp, "blocks");
                        if (body != null && !body.isEmpty()) {
                            ArrayList<BlockBean> bodyHeads = buildList(
                                    body, depth + 2, "case blocks");
                            caseBean.subStack1 = Integer.parseInt(
                                    bodyHeads.get(0).id);
                        }
                        if (first == null) {
                            first = caseBean;
                        }
                        if (previous != null) {
                            previous.nextBlock = Integer.parseInt(
                                    caseBean.id);
                        }
                        previous = caseBean;
                    }
                    JsonArray defaultBody = arrayOf(op, "default");
                    if (defaultBody != null && !defaultBody.isEmpty()) {
                        if (++total > MAX_TOTAL) {
                            throw new AgentTools.ToolException(
                                    "E_BLOCKS_LIMIT",
                                    "At most " + MAX_TOTAL
                                            + " blocks per handler.");
                        }
                        BlockBean defaultBean = newBean("defaultSwitch",
                                "c");
                        allStatements.add(defaultBean);
                        lines.add(indent(depth + 1) + "Otherwise:");
                        ArrayList<BlockBean> bodyHeads = buildList(
                                defaultBody, depth + 2,
                                "default blocks");
                        defaultBean.subStack1 = Integer.parseInt(
                                bodyHeads.get(0).id);
                        if (first == null) {
                            first = defaultBean;
                        }
                        if (previous != null) {
                            previous.nextBlock = Integer.parseInt(
                                    defaultBean.id);
                        }
                        previous = defaultBean;
                    }
                    bean.subStack1 = Integer.parseInt(first.id);
                    return bean;
                }
                case "open_screen" -> {
                    // The editor's intentSetScreen + startActivity
                    // pair, over an Intent component: an existing one
                    // when the screen has one, otherwise one the
                    // applier creates first (named on the card).
                    String screen = stringOf(op, "screen");
                    if (screen.isEmpty()) {
                        screen = stringOf(op, "to");
                    }
                    ProjectFileBean target =
                            SketchwareProjects.findActivity(scId, screen);
                    if (target == null) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "There is no screen called " + screen
                                        + " in this project to open. "
                                        + "Create it first, or check "
                                        + "list_activities.");
                    }
                    String componentId = stringOf(op, "component");
                    if (!componentId.isEmpty()) {
                        Integer type = components.get(componentId);
                        if (type == null || type
                                != ComponentBean.COMPONENT_TYPE_INTENT) {
                            throw new AgentTools.ToolException(
                                    "E_BAD_BLOCK",
                                    "There is no Intent component "
                                            + "called " + componentId
                                            + " on this screen.");
                        }
                    } else {
                        for (Map.Entry<String, Integer> entry
                                : components.entrySet()) {
                            if (entry.getValue()
                                    == ComponentBean
                                            .COMPONENT_TYPE_INTENT) {
                                componentId = entry.getKey();
                                break;
                            }
                        }
                    }
                    if (componentId.isEmpty()) {
                        if (componentToCreate == null) {
                            int n = 1;
                            while (components.containsKey(
                                    "intent" + n)
                                    || byId.containsKey("intent" + n)
                                    || variables.containsKey(
                                            "intent" + n)) {
                                n++;
                            }
                            componentToCreate = "intent" + n;
                        }
                        componentId = componentToCreate;
                    }
                    String targetName = SketchwareProjects.activityName(target);
                    BlockBean setScreen = newBean("intentSetScreen",
                            " ");
                    setScreen.parameters.add(componentId);
                    setScreen.parameters.add(targetName);
                    allStatements.add(setScreen);
                    addExpect(componentId + ".setClass",
                            "Point " + componentId + " at "
                                    + targetName);
                    BlockBean start = newBean("startActivity", " ");
                    start.parameters.add(componentId);
                    allStatements.add(start);
                    addExpect("startActivity(" + componentId,
                            "Open " + targetName);
                    setScreen.nextBlock = Integer.parseInt(start.id);
                    lines.add(indent(depth) + "Open "
                            + SketchwareProjects.activityName(target));
                    return setScreen;
                }
                case "set_variable" -> {
                    JsonElement value = op.get("value");
                    if (value == null || !value.isJsonObject()) {
                        return buildTranslated(op, depth);
                    }
                    // Assign from any value expression: a map's
                    // stored text, a widget's text, a calculation —
                    // the editor's setVar blocks take a reporter
                    // in the value slot exactly like this.
                    String name = stringOf(op, "variable");
                    requireVariable(variables, name);
                    int varKind = variables.get(name);
                    if (varKind == 3) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "\"" + name + "\" is a map variable; "
                                        + "use put_in_map, clear_map "
                                        + "or new_map for it instead.");
                    }
                    char slot = varKind == 0 ? 'b'
                            : varKind == 1 ? 'd' : 's';
                    String param = expr(value, slot,
                            "set_variable needs a value that fits "
                                    + "\"" + name + "\"");
                    BlockBean bean = newBean(setVarOpCode(varKind),
                            " ");
                    bean.parameters.add(name);
                    bean.parameters.add(param);
                    allStatements.add(bean);
                    addExpect(name + " = ",
                            "Set variable " + name + " to "
                                    + describeExpr(value));
                    lines.add(indent(depth) + "Set variable “" + name
                            + "” to " + describeExpr(value));
                    return bean;
                }
                case "snackbar" -> {
                    // The Pro showSnackbar container: a snackbar on
                    // a widget whose action button runs the body.
                    ViewBean widget = requireWidget(op, byId);
                    BlockBean bean = newBean("showSnackbar", "c");
                    bean.parameters.add(widget.id);
                    bean.parameters.add(expr(op.get("text"), 's',
                            "A snackbar needs its message \"text\""));
                    bean.parameters.add(expr(op.get("action"), 's',
                            "A snackbar needs its button \"action\" "
                                    + "text"));
                    allStatements.add(bean);
                    addExpect("Snackbar.make(",
                            "Show a snackbar on " + widget.id);
                    lines.add(indent(depth) + "Show a snackbar on "
                            + widget.id + " (“"
                            + describeExpr(op.get("text"))
                            + "”, button “"
                            + describeExpr(op.get("action")) + "”):");
                    ArrayList<BlockBean> body = buildList(
                            arrayOf(op, "blocks"), depth + 1, "blocks");
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    return bean;
                }
                case "fb_get_children" -> {
                    // The firebaseGetChildren container: read a
                    // node's children once into a list of maps, then
                    // run the body with the filled list.
                    String componentId = requireComponent(op,
                            ComponentBean.COMPONENT_TYPE_FIREBASE,
                            "Firebase DB");
                    String list = stringOf(op, "variable");
                    Integer listKind = lists.get(list);
                    if (listKind == null || listKind != 3) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "fb_get_children fills a list of "
                                        + "maps; “" + list + "” is "
                                        + "not one on this screen. "
                                        + "Declare it with "
                                        + "create_variable (list_map).");
                    }
                    BlockBean bean = newBean("firebaseGetChildren",
                            "c");
                    bean.parameters.add(componentId);
                    bean.parameters.add(list);
                    allStatements.add(bean);
                    addExpect("addListenerForSingleValueEvent(",
                            "When " + componentId + " children load "
                                    + "into " + list);
                    lines.add(indent(depth) + "When " + componentId
                            + "'s children load into “" + list
                            + "”:");
                    ArrayList<BlockBean> body = buildList(
                            arrayOf(op, "blocks"), depth + 1, "blocks");
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    return bean;
                }
                case "repeat_index", "repeat_index_down" -> {
                    // repeatKnownNum: the same counted loop, but
                    // the body can read the counter by name. The
                    // name is free text in the editor too, stored
                    // as the second slot; referencing it is an
                    // ordinary getVar whose spec is that name.
                    boolean down = "repeat_index_down".equals(kind);
                    String name = stringOf(op, "name");
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "repeat_index needs \"name\": the "
                                        + "counter's name (letters, "
                                        + "digits, _, starting with a "
                                        + "letter).");
                    }
                    if (variables.containsKey(name)
                            || loopVars.contains(name)) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "“" + name + "” is already used on "
                                        + "this screen; pick another "
                                        + "counter name.");
                    }
                    String times = expr(op.get("times"), 'd',
                            "repeat_index needs how many \"times\"");
                    BlockBean bean = newBean(down
                            ? "RepeatKnownNumDescending"
                            : "repeatKnownNum", "c");
                    bean.parameters.add(times);
                    bean.parameters.add(name);
                    allStatements.add(bean);
                    addExpect("for (int " + name,
                            "Repeat " + describeExpr(op.get("times"))
                                    + " times counting " + name);
                    lines.add(indent(depth) + "Repeat "
                            + describeExpr(op.get("times"))
                            + " times (counter “" + name + "”):");
                    loopVars.add(name);
                    loopVarNames.add(name);
                    ArrayList<BlockBean> body;
                    try {
                        body = buildList(arrayOf(op, "blocks"),
                                depth + 1, "blocks");
                    } finally {
                        loopVars.remove(name);
                    }
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    return bean;
                }
                case "start_service", "stop_service" -> {
                    // startService/stopService over a screen class,
                    // resolved like open_screen resolves screens.
                    String screen = stringOf(op, "screen");
                    ProjectFileBean target =
                            SketchwareProjects.findActivity(scId,
                                    screen);
                    if (target == null) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "There is no screen called " + screen
                                        + " in this project. Create "
                                        + "it first, or check "
                                        + "list_activities.");
                    }
                    boolean start = "start_service".equals(kind);
                    String targetName =
                            SketchwareProjects.activityName(target);
                    BlockBean bean = newBean(start ? "startService"
                            : "stopService", " ");
                    bean.parameters.add(targetName);
                    allStatements.add(bean);
                    addExpect((start ? "startService" : "stopService")
                                    + "(new Intent(",
                            (start ? "Start " : "Stop ")
                                    + targetName);
                    lines.add(indent(depth) + (start ? "Start the "
                            : "Stop the ") + targetName + " service");
                    return bean;
                }
                case "return_fragment" -> {
                    // The last block of a Fragment Adapter's
                    // onFragmentAdded handler: which screen class
                    // each page shows (returnFragment, Fx-verified
                    // "return new %s();").
                    if (!"onFragmentAdded".equals(currentEvent)) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "return_fragment only works inside a "
                                        + "Fragment Adapter component's "
                                        + "onFragmentAdded handler — it "
                                        + "decides which screen each "
                                        + "page shows. Put it there, as "
                                        + "the last block.");
                    }
                    String screen = stringOf(op, "screen");
                    ProjectFileBean target =
                            SketchwareProjects.findActivity(scId,
                                    screen);
                    if (target == null) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "There is no screen called " + screen
                                        + " in this project to show as "
                                        + "a page. Create it first, or "
                                        + "check list_activities.");
                    }
                    String className =
                            SketchwareProjects.activityName(target);
                    BlockBean bean = newBean("returnFragment", "f");
                    bean.parameters.add(className);
                    allStatements.add(bean);
                    addExpect("return new " + className + "()",
                            "Show " + className + " as this page");
                    lines.add(indent(depth) + "Show " + className
                            + " as this page");
                    return bean;
                }
                case "return_title" -> {
                    // The last block of onTabAdded: the page's title
                    // (returnTitle, "return %s;").
                    if (!"onTabAdded".equals(currentEvent)) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "return_title only works inside a "
                                        + "Fragment Adapter component's "
                                        + "onTabAdded handler — it gives "
                                        + "each page its title. Put it "
                                        + "there, as the last block.");
                    }
                    BlockBean bean = newBean("returnTitle", "f");
                    bean.parameters.add(expr(op.get("title"), 's',
                            "return_title needs \"title\": the page "
                                    + "title"));
                    allStatements.add(bean);
                    addExpect("return ",
                            "Use " + describeExpr(op.get("title"))
                                    + " as the page title");
                    lines.add(indent(depth) + "Use "
                            + describeExpr(op.get("title"))
                            + " as the page title");
                    return bean;
                }
                case "return_text" -> {
                    // The last block of an AsyncTask's doInBackground:
                    // the text handed to onPostExecute (returnString,
                    // "return (%s);").
                    if (!"doInBackground".equals(currentEvent)) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "return_text only works inside an "
                                        + "AsyncTask component's "
                                        + "doInBackground handler — it "
                                        + "is the result onPostExecute "
                                        + "receives. Put it there, as "
                                        + "the last block.");
                    }
                    BlockBean bean = newBean("returnString", " ");
                    bean.parameters.add(expr(op.get("value"), 's',
                            "return_text needs \"value\": the result "
                                    + "text"));
                    allStatements.add(bean);
                    addExpect("return (",
                            "Finish with "
                                    + describeExpr(op.get("value"))
                                    + " as the result");
                    lines.add(indent(depth) + "Finish with "
                            + describeExpr(op.get("value"))
                            + " as the result");
                    return bean;
                }
                case "asynctask_execute" -> {
                    // AsyncTaskExecute: "new %s().execute(%s);" — run
                    // a background task with a message for it.
                    String componentId = requireComponent(op, 36,
                            "AsyncTask");
                    BlockBean bean = newBean("AsyncTaskExecute", " ");
                    bean.parameters.add(componentId);
                    bean.parameters.add(expr(op.get("message"), 's',
                            "asynctask_execute needs \"message\": the "
                                    + "text the task receives"));
                    allStatements.add(bean);
                    addExpect("new " + componentId + "().execute(",
                            "Run the " + componentId
                                    + " background task");
                    lines.add(indent(depth) + "Run the " + componentId
                            + " background task with "
                            + describeExpr(op.get("message")));
                    return bean;
                }
                case "asynctask_publish" -> {
                    // AsyncTaskPublishProgress: only callable from
                    // inside the task's own doInBackground.
                    if (!"doInBackground".equals(currentEvent)) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "asynctask_publish only works inside "
                                        + "an AsyncTask component's "
                                        + "doInBackground handler — it "
                                        + "reports progress to "
                                        + "onProgressUpdate.");
                    }
                    BlockBean bean =
                            newBean("AsyncTaskPublishProgress", " ");
                    bean.parameters.add(expr(op.get("value"), 'd',
                            "asynctask_publish needs \"value\": the "
                                    + "progress number"));
                    allStatements.add(bean);
                    addExpect("publishProgress((int)",
                            "Report progress "
                                    + describeExpr(op.get("value")));
                    lines.add(indent(depth) + "Report progress "
                            + describeExpr(op.get("value")));
                    return bean;
                }
                case "datepicker_show" -> {
                    // datePickerDialogShow takes no value; the picked
                    // date arrives in the DatePickerDialog
                    // component's onDateSet handler, whose listener
                    // class is what this block shows.
                    boolean hasPicker = false;
                    for (Integer type : components.values()) {
                        if (type == ComponentBean
                                .COMPONENT_TYPE_DATE_PICKER_DIALOG) {
                            hasPicker = true;
                            break;
                        }
                    }
                    if (!hasPicker) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "datepicker_show needs a DatePickerDialog "
                                        + "component on this screen — "
                                        + "add one with create_component "
                                        + "(type date_picker) and give it "
                                        + "an onDateSet handler for the "
                                        + "picked date.");
                    }
                    BlockBean bean =
                            newBean("datePickerDialogShow", " ");
                    allStatements.add(bean);
                    addExpect("DatePickerFragment",
                            "Show the date picker");
                    lines.add(indent(depth) + "Show the date picker");
                    return bean;
                }
                case "timepicker_show" -> {
                    String componentId = requireComponent(op,
                            ComponentBean
                                    .COMPONENT_TYPE_TIME_PICKER_DIALOG,
                            "TimePickerDialog");
                    BlockBean bean =
                            newBean("timePickerDialogShow", " ");
                    bean.parameters.add(componentId);
                    allStatements.add(bean);
                    addExpect(componentId + ".show()",
                            "Show the " + componentId + " time picker");
                    lines.add(indent(depth) + "Show the " + componentId
                            + " time picker");
                    return bean;
                }
                case "menu_item" -> {
                    // menuAddMenuItem: declare a named item so later
                    // blocks can show, hide or disable it again.
                    requireOptionsMenu("menu_item");
                    String name = newMenuName(op, "name",
                            menuItemNames);
                    String action = stringOf(op, "action")
                            .toUpperCase(Locale.ROOT);
                    String actionConstant = switch (action) {
                        case "", "ALWAYS", "SHOW_AS_ACTION_ALWAYS" ->
                                "SHOW_AS_ACTION_ALWAYS";
                        case "IF_ROOM", "SHOW_AS_ACTION_IF_ROOM" ->
                                "SHOW_AS_ACTION_IF_ROOM";
                        case "NEVER", "SHOW_AS_ACTION_NEVER" ->
                                "SHOW_AS_ACTION_NEVER";
                        case "WITH_TEXT", "SHOW_AS_ACTION_WITH_TEXT" ->
                                "SHOW_AS_ACTION_WITH_TEXT";
                        case "COLLAPSE", "COLLAPSE_ACTION_VIEW",
                                "SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW" ->
                                "SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW";
                        default -> throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "menu_item \"action\" is one of: "
                                        + "always, if_room, never, "
                                        + "with_text, collapse.");
                    };
                    String icon = stringOf(op, "icon");
                    if (!SketchwareProjects.resources(scId).images
                            .contains(icon)) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "There is no drawable called \"" + icon
                                        + "\" in this project. Use "
                                        + "list_resources to see its "
                                        + "images.");
                    }
                    BlockBean bean = newBean("menuAddMenuItem", " ");
                    bean.parameters.add(name);
                    bean.parameters.add(numericField(op, "id"));
                    bean.parameters.add(expr(op.get("title"), 's',
                            "menu_item needs \"title\": the item text"));
                    bean.parameters.add(icon);
                    bean.parameters.add(actionConstant);
                    allStatements.add(bean);
                    menuItemNames.add(name);
                    addExpect("MenuItem " + name + " = menu.add(",
                            "Add menu item " + describeExpr(
                                    op.get("title")));
                    lines.add(indent(depth) + "Add menu item "
                            + describeExpr(op.get("title")) + " ("
                            + name + ")");
                    return bean;
                }
                case "submenu" -> {
                    // menuAddSubmenu: a named submenu whose items
                    // are the body (submenu_item blocks).
                    requireOptionsMenu("submenu");
                    String name = newMenuName(op, "name",
                            submenuNames);
                    BlockBean bean = newBean("menuAddSubmenu", "c");
                    bean.parameters.add(name);
                    bean.parameters.add(expr(op.get("title"), 's',
                            "submenu needs \"title\": its text"));
                    allStatements.add(bean);
                    addExpect("SubMenu " + name + " = menu.addSubMenu(",
                            "Add submenu " + describeExpr(
                                    op.get("title")));
                    lines.add(indent(depth) + "Add submenu "
                            + describeExpr(op.get("title")) + " ("
                            + name + "):");
                    submenuNames.add(name);
                    ArrayList<BlockBean> body = buildList(
                            arrayOf(op, "blocks"), depth + 1, "blocks");
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    return bean;
                }
                case "submenu_item" -> {
                    requireOptionsMenu("submenu_item");
                    String name = stringOf(op, "submenu");
                    if (!submenuNames.contains(name)) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "submenu_item adds to a submenu "
                                        + "declared earlier in this "
                                        + "handler with a submenu block; "
                                        + "\"" + name + "\" is not one.");
                    }
                    BlockBean bean = newBean("submenuAddItem", " ");
                    bean.parameters.add(name);
                    bean.parameters.add(numericField(op, "id"));
                    bean.parameters.add(expr(op.get("title"), 's',
                            "submenu_item needs \"title\": the item "
                                    + "text"));
                    allStatements.add(bean);
                    addExpect(name + ".add(0,",
                            "Add " + describeExpr(op.get("title"))
                                    + " to submenu " + name);
                    lines.add(indent(depth) + "Add "
                            + describeExpr(op.get("title"))
                            + " to submenu " + name);
                    return bean;
                }
                case "menu_item_visible", "menu_item_enabled" -> {
                    // menuItemSetVisible / menuItemSetEnabled on a
                    // named item declared earlier in this handler.
                    requireOptionsMenu(kind);
                    boolean visible = "menu_item_visible".equals(kind);
                    String name = stringOf(op, "name");
                    if (!menuItemNames.contains(name)) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                kind + " works on a menu item declared "
                                        + "earlier in this handler with "
                                        + "menu_item; \"" + name
                                        + "\" is not one.");
                    }
                    BlockBean bean = newBean(visible
                            ? "menuItemSetVisible"
                            : "menuItemSetEnabled", " ");
                    bean.parameters.add(name);
                    bean.parameters.add(expr(op.get("flag"), 'b',
                            kind + " needs \"flag\": true or false"));
                    allStatements.add(bean);
                    addExpect(name + (visible ? ".setVisible("
                                    : ".setEnabled("),
                            (visible ? "Show or hide " : "Enable or "
                                    + "disable ") + name);
                    lines.add(indent(depth) + (visible
                            ? "Set whether " + name + " is visible"
                            : "Set whether " + name + " is enabled"));
                    return bean;
                }
                case "otp_listener" -> {
                    // otpViewSetOTPListener: the body runs when the
                    // user finishes typing the code, which is
                    // {"arg": "otp"} inside it.
                    ViewBean widget = requireWidget(op, byId);
                    if (!"OTPView".equals(
                            AgentEdits.typeName(widget.type))) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                widget.id + " is a "
                                        + AgentEdits.typeName(widget.type)
                                        + ", not an OTPView.");
                    }
                    BlockBean bean =
                            newBean("otpViewSetOTPListener", "c");
                    bean.parameters.add(widget.id);
                    allStatements.add(bean);
                    addExpect(widget.id
                                    + ".setOTPListener(new OTPListener()",
                            "When a full code is entered in "
                                    + widget.id);
                    lines.add(indent(depth) + "When a full code is "
                            + "entered in " + widget.id + ":");
                    Character previous = eventArgs.put("otp", 's');
                    try {
                        ArrayList<BlockBean> body = buildList(
                                arrayOf(op, "blocks"), depth + 1,
                                "blocks");
                        bean.subStack1 = Integer.parseInt(
                                body.get(0).id);
                    } finally {
                        if (previous == null) {
                            eventArgs.remove("otp");
                        } else {
                            eventArgs.put("otp", previous);
                        }
                    }
                    return bean;
                }
                case "youtube_listener" -> {
                    // YTPVSetListener: cue a video when the player is
                    // ready, then run the body.
                    ViewBean widget = requireWidget(op, byId);
                    if (widget.type
                            != ViewBeans.VIEW_TYPE_WIDGET_YOUTUBEPLAYERVIEW) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                widget.id + " is a "
                                        + AgentEdits.typeName(widget.type)
                                        + ", not a YouTube player.");
                    }
                    BlockBean bean = newBean("YTPVSetListener", "c");
                    bean.parameters.add(widget.id);
                    bean.parameters.add(expr(op.get("video_id"), 's',
                            "youtube_listener needs \"video_id\": the "
                                    + "video to load"));
                    allStatements.add(bean);
                    addExpect(widget.id + ".addYouTubePlayerListener("
                                    + "new AbstractYouTubePlayerListener()",
                            "When " + widget.id + " is ready, load "
                                    + "the video");
                    lines.add(indent(depth) + "When " + widget.id
                            + " is ready, load the video:");
                    ArrayList<BlockBean> body = buildList(
                            arrayOf(op, "blocks"), depth + 1, "blocks");
                    bean.subStack1 = Integer.parseInt(body.get(0).id);
                    return bean;
                }
                default -> {
                    return buildTranslated(op, depth);
                }
            }
        }

        /** One statement from the literal/picker vocabulary. */
        private BlockBean buildTranslated(JsonObject op, int depth)
                throws AgentTools.ToolException {
            Stmt stmt = translate(context, op, byId, variables, lists,
                    components, activityName);
            stmts.add(stmt);
            BlockBean bean = newBean(stmt.opCode, stmt.type);
            bean.parameters.addAll(stmt.params);
            allStatements.add(bean);
            lines.add(indent(depth) + stmt.sentence);
            return bean;
        }

        /** A JSON array member, or null when absent/misshapen. */
        static JsonArray arrayOf(JsonObject object, String key) {
            try {
                return object.getAsJsonArray(key);
            } catch (Exception e) {
                return null;
            }
        }

        /** Builds one sub-chain and links it among itself. */
        ArrayList<BlockBean> buildList(JsonArray array, int depth,
                                       String what)
                throws AgentTools.ToolException {
            if (array == null || array.isEmpty()) {
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        "A container needs at least one block in its \""
                                + what + "\" list.");
            }
            if (array.size() > MAX_BLOCKS) {
                throw new AgentTools.ToolException("E_BLOCKS_LIMIT",
                        "At most " + MAX_BLOCKS + " blocks in one \""
                                + what + "\" list.");
            }
            ArrayList<BlockBean> heads = new ArrayList<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Every block must be an object like "
                                    + "{\"block\":\"toast\","
                                    + "\"message\":\"Hi\"}.");
                }
                heads.add(buildStatement(element.getAsJsonObject(),
                        depth));
            }
            HashMap<String, BlockBean> createdById = new HashMap<>();
            for (BlockBean bean : created) {
                createdById.put(bean.id, bean);
            }
            for (int i = 0; i + 1 < heads.size(); i++) {
                // Link from the tail: a statement may itself be a
                // short chain (open_screen is two blocks).
                BlockBean tail = heads.get(i);
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (tail.nextBlock >= 0 && seen.add(tail.id)
                        && createdById.containsKey(
                                String.valueOf(tail.nextBlock))) {
                    tail = createdById.get(
                            String.valueOf(tail.nextBlock));
                }
                tail.nextBlock = Integer.parseInt(heads.get(i + 1).id);
            }
            return heads;
        }

        /** " (this event passes: childKey, childValue)" or none. */
        private String eventArgsHint() {
            if (eventArgs.isEmpty()) {
                return " (this event passes no values)";
            }
            return " (this event passes: "
                    + String.join(", ", eventArgs.keySet()) + ")";
        }

        /**
         * Turns one value expression into a stored parameter: a
         * literal inline, or "@id"/"@var:name" pointing at a reporter
         * bean. {@code slot} is the editor slot kind — 'd' number,
         * 'b' boolean, 's' string — and shapes are checked against it.
         */
        String expr(JsonElement element, char slot, String what)
                throws AgentTools.ToolException {
            if (element == null || element.isJsonNull()) {
                throw new AgentTools.ToolException("E_BAD_VALUE",
                        what + ", e.g. {\"var\":\"taps\"}, {\"number\":3} "
                                + "or {\"op\":\">\",\"a\":...,\"b\":...}.");
            }
            if (element.isJsonPrimitive()) {
                if (element.getAsJsonPrimitive().isNumber()) {
                    if (slot != 'd') {
                        throw badValue(what, "a number does not fit "
                                + "here");
                    }
                    return element.getAsString();
                }
                if (element.getAsJsonPrimitive().isBoolean()) {
                    if (slot != 'b') {
                        throw badValue(what, "a true/false value does "
                                + "not fit here");
                    }
                    return element.getAsString();
                }
                String text = element.getAsString();
                if (slot == 's') {
                    return text;
                }
                if (slot == 'd') {
                    try {
                        Double.parseDouble(text);
                        return text;
                    } catch (Exception ignored) {
                        throw badValue(what, "\"" + text
                                + "\" is not a number");
                    }
                }
                throw badValue(what, "\"" + text + "\" does not fit "
                        + "here");
            }
            if (!element.isJsonObject()) {
                throw badValue(what, "unsupported value shape");
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("arg")) {
                // A value the event itself passes (a Firebase child
                // key, a sensor reading, a success flag): stored as
                // the editor's getArg reporter whose spec is the
                // value's name — Fx generates "_" + name.
                String name = stringOf(object, "arg");
                Character letter = eventArgs.get(name);
                if (letter == null) {
                    throw badValue(what, "this event does not pass a "
                            + "value called \"" + name + "\""
                            + eventArgsHint());
                }
                if (letter == 'a') {
                    throw badValue(what, "\"" + name + "\" is a whole "
                            + "set of values, not a single one; read "
                            + "one value out of it with {\"map_get\": "
                            + "{\"arg\": \"" + name + "\"}, \"key\": "
                            + "\"...\"}");
                }
                if (letter.charValue() != slot) {
                    throw badValue(what, "the " + name + " value is "
                            + (letter == 'd' ? "a number"
                                    : letter == 'b' ? "true/false"
                                            : "text")
                            + " and does not fit here");
                }
                BlockBean bean = newBean("getArg",
                        String.valueOf(letter.charValue()));
                bean.spec = name;
                return "@" + bean.id;
            }
            if (object.has("var")) {
                String name = stringOf(object, "var");
                if (!variables.containsKey(name)
                        && loopVars.contains(name)) {
                    // A repeat_index loop variable: a number that
                    // only exists inside its loop body.
                    if (slot != 'd') {
                        throw badValue(what, "the loop counter \""
                                + name + "\" is a number and does "
                                + "not fit here");
                    }
                    return "@var:" + name;
                }
                requireVariable(variables, name);
                int kind = variables.get(name);
                int needed = slot == 'd' ? 1 : slot == 'b' ? 0 : 2;
                if (kind != needed) {
                    throw new AgentTools.ToolException("E_BAD_VALUE",
                            "Variable \"" + name + "\" is a "
                                    + SketchwareProjects.variableTypeName(
                                            kind) + " variable and does "
                                    + "not fit here.");
                }
                return "@var:" + name;
            }
            if (object.has("number")) {
                if (slot != 'd') {
                    throw badValue(what, "a number does not fit here");
                }
                String number = object.get("number").getAsString();
                try {
                    Double.parseDouble(number);
                } catch (Exception ignored) {
                    throw badValue(what, "\"" + number
                            + "\" is not a number");
                }
                return number;
            }
            if (object.has("text")) {
                if (slot != 's') {
                    throw badValue(what, "text does not fit here");
                }
                return object.get("text").getAsString();
            }
            if (object.has("bool")) {
                if (slot != 'b') {
                    throw badValue(what, "a true/false value does not "
                            + "fit here");
                }
                return object.get("bool").getAsBoolean()
                        ? "true" : "false";
            }
            if (object.has("list_length") || object.has("item_of")
                    || object.has("list_has") || object.has("map_get")
                    || object.has("map_has")) {
                // List and map reporters: lengthList,
                // getAtListStr/Int, containListStr/Int, mapGet and
                // mapContainKey — one or two slots each, Fx-verified.
                if (object.has("list_length")) {
                    String list = stringOf(object, "list_length");
                    if (!lists.containsKey(list)) {
                        throw badValue(what, "there is no list "
                                + "called " + list + " on this "
                                + "screen");
                    }
                    if (slot != 'd') {
                        throw badValue(what, "the length of " + list
                                + " is a number and does not fit "
                                + "here");
                    }
                    BlockBean bean = newBean("lengthList", "d");
                    bean.parameters.add(list);
                    return "@" + bean.id;
                }
                if (object.has("item_of")) {
                    String list = stringOf(object, "item_of");
                    Integer listKind = lists.get(list);
                    if (listKind == null) {
                        throw badValue(what, "there is no list "
                                + "called " + list + " on this "
                                + "screen");
                    }
                    if (listKind == 3) {
                        throw badValue(what, "items of a list of "
                                + "maps do not fit here yet");
                    }
                    char needed = listKind == 2 ? 's' : 'd';
                    if (slot != needed) {
                        throw badValue(what, "an item of " + list
                                + " does not fit here");
                    }
                    BlockBean bean = newBean(
                            listKind == 2 ? "getAtListStr"
                                    : "getAtListInt",
                            String.valueOf(needed));
                    bean.parameters.add(expr(object.get("index"),
                            'd', what));
                    bean.parameters.add(list);
                    return "@" + bean.id;
                }
                if (object.has("list_has")) {
                    String list = stringOf(object, "list_has");
                    Integer listKind = lists.get(list);
                    if (listKind == null || listKind == 3) {
                        throw badValue(what, "there is no list of "
                                + "text or numbers called " + list
                                + " on this screen");
                    }
                    if (slot != 'b') {
                        throw badValue(what, "whether " + list
                                + " contains a value is true/false "
                                + "and does not fit here");
                    }
                    BlockBean bean = newBean(
                            listKind == 2 ? "containListStr"
                                    : "containListInt", "b");
                    bean.parameters.add(list);
                    bean.parameters.add(expr(object.get("value"),
                            listKind == 2 ? 's' : 'd', what));
                    return "@" + bean.id;
                }
                String mapKey = object.has("map_get") ? "map_get"
                        : "map_has";
                // The map itself: a map variable named directly, or
                // a set of values this event passed in (childValue
                // and friends), which the editor plugs into the very
                // same first slot.
                String source;
                JsonElement mapElement = object.get(mapKey);
                if (mapElement != null && mapElement.isJsonObject()) {
                    String argName = stringOf(
                            mapElement.getAsJsonObject(), "arg");
                    Character letter = eventArgs.get(argName);
                    if (letter == null || letter != 'a') {
                        throw badValue(what, "\"" + argName + "\" is "
                                + "not a set of values this event "
                                + "passes" + eventArgsHint());
                    }
                    BlockBean argBean = newBean("getArg", "a");
                    argBean.spec = argName;
                    source = "@" + argBean.id;
                } else {
                    source = stringOf(object, mapKey);
                    Integer kind = variables.get(source);
                    if (kind == null || kind != 3) {
                        throw badValue(what, "\"" + source + "\" is "
                                + "not a map variable on this screen");
                    }
                }
                String key = stringOf(object, "key");
                if (key.isEmpty()) {
                    throw badValue(what, "reading a map needs its "
                            + "\"key\"");
                }
                if ("map_get".equals(mapKey)) {
                    if (slot != 's') {
                        throw badValue(what, "a value from a map is "
                                + "text and does not fit here");
                    }
                    BlockBean bean = newBean("mapGet", "s");
                    bean.parameters.add(source);
                    bean.parameters.add(key);
                    return "@" + bean.id;
                }
                if (slot != 'b') {
                    throw badValue(what, "whether a map has a key is "
                            + "true/false and does not fit here");
                }
                BlockBean bean = newBean("mapContainKey", "b");
                bean.parameters.add(source);
                bean.parameters.add(key);
                return "@" + bean.id;
            }
            if (object.has("instance_of")) {
                // %s instanceof %s: the second slot is a class
                // name the editor lets its user type freely.
                if (slot != 'b') {
                    throw badValue(what, "that yes/no value does "
                            + "not fit here");
                }
                String className = stringOf(object, "class");
                if (!className.matches(
                        "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$]"
                                + "[A-Za-z0-9_$]*)*")) {
                    throw badValue(what, "this needs \"class\": a "
                            + "class name like String or TextView");
                }
                BlockBean bean = newBean("instanceOfOperator", "b");
                bean.parameters.add(expr(object.get("instance_of"),
                        's', what));
                bean.parameters.add(className);
                return "@" + bean.id;
            }
            if (object.has("replace_all")) {
                if (slot != 's') {
                    throw badValue(what, "that text does not fit "
                            + "here");
                }
                BlockBean bean = newBean("stringReplaceAll", "s");
                bean.parameters.add(expr(object.get("replace_all"),
                        's', what));
                bean.parameters.add(expr(object.get("regex"),
                        's', what));
                bean.parameters.add(expr(object.get("with"),
                        's', what));
                return "@" + bean.id;
            }
            if (object.has("json_of_list_map")) {
                if (slot != 's') {
                    throw badValue(what, "JSON text does not fit "
                            + "here");
                }
                String list = stringOf(object, "json_of_list_map");
                Integer listKind = lists.get(list);
                if (listKind == null || listKind != 3) {
                    throw badValue(what, "there is no list of maps "
                            + "called " + list + " on this screen");
                }
                BlockBean bean = newBean("listMapToStr", "s");
                bean.parameters.add(list);
                return "@" + bean.id;
            }
            if (object.has("has_extra")) {
                // Whether the screen that opened this one passed a
                // named extra (intentHasExtra, a single text slot).
                if (slot != 'b') {
                    throw badValue(what, "that yes/no value does "
                            + "not fit here");
                }
                String key = stringOf(object, "has_extra");
                if (key.isEmpty()) {
                    throw badValue(what, "this needs the name of "
                            + "the extra to look for");
                }
                BlockBean bean = newBean("intentHasExtra", "b");
                bean.parameters.add(key);
                return "@" + bean.id;
            }
            if (object.has("asset_text")) {
                // The text of an asset file opened earlier in this
                // handler by open_asset (copyAssetFile over the
                // stream that block declared).
                if (slot != 's') {
                    throw badValue(what, "that text does not fit "
                            + "here");
                }
                String name = stringOf(object, "asset_text");
                if (!assetStreams.contains(name)) {
                    throw badValue(what, "no asset stream called "
                            + name + " was opened in this handler; "
                            + "use open_asset first");
                }
                BlockBean bean = newBean("copyAssetFile", "s");
                bean.parameters.add(name);
                return "@" + bean.id;
            }
            if (object.has("jpeg_rotation")) {
                if (slot != 'd') {
                    throw badValue(what, "that number does not fit "
                            + "here");
                }
                BlockBean bean = newBean("getJpegRotate", "d");
                bean.parameters.add(expr(object.get("jpeg_rotation"),
                        's', what));
                return "@" + bean.id;
            }
            if (object.has("public_dir")) {
                // A public folder on shared storage; the stored
                // value is the Environment constant name itself.
                if (slot != 's') {
                    throw badValue(what, "that text does not fit "
                            + "here");
                }
                String constant = stringOf(object, "public_dir")
                        .toUpperCase(Locale.ROOT);
                java.util.Set<String> allowed = java.util.Set.of(
                        "DIRECTORY_MUSIC", "DIRECTORY_PODCASTS",
                        "DIRECTORY_RINGTONES", "DIRECTORY_ALARMS",
                        "DIRECTORY_NOTIFICATIONS", "DIRECTORY_PICTURES",
                        "DIRECTORY_MOVIES", "DIRECTORY_DOWNLOADS",
                        "DIRECTORY_DCIM", "DIRECTORY_DOCUMENT");
                if (!allowed.contains(constant)) {
                    throw badValue(what, "\"public_dir\" must be "
                            + "one of: " + String.join(", ", allowed));
                }
                BlockBean bean = newBean("getPublicDir", "s");
                bean.parameters.add(constant);
                return "@" + bean.id;
            }
            if (object.has("list_map_value")
                    || object.has("list_map_has")) {
                // Reading inside the map at one position of a list
                // of maps. Fx emits the two blocks with different
                // parameter orders; both are honoured exactly.
                boolean value = object.has("list_map_value");
                if (slot != (value ? 's' : 'b')) {
                    throw badValue(what, "that value does not fit "
                            + "here");
                }
                String list = stringOf(object, value
                        ? "list_map_value" : "list_map_has");
                Integer listKind = lists.get(list);
                if (listKind == null || listKind != 3) {
                    throw badValue(what, "there is no list of maps "
                            + "called " + list + " on this screen");
                }
                String index = expr(object.get("index"), 'd', what);
                String key = expr(object.get("key"), 's', what);
                BlockBean bean = newBean(value ? "getAtListMap"
                        : "containListMap", value ? "s" : "b");
                if (value) {
                    bean.parameters.add(index);
                    bean.parameters.add(key);
                    bean.parameters.add(list);
                } else {
                    bean.parameters.add(list);
                    bean.parameters.add(index);
                    bean.parameters.add(key);
                }
                return "@" + bean.id;
            }
            if (object.has("pattern_md5_of")
                    || object.has("pattern_sha1_of")
                    || object.has("pattern_text_of")) {
                // A PatternLockView's drawn pattern (kept in a list
                // of text) as MD5 / SHA1 / plain text.
                if (slot != 's') {
                    throw badValue(what, "that text does not fit "
                            + "here");
                }
                String key = object.has("pattern_md5_of")
                        ? "pattern_md5_of" : object.has(
                                "pattern_sha1_of") ? "pattern_sha1_of"
                                : "pattern_text_of";
                ViewBean widget = findWidget(byId,
                        stringOf(object, key));
                if (widget == null || !"PatternLockView".equals(
                        AgentEdits.typeName(widget.type))) {
                    throw badValue(what, "there is no pattern view "
                            + "called " + stringOf(object, key)
                            + " on this screen");
                }
                String list = stringOf(object, "list");
                Integer listKind = lists.get(list);
                if (listKind == null || listKind != 2) {
                    throw badValue(what, "the pattern is kept in a "
                            + "list of text; \"" + list + "\" is "
                            + "not one on this screen");
                }
                BlockBean bean = newBean(
                        "pattern_md5_of".equals(key) ? "patternToMD5"
                                : "pattern_sha1_of".equals(key)
                                        ? "patternToSha1"
                                        : "patternToString", "s");
                bean.parameters.add(widget.id);
                bean.parameters.add(list);
                return "@" + bean.id;
            }
            if (object.has("visibility_is")) {
                // checkViewVisibility: a widget's visibility
                // compared against VISIBLE / INVISIBLE / GONE.
                if (slot != 'b') {
                    throw badValue(what, "that yes/no value does "
                            + "not fit here");
                }
                ViewBean widget = findWidget(byId,
                        stringOf(object, "visibility_is"));
                if (widget == null) {
                    throw badValue(what, "there is no widget called "
                            + stringOf(object, "visibility_is")
                            + " on this screen");
                }
                String mode = stringOf(object, "mode")
                        .toUpperCase(Locale.ROOT);
                if (!mode.equals("VISIBLE") && !mode.equals(
                        "INVISIBLE") && !mode.equals("GONE")) {
                    throw badValue(what, "\"mode\" must be VISIBLE, "
                            + "INVISIBLE or GONE");
                }
                BlockBean bean = newBean("checkViewVisibility", "b");
                bean.parameters.add(widget.id);
                bean.parameters.add(mode);
                return "@" + bean.id;
            }
            if (object.has("pref_get") || object.has("pref_has")
                    || object.has("calendar_millis")
                    || object.has("calendar_text")
                    || object.has("calendar_diff")
                    || object.has("mp_position_of")
                    || object.has("mp_duration_of")
                    || object.has("mp_playing")
                    || object.has("mp_looping")
                    || object.has("anim_running")
                    || object.has("tts_speaking")
                    || object.has("bt_enabled") || object.has("bt_on")
                    || object.has("ad_loaded")
                    || object.has("fb_push_key")
                    || object.has("bt_uuid")
                    || object.has("load_sound")
                    || object.has("play_sound")) {
                // Component reporters (wave 10): the component id
                // is the first slot, validated against the screen's
                // component list and type, exactly as the palette
                // gates these blocks.
                String key = null;
                for (String candidate : new String[]{"pref_get",
                        "pref_has", "calendar_millis", "calendar_text",
                        "calendar_diff", "mp_position_of",
                        "mp_duration_of", "mp_playing", "mp_looping",
                        "anim_running", "tts_speaking", "bt_enabled",
                        "bt_on", "ad_loaded", "fb_push_key", "bt_uuid",
                        "load_sound", "play_sound"}) {
                    if (object.has(candidate)) {
                        key = candidate;
                        break;
                    }
                }
                int type;
                char needed;
                String opCode;
                switch (key) {
                    case "pref_get" -> {
                        type = ComponentBean.COMPONENT_TYPE_SHAREDPREF;
                        needed = 's';
                        opCode = "fileGetData";
                    }
                    case "pref_has" -> {
                        type = ComponentBean.COMPONENT_TYPE_SHAREDPREF;
                        needed = 'b';
                        opCode = "fileContainsData";
                    }
                    case "calendar_millis" -> {
                        type = ComponentBean.COMPONENT_TYPE_CALENDAR;
                        needed = 'd';
                        opCode = "calendarGetTime";
                    }
                    case "calendar_text" -> {
                        type = ComponentBean.COMPONENT_TYPE_CALENDAR;
                        needed = 's';
                        opCode = "calendarFormat";
                    }
                    case "calendar_diff" -> {
                        type = ComponentBean.COMPONENT_TYPE_CALENDAR;
                        needed = 'd';
                        opCode = "calendarDiff";
                    }
                    case "mp_position_of" -> {
                        type = ComponentBean.COMPONENT_TYPE_MEDIAPLAYER;
                        needed = 'd';
                        opCode = "mediaplayerGetCurrent";
                    }
                    case "mp_duration_of" -> {
                        type = ComponentBean.COMPONENT_TYPE_MEDIAPLAYER;
                        needed = 'd';
                        opCode = "mediaplayerGetDuration";
                    }
                    case "mp_playing" -> {
                        type = ComponentBean.COMPONENT_TYPE_MEDIAPLAYER;
                        needed = 'b';
                        opCode = "mediaplayerIsPlaying";
                    }
                    case "mp_looping" -> {
                        type = ComponentBean.COMPONENT_TYPE_MEDIAPLAYER;
                        needed = 'b';
                        opCode = "mediaplayerIsLooping";
                    }
                    case "anim_running" -> {
                        type = ComponentBean
                                .COMPONENT_TYPE_OBJECTANIMATOR;
                        needed = 'b';
                        opCode = "objectanimatorIsRunning";
                    }
                    case "tts_speaking" -> {
                        type = ComponentBean
                                .COMPONENT_TYPE_TEXT_TO_SPEECH;
                        needed = 'b';
                        opCode = "textToSpeechIsSpeaking";
                    }
                    case "bt_enabled" -> {
                        type = ComponentBean
                                .COMPONENT_TYPE_BLUETOOTH_CONNECT;
                        needed = 'b';
                        opCode = "bluetoothConnectIsBluetoothEnabled";
                    }
                    case "bt_on" -> {
                        type = ComponentBean
                                .COMPONENT_TYPE_BLUETOOTH_CONNECT;
                        needed = 'b';
                        opCode = "bluetoothConnectIsBluetoothActivated";
                    }
                    case "ad_loaded" -> {
                        type = ComponentBean
                                .COMPONENT_TYPE_INTERSTITIAL_AD;
                        needed = 'b';
                        opCode = "interstitialAdIsLoaded";
                    }
                    case "fb_push_key" -> {
                        type = ComponentBean.COMPONENT_TYPE_FIREBASE;
                        needed = 's';
                        opCode = "firebaseGetPushKey";
                    }
                    case "bt_uuid" -> {
                        type = ComponentBean
                                .COMPONENT_TYPE_BLUETOOTH_CONNECT;
                        needed = 's';
                        opCode = "bluetoothConnectGetRandomUuid";
                    }
                    case "load_sound" -> {
                        type = ComponentBean.COMPONENT_TYPE_SOUNDPOOL;
                        needed = 'd';
                        opCode = "soundpoolLoad";
                    }
                    default -> {
                        type = ComponentBean.COMPONENT_TYPE_SOUNDPOOL;
                        needed = 'd';
                        opCode = "soundpoolStreamPlay";
                    }
                }
                if (slot != needed) {
                    throw badValue(what, "that component value does "
                            + "not fit here");
                }
                String componentId = stringOf(object, key);
                Integer actual = components.get(componentId);
                if (actual == null || actual != type) {
                    throw badValue(what, "there is no "
                            + ComponentBean.getComponentName(null, type)
                            + " component called " + componentId
                            + " on this screen");
                }
                BlockBean bean = newBean(opCode,
                        String.valueOf(needed));
                bean.parameters.add(componentId);
                switch (key) {
                    case "pref_get", "pref_has" -> {
                        String prefKey = stringOf(object, "key");
                        if (prefKey.isEmpty()) {
                            throw badValue(what, "this needs the "
                                    + "\"key\" to read");
                        }
                        bean.parameters.add(prefKey);
                    }
                    case "calendar_text" -> {
                        String format = stringOf(object, "format");
                        if (format.isEmpty()) {
                            throw badValue(what, "this needs a "
                                    + "\"format\" like yyyy-MM-dd");
                        }
                        bean.parameters.add(format);
                    }
                    case "calendar_diff" -> {
                        String other = stringOf(object, "other");
                        Integer otherType = components.get(other);
                        if (otherType == null || otherType
                                != ComponentBean
                                        .COMPONENT_TYPE_CALENDAR) {
                            throw badValue(what, "\"other\" must "
                                    + "name another Calendar "
                                    + "component");
                        }
                        bean.parameters.add(other);
                    }
                    case "load_sound" -> {
                        String sound = stringOf(object, "sound");
                        if (!sound.matches("(?i)[a-z0-9_]+")) {
                            throw badValue(what, "this needs "
                                    + "\"sound\": a raw sound name");
                        }
                        bean.parameters.add(
                                sound.toLowerCase(Locale.ROOT));
                    }
                    case "play_sound" -> {
                        bean.parameters.add(expr(object.get("a"),
                                'd', what));
                        bean.parameters.add(expr(object.get("b"),
                                'd', what));
                    }
                    default -> {
                    }
                }
                return "@" + bean.id;
            }
            if (object.has("json_of_map") || object.has("json_of_list")) {
                // JSON text of a map or list (Operator page):
                // mapToStr / GsonListTojsonString, one slot each.
                if (slot != 's') {
                    throw badValue(what, "JSON text does not fit here");
                }
                if (object.has("json_of_map")) {
                    String name = stringOf(object, "json_of_map");
                    Integer kind = variables.get(name);
                    if (kind == null || kind != 3) {
                        throw badValue(what, "“" + name + "” is not a "
                                + "map variable on this screen");
                    }
                    BlockBean bean = newBean("mapToStr", "s");
                    bean.parameters.add(name);
                    return "@" + bean.id;
                }
                String list = stringOf(object, "json_of_list");
                if (!lists.containsKey(list)) {
                    throw badValue(what, "there is no list called "
                            + list + " on this screen");
                }
                BlockBean bean = newBean("GsonListTojsonString", "s");
                bean.parameters.add(list);
                return "@" + bean.id;
            }
            if (object.has("file_exists") || object.has("is_file")
                    || object.has("is_folder") || object.has("read_file")
                    || object.has("file_length")) {
                // File reporters (File page): fileutilisexist,
                // fileutilisfile, fileutilisdir, fileutilread and
                // fileutillength — one path slot each, Fx-verified.
                String key = object.has("file_exists") ? "file_exists"
                        : object.has("is_file") ? "is_file"
                        : object.has("is_folder") ? "is_folder"
                        : object.has("read_file") ? "read_file"
                        : "file_length";
                char needed = "read_file".equals(key) ? 's'
                        : "file_length".equals(key) ? 'd' : 'b';
                if (slot != needed) {
                    throw badValue(what, "that file value does not "
                            + "fit here");
                }
                String opCode = switch (key) {
                    case "file_exists" -> "fileutilisexist";
                    case "is_file" -> "fileutilisfile";
                    case "is_folder" -> "fileutilisdir";
                    case "read_file" -> "fileutilread";
                    default -> "fileutillength";
                };
                BlockBean bean = newBean(opCode,
                        String.valueOf(needed));
                bean.parameters.add(expr(object.get(key), 's', what));
                return "@" + bean.id;
            }
            if (object.has("index_of") || object.has("map_size")
                    || object.has("map_empty")
                    || object.has("map_has_value")) {
                if (object.has("index_of")) {
                    String list = stringOf(object, "index_of");
                    Integer listKind = lists.get(list);
                    if (listKind == null || listKind == 3) {
                        throw badValue(what, "there is no list of "
                                + "text or numbers called " + list
                                + " on this screen");
                    }
                    if (slot != 'd') {
                        throw badValue(what, "the position in " + list
                                + " is a number and does not fit here");
                    }
                    BlockBean bean = newBean(
                            listKind == 2 ? "indexListStr"
                                    : "indexListInt", "d");
                    bean.parameters.add(expr(object.get("value"),
                            listKind == 2 ? 's' : 'd', what));
                    bean.parameters.add(list);
                    return "@" + bean.id;
                }
                String name = stringOf(object,
                        object.has("map_size") ? "map_size"
                                : object.has("map_empty") ? "map_empty"
                                : "map_has_value");
                Integer kind = variables.get(name);
                if (kind == null || kind != 3) {
                    throw badValue(what, "“" + name + "” is not a map "
                            + "variable on this screen");
                }
                if (object.has("map_size")) {
                    if (slot != 'd') {
                        throw badValue(what, "the size of map " + name
                                + " is a number and does not fit here");
                    }
                    BlockBean bean = newBean("mapSize", "d");
                    bean.parameters.add(name);
                    return "@" + bean.id;
                }
                if (slot != 'b') {
                    throw badValue(what, "that is true/false and does "
                            + "not fit here");
                }
                if (object.has("map_empty")) {
                    BlockBean bean = newBean("mapIsEmpty", "b");
                    bean.parameters.add(name);
                    return "@" + bean.id;
                }
                String value = stringOf(object, "value");
                if (value.isEmpty()) {
                    throw badValue(what, "map_has_value needs the "
                            + "\"value\" text to look for");
                }
                BlockBean bean = newBean("mapContainValue", "b");
                bean.parameters.add(name);
                bean.parameters.add(value);
                return "@" + bean.id;
            }
            if (object.has("fn")) {
                // Function reporters (Math/Operator pages): one
                // shape, {"fn":"abs","a":...} — every opCode and
                // emission checked against Fx.
                String fn = stringOf(object, "fn")
                        .toLowerCase(Locale.ROOT);
                switch (fn) {
                    case "abs", "ceil", "floor", "round", "sqrt" -> {
                        if (slot != 'd') {
                            throw badValue(what, fn + " gives a "
                                    + "number and does not fit here");
                        }
                        String opCode = switch (fn) {
                            case "abs" -> "mathAbs";
                            case "ceil" -> "mathCeil";
                            case "floor" -> "mathFloor";
                            case "round" -> "mathRound";
                            default -> "mathSqrt";
                        };
                        BlockBean bean = newBean(opCode, "d");
                        bean.parameters.add(expr(object.get("a"),
                                'd', what));
                        return "@" + bean.id;
                    }
                    case "pow", "max", "min", "random" -> {
                        if (slot != 'd') {
                            throw badValue(what, fn + " gives a "
                                    + "number and does not fit here");
                        }
                        String opCode = switch (fn) {
                            case "pow" -> "mathPow";
                            case "max" -> "mathMax";
                            case "min" -> "mathMin";
                            default -> "random";
                        };
                        BlockBean bean = newBean(opCode, "d");
                        bean.parameters.add(expr(object.get("a"),
                                'd', what));
                        bean.parameters.add(expr(object.get("b"),
                                'd', what));
                        return "@" + bean.id;
                    }
                    case "pi" -> {
                        if (slot != 'd') {
                            throw badValue(what, "pi is a number "
                                    + "and does not fit here");
                        }
                        BlockBean bean = newBean("mathPi", "d");
                        return "@" + bean.id;
                    }
                    case "len" -> {
                        if (slot != 'd') {
                            throw badValue(what, "a length is a "
                                    + "number and does not fit here");
                        }
                        BlockBean bean = newBean("stringLength", "d");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "concat" -> {
                        if (slot != 's') {
                            throw badValue(what, "joined text does "
                                    + "not fit here");
                        }
                        BlockBean bean = newBean("stringJoin", "s");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        bean.parameters.add(expr(object.get("b"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "contains" -> {
                        if (slot != 'b') {
                            throw badValue(what, "whether text "
                                    + "contains text is true/false "
                                    + "and does not fit here");
                        }
                        BlockBean bean = newBean("stringContains",
                                "b");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        bean.parameters.add(expr(object.get("b"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "upper", "lower", "trim" -> {
                        if (slot != 's') {
                            throw badValue(what, fn + " gives text "
                                    + "and does not fit here");
                        }
                        String opCode = switch (fn) {
                            case "upper" -> "toUpperCase";
                            case "lower" -> "toLowerCase";
                            default -> "trim";
                        };
                        BlockBean bean = newBean(opCode, "s");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "to_number" -> {
                        if (slot != 'd') {
                            throw badValue(what, "a converted "
                                    + "number does not fit here");
                        }
                        BlockBean bean = newBean("toNumber", "d");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "to_text" -> {
                        if (slot != 's') {
                            throw badValue(what, "converted text "
                                    + "does not fit here");
                        }
                        BlockBean bean = newBean(
                                "toStringWithDecimal", "s");
                        bean.parameters.add(expr(object.get("a"),
                                'd', what));
                        return "@" + bean.id;
                    }
                    case "html" -> {
                        if (slot != 's') {
                            throw badValue(what, "HTML text does "
                                    + "not fit here");
                        }
                        BlockBean bean = newBean("html", "s");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "hash_code" -> {
                        if (slot != 'd') {
                            throw badValue(what, "a hash number "
                                    + "does not fit here");
                        }
                        BlockBean bean = newBean("toHashCode", "d");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "to_string" -> {
                        // String.valueOf((long) x): a whole number
                        // as text, without decimals.
                        if (slot != 's') {
                            throw badValue(what, "converted text "
                                    + "does not fit here");
                        }
                        BlockBean bean = newBean("toString", "s");
                        bean.parameters.add(expr(object.get("a"),
                                'd', what));
                        return "@" + bean.id;
                    }
                    case "substring" -> {
                        if (slot != 's') {
                            throw badValue(what, "part of a text "
                                    + "does not fit here");
                        }
                        BlockBean bean = newBean("stringSub", "s");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        bean.parameters.add(expr(object.get("from"),
                                'd', what));
                        bean.parameters.add(expr(object.get("to"),
                                'd', what));
                        return "@" + bean.id;
                    }
                    case "choose" -> {
                        // The ternary reporters: %b ? %d : %d or
                        // %b ? %s : %s depending on the slot it
                        // feeds (ternaryNumber/ternaryString).
                        if (slot != 'd' && slot != 's') {
                            throw badValue(what, "a chosen value is "
                                    + "a number or text and does not "
                                    + "fit here");
                        }
                        BlockBean bean = newBean(
                                slot == 'd' ? "ternaryNumber"
                                        : "ternaryString",
                                String.valueOf(slot));
                        bean.parameters.add(expr(
                                object.get("condition"), 'b', what));
                        bean.parameters.add(expr(object.get("a"),
                                slot, what));
                        bean.parameters.add(expr(object.get("b"),
                                slot, what));
                        return "@" + bean.id;
                    }
                    case "auth_email", "auth_uid", "auth_logged_in",
                            "is_connected" -> {
                        // Slot-less reporters: the signed-in user's
                        // email/uid, whether anyone is signed in
                        // (FirebaseAuth), and network state.
                        char needed = "auth_logged_in".equals(fn)
                                || "is_connected".equals(fn) ? 'b' : 's';
                        if (slot != needed) {
                            throw badValue(what, "that value does "
                                    + "not fit here");
                        }
                        BlockBean bean = newBean(
                                switch (fn) {
                                    case "auth_email" ->
                                            "firebaseauthGetCurrentUser";
                                    case "auth_uid" ->
                                            "firebaseauthGetUid";
                                    case "auth_logged_in" ->
                                            "firebaseauthIsLoggedIn";
                                    default -> "isConnected";
                                }, String.valueOf(needed));
                        return "@" + bean.id;
                    }
                    case "list_scroll_state", "pager_scroll_state",
                            "recycler_scroll_state" -> {
                        // The scroll-state constants the editor
                        // offers inside scroll events (ListView /
                        // ViewPager / RecyclerView nested names);
                        // the stored slot is the constant name.
                        if (slot != 'd') {
                            throw badValue(what, "that number does "
                                    + "not fit here");
                        }
                        String mode = stringOf(object, "mode")
                                .toUpperCase(Locale.ROOT);
                        java.util.Set<String> allowed =
                                "recycler_scroll_state".equals(fn)
                                        ? java.util.Set.of(
                                                "SCROLL_STATE_IDLE",
                                                "SCROLL_STATE_SETTLING",
                                                "SCROLL_STATE_DRAGGING")
                                        : java.util.Set.of(
                                                "SCROLL_STATE_IDLE",
                                                "SCROLL_STATE_FLING",
                                                "SCROLL_STATE_TOUCH_SCROLL");
                        if ("pager_scroll_state".equals(fn)) {
                            allowed = java.util.Set.of(
                                    "SCROLL_STATE_IDLE",
                                    "SCROLL_STATE_DRAGGING",
                                    "SCROLL_STATE_SETTLING");
                        }
                        if (!allowed.contains(mode)) {
                            throw badValue(what, "\"mode\" must be "
                                    + "one of: " + String.join(", ",
                                            allowed));
                        }
                        BlockBean bean = newBean(
                                switch (fn) {
                                    case "list_scroll_state" ->
                                            "listscrollparam";
                                    case "pager_scroll_state" ->
                                            "pagerscrollparam";
                                    default -> "recyclerscrollparam";
                                }, "d");
                        bean.parameters.add(mode);
                        return "@" + bean.id;
                    }
                    case "drawer_open" -> {
                        if (slot != 'b') {
                            throw badValue(what, "whether the drawer "
                                    + "is open is true/false and does "
                                    + "not fit here");
                        }
                        BlockBean bean = newBean("isDrawerOpen", "b");
                        return "@" + bean.id;
                    }
                    case "sin", "cos", "tan", "asin", "acos", "atan",
                            "exp", "log", "log10", "to_degrees",
                            "to_radians", "dip" -> {
                        if (slot != 'd') {
                            throw badValue(what, fn + " gives a "
                                    + "number and does not fit here");
                        }
                        String opCode = switch (fn) {
                            case "sin" -> "mathSin";
                            case "cos" -> "mathCos";
                            case "tan" -> "mathTan";
                            case "asin" -> "mathAsin";
                            case "acos" -> "mathAcos";
                            case "atan" -> "mathAtan";
                            case "exp" -> "mathExp";
                            case "log" -> "mathLog";
                            case "log10" -> "mathLog10";
                            case "to_degrees" -> "mathToDegree";
                            case "to_radians" -> "mathToRadian";
                            default -> "mathGetDip";
                        };
                        BlockBean bean = newBean(opCode, "d");
                        bean.parameters.add(expr(object.get("a"),
                                'd', what));
                        return "@" + bean.id;
                    }
                    case "e", "display_width", "display_height" -> {
                        if (slot != 'd') {
                            throw badValue(what, fn + " is a number "
                                    + "and does not fit here");
                        }
                        BlockBean bean = newBean(
                                "e".equals(fn) ? "mathE"
                                        : "display_width".equals(fn)
                                        ? "mathGetDisplayWidth"
                                        : "mathGetDisplayHeight", "d");
                        return "@" + bean.id;
                    }
                    case "replace", "replace_first" -> {
                        if (slot != 's') {
                            throw badValue(what, "replaced text does "
                                    + "not fit here");
                        }
                        BlockBean bean = newBean(
                                "replace".equals(fn) ? "stringReplace"
                                        : "stringReplaceFirst", "s");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        bean.parameters.add(expr(object.get("b"),
                                's', what));
                        bean.parameters.add(expr(object.get("c"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "index_in", "last_index_in" -> {
                        if (slot != 'd') {
                            throw badValue(what, "a position is a "
                                    + "number and does not fit here");
                        }
                        BlockBean bean = newBean(
                                "index_in".equals(fn) ? "stringIndex"
                                        : "stringLastIndex", "d");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        bean.parameters.add(expr(object.get("b"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "matches" -> {
                        if (slot != 'b') {
                            throw badValue(what, "whether text "
                                    + "matches a pattern is true/false "
                                    + "and does not fit here");
                        }
                        BlockBean bean = newBean("stringMatches", "b");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        bean.parameters.add(expr(object.get("b"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "reverse_text", "is_empty", "parse_int" -> {
                        char needed = "reverse_text".equals(fn) ? 's'
                                : "is_empty".equals(fn) ? 'b' : 'd';
                        if (slot != needed) {
                            throw badValue(what, "that value does not "
                                    + "fit here");
                        }
                        BlockBean bean = newBean(
                                "reverse_text".equals(fn) ? "reverse"
                                        : "is_empty".equals(fn)
                                        ? "isEmpty" : "strParseInteger",
                                String.valueOf(needed));
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "format_number" -> {
                        if (slot != 's') {
                            throw badValue(what, "formatted text "
                                    + "does not fit here");
                        }
                        // toStringFormat: number slot first, the
                        // DecimalFormat pattern second (Fx swaps).
                        BlockBean bean = newBean("toStringFormat", "s");
                        bean.parameters.add(expr(object.get("a"),
                                'd', what));
                        bean.parameters.add(expr(object.get("b"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "substring_from" -> {
                        if (slot != 's') {
                            throw badValue(what, "part of a text "
                                    + "does not fit here");
                        }
                        BlockBean bean = newBean("stringSubSingle",
                                "s");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        bean.parameters.add(expr(object.get("from"),
                                'd', what));
                        return "@" + bean.id;
                    }
                    case "starts_with", "ends_with" -> {
                        if (slot != 'b') {
                            throw badValue(what, "whether text starts "
                                    + "or ends with text is true/false "
                                    + "and does not fit here");
                        }
                        BlockBean bean = newBean(
                                "starts_with".equals(fn)
                                        ? "fileutilStartsWith"
                                        : "fileutilEndsWith", "b");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        bean.parameters.add(expr(object.get("b"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "last_segment" -> {
                        if (slot != 's') {
                            throw badValue(what, "the last part of a "
                                    + "path is text and does not fit "
                                    + "here");
                        }
                        BlockBean bean = newBean(
                                "fileutilGetLastSegmentPath", "s");
                        bean.parameters.add(expr(object.get("a"),
                                's', what));
                        return "@" + bean.id;
                    }
                    case "external_dir", "app_dir" -> {
                        if (slot != 's') {
                            throw badValue(what, "a folder path is "
                                    + "text and does not fit here");
                        }
                        BlockBean bean = newBean(
                                "external_dir".equals(fn)
                                        ? "getExternalStorageDir"
                                        : "getPackageDataDir", "s");
                        return "@" + bean.id;
                    }
                    default -> throw badValue(what, "unknown "
                            + "function \"" + fn + "\" (abs, ceil, "
                            + "floor, round, sqrt, pow, max, min, "
                            + "random, pi, len, concat, contains, "
                            + "upper, lower, trim, substring, "
                            + "substring_from, to_number, to_text, "
                            + "starts_with, ends_with, last_segment, "
                            + "external_dir, app_dir, sin, cos, tan, "
                            + "asin, acos, atan, exp, log, log10, "
                            + "to_degrees, to_radians, dip, e, "
                            + "display_width, display_height, replace, "
                            + "replace_first, index_in, last_index_in, "
                            + "matches, reverse_text, is_empty, "
                            + "parse_int, format_number)");
                }
            }
            if (object.has("extra")) {
                // The value another screen passed when it opened
                // this one: the editor's intentGetString reporter
                // (getIntent().getStringExtra(key)).
                if (slot != 's') {
                    throw badValue(what, "an extra passed to this "
                            + "screen is text and does not fit here");
                }
                String key = stringOf(object, "extra");
                if (key.isEmpty()) {
                    throw badValue(what, "an extra needs its key, "
                            + "e.g. {\"extra\":\"name\"}");
                }
                BlockBean bean = newBean("intentGetString", "s");
                bean.parameters.add(key);
                return "@" + bean.id;
            }
            if (object.has("width_of") || object.has("height_of")
                    || object.has("alpha_of") || object.has("rotation_of")
                    || object.has("scale_x_of")
                    || object.has("scale_y_of")
                    || object.has("translation_x_of")
                    || object.has("translation_y_of")
                    || object.has("rating_of") || object.has("max_of")
                    || object.has("page_of") || object.has("selection_of")
                    || object.has("selection_start_of")
                    || object.has("selection_end_of")
                    || object.has("video_position_of")
                    || object.has("video_duration_of")
                    || object.has("video_playing")
                    || object.has("web_progress_of")
                    || object.has("can_go_back")
                    || object.has("can_go_forward")
                    || object.has("can_pause")
                    || object.has("can_seek_back")
                    || object.has("can_seek_forward")
                    || object.has("last_visible_of")
                    || object.has("location_x_of")
                    || object.has("location_y_of")
                    || object.has("badge_count_of")
                    || object.has("otp_text_of")
                    || object.has("counter_max_of")
                    || object.has("page_limit_of")) {
                // Widget geometry/state reporters (View page):
                // getWidth/getHeight/getAlpha/getRotation/getScaleX/
                // getScaleY/getTranslationX/getTranslationY for any
                // widget, plus per-family getters — every opCode and
                // single-widget slot checked against the palette
                // table and Fx.
                String key = null;
                for (String candidate : new String[]{"width_of",
                        "height_of", "alpha_of", "rotation_of",
                        "scale_x_of", "scale_y_of", "translation_x_of",
                        "translation_y_of", "rating_of", "max_of",
                        "page_of", "selection_of", "selection_start_of",
                        "selection_end_of", "video_position_of",
                        "video_duration_of", "video_playing",
                        "web_progress_of", "can_go_back",
                        "can_go_forward", "can_pause", "can_seek_back",
                        "can_seek_forward", "last_visible_of",
                        "location_x_of", "location_y_of",
                        "badge_count_of",
                        "otp_text_of", "counter_max_of",
                        "page_limit_of"}) {
                    if (object.has(candidate)) {
                        key = candidate;
                        break;
                    }
                }
                String id = stringOf(object, key);
                ViewBean widget = findWidget(byId, id);
                if (widget == null) {
                    throw badValue(what, "there is no widget called "
                            + id + " on this screen");
                }
                String typeName = AgentEdits.typeName(widget.type);
                char needed = "video_playing".equals(key)
                        || "can_go_back".equals(key)
                        || "can_go_forward".equals(key)
                        || "can_pause".equals(key)
                        || "can_seek_back".equals(key)
                        || "can_seek_forward".equals(key) ? 'b'
                        : "otp_text_of".equals(key) ? 's' : 'd';
                if (slot != needed) {
                    throw badValue(what, "that value does not fit here");
                }
                String opCode;
                String family = switch (key) {
                    case "width_of" -> {
                        opCode = "getWidth";
                        yield "";
                    }
                    case "height_of" -> {
                        opCode = "getHeight";
                        yield "";
                    }
                    case "alpha_of" -> {
                        opCode = "getAlpha";
                        yield "";
                    }
                    case "rotation_of" -> {
                        opCode = "getRotate";
                        yield "";
                    }
                    case "scale_x_of" -> {
                        opCode = "getScaleX";
                        yield "";
                    }
                    case "scale_y_of" -> {
                        opCode = "getScaleY";
                        yield "";
                    }
                    case "translation_x_of" -> {
                        opCode = "getTranslationX";
                        yield "";
                    }
                    case "translation_y_of" -> {
                        opCode = "getTranslationY";
                        yield "";
                    }
                    case "rating_of" -> {
                        opCode = "getRating";
                        yield "RatingBar";
                    }
                    case "max_of" -> {
                        opCode = "seekBarGetMax";
                        yield "SeekBar";
                    }
                    case "page_of" -> {
                        opCode = "pagerGetCurrentItem";
                        yield "ViewPager";
                    }
                    case "selection_of" -> {
                        opCode = "spnGetSelection";
                        yield "Spinner";
                    }
                    case "selection_start_of" -> {
                        opCode = "EdittextGetselectionStart";
                        yield "EditText";
                    }
                    case "selection_end_of" -> {
                        opCode = "EdittextGetselectionEnd";
                        yield "EditText";
                    }
                    case "video_position_of" -> {
                        opCode = "videoviewGetCurrentPosition";
                        yield "VideoView";
                    }
                    case "video_duration_of" -> {
                        opCode = "videoviewGetDuration";
                        yield "VideoView";
                    }
                    case "video_playing" -> {
                        opCode = "videoviewIsPlaying";
                        yield "VideoView";
                    }
                    case "web_progress_of" -> {
                        opCode = "webviewGetProgress";
                        yield "WebView";
                    }
                    case "can_go_back" -> {
                        opCode = "webViewCanGoBack";
                        yield "WebView";
                    }
                    case "can_go_forward" -> {
                        opCode = "webViewCanGoForward";
                        yield "WebView";
                    }
                    case "can_pause" -> {
                        opCode = "videoviewCanPause";
                        yield "VideoView";
                    }
                    case "can_seek_back" -> {
                        opCode = "videoviewCanSeekBackward";
                        yield "VideoView";
                    }
                    case "can_seek_forward" -> {
                        opCode = "videoviewCanSeekForward";
                        yield "VideoView";
                    }
                    case "last_visible_of" -> {
                        opCode = "getLastVisiblePosition";
                        yield "ListView";
                    }
                    case "location_x_of" -> {
                        opCode = "getLocationX";
                        yield "";
                    }
                    case "location_y_of" -> {
                        opCode = "getLocationY";
                        yield "";
                    }
                    case "badge_count_of" -> {
                        opCode = "getBadgeCount";
                        yield "BadgeView";
                    }
                    case "otp_text_of" -> {
                        opCode = "otpViewGetOTPText";
                        yield "OTPView";
                    }
                    case "counter_max_of" -> {
                        opCode = "tilGetCounterMaxLength";
                        yield "TextInputLayout";
                    }
                    default -> {
                        opCode = "pagerGetOffscreenPageLimit";
                        yield "ViewPager";
                    }
                };
                if (!family.isEmpty() && !family.equals(typeName)) {
                    throw badValue(what, widget.id + " is a "
                            + typeName + ", not a " + family);
                }
                BlockBean bean = newBean(opCode,
                        String.valueOf(needed));
                bean.parameters.add(widget.id);
                return "@" + bean.id;
            }
            if (object.has("text_of") || object.has("url_of")
                    || object.has("checked") || object.has("enabled")
                    || object.has("progress_of")) {
                // Reporters that read a widget's live value: the
                // editor's getText/webViewGetUrl/getChecked/
                // getEnable/seekBarGetProgress blocks, one widget-id
                // parameter each, Fx-verified emissions.
                String key = object.has("text_of") ? "text_of"
                        : object.has("url_of") ? "url_of"
                        : object.has("checked") ? "checked"
                        : object.has("enabled") ? "enabled"
                        : "progress_of";
                String id = stringOf(object, key);
                ViewBean widget = findWidget(byId, id);
                if (widget == null) {
                    throw badValue(what, "there is no widget called "
                            + id + " on this screen");
                }
                String typeName = AgentEdits.typeName(widget.type);
                switch (key) {
                    case "text_of" -> {
                        if (slot != 's') {
                            throw badValue(what, "the text of "
                                    + widget.id + " is text and does "
                                    + "not fit here");
                        }
                        if (!TEXT_TYPES.contains(typeName)) {
                            throw badValue(what, widget.id + " is a "
                                    + typeName + ", which has no text");
                        }
                        BlockBean bean = newBean("getText", "s");
                        bean.parameters.add(widget.id);
                        return "@" + bean.id;
                    }
                    case "url_of" -> {
                        if (slot != 's') {
                            throw badValue(what, "the url of "
                                    + widget.id + " is text and does "
                                    + "not fit here");
                        }
                        if (!"WebView".equals(typeName)) {
                            throw badValue(what, widget.id + " is a "
                                    + typeName + ", not a WebView");
                        }
                        BlockBean bean = newBean("webViewGetUrl", "s");
                        bean.parameters.add(widget.id);
                        return "@" + bean.id;
                    }
                    case "checked" -> {
                        if (slot != 'b') {
                            throw badValue(what, "whether " + widget.id
                                    + " is checked is true/false and "
                                    + "does not fit here");
                        }
                        if (!"CheckBox".equals(typeName)) {
                            throw badValue(what, widget.id + " is a "
                                    + typeName + ", not a CheckBox");
                        }
                        BlockBean bean = newBean("getChecked", "b");
                        bean.parameters.add(widget.id);
                        return "@" + bean.id;
                    }
                    case "enabled" -> {
                        if (slot != 'b') {
                            throw badValue(what, "whether " + widget.id
                                    + " is enabled is true/false and "
                                    + "does not fit here");
                        }
                        BlockBean bean = newBean("getEnable", "b");
                        bean.parameters.add(widget.id);
                        return "@" + bean.id;
                    }
                    default -> {
                        if (slot != 'd') {
                            throw badValue(what, "the progress of "
                                    + widget.id + " is a number and "
                                    + "does not fit here");
                        }
                        if (!"SeekBar".equals(typeName)) {
                            throw badValue(what, widget.id + " is a "
                                    + typeName + ", not a SeekBar");
                        }
                        BlockBean bean = newBean(
                                "seekBarGetProgress", "d");
                        bean.parameters.add(widget.id);
                        return "@" + bean.id;
                    }
                }
            }
            if (object.has("op")) {
                String operation = stringOf(object, "op");
                switch (operation) {
                    case "+", "-", "*", "/", "%" -> {
                        if (slot != 'd') {
                            throw badValue(what, "a calculation does "
                                    + "not fit here");
                        }
                        BlockBean bean = newBean(operation, "d");
                        bean.parameters.add(expr(object.get("a"), 'd',
                                what));
                        bean.parameters.add(expr(object.get("b"), 'd',
                                what));
                        return "@" + bean.id;
                    }
                    case "<", ">", "=" -> {
                        if (slot != 'b') {
                            throw badValue(what, "a comparison does "
                                    + "not fit here");
                        }
                        if ("=".equals(operation)
                                && (isBoolExpr(object.get("a"))
                                        || isBoolExpr(object.get("b")))) {
                            // True/false equality (a checkbox
                            // checked, a flag set): the same "="
                            // block, fed booleans — Fx emits ==,
                            // which is correct for booleans.
                            BlockBean bean = newBean(operation, "b");
                            bean.parameters.add(expr(object.get("a"),
                                    'b', what));
                            bean.parameters.add(expr(object.get("b"),
                                    'b', what));
                            return "@" + bean.id;
                        }
                        if ("=".equals(operation)
                                && (isTextExpr(object.get("a"))
                                        || isTextExpr(object.get("b")))) {
                            // Text equality is its own editor block;
                            // the numeric "=" would emit ==, which
                            // compares string objects, not text.
                            BlockBean bean = newBean("stringEquals",
                                    "b");
                            bean.parameters.add(expr(object.get("a"),
                                    's', what));
                            bean.parameters.add(expr(object.get("b"),
                                    's', what));
                            return "@" + bean.id;
                        }
                        BlockBean bean = newBean(operation, "b");
                        bean.parameters.add(expr(object.get("a"), 'd',
                                what));
                        bean.parameters.add(expr(object.get("b"), 'd',
                                what));
                        return "@" + bean.id;
                    }
                    case "and", "or" -> {
                        if (slot != 'b') {
                            throw badValue(what, "a logic value does "
                                    + "not fit here");
                        }
                        BlockBean bean = newBean(
                                "and".equals(operation) ? "&&" : "||",
                                "b");
                        bean.parameters.add(expr(object.get("a"), 'b',
                                what));
                        bean.parameters.add(expr(object.get("b"), 'b',
                                what));
                        return "@" + bean.id;
                    }
                    case "not" -> {
                        if (slot != 'b') {
                            throw badValue(what, "a logic value does "
                                    + "not fit here");
                        }
                        BlockBean bean = newBean("not", "b");
                        bean.parameters.add(expr(object.get("a"), 'b',
                                what));
                        return "@" + bean.id;
                    }
                    default -> throw badValue(what, "unknown operation "
                            + "\"" + operation + "\" (use + - * / % "
                            + "< > = and or not)");
                }
            }
            throw badValue(what, "unsupported value shape");
        }

        /** A map_get/map_has source, named or event-passed. */
        private String mapSourceText(JsonObject object, String key) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonObject()) {
                return "the " + stringOf(value.getAsJsonObject(), "arg")
                        + " values";
            }
            return stringOf(object, key);
        }

        /** One value expression in plain words, for the card. */
        String describeExpr(JsonElement element) {
            if (element == null || element.isJsonNull()) {
                return "?";
            }
            if (element.isJsonPrimitive()) {
                return element.getAsJsonPrimitive().isString()
                        ? "“" + element.getAsString() + "”"
                        : element.getAsString();
            }
            if (!element.isJsonObject()) {
                return "?";
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("arg")) {
                return "the " + stringOf(object, "arg")
                        + " this event passes";
            }
            if (object.has("fn")) {
                String fn = stringOf(object, "fn")
                        .toLowerCase(Locale.ROOT);
                return switch (fn) {
                    case "len" -> "length of "
                            + describeExpr(object.get("a"));
                    case "concat" -> describeExpr(object.get("a"))
                            + " joined with "
                            + describeExpr(object.get("b"));
                    case "contains" -> describeExpr(object.get("a"))
                            + " contains "
                            + describeExpr(object.get("b"));
                    case "substring" -> "part of "
                            + describeExpr(object.get("a")) + " from "
                            + describeExpr(object.get("from")) + " to "
                            + describeExpr(object.get("to"));
                    case "pow" -> describeExpr(object.get("a"))
                            + " to the power of "
                            + describeExpr(object.get("b"));
                    case "random" -> "a random number from "
                            + describeExpr(object.get("a")) + " to "
                            + describeExpr(object.get("b"));
                    case "pi" -> "pi";
                    case "starts_with" -> describeExpr(object.get("a"))
                            + " starts with "
                            + describeExpr(object.get("b"));
                    case "ends_with" -> describeExpr(object.get("a"))
                            + " ends with "
                            + describeExpr(object.get("b"));
                    case "last_segment" -> "the last part of "
                            + describeExpr(object.get("a"));
                    case "external_dir" -> "the device storage folder";
                    case "app_dir" -> "the app's private folder";
                    case "replace" -> describeExpr(object.get("a"))
                            + " with "
                            + describeExpr(object.get("b"))
                            + " replaced by "
                            + describeExpr(object.get("c"));
                    case "replace_first" -> describeExpr(object.get("a"))
                            + " with the first "
                            + describeExpr(object.get("b"))
                            + " replaced by "
                            + describeExpr(object.get("c"));
                    case "index_in" -> "the position of "
                            + describeExpr(object.get("b")) + " in "
                            + describeExpr(object.get("a"));
                    case "last_index_in" -> "the last position of "
                            + describeExpr(object.get("b")) + " in "
                            + describeExpr(object.get("a"));
                    case "matches" -> describeExpr(object.get("a"))
                            + " matches the pattern "
                            + describeExpr(object.get("b"));
                    case "reverse_text" -> describeExpr(object.get("a"))
                            + " reversed";
                    case "is_empty" -> describeExpr(object.get("a"))
                            + " is empty";
                    case "parse_int" -> "the whole number in "
                            + describeExpr(object.get("a"));
                    case "format_number" -> describeExpr(object.get("a"))
                            + " formatted as "
                            + describeExpr(object.get("b"));
                    case "substring_from" -> "part of "
                            + describeExpr(object.get("a")) + " from "
                            + describeExpr(object.get("from"));
                    case "choose" -> describeExpr(object.get("a"))
                            + " if "
                            + describeExpr(object.get("condition"))
                            + ", otherwise "
                            + describeExpr(object.get("b"));
                    case "drawer_open" -> "the drawer is open";
                    case "auth_email" -> "the signed-in user's email";
                    case "auth_uid" -> "the signed-in user's ID";
                    case "auth_logged_in" -> "someone is signed in";
                    case "is_connected" -> "the device is online";
                    case "html" -> "the HTML in a text";
                    case "hash_code" -> "a text's hash number";
                    case "to_string" -> "a whole number as text";
                    case "list_scroll_state" -> "a list scroll state";
                    case "pager_scroll_state" -> "a pager scroll state";
                    case "recycler_scroll_state" ->
                            "a recycler scroll state";
                    case "display_width" -> "the screen width";
                    case "display_height" -> "the screen height";
                    case "e" -> "e (2.718…)";
                    default -> fn + " of "
                            + describeExpr(object.get("a"))
                            + (object.has("b") ? " and "
                                    + describeExpr(object.get("b"))
                                    : "");
                };
            }
            if (object.has("extra")) {
                return "extra \u201C" + stringOf(object, "extra")
                        + "\u201D";
            }
            if (object.has("list_length")) {
                return "length of " + stringOf(object, "list_length");
            }
            if (object.has("item_of")) {
                return "item " + describeExpr(object.get("index"))
                        + " of " + stringOf(object, "item_of");
            }
            if (object.has("list_has")) {
                return stringOf(object, "list_has") + " contains "
                        + describeExpr(object.get("value"));
            }
            if (object.has("map_get")) {
                return "map " + mapSourceText(object, "map_get")
                        + " at “" + stringOf(object, "key")
                        + "”";
            }
            if (object.has("map_has")) {
                return "map " + mapSourceText(object, "map_has")
                        + " has “" + stringOf(object, "key")
                        + "”";
            }
            if (object.has("map_size")) {
                return "the number of entries in map "
                        + stringOf(object, "map_size");
            }
            if (object.has("map_empty")) {
                return "map " + stringOf(object, "map_empty")
                        + " is empty";
            }
            if (object.has("map_has_value")) {
                return "map " + stringOf(object, "map_has_value")
                        + " contains \u201C" + stringOf(object, "value")
                        + "\u201D";
            }
            if (object.has("index_of")) {
                return "the position of "
                        + describeExpr(object.get("value")) + " in "
                        + stringOf(object, "index_of");
            }
            if (object.has("instance_of")) {
                return describeExpr(object.get("instance_of"))
                        + " is a " + stringOf(object, "class");
            }
            if (object.has("replace_all")) {
                return describeExpr(object.get("replace_all"))
                        + " with matches replaced";
            }
            if (object.has("json_of_list_map")) {
                return "list of maps " + stringOf(object,
                        "json_of_list_map") + " as JSON text";
            }
            if (object.has("has_extra")) {
                return "the screen was passed a “"
                        + stringOf(object, "has_extra") + "”";
            }
            if (object.has("asset_text")) {
                return "the text of asset stream "
                        + stringOf(object, "asset_text");
            }
            if (object.has("jpeg_rotation")) {
                return "the rotation of the photo at "
                        + describeExpr(object.get("jpeg_rotation"));
            }
            if (object.has("public_dir")) {
                return "the public "
                        + stringOf(object, "public_dir") + " folder";
            }
            if (object.has("list_map_value")) {
                return "the “" + describeExpr(object.get("key"))
                        + "” in the map at "
                        + describeExpr(object.get("index")) + " of "
                        + stringOf(object, "list_map_value");
            }
            if (object.has("list_map_has")) {
                return stringOf(object, "list_map_has") + " has “"
                        + describeExpr(object.get("key")) + "” at "
                        + describeExpr(object.get("index"));
            }
            if (object.has("pattern_md5_of")
                    || object.has("pattern_sha1_of")
                    || object.has("pattern_text_of")) {
                String key = object.has("pattern_md5_of")
                        ? "pattern_md5_of" : object.has(
                                "pattern_sha1_of") ? "pattern_sha1_of"
                                : "pattern_text_of";
                return "the pattern of " + stringOf(object, key);
            }
            if (object.has("visibility_is")) {
                return stringOf(object, "visibility_is") + " is "
                        + stringOf(object, "mode");
            }
            for (String key : new String[]{"pref_get", "pref_has",
                    "calendar_millis", "calendar_text", "calendar_diff",
                    "mp_position_of", "mp_duration_of", "mp_playing",
                    "mp_looping", "anim_running", "tts_speaking",
                    "bt_enabled", "bt_on", "ad_loaded", "fb_push_key",
                    "bt_uuid", "load_sound", "play_sound"}) {
                if (object.has(key)) {
                    String label = switch (key) {
                        case "pref_get" -> "the saved text at “"
                                + stringOf(object, "key") + "” in ";
                        case "pref_has" -> "has the key “"
                                + stringOf(object, "key") + "”: ";
                        case "calendar_millis" -> "the time of ";
                        case "calendar_text" -> "the date of ";
                        case "calendar_diff" -> "the difference "
                                + "between ";
                        case "mp_position_of" -> "the position of ";
                        case "mp_duration_of" -> "the duration of ";
                        case "mp_playing" -> "is playing: ";
                        case "mp_looping" -> "is looping: ";
                        case "anim_running" -> "is running: ";
                        case "tts_speaking" -> "is speaking: ";
                        case "bt_enabled" -> "Bluetooth is on (";
                        case "bt_on" -> "Bluetooth is activated (";
                        case "ad_loaded" -> "has an ad loaded: ";
                        case "fb_push_key" -> "a new push key in ";
                        case "bt_uuid" -> "a random ID from ";
                        case "load_sound" -> "the sound ID loaded "
                                + "into ";
                        default -> "playing a sound in ";
                    };
                    return label + stringOf(object, key);
                }
            }
            if (object.has("json_of_map")) {
                return "map " + stringOf(object, "json_of_map")
                        + " as JSON text";
            }
            if (object.has("json_of_list")) {
                return "list " + stringOf(object, "json_of_list")
                        + " as JSON text";
            }
            if (object.has("file_exists")) {
                return "the file at "
                        + describeExpr(object.get("file_exists"))
                        + " exists";
            }
            if (object.has("is_file")) {
                return describeExpr(object.get("is_file"))
                        + " is a file";
            }
            if (object.has("is_folder")) {
                return describeExpr(object.get("is_folder"))
                        + " is a folder";
            }
            if (object.has("read_file")) {
                return "the content of the file at "
                        + describeExpr(object.get("read_file"));
            }
            if (object.has("file_length")) {
                return "the size of the file at "
                        + describeExpr(object.get("file_length"));
            }
            for (String key : new String[]{"width_of", "height_of",
                    "alpha_of", "rotation_of", "scale_x_of",
                    "scale_y_of", "translation_x_of",
                    "translation_y_of", "rating_of", "max_of",
                    "page_of", "selection_of", "selection_start_of",
                    "selection_end_of", "video_position_of",
                    "video_duration_of", "video_playing",
                    "web_progress_of", "can_go_back",
                    "can_go_forward", "can_pause", "can_seek_back",
                    "can_seek_forward", "last_visible_of",
                    "location_x_of", "location_y_of",
                    "badge_count_of",
                    "otp_text_of", "counter_max_of",
                    "page_limit_of"}) {
                if (object.has(key)) {
                    String label = switch (key) {
                        case "width_of" -> "width";
                        case "height_of" -> "height";
                        case "alpha_of" -> "opacity";
                        case "rotation_of" -> "rotation";
                        case "scale_x_of" -> "horizontal scale";
                        case "scale_y_of" -> "vertical scale";
                        case "translation_x_of" -> "horizontal offset";
                        case "translation_y_of" -> "vertical offset";
                        case "rating_of" -> "rating";
                        case "max_of" -> "maximum";
                        case "page_of" -> "current page";
                        case "selection_of" -> "selected position";
                        case "selection_start_of" -> "selection start";
                        case "selection_end_of" -> "selection end";
                        case "video_position_of" -> "playback position";
                        case "video_duration_of" -> "duration";
                        case "video_playing" -> "playing state";
                        case "web_progress_of" -> "loading progress";
                        case "can_go_back" -> "can-go-back state";
                        case "can_pause" -> "can-pause state";
                        case "can_seek_back" -> "can-seek-back state";
                        case "can_seek_forward" ->
                                "can-seek-forward state";
                        case "last_visible_of" ->
                                "last visible position";
                        case "location_x_of" -> "screen x";
                        case "location_y_of" -> "screen y";
                        case "badge_count_of" -> "badge count";
                        case "otp_text_of" -> "entered code";
                        case "counter_max_of" -> "character limit";
                        case "page_limit_of" -> "page limit";
                        default -> "can-go-forward state";
                    };
                    return "the " + label + " of "
                            + stringOf(object, key);
                }
            }
            if (object.has("text_of")) {
                return "text of " + stringOf(object, "text_of");
            }
            if (object.has("url_of")) {
                return "url of " + stringOf(object, "url_of");
            }
            if (object.has("checked")) {
                return stringOf(object, "checked") + " is checked";
            }
            if (object.has("enabled")) {
                return stringOf(object, "enabled") + " is enabled";
            }
            if (object.has("progress_of")) {
                return "progress of " + stringOf(object, "progress_of");
            }
            if (object.has("var")) {
                return stringOf(object, "var");
            }
            if (object.has("number")) {
                return object.get("number").getAsString();
            }
            if (object.has("text")) {
                return "“" + object.get("text").getAsString() + "”";
            }
            if (object.has("bool")) {
                return object.get("bool").getAsBoolean()
                        ? "true" : "false";
            }
            if (object.has("op")) {
                String operation = stringOf(object, "op");
                if ("not".equals(operation)) {
                    return "not (" + describeExpr(object.get("a")) + ")";
                }
                String symbol = switch (operation) {
                    case "and" -> " and ";
                    case "or" -> " or ";
                    default -> " " + operation + " ";
                };
                return describeExpr(object.get("a")) + symbol
                        + describeExpr(object.get("b"));
            }
            return "?";
        }

        private void addExpect(String expectCode, String sentence) {
            Stmt stmt = new Stmt();
            stmt.expectCode = expectCode;
            stmt.sentence = sentence;
            stmts.add(stmt);
        }

        /** True when an expression yields true/false. */
        private boolean isBoolExpr(JsonElement element) {
            if (element == null || element.isJsonNull()) {
                return false;
            }
            if (element.isJsonPrimitive()) {
                return element.getAsJsonPrimitive().isBoolean();
            }
            if (!element.isJsonObject()) {
                return false;
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("checked") || object.has("enabled")
                    || object.has("map_has") || object.has("list_has")
                    || object.has("bool") || object.has("file_exists")
                    || object.has("is_file") || object.has("is_folder")
                    || object.has("map_empty")
                    || object.has("map_has_value")
                    || object.has("has_extra")
                    || object.has("instance_of")
                    || object.has("list_map_has")
                    || object.has("visibility_is")
                    || object.has("can_pause")
                    || object.has("can_seek_back")
                    || object.has("can_seek_forward")
                    || object.has("pref_has")
                    || object.has("mp_playing")
                    || object.has("mp_looping")
                    || object.has("anim_running")
                    || object.has("tts_speaking")
                    || object.has("bt_enabled") || object.has("bt_on")
                    || object.has("ad_loaded")
                    || object.has("video_playing")
                    || object.has("can_go_back")
                    || object.has("can_go_forward")) {
                return true;
            }
            if (object.has("fn")) {
                String fn = stringOf(object, "fn")
                        .toLowerCase(Locale.ROOT);
                return "contains".equals(fn) || "starts_with".equals(fn)
                        || "ends_with".equals(fn) || "matches".equals(fn)
                        || "is_empty".equals(fn)
                        || "drawer_open".equals(fn)
                        || "auth_logged_in".equals(fn)
                        || "is_connected".equals(fn);
            }
            if (object.has("var")) {
                Integer kind = variables.get(stringOf(object, "var"));
                return kind != null && kind == 0;
            }
            if (object.has("op")) {
                String operation = stringOf(object, "op");
                return operation.equals("and") || operation.equals("or")
                        || operation.equals("not")
                        || operation.equals("=");
            }
            return false;
        }

        /** True when an expression yields text (for "=" routing). */
        private boolean isTextExpr(JsonElement element) {
            if (element == null || element.isJsonNull()) {
                return false;
            }
            if (element.isJsonPrimitive()) {
                return element.getAsJsonPrimitive().isString();
            }
            if (!element.isJsonObject()) {
                return false;
            }
            JsonObject object = element.getAsJsonObject();
            if (object.has("text_of") || object.has("url_of")
                    || object.has("text")) {
                return true;
            }
            if (object.has("fn")) {
                String fn = stringOf(object, "fn")
                        .toLowerCase(Locale.ROOT);
                return fn.equals("concat") || fn.equals("upper")
                        || fn.equals("lower") || fn.equals("trim")
                        || fn.equals("to_text")
                        || fn.equals("substring");
            }
            if (object.has("var")) {
                Integer kind = variables.get(stringOf(object, "var"));
                return kind != null && kind == 2;
            }
            return false;
        }

        private AgentTools.ToolException badValue(String what,
                                                  String why) {
            return new AgentTools.ToolException("E_BAD_VALUE",
                    Character.toUpperCase(what.charAt(0))
                            + what.substring(1) + ": " + why + ".");
        }

        private static String indent(int depth) {
            return "  ".repeat(depth);
        }
    }

    /**
     * Applies an approved plan: registers the event when missing,
     * appends the new chain, saves through the data manager, commits,
     * and verifies by reading the event back. Any failure restores
     * the blocks as they were.
     */
    public static JsonObject apply(Context context, Plan plan)
            throws AgentTools.ToolException {
        String javaName = plan.activity.getJavaName();
        eC store = jC.a(plan.scId);
        String key = plan.targetId + "_" + plan.eventName;

        ArrayList<BlockBean> current = readBlocks(store, javaName, plan);
        if (current.size() != plan.existingCount) {
            throw new AgentTools.ToolException("E_LOGIC_CHANGED",
                    "This event's blocks changed since the handler was "
                            + "proposed, so it was not applied. Look at "
                            + "the screen again and propose a fresh one.");
        }
        ArrayList<BlockBean> previous = new ArrayList<>();
        for (BlockBean bean : current) {
            previous.add(bean.clone());
        }
        boolean registeredHere = false;
        try {
            SketchwareActions.backupProjectOnce(plan.scId);
            if (plan.newComponentId != null) {
                // The editor's own component-add path; like an event
                // registered below, it stays if a later step fails —
                // an unused Intent component is harmless.
                store.a(javaName, ComponentBean.COMPONENT_TYPE_INTENT,
                        plan.newComponentId);
            }
            if (!plan.eventExists
                    && !eventRegistered(store, javaName, plan)) {
                if (plan.activityEvent) {
                    store.a(javaName, EventBean.EVENT_TYPE_ACTIVITY, 0,
                            plan.targetId, plan.eventName);
                } else if (plan.componentEvent) {
                    // The same call AddEventActivity makes when a
                    // user adds a component event by hand.
                    store.a(javaName, EventBean.EVENT_TYPE_COMPONENT,
                            plan.componentType, plan.targetId,
                            plan.eventName);
                } else {
                    store.a(javaName, EventBean.EVENT_TYPE_VIEW,
                            targetTypeOf(plan), plan.targetId,
                            plan.eventName);
                }
                registeredHere = true;
            }
            // Join at the chain head and store in the editor's
            // own order: Fx and the loader both start from list
            // element 0, so the head statement must sit there —
            // linking to the first created bean instead strands
            // the new chain behind a condition reporter.
            ArrayList<BlockBean> combined = editorOrder(
                    joined(current, freshGenerationList(plan)));
            java.util.Set<String> freshIds = new java.util.HashSet<>();
            for (BlockBean bean : plan.fresh) {
                freshIds.add(bean.id);
            }
            String olderProblem = null;
            for (String problem : editorLoadProblems(combined)) {
                boolean ours = false;
                for (String freshId : freshIds) {
                    if (problem.startsWith("block " + freshId + " ")) {
                        ours = true;
                        break;
                    }
                }
                if (ours) {
                    throw new IllegalStateException(
                            "The new blocks would not open in the "
                                    + "editor (" + problem + ")");
                }
                if (olderProblem == null) {
                    olderProblem = problem;
                }
            }
            store.a(javaName, key, combined);
            SketchwareActions.commitProject(plan.scId);

            ArrayList<BlockBean> readBack =
                    store.a(javaName, key);
            String problem = verify(readBack, combined, plan);
            if (problem != null) {
                throw new IllegalStateException(problem);
            }
            if (plan.newComponentId != null) {
                boolean found = false;
                ArrayList<ComponentBean> components =
                        store.e(javaName);
                if (components != null) {
                    for (ComponentBean component : components) {
                        if (plan.newComponentId.equals(
                                component.componentId)) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    throw new IllegalStateException(
                            "The Intent component was not saved.");
                }
            }
            JsonObject result = new JsonObject();
            result.addProperty("applied", true);
            result.addProperty("blocks_added", plan.chain.size());
            result.addProperty("blocks_total", readBack.size());
            result.addProperty("verified",
                    plan.header + " now runs "
                            + readBack.size() + " block"
                            + (readBack.size() == 1 ? "" : "s")
                            + " (read back and checked).");
            if (olderProblem != null) {
                result.addProperty("warning",
                        "This event already holds an older problem "
                                + "that will not open in the editor ("
                                + olderProblem + "). Removing that "
                                + "block with edit_event_block clears "
                                + "it.");
            }
            return result;
        } catch (Exception e) {
            // Restore the blocks as they were; an event registered by
            // this call may stay behind, empty — the editor simply
            // shows an unwired event, and the user can delete it.
            try {
                store.a(javaName, key, previous);
                SketchwareActions.commitProject(plan.scId);
            } catch (Exception ignored) {
                // The restore is best-effort; the error below is what
                // the user needs to see.
            }
            if (e instanceof AgentTools.ToolException toolException) {
                throw toolException;
            }
            throw new AgentTools.ToolException("E_APPLY_FAILED",
                    "The handler could not be applied (" + e.getMessage()
                            + "); the event's blocks were restored as "
                            + "they were."
                            + (registeredHere
                                    ? " An empty " + plan.eventName
                                            + " event may remain."
                                    : ""));
        }
    }

    // -- translation --------------------------------------------------------

    /**
     * The block kind a model meant: "block" canonically, but models
     * mix in edit_screen's "op", or echo stored opCodes (doToast,
     * setText, ifElse) after reading an event back. All resolve to
     * the statement vocabulary instead of failing as unknown "".
     */
    private static String blockKindOf(JsonObject op) {
        String kind = stringOf(op, "block");
        if (kind.isEmpty()) {
            kind = stringOf(op, "op");
        }
        if (kind.isEmpty()) {
            kind = stringOf(op, "kind");
        }
        if (kind.isEmpty()) {
            kind = stringOf(op, "opCode");
        }
        if (kind.isEmpty()) {
            kind = stringOf(op, "opcode");
        }
        if (kind.isEmpty()) {
            kind = stringOf(op, "type");
        }
        if (kind.isEmpty()) {
            kind = stringOf(op, "action");
        }
        if (kind.isEmpty()) {
            kind = stringOf(op, "name");
        }
        if (kind.isEmpty()) {
            // Shape alone names some blocks unambiguously.
            if (op.has("cases")) {
                return "switch";
            }
            if (op.has("catch")) {
                return "try_catch";
            }
            if (op.has("condition")) {
                return op.has("else") ? "if_else" : "if";
            }
            if (op.has("times")) {
                return "repeat";
            }
            if (op.has("screen")) {
                return "open_screen";
            }
            return "";
        }
        String key = kind.toLowerCase(Locale.ROOT)
                .replace("_", "").replace(" ", "");
        return switch (key) {
            case "dotoast", "toastmessage", "showtoast" -> "toast";
            case "settext" -> "set_text";
            case "setvisible", "setvisibility" -> "set_visibility";
            case "setenabled", "setenable" -> "set_enabled";
            case "setvariable", "setvarint", "setvarstring",
                    "setvarboolean" -> "set_variable";
            case "increaseint" -> "increase";
            case "decreaseint" -> "decrease";
            case "finishactivity" -> "finish";
            case "ifelse" -> "if_else";
            case "whileloop" -> "while";
            case "trycatch" -> "try_catch";
            case "writefile", "fileutilwrite" -> "write_file";
            case "deletefile", "fileutildelete" -> "delete_file";
            case "makedir", "makefolder", "fileutilmakedir" ->
                    "make_folder";
            case "copyfile", "fileutilcopy" -> "copy_file";
            case "movefile", "fileutilmove" -> "move_file";
            case "copyfolder", "copydir", "fileutilcopydir" ->
                    "copy_folder";
            case "renamefile" -> "rename_file";
            case "listfiles", "fileutillistdir", "listdir" ->
                    "list_files";
            case "removefromlist", "deletelist", "removeat" ->
                    "remove_from_list";
            case "insertinlist" -> "insert_in_list";
            case "setinlist", "setatpos" -> "set_in_list";
            case "reverselist" -> "reverse_list";
            case "shufflelist" -> "shuffle_list";
            case "sortlist" -> "sort_list";
            case "swapinlist" -> "swap_in_list";
            case "removemapkey", "mapremovekey" -> "remove_map_key";
            case "mapkeystolist", "mapgetallkeys" -> "map_keys_to_list";
            case "getmapfromlist", "getmapinlist" -> "get_map_from_list";
            case "addmaptolist" -> "add_map_to_list";
            case "insertmaptolist" -> "insert_map_to_list";
            case "addmapentry", "addlistmap" -> "add_map_entry";
            case "insertmapentry", "insertlistmap" -> "insert_map_entry";
            case "setmapentry", "setlistmap" -> "set_map_entry";
            default -> kind.toLowerCase(Locale.ROOT);
        };
    }

    private static Stmt translate(Context context, JsonObject op,
                                  Map<String, ViewBean> byId,
                                  Map<String, Integer> variables,
                                  Map<String, Integer> lists,
                                  Map<String, Integer> components,
                                  String activityName)
            throws AgentTools.ToolException {
        String kind = blockKindOf(op);
        Stmt stmt = new Stmt();
        switch (kind) {
            case "toast" -> {
                String fromVariable = stringOf(op, "from_variable");
                stmt.opCode = "doToast";
                stmt.type = " ";
                if (!fromVariable.isEmpty()) {
                    requireVariable(variables, fromVariable);
                    requireVariableKind(variables, fromVariable, 2,
                            "A message can only show a string variable; \""
                                    + fromVariable + "\" is a "
                                    + SketchwareProjects.variableTypeName(
                                            variables.get(fromVariable))
                                    + " variable.");
                    stmt.params.add("@var:" + fromVariable);
                    stmt.sentence = "Show the value of variable “"
                            + fromVariable + "” as a message";
                    stmt.expectCode =
                            "showMessage(getApplicationContext(), "
                                    + fromVariable;
                    break;
                }
                if (op.has("message")
                        && op.get("message").isJsonObject()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "A toast message is plain text. To show a "
                                    + "value — an event's "
                                    + "{\"arg\":...}, a map entry, a "
                                    + "widget's text — first put it in "
                                    + "a string variable with "
                                    + "set_variable, then toast "
                                    + "{from_variable}.");
                }
                String message = stringOf(op, "message");
                if (message.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "A toast block needs a \"message\".");
                }
                if (message.length() > 200) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Toast messages are capped at 200 characters.");
                }
                stmt.params.add(message);
                stmt.sentence = "Show message “" + message + "”";
                stmt.expectCode = "SketchwareUtil.showMessage";
            }
            case "set_text" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!TEXT_TYPES.contains(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ", which has no text to set.");
                }
                String fromVariable = stringOf(op, "from_variable");
                stmt.opCode = "setText";
                stmt.type = " ";
                stmt.params.add(widget.id);
                if (!fromVariable.isEmpty()) {
                    requireVariable(variables, fromVariable);
                    requireVariableKind(variables, fromVariable, 2,
                            "Text can only come from a string variable; \""
                                    + fromVariable + "\" is a "
                                    + SketchwareProjects.variableTypeName(
                                            variables.get(fromVariable))
                                    + " variable.");
                    stmt.params.add("@var:" + fromVariable);
                    stmt.sentence = "Set " + widget.id
                            + " text to the value of variable “"
                            + fromVariable + "”";
                    stmt.expectCode = widget.id + ".setText(" + fromVariable;
                    break;
                }
                if (op.has("text") && op.get("text").isJsonObject()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_text takes plain text here. To show "
                                    + "a value — an event's "
                                    + "{\"arg\":...}, a map entry, a "
                                    + "widget's text — first put it in "
                                    + "a string variable with "
                                    + "set_variable, then set_text "
                                    + "{from_variable}.");
                }
                String text = stringOf(op, "text");
                stmt.params.add(text);
                stmt.sentence = "Set " + widget.id + " text to “"
                        + text + "”";
                stmt.expectCode = widget.id + ".setText(";
            }
            case "set_visibility" -> {
                ViewBean widget = requireWidget(op, byId);
                String state = stringOf(op, "state");
                if (state.isEmpty() && op.has("visible")) {
                    state = boolOf(op, "visible", true)
                            ? "VISIBLE" : "GONE";
                }
                state = state.toUpperCase();
                if (!state.equals("VISIBLE") && !state.equals("GONE")
                        && !state.equals("INVISIBLE")) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_visibility needs \"visible\": true/false "
                                    + "or a state of visible, gone or "
                                    + "invisible.");
                }
                stmt.opCode = "setVisible";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(state);
                stmt.sentence = (state.equals("VISIBLE") ? "Show "
                        : state.equals("GONE") ? "Hide " : "Make ")
                        + widget.id
                        + (state.equals("INVISIBLE")
                                ? " invisible (keeps its space)" : "");
                stmt.expectCode = widget.id + ".setVisibility(View.";
            }
            case "set_enabled" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!op.has("enabled") || !op.get("enabled").isJsonPrimitive()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_enabled needs \"enabled\": true or false.");
                }
                boolean enabled = boolOf(op, "enabled", true);
                stmt.opCode = "setEnable";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(String.valueOf(enabled));
                stmt.sentence = (enabled ? "Enable " : "Disable ")
                        + widget.id;
                stmt.expectCode = widget.id + ".setEnabled(";
            }
            case "set_text_color" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!TEXT_TYPES.contains(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ", which has no text colour to set.");
                }
                String colour = colourParam(op);
                stmt.opCode = "setTextColor";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(colour);
                stmt.sentence = "Set " + widget.id + " text colour to "
                        + colour;
                stmt.expectCode = widget.id + ".setTextColor(";
            }
            case "set_image_url" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!"ImageView".equals(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Only an ImageView loads an image from a "
                                    + "URL; " + widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ".");
                }
                String url = stringOf(op, "url");
                if (url.isEmpty()) {
                    url = stringOf(op, "text");
                }
                if (url.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_image_url needs \"url\".");
                }
                stmt.opCode = "setImageUrl";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(url);
                stmt.sentence = "Load the image at " + url + " into "
                        + widget.id;
                // Fx emits the app's image loader call; the dry run
                // itself proves the shape, this just anchors it.
                stmt.expectCode = "Glide.with(getApplicationContext())"
                        + ".load(Uri.parse(";
            }
            case "set_hint" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!"EditText".equals(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Only an EditText has a hint to set; "
                                    + widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ".");
                }
                String hint = stringOf(op, "text");
                if (hint.isEmpty() && op.has("message")) {
                    hint = stringOf(op, "message");
                }
                stmt.opCode = "setHint";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(hint);
                stmt.sentence = "Set " + widget.id + " hint to \""
                        + hint + "\"";
                stmt.expectCode = widget.id + ".setHint(";
            }
            case "set_clickable" -> {
                ViewBean widget = requireWidget(op, byId);
                boolean clickable = boolOf(op, "enabled", true);
                if (op.has("clickable")) {
                    clickable = boolOf(op, "clickable", true);
                }
                stmt.opCode = "setClickable";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(String.valueOf(clickable));
                stmt.sentence = (clickable ? "Make " : "Make un-clickable ")
                        + widget.id + (clickable ? " clickable" : "");
                stmt.expectCode = widget.id + ".setClickable(";
            }
            case "copy_to_clipboard" -> {
                String text = stringOf(op, "text");
                if (text.isEmpty()) {
                    text = stringOf(op, "message");
                }
                if (text.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "copy_to_clipboard needs \"text\" to copy.");
                }
                stmt.opCode = "copyToClipboard";
                stmt.type = " ";
                stmt.params.add(text);
                stmt.sentence = "Copy \"" + text
                        + "\" to the clipboard";
                stmt.expectCode = "setPrimaryClip(ClipData.newPlainText(";
            }
            case "set_screen_title" -> {
                String text = stringOf(op, "text");
                if (text.isEmpty()) {
                    text = stringOf(op, "message");
                }
                if (text.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_screen_title needs \"text\".");
                }
                stmt.opCode = "setTitle";
                stmt.type = " ";
                stmt.params.add(text);
                stmt.sentence = "Set the screen title to \"" + text
                        + "\"";
                stmt.expectCode = "setTitle(";
            }
            case "open_drawer", "close_drawer" -> {
                boolean open = "open_drawer".equals(kind);
                stmt.opCode = open ? "openDrawer" : "closeDrawer";
                stmt.type = " ";
                stmt.sentence = open ? "Open the navigation drawer"
                        : "Close the navigation drawer";
                stmt.expectCode = open ? "openDrawer(GravityCompat.START)"
                        : "closeDrawer(GravityCompat.START)";
            }
            case "load_url" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!"WebView".equals(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Only a WebView loads a URL; " + widget.id
                                    + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ".");
                }
                String url = stringOf(op, "url");
                if (url.isEmpty()) {
                    url = stringOf(op, "text");
                }
                if (url.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "load_url needs \"url\".");
                }
                stmt.opCode = "webViewLoadUrl";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(url);
                stmt.sentence = "Load " + url + " in " + widget.id;
                stmt.expectCode = widget.id + ".loadUrl(";
            }
            case "set_progress" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!"SeekBar".equals(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Only a SeekBar has progress to set; "
                                    + widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ".");
                }
                String value = stringOf(op, "value");
                try {
                    Double.parseDouble(value);
                } catch (Exception e) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_progress needs a numeric \"value\".");
                }
                stmt.opCode = "seekBarSetProgress";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(value);
                stmt.sentence = "Set " + widget.id + " progress to "
                        + value;
                // Fx emits "setProgress((int)%s)" — literal numbers
                // come straight through, so just the call is anchored.
                stmt.expectCode = widget.id + ".setProgress(";
            }
            case "finish" -> {
                stmt.opCode = "finishActivity";
                stmt.type = "f";
                stmt.sentence = "Close this screen";
                stmt.expectCode = "finish();";
            }
            case "set_variable" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                int kind2 = variables.get(name);
                if (kind2 == 3) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "\"" + name + "\" is a map variable; "
                                    + "use put_in_map, clear_map or "
                                    + "new_map for it instead.");
                }
                stmt.type = " ";
                String copyFrom = stringOf(op, "from_variable");
                if (!copyFrom.isEmpty()) {
                    requireVariable(variables, copyFrom);
                    if (variables.get(copyFrom) != kind2) {
                        throw new AgentTools.ToolException("E_BAD_BLOCK",
                                "Types don't match: \"" + copyFrom
                                        + "\" is a "
                                        + SketchwareProjects
                                                .variableTypeName(
                                                        variables.get(
                                                                copyFrom))
                                        + " variable, but \"" + name
                                        + "\" is a "
                                        + SketchwareProjects
                                                .variableTypeName(kind2)
                                        + " variable.");
                    }
                    stmt.opCode = setVarOpCode(kind2);
                    stmt.params.add(name);
                    stmt.params.add("@var:" + copyFrom);
                    stmt.sentence = "Set variable “" + name
                            + "” to the value of “" + copyFrom + "”";
                    stmt.expectCode = name + " = ";
                    break;
                }
                stmt.opCode = setVarOpCode(kind2);
                stmt.params.add(name);
                if (kind2 == 1) {
                    String value = stringOf(op, "value");
                    try {
                        Double.parseDouble(value);
                    } catch (Exception e) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "Variable \"" + name + "\" is a number "
                                        + "variable, so \"value\" must be "
                                        + "a number (got \"" + value
                                        + "\").");
                    }
                    stmt.params.add(value);
                    stmt.sentence = "Set variable “" + name + "” to "
                            + value;
                } else if (kind2 == 0) {
                    // A true/false variable must say which. Silently
                    // reading an empty value as false is how an agent
                    // ends up setting the same flag "on" in a start
                    // handler and "off" in a pause handler with the
                    // very same empty socket — two opposite meanings
                    // that cannot both be right.
                    if (!op.has("value") || op.get("value").isJsonNull()
                            || (op.get("value").isJsonPrimitive()
                                    && op.get("value").getAsString()
                                            .trim().isEmpty())) {
                        throw new AgentTools.ToolException("E_BAD_BLOCK",
                                "Variable \"" + name + "\" is a true/false "
                                        + "variable, so \"value\" must be "
                                        + "explicitly true or false — an "
                                        + "empty value cannot mean one "
                                        + "thing here and the opposite "
                                        + "somewhere else.");
                    }
                    boolean value = boolOf(op, "value", false);
                    stmt.params.add(String.valueOf(value));
                    stmt.sentence = "Set variable “" + name + "” to "
                            + (value ? "true" : "false");
                } else {
                    String value = stringOf(op, "value");
                    if (value.length() > 1000) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "Variable text is capped at 1000 "
                                        + "characters.");
                    }
                    stmt.params.add(value);
                    stmt.sentence = "Set variable “" + name + "” to “"
                            + value + "”";
                }
                stmt.expectCode = name + " = ";
            }
            case "increase", "decrease" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 1,
                        "Increase/decrease only work on number variables; "
                                + "\"" + name + "\" is a "
                                + SketchwareProjects.variableTypeName(
                                        variables.get(name)) + " variable.");
                boolean up = "increase".equals(kind);
                stmt.opCode = up ? "increaseInt" : "decreaseInt";
                stmt.type = " ";
                stmt.params.add(name);
                stmt.sentence = (up ? "Add 1 to" : "Subtract 1 from")
                        + " variable “" + name + "”";
                stmt.expectCode = name + (up ? "++;" : "--;");
            }
            case "add_to_list" -> {
                String list = stringOf(op, "list");
                Integer listKind = lists.get(list);
                if (listKind == null) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "There is no list called " + list
                                    + " on this screen. Declare one "
                                    + "with create_variable (type "
                                    + "list_string or list_number).");
                }
                if (listKind == 3) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Adding to a list of maps is not "
                                    + "supported yet; " + list
                                    + " is a list of maps.");
                }
                String fromVariable = stringOf(op, "from_variable");
                stmt.type = " ";
                if (listKind == 2) {
                    stmt.opCode = "addListStr";
                    if (!fromVariable.isEmpty()) {
                        requireVariable(variables, fromVariable);
                        requireVariableKind(variables, fromVariable, 2,
                                "Only a string variable's value can "
                                        + "go into a list of text.");
                        stmt.params.add("@var:" + fromVariable);
                        stmt.sentence = "Add the value of “"
                                + fromVariable + "” to list “" + list
                                + "”";
                    } else {
                        String text = stringOf(op, "text");
                        if (text.isEmpty()) {
                            text = stringOf(op, "value");
                        }
                        stmt.params.add(text);
                        stmt.sentence = "Add “" + text + "” to list “"
                                + list + "”";
                    }
                    stmt.params.add(list);
                } else {
                    stmt.opCode = "addListInt";
                    if (!fromVariable.isEmpty()) {
                        requireVariable(variables, fromVariable);
                        requireVariableKind(variables, fromVariable, 1,
                                "Only a number variable's value can "
                                        + "go into a list of numbers.");
                        stmt.params.add("@var:" + fromVariable);
                        stmt.sentence = "Add the value of “"
                                + fromVariable + "” to list “" + list
                                + "”";
                    } else {
                        String value = stringOf(op, "value");
                        try {
                            Double.parseDouble(value);
                        } catch (Exception e) {
                            throw new AgentTools.ToolException(
                                    "E_BAD_BLOCK",
                                    "List “" + list + "” holds "
                                            + "numbers, so \"value\" "
                                            + "must be a number (got "
                                            + "\"" + value + "\").");
                        }
                        stmt.params.add(value);
                        stmt.sentence = "Add " + value + " to list “"
                                + list + "”";
                    }
                    stmt.params.add(list);
                }
                stmt.expectCode = list + ".add(";
            }
            case "clear_list" -> {
                String list = stringOf(op, "list");
                if (!lists.containsKey(list)) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "There is no list called " + list
                                    + " on this screen.");
                }
                stmt.opCode = "clearList";
                stmt.type = " ";
                stmt.params.add(list);
                stmt.sentence = "Empty list “" + list + "”";
                stmt.expectCode = list + ".clear()";
            }
            case "show_list" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!"ListView".equals(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ", not a ListView.");
                }
                String list = stringOf(op, "list");
                Integer listKind = lists.get(list);
                if (listKind == null || listKind != 2) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "show_list needs a list of text on this "
                                    + "screen; “" + list + "” is not "
                                    + "one.");
                }
                stmt.opCode = "listSetData";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(list);
                stmt.sentence = "Show list “" + list + "” in "
                        + widget.id;
                stmt.expectCode = widget.id + ".setAdapter(";
            }
            case "list_custom_data", "grid_custom_data",
                    "recycler_custom_data", "pager_custom_data",
                    "spinner_custom_data" -> {
                // The *SetCustomViewData family: one Fx case fills a
                // widget from a list of maps through its custom item
                // layout ("<widget>.setAdapter(new <Widget>Adapter(
                // <list>));"); each row is then dressed by the
                // widget's onBindCustomView handler.
                ViewBean widget = requireWidget(op, byId);
                String widgetType;
                String dataOpCode;
                switch (kind) {
                    case "list_custom_data" -> {
                        widgetType = "ListView";
                        dataOpCode = "listSetCustomViewData";
                    }
                    case "grid_custom_data" -> {
                        widgetType = "GridView";
                        dataOpCode = "gridSetCustomViewData";
                    }
                    case "recycler_custom_data" -> {
                        widgetType = "RecyclerView";
                        dataOpCode = "recyclerSetCustomViewData";
                    }
                    case "pager_custom_data" -> {
                        widgetType = "ViewPager";
                        dataOpCode = "pagerSetCustomViewData";
                    }
                    default -> {
                        widgetType = "Spinner";
                        dataOpCode = "spnSetCustomViewData";
                    }
                }
                if (!widgetType.equals(
                        AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ", not a " + widgetType + ".");
                }
                if (widget.customView == null
                        || widget.customView.isEmpty()
                        || "none".equals(widget.customView)) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            widget.id + " has no item layout yet. Give "
                                    + "it one with edit_screen (op "
                                    + "set_list_item_view) — creating "
                                    + "the layout with create_custom_view "
                                    + "first if needed — then fill each "
                                    + "row in its onBindCustomView event.");
                }
                String list = stringOf(op, "list");
                Integer listKind = lists.get(list);
                if (listKind == null || listKind != 3) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            kind + " needs a list of maps on this "
                                    + "screen; “" + list + "” is not one. "
                                    + "Declare it with create_variable "
                                    + "(type list_map).");
                }
                stmt.opCode = dataOpCode;
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(list);
                stmt.sentence = "Show each row of “" + list + "” in "
                        + widget.id + " using its item layout";
                stmt.expectCode = widget.id + ".setAdapter(new";
            }
            case "put_in_map" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 3,
                        "put_in_map needs a map variable; “" + name
                                + "” is not one. Declare it with "
                                + "create_variable (type map).");
                String key = stringOf(op, "key");
                if (key.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "put_in_map needs a \"key\".");
                }
                stmt.opCode = "mapPut";
                stmt.type = " ";
                stmt.params.add(name);
                stmt.params.add(key);
                String fromVariable = stringOf(op, "from_variable");
                if (!fromVariable.isEmpty()) {
                    requireVariable(variables, fromVariable);
                    requireVariableKind(variables, fromVariable, 2,
                            "A map stores text, so the value must "
                                    + "come from a string variable.");
                    stmt.params.add("@var:" + fromVariable);
                    stmt.sentence = "In map “" + name + "”, store "
                            + "the value of “" + fromVariable
                            + "” under “" + key + "”";
                } else {
                    String text = stringOf(op, "text");
                    if (text.isEmpty()) {
                        text = stringOf(op, "value");
                    }
                    stmt.params.add(text);
                    stmt.sentence = "In map “" + name + "”, store “"
                            + text + "” under “" + key + "”";
                }
                stmt.expectCode = name + ".put(";
            }
            case "clear_map" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 3,
                        "clear_map needs a map variable.");
                stmt.opCode = "mapClear";
                stmt.type = " ";
                stmt.params.add(name);
                stmt.sentence = "Empty map “" + name + "”";
                stmt.expectCode = name + ".clear()";
            }
            case "new_map" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 3,
                        "new_map needs a map variable.");
                stmt.opCode = "mapCreateNew";
                stmt.type = " ";
                stmt.params.add(name);
                stmt.sentence = "Start map “" + name + "” fresh";
                stmt.expectCode = name + " = new HashMap";
            }
            case "write_file" -> {
                // The editor's fileutilwrite: content first, then
                // the path (Fx emits FileUtil.writeFile(path, text)).
                String text = stringOf(op, "text");
                String fromVariable = stringOf(op, "from_variable");
                stmt.opCode = "fileutilwrite";
                stmt.type = " ";
                if (!fromVariable.isEmpty()) {
                    requireVariable(variables, fromVariable);
                    requireVariableKind(variables, fromVariable, 2,
                            "A file's content can only come from a "
                                    + "string variable.");
                    stmt.params.add("@var:" + fromVariable);
                    text = "the value of “" + fromVariable + "”";
                } else {
                    if (text.isEmpty()) {
                        text = stringOf(op, "value");
                    }
                    if (text.isEmpty()) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "write_file needs the \"text\" to "
                                        + "write, or \"from_variable\" "
                                        + "naming a string variable.");
                    }
                    stmt.params.add(text);
                    text = "“" + text + "”";
                }
                PathArg path = pathParam(op, variables, "path");
                stmt.params.add(path.stored);
                stmt.sentence = "Write " + text + " to the file at "
                        + path.shown;
                stmt.expectCode = "FileUtil.writeFile(";
            }
            case "delete_file" -> {
                stmt.opCode = "fileutildelete";
                stmt.type = " ";
                PathArg path = pathParam(op, variables, "path");
                stmt.params.add(path.stored);
                stmt.sentence = "Delete the file at " + path.shown;
                stmt.expectCode = "FileUtil.deleteFile(";
            }
            case "make_folder" -> {
                stmt.opCode = "fileutilmakedir";
                stmt.type = " ";
                PathArg path = pathParam(op, variables, "path");
                stmt.params.add(path.stored);
                stmt.sentence = "Create the folder at " + path.shown;
                stmt.expectCode = "FileUtil.makeDir(";
            }
            case "copy_file", "move_file", "copy_folder",
                    "rename_file" -> {
                String from = stringOf(op, "from");
                String to = stringOf(op, "to");
                if (from.isEmpty() || to.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            kind + " needs \"from\" and \"to\" paths.");
                }
                stmt.type = " ";
                stmt.params.add(from);
                stmt.params.add(to);
                switch (kind) {
                    case "copy_file" -> {
                        stmt.opCode = "fileutilcopy";
                        stmt.sentence = "Copy the file at “" + from
                                + "” to “" + to + "”";
                        stmt.expectCode = "FileUtil.copyFile(";
                    }
                    case "move_file" -> {
                        stmt.opCode = "fileutilmove";
                        stmt.sentence = "Move the file at “" + from
                                + "” to “" + to + "”";
                        stmt.expectCode = "FileUtil.moveFile(";
                    }
                    case "copy_folder" -> {
                        stmt.opCode = "fileutilcopydir";
                        stmt.sentence = "Copy the folder at “" + from
                                + "” to “" + to + "”";
                        stmt.expectCode = "FileUtil.copyDir(";
                    }
                    default -> {
                        stmt.opCode = "renameFile";
                        stmt.sentence = "Rename the file at “" + from
                                + "” to “" + to + "”";
                        stmt.expectCode = "renameTo(e5Cyk)";
                    }
                }
            }
            case "list_files" -> {
                String list = stringOf(op, "list");
                Integer listKind = lists.get(list);
                if (listKind == null || listKind != 2) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "list_files fills a list of text with the "
                                    + "names in a folder; “" + list
                                    + "” is not one on this screen.");
                }
                stmt.opCode = "fileutillistdir";
                stmt.type = " ";
                PathArg path = pathParam(op, variables, "path");
                stmt.params.add(path.stored);
                stmt.params.add(list);
                stmt.sentence = "List the files in the folder at "
                        + path.shown + " into list “" + list + "”";
                stmt.expectCode = "FileUtil.listDir(";
            }
            case "remove_from_list" -> {
                String list = requireList(op, lists);
                stmt.opCode = "deleteList";
                stmt.type = " ";
                String index = indexParam(op);
                stmt.params.add(index);
                stmt.params.add(list);
                stmt.sentence = "Remove item " + index + " from list “"
                        + list + "”";
                stmt.expectCode = list + ".remove((int)(";
            }
            case "insert_in_list", "set_in_list" -> {
                String list = requireList(op, lists);
                Integer listKind = lists.get(list);
                if (listKind == 3) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "“" + list + "” is a list of maps; use "
                                    + "add_map_entry, insert_map_entry "
                                    + "or set_map_entry for it.");
                }
                String index = indexParam(op);
                boolean insert = "insert_in_list".equals(kind);
                stmt.type = " ";
                if (listKind == 2) {
                    stmt.opCode = insert ? "insertListStr"
                            : "setAtPosListstr";
                    String text = listTextParam(op, variables, list);
                    stmt.params.add(text);
                    stmt.params.add(index);
                } else {
                    stmt.opCode = insert ? "insertListInt"
                            : "setAtPosListnum";
                    stmt.params.add(numberParam(op, list));
                    stmt.params.add(index);
                }
                stmt.params.add(list);
                stmt.sentence = (insert ? "Insert " : "Set ")
                        + "item " + index + " of list “" + list + "”"
                        + (insert ? " to a new value" : "");
                stmt.expectCode = list + (insert ? ".add((int)("
                        : ".set((int)");
            }
            case "reverse_list", "shuffle_list", "sort_list" -> {
                String list = requireList(op, lists);
                Integer listKind = lists.get(list);
                stmt.type = " ";
                stmt.params.add(list);
                switch (kind) {
                    case "reverse_list" -> {
                        stmt.opCode = "reverseList";
                        stmt.sentence = "Reverse the order of list “"
                                + list + "”";
                        stmt.expectCode = "Collections.reverse(" + list;
                    }
                    case "shuffle_list" -> {
                        stmt.opCode = "shuffleList";
                        stmt.sentence = "Shuffle list “" + list + "”";
                        stmt.expectCode = "Collections.shuffle(" + list;
                    }
                    default -> {
                        if (listKind == 3) {
                            String key = stringOf(op, "key");
                            if (key.isEmpty()) {
                                throw new AgentTools.ToolException(
                                        "E_BAD_BLOCK",
                                        "Sorting a list of maps needs "
                                                + "the \"key\" to sort "
                                                + "by.");
                            }
                            stmt.opCode = "sortListmap";
                            stmt.params.clear();
                            stmt.params.add(list);
                            stmt.params.add(key);
                            stmt.params.add(String.valueOf(
                                    boolOf(op, "numeric", false)));
                            stmt.params.add(String.valueOf(
                                    boolOf(op, "ascending", true)));
                            stmt.sentence = "Sort list “" + list
                                    + "” by “" + key + "”";
                            stmt.expectCode = "SketchwareUtil.sortListMap(";
                        } else if (listKind == 2) {
                            stmt.opCode = "sortList";
                            stmt.sentence = "Sort list “" + list + "”";
                            stmt.expectCode = "Collections.sort(" + list;
                        } else {
                            stmt.opCode = "sortListnum";
                            stmt.sentence = "Sort list “" + list + "”";
                            stmt.expectCode = "Collections.sort(" + list;
                        }
                    }
                }
            }
            case "swap_in_list" -> {
                String list = requireList(op, lists);
                stmt.opCode = "swapInList";
                stmt.type = " ";
                stmt.params.add(list);
                stmt.params.add(indexParam(op));
                String index2 = stringOf(op, "index2");
                try {
                    Double.parseDouble(index2);
                } catch (Exception e) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "swap_in_list needs a numeric \"index2\".");
                }
                stmt.params.add(index2);
                stmt.sentence = "Swap items " + stmt.params.get(1)
                        + " and " + index2 + " in list “" + list + "”";
                stmt.expectCode = "Collections.swap(" + list;
            }
            case "remove_map_key" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 3,
                        "remove_map_key needs a map variable.");
                String key = stringOf(op, "key");
                if (key.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "remove_map_key needs a \"key\".");
                }
                stmt.opCode = "mapRemoveKey";
                stmt.type = " ";
                stmt.params.add(name);
                stmt.params.add(key);
                stmt.sentence = "Remove “" + key + "” from map “"
                        + name + "”";
                stmt.expectCode = name + ".remove(";
            }
            case "map_keys_to_list" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 3,
                        "map_keys_to_list needs a map variable.");
                String list = stringOf(op, "list");
                Integer listKind = lists.get(list);
                if (listKind == null || listKind != 2) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "map_keys_to_list fills a list of text; “"
                                    + list + "” is not one on this "
                                    + "screen.");
                }
                stmt.opCode = "mapGetAllKeys";
                stmt.type = " ";
                stmt.params.add(name);
                stmt.params.add(list);
                stmt.sentence = "Put all the keys of map “" + name
                        + "” into list “" + list + "”";
                stmt.expectCode = "SketchwareUtil.getAllKeysFromMap(";
            }
            case "get_map_from_list" -> {
                String list = requireList(op, lists);
                if (lists.get(list) != 3) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "get_map_from_list needs a list of maps; “"
                                    + list + "” is not one.");
                }
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 3,
                        "get_map_from_list stores into a map variable.");
                stmt.opCode = "getMapInList";
                stmt.type = " ";
                stmt.params.add(indexParam(op));
                stmt.params.add(list);
                stmt.params.add(name);
                stmt.sentence = "Take the map at item "
                        + stmt.params.get(0) + " of list “" + list
                        + "” into map “" + name + "”";
                stmt.expectCode = name + " = " + list + ".get((int)";
            }
            case "add_map_to_list", "insert_map_to_list" -> {
                String list = requireList(op, lists);
                if (lists.get(list) != 3) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            kind + " needs a list of maps; “" + list
                                    + "” is not one.");
                }
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 3,
                        kind + " needs a map variable to add.");
                boolean insert = "insert_map_to_list".equals(kind);
                stmt.opCode = insert ? "insertMapToList" : "addMapToList";
                stmt.type = " ";
                stmt.params.add(name);
                if (insert) {
                    stmt.params.add(indexParam(op));
                }
                stmt.params.add(list);
                stmt.sentence = (insert ? "Insert" : "Add") + " map “"
                        + name + "” " + (insert ? "into" : "to")
                        + " list “" + list + "”";
                stmt.expectCode = list + ".add(";
            }
            case "add_map_entry", "insert_map_entry",
                    "set_map_entry" -> {
                String list = requireList(op, lists);
                if (lists.get(list) != 3) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            kind + " needs a list of maps; “" + list
                                    + "” is not one.");
                }
                String key = stringOf(op, "key");
                if (key.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            kind + " needs a \"key\".");
                }
                String text = stringOf(op, "text");
                if (text.isEmpty()) {
                    text = stringOf(op, "value");
                }
                stmt.type = " ";
                stmt.params.add(key);
                stmt.params.add(text);
                switch (kind) {
                    case "add_map_entry" -> {
                        stmt.opCode = "addListMap";
                        stmt.params.add(list);
                        stmt.sentence = "Add a map with “" + key
                                + "” = “" + text + "” to list “"
                                + list + "”";
                    }
                    case "insert_map_entry" -> {
                        stmt.opCode = "insertListMap";
                        stmt.params.add(indexParam(op));
                        stmt.params.add(list);
                        stmt.sentence = "Insert a map with “" + key
                                + "” = “" + text + "” into list “"
                                + list + "”";
                    }
                    default -> {
                        stmt.opCode = "setListMap";
                        stmt.params.add(indexParam(op));
                        stmt.params.add(list);
                        stmt.sentence = "In the map at item "
                                + stmt.params.get(2) + " of list “"
                                + list + "”, set “" + key + "” to “"
                                + text + "”";
                    }
                }
                stmt.expectCode = "_item.put(";
                if ("setListMap".equals(stmt.opCode)) {
                    stmt.expectCode = list + ".get((int)";
                }
            }
            case "map_from_json", "list_from_json" -> {
                // The Gson conversion statements: text slot first,
                // then the map/list it fills (Fx-verified orders).
                String text = stringOf(op, "text");
                String fromVariable = stringOf(op, "from_variable");
                String shown;
                if (!fromVariable.isEmpty()) {
                    requireVariable(variables, fromVariable);
                    requireVariableKind(variables, fromVariable, 2,
                            "JSON text can only come from a string "
                                    + "variable.");
                    text = "@var:" + fromVariable;
                    shown = "the JSON in “" + fromVariable + "”";
                } else if (text.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            kind + " needs the JSON \"text\" (or "
                                    + "\"from_variable\").");
                } else {
                    shown = "“" + text + "”";
                }
                stmt.type = " ";
                stmt.params.add(text);
                if ("map_from_json".equals(kind)) {
                    String name = stringOf(op, "variable");
                    requireVariable(variables, name);
                    requireVariableKind(variables, name, 3,
                            "map_from_json fills a map variable.");
                    stmt.opCode = "strToMap";
                    stmt.params.add(name);
                    stmt.sentence = "Fill map “" + name + "” from "
                            + shown;
                    stmt.expectCode = name + " = new Gson().fromJson(";
                } else {
                    String list = requireList(op, lists);
                    Integer listKind = lists.get(list);
                    stmt.opCode = listKind == 2
                            ? "GsonStringToListString"
                            : listKind == 1 ? "GsonStringToListNumber"
                            : "strToListMap";
                    stmt.params.add(list);
                    stmt.sentence = "Fill list “" + list + "” from "
                            + shown;
                    stmt.expectCode = list + " = new Gson().fromJson(";
                }
            }
            case "finish_all" -> {
                stmt.opCode = "finishAffinity";
                stmt.type = "f";
                stmt.sentence = "Close all screens";
                stmt.expectCode = "finishAffinity();";
            }
            case "crop_image" -> {
                // imageCrop: open the crop screen for an image
                // file; the answer comes back to onActivityResult
                // under the request code given here.
                String path = stringOf(op, "text");
                if (path.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "crop_image needs \"text\": the image "
                                    + "file's path.");
                }
                stmt.opCode = "imageCrop";
                stmt.type = " ";
                stmt.params.add(path);
                stmt.params.add(numericField(op, "value"));
                stmt.sentence = "Crop the image at “" + path + "”";
                stmt.expectCode = "SketchwareUtil.CropImage(this,";
            }
            case "youtube_lifecycle" -> {
                // YTPVLifecycle: register the player with the
                // screen's lifecycle, as the player's own docs
                // require ("getLifecycle().addObserver(%1$s);").
                ViewBean widget = requireWidget(op, byId);
                if (widget.type
                        != ViewBeans.VIEW_TYPE_WIDGET_YOUTUBEPLAYERVIEW) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ", not a YouTube player.");
                }
                stmt.opCode = "YTPVLifecycle";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.sentence = "Let " + widget.id + " follow the "
                        + "screen's lifecycle";
                stmt.expectCode = "getLifecycle().addObserver("
                        + widget.id + ")";
            }
            case "light_status_bar" -> {
                stmt.opCode = "LightStatusBar";
                stmt.type = " ";
                stmt.sentence = "Use a light status bar";
                stmt.expectCode = "SYSTEM_UI_FLAG_LIGHT_STATUS_BAR";
            }
            case "status_bar_color" -> {
                stmt.opCode = "changeStatebarColour";
                stmt.type = " ";
                stmt.params.add(activityName);
                stmt.params.add(colourField(op, "color"));
                stmt.sentence = "Set the status bar colour to "
                        + stringOf(op, "color");
                stmt.expectCode = "setStatusBarColor(";
            }
            case "custom_toast", "custom_toast_icon" -> {
                boolean icon = "custom_toast_icon".equals(kind);
                stmt.opCode = icon ? "customToastWithIcon"
                        : "customToast";
                stmt.type = " ";
                stmt.params.add(stringOf(op, "text"));
                stmt.params.add(colourField(op, "color"));
                stmt.params.add(numericField(op, "value"));
                stmt.params.add(colourField(op, "color2"));
                stmt.params.add(numericField(op, "value2"));
                String gravity = stringOf(op, "mode")
                        .toUpperCase(Locale.ROOT);
                if (!gravity.equals("TOP") && !gravity.equals(
                        "CENTER") && !gravity.equals("BOTTOM")) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "A custom toast's \"mode\" (position) "
                                    + "must be TOP, CENTER or "
                                    + "BOTTOM.");
                }
                stmt.params.add(gravity);
                if (icon) {
                    String image = stringOf(op, "image");
                    if (!image.matches("(?i)[a-z0-9_]+")) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "custom_toast_icon needs \"image\": "
                                        + "the name of an image in "
                                        + "the project.");
                    }
                    stmt.params.add(image.toLowerCase(Locale.ROOT));
                }
                stmt.sentence = "Show a custom toast: “"
                        + stringOf(op, "text") + "”";
                stmt.expectCode = "SketchwareUtil.CustomToast";
            }
            case "fab_icon", "fab_size", "fab_visibility" -> {
                // The activity's own floating action button: Fx
                // addresses the _fab field directly, no widget.
                stmt.type = " ";
                switch (kind) {
                    case "fab_icon" -> {
                        String image = stringOf(op, "image");
                        if (!image.matches("(?i)[a-z0-9_]+")) {
                            throw new AgentTools.ToolException(
                                    "E_BAD_BLOCK",
                                    "fab_icon needs \"image\": the "
                                            + "name of an image in "
                                            + "the project.");
                        }
                        stmt.opCode = "fabIcon";
                        stmt.params.add(
                                image.toLowerCase(Locale.ROOT));
                        stmt.sentence = "Set the round button's "
                                + "icon to “" + image + "”";
                        stmt.expectCode = "setImageResource(R.drawable.";
                    }
                    case "fab_size" -> {
                        String mode = stringOf(op, "mode")
                                .toUpperCase(Locale.ROOT);
                        if (!mode.equals("AUTO") && !mode.equals(
                                "MINI") && !mode.equals("NORMAL")) {
                            throw new AgentTools.ToolException(
                                    "E_BAD_BLOCK",
                                    "fab_size's \"mode\" must be "
                                            + "AUTO, MINI or NORMAL.");
                        }
                        stmt.opCode = "fabSize";
                        stmt.params.add(mode);
                        stmt.sentence = "Set the round button's "
                                + "size to " + mode;
                        stmt.expectCode =
                                "setSize(FloatingActionButton.SIZE_";
                    }
                    default -> {
                        String mode = stringOf(op, "mode")
                                .toLowerCase(Locale.ROOT);
                        if (!mode.equals("show") && !mode.equals(
                                "hide")) {
                            throw new AgentTools.ToolException(
                                    "E_BAD_BLOCK",
                                    "fab_visibility's \"mode\" must "
                                            + "be show or hide.");
                        }
                        stmt.opCode = "fabVisibility";
                        stmt.params.add(mode);
                        stmt.sentence = ("show".equals(mode)
                                ? "Show" : "Hide")
                                + " the round button";
                        stmt.expectCode = "." + mode + "();";
                    }
                }
            }
            case "add_permission", "remove_permission" -> {
                // No generated code: PermissionManager scans the
                // activity's blocks at build time and requests the
                // named permission at runtime.
                String permission = stringOf(op, "text")
                        .toUpperCase(Locale.ROOT);
                if (!permission.matches("[A-Z0-9_]+")) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "This block needs \"text\": a permission "
                                    + "name like CAMERA or "
                                    + "ACCESS_FINE_LOCATION.");
                }
                boolean add = "add_permission".equals(kind);
                stmt.opCode = add ? "addPermission"
                        : "removePermission";
                stmt.type = " ";
                stmt.params.add(permission);
                stmt.sentence = (add ? "Ask for the " : "Stop "
                        + "asking for the ") + permission
                        + " permission";
                stmt.expectCode = "";
            }
            case "auth_sign_out" -> {
                // firebaseauthSignOutUser stores no slots at all;
                // the component is still validated so a typo
                // cannot pass silently.
                String componentId = stringOf(op, "component");
                Integer type = components.get(componentId);
                if (type == null || type != ComponentBean
                        .COMPONENT_TYPE_FIREBASE_AUTH) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "auth_sign_out needs a Firebase Auth "
                                    + "component on this screen; "
                                    + "add one with create_component "
                                    + "(type firebase_auth).");
                }
                stmt.opCode = "firebaseauthSignOutUser";
                stmt.type = " ";
                stmt.sentence = "Sign out";
                stmt.expectCode =
                        "FirebaseAuth.getInstance().signOut();";
            }
            case "map_put_number", "map_put_flag", "map_put_list",
                    "map_put_map" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 3,
                        "This block needs a map variable.");
                String key = stringOf(op, "key");
                if (key.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "This block needs the \"key\" to store "
                                    + "under.");
                }
                stmt.type = " ";
                stmt.params.add(name);
                stmt.params.add(key);
                switch (kind) {
                    case "map_put_number" -> {
                        stmt.opCode = "hashmapPutNumber2";
                        stmt.params.add(numericField(op, "value"));
                        stmt.sentence = "In map “" + name + "”, store "
                                + "the number " + stringOf(op, "value")
                                + " under “" + key + "”";
                        stmt.expectCode = ".put(";
                    }
                    case "map_put_flag" -> {
                        stmt.opCode = "hashmapPutBoolean";
                        stmt.params.add(String.valueOf(
                                boolOf(op, "flag", true)));
                        stmt.sentence = "In map “" + name + "”, store "
                                + boolOf(op, "flag", true)
                                + " under “" + key + "”";
                        stmt.expectCode = ".put(";
                    }
                    case "map_put_list" -> {
                        String list = requireList(op, lists);
                        int listKind = lists.get(list);
                        if (listKind == 2) {
                            stmt.opCode = "hashmapPutListstr";
                        } else if (listKind == 3) {
                            stmt.opCode = "hashmapPutListmap";
                        } else {
                            throw new AgentTools.ToolException(
                                    "E_BAD_BLOCK",
                                    "map_put_list stores a list of "
                                            + "text or of maps; “"
                                            + list + "” is a list of "
                                            + "numbers.");
                        }
                        stmt.params.add(list);
                        stmt.sentence = "In map “" + name + "”, store "
                                + "the list “" + list + "” under “"
                                + key + "”";
                        stmt.expectCode = ".put(";
                    }
                    default -> {
                        String other = stringOf(op, "from_variable");
                        requireVariable(variables, other);
                        requireVariableKind(variables, other, 3,
                                "map_put_map stores another map "
                                        + "variable.");
                        stmt.opCode = "hashmapPutMap";
                        stmt.params.add(other);
                        stmt.sentence = "In map “" + name + "”, store "
                                + "the map “" + other + "” under “"
                                + key + "”";
                        stmt.expectCode = ".put(";
                    }
                }
            }
            case "add_all_to_list" -> {
                String list = requireList(op, lists);
                String from = stringOf(op, "from");
                if (!lists.containsKey(from)) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "There is no list called " + from
                                    + " on this screen to copy from.");
                }
                if (!lists.get(from).equals(lists.get(list))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "add_all_to_list copies between two "
                                    + "lists of the same kind; “"
                                    + list + "” and “" + from
                                    + "” hold different things.");
                }
                stmt.opCode = "listAddAll";
                stmt.type = " ";
                stmt.params.add(list);
                stmt.params.add(from);
                stmt.sentence = "Add everything in “" + from
                        + "” to “" + list + "”";
                stmt.expectCode = ".addAll(";
            }
            case "set_map_in_list" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 3,
                        "set_map_in_list needs a map variable to "
                                + "place.");
                String list = requireList(op, lists);
                if (lists.get(list) != 3) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_map_in_list places into a list of "
                                    + "maps; “" + list + "” is not "
                                    + "one.");
                }
                stmt.opCode = "setMapAtPosListmap";
                stmt.type = " ";
                stmt.params.add(name);
                stmt.params.add(indexParam(op));
                stmt.params.add(list);
                stmt.sentence = "Put map “" + name + "” at item "
                        + stringOf(op, "index") + " of “" + list
                        + "”";
                stmt.expectCode = ".set((int)";
            }
            case "remove_map_from_list" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 3,
                        "remove_map_from_list needs the map "
                                + "variable to remove.");
                String list = requireList(op, lists);
                if (lists.get(list) != 3) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "remove_map_from_list works on a list "
                                    + "of maps; “" + list + "” is "
                                    + "not one.");
                }
                stmt.opCode = "deleteMapFromListmap";
                stmt.type = " ";
                stmt.params.add(name);
                stmt.params.add(list);
                stmt.sentence = "Remove map “" + name + "” from “"
                        + list + "”";
                stmt.expectCode = ".remove(";
            }
            case "split_into_list" -> {
                String list = requireList(op, lists);
                if (lists.get(list) != 2) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "split_into_list fills a list of text; "
                                    + "“" + list + "” is not one.");
                }
                stmt.opCode = "stringSplitToList";
                stmt.type = " ";
                stmt.params.add(stringOf(op, "text"));
                stmt.params.add(stringOf(op, "text2"));
                stmt.params.add(list);
                stmt.sentence = "Split “" + stringOf(op, "text")
                        + "” by “" + stringOf(op, "text2")
                        + "” into “" + list + "”";
                stmt.expectCode = ".split(";
            }
            case "open_asset" -> {
                String name = stringOf(op, "name");
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "open_asset needs \"name\": what to call "
                                    + "the stream (letters, digits, "
                                    + "_, starting with a letter).");
                }
                stmt.opCode = "getAssetFile";
                stmt.type = " ";
                stmt.params.add(name);
                stmt.params.add(stringOf(op, "text"));
                stmt.sentence = "Open the asset “"
                        + stringOf(op, "text") + "” as " + name;
                stmt.expectCode = "getAssets().open(";
            }
            case "scale_bitmap", "resize_bitmap_ratio",
                    "resize_bitmap_square", "resize_bitmap_circle",
                    "resize_bitmap_rounded", "crop_bitmap_center",
                    "rotate_bitmap", "skew_bitmap",
                    "bitmap_brightness", "bitmap_contrast",
                    "bitmap_color_filter" -> {
                // The FileUtil bitmap family: from path, to path,
                // then the block's own numbers (Fx-verified).
                stmt.type = " ";
                stmt.params.add(stringOf(op, "text"));
                stmt.params.add(stringOf(op, "text2"));
                String verb;
                switch (kind) {
                    case "scale_bitmap" -> {
                        stmt.opCode = "scaleBitmapFile";
                        stmt.params.add(numericField(op, "value"));
                        stmt.params.add(numericField(op, "value2"));
                        verb = "Scale";
                    }
                    case "resize_bitmap_ratio" -> {
                        stmt.opCode = "resizeBitmapFileRetainRatio";
                        stmt.params.add(numericField(op, "value"));
                        verb = "Resize";
                    }
                    case "resize_bitmap_square" -> {
                        stmt.opCode = "resizeBitmapFileToSquare";
                        stmt.params.add(numericField(op, "value"));
                        verb = "Resize square";
                    }
                    case "resize_bitmap_circle" -> {
                        stmt.opCode = "resizeBitmapFileToCircle";
                        verb = "Crop to a circle";
                    }
                    case "resize_bitmap_rounded" -> {
                        stmt.opCode =
                                "resizeBitmapFileWithRoundedBorder";
                        stmt.params.add(numericField(op, "value"));
                        verb = "Round the corners of";
                    }
                    case "crop_bitmap_center" -> {
                        stmt.opCode = "cropBitmapFileFromCenter";
                        stmt.params.add(numericField(op, "value"));
                        stmt.params.add(numericField(op, "value2"));
                        verb = "Centre-crop";
                    }
                    case "rotate_bitmap" -> {
                        stmt.opCode = "rotateBitmapFile";
                        stmt.params.add(numericField(op, "value"));
                        verb = "Rotate";
                    }
                    case "skew_bitmap" -> {
                        stmt.opCode = "skewBitmapFile";
                        stmt.params.add(numericField(op, "value"));
                        stmt.params.add(numericField(op, "value2"));
                        verb = "Skew";
                    }
                    case "bitmap_brightness" -> {
                        stmt.opCode = "setBitmapFileBrightness";
                        stmt.params.add(numericField(op, "value"));
                        verb = "Set the brightness of";
                    }
                    case "bitmap_contrast" -> {
                        stmt.opCode = "setBitmapFileContrast";
                        stmt.params.add(numericField(op, "value"));
                        verb = "Set the contrast of";
                    }
                    default -> {
                        stmt.opCode = "setBitmapFileColorFilter";
                        stmt.params.add(colourField(op, "color"));
                        verb = "Tint";
                    }
                }
                stmt.sentence = verb + " the image at “"
                        + stringOf(op, "text") + "” into “"
                        + stringOf(op, "text2") + "”";
                stmt.expectCode = "FileUtil.";
            }
            case "set_fragment_adapter" -> {
                // A ViewPager bound to a FragmentAdapter
                // component: pager first, component, tab count.
                ViewBean pager = findWidget(byId,
                        stringOf(op, "target"));
                if (pager == null || !"ViewPager".equals(
                        AgentEdits.typeName(pager.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_fragment_adapter needs a ViewPager "
                                    + "as its \"target\".");
                }
                String componentId = stringOf(op, "component");
                Integer type = components.get(componentId);
                if (type == null || type != ComponentBean
                        .COMPONENT_TYPE_FRAGMENT_ADAPTER) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_fragment_adapter needs a Fragment "
                                    + "Adapter component; add one "
                                    + "with create_component (type "
                                    + "fragment_adapter).");
                }
                stmt.opCode = "pagerSetFragmentAdapter";
                stmt.type = " ";
                stmt.params.add(pager.id);
                stmt.params.add(componentId);
                stmt.params.add(numericField(op, "value"));
                stmt.sentence = "Give " + pager.id + " the pages "
                        + "from " + componentId;
                stmt.expectCode = ".setAdapter(";
            }
            case "menu_add_item" -> {
                // Only meaningful inside onCreateOptionsMenu,
                // where the menu itself is in scope.
                stmt.opCode = "menuAddItem";
                stmt.type = " ";
                stmt.params.add(numericField(op, "value"));
                stmt.params.add(stringOf(op, "text"));
                stmt.sentence = "Add menu item “"
                        + stringOf(op, "text") + "” (id "
                        + stringOf(op, "value") + ")";
                stmt.expectCode = "menu.add(0,";
            }
            case "menu_inflate" -> {
                String menu = stringOf(op, "menu");
                if (!menu.matches("(?i)[a-z0-9_]+")) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "menu_inflate needs \"menu\": the name "
                                    + "of a menu file in the "
                                    + "project.");
                }
                stmt.opCode = "menuInflater";
                stmt.type = " ";
                stmt.params.add(menu.toLowerCase(Locale.ROOT));
                stmt.sentence = "Use the “" + menu + "” menu file";
                stmt.expectCode = "getMenuInflater().inflate(R.menu.";
            }
            default -> {
                WA action = WIDGET_ACTIONS.get(kind);
                CA componentAction = action == null
                        ? COMPONENT_ACTIONS.get(kind) : null;
                if (action != null) {
                    buildWidgetAction(op, byId, lists, action, stmt);
                } else if (componentAction != null) {
                    buildComponentAction(op, byId, variables, lists,
                            components, activityName, componentAction,
                            stmt);
                } else {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                    (kind.isEmpty()
                            ? "A block arrived with no \"block\" name: "
                                    + abbrev(op.toString()) + " "
                            : "Unknown block \"" + kind + "\": "
                                    + abbrev(op.toString()) + " ")
                            + "Blocks are: toast, "
                            + "set_text, set_visibility, set_enabled, "
                            + "finish, set_variable, increase, decrease, "
                            + "add_to_list, clear_list, show_list, "
                            + "put_in_map, clear_map, new_map, write_file, "
                            + "delete_file, make_folder, copy_file, "
                            + "move_file, copy_folder, rename_file, "
                            + "list_files, remove_from_list, "
                            + "insert_in_list, set_in_list, reverse_list, "
                            + "shuffle_list, sort_list, swap_in_list, "
                            + "remove_map_key, map_keys_to_list, "
                            + "get_map_from_list, add_map_to_list, "
                            + "insert_map_to_list, add_map_entry, "
                            + "insert_map_entry, set_map_entry, "
                            + "map_from_json, list_from_json, and the "
                            + "widget actions (set_text_size, "
                            + "set_background_color, web_go_back, "
                            + "play_video, set_rating, and kin).");
                }
            }
        }
        // The app's own slot table is the shape authority: what the
        // editor would store for this block has exactly these slots.
        // Pro-defined blocks are not in the stock table; for them the
        // canonical spec text (the same xB lookup specFor uses, which
        // is how the editor itself learns a Pro block's shape) gives
        // the slots instead.
        ArrayList<String> slots;
        try {
            slots = lq.a(stmt.opCode);
        } catch (Exception e) {
            slots = null;
        }
        if (slots == null || slots.size() != stmt.params.size()) {
            ArrayList<String> specSlots = new ArrayList<>();
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("%[a-zA-Z]+(?:\\.[a-zA-Z]+)*")
                    .matcher(specFor(context, stmt.opCode));
            while (matcher.find()) {
                specSlots.add(matcher.group());
            }
            if (specSlots.size() == stmt.params.size()) {
                slots = specSlots;
            }
        }
        if (slots == null || slots.size() != stmt.params.size()) {
            throw new AgentTools.ToolException("E_INTERNAL",
                    "Sketchware's block table disagrees about \""
                            + stmt.opCode + "\"; refusing to guess its "
                            + "shape.");
        }
        return stmt;
    }

    /** A file path argument: literal text, or a string variable's. */
    private static final class PathArg {
        final String stored;
        final String shown;

        PathArg(String stored, String shown) {
            this.stored = stored;
            this.shown = shown;
        }
    }

    private static PathArg pathParam(JsonObject op,
                                     Map<String, Integer> variables,
                                     String key)
            throws AgentTools.ToolException {
        String fromVariable = stringOf(op, key + "_from_variable");
        if (!fromVariable.isEmpty()) {
            requireVariable(variables, fromVariable);
            requireVariableKind(variables, fromVariable, 2,
                    "A path can only come from a string variable.");
            return new PathArg("@var:" + fromVariable,
                    "the path in “" + fromVariable + "”");
        }
        String path = stringOf(op, key);
        if (path.isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "This block needs a \"" + key + "\" (a file path "
                            + "like /sdcard/notes.txt), or \""
                            + key + "_from_variable\".");
        }
        return new PathArg(path, "“" + path + "”");
    }

    private static String requireList(JsonObject op,
                                      Map<String, Integer> lists)
            throws AgentTools.ToolException {
        String list = stringOf(op, "list");
        if (!lists.containsKey(list)) {
            // Same correction as requireComponent: an unnamed list is a
            // model that forgot what it declared, so the answer names
            // the lists this screen actually has.
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    (list.isEmpty()
                            ? "This block needs a list and none was named. "
                            : "There is no list called " + list
                                    + " on this screen. ")
                            + describe("This screen has: ",
                                    "It declares no lists yet — declare "
                                            + "one with create_variable "
                                            + "(list_string, list_number "
                                            + "or list_map).",
                                    lists.keySet()));
        }
        return list;
    }

    /**
     * Turns a set of names into the sentence that ends a refusal: what
     * is there, or plainly that there is nothing. A check that refuses
     * without saying what would have worked just gets retried.
     */
    private static String describe(String prefix, String empty,
                                   java.util.Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return empty;
        }
        StringBuilder text = new StringBuilder(prefix);
        int shown = 0;
        for (String name : names) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (shown > 0) {
                text.append(", ");
            }
            text.append(name);
            if (++shown == 12) {
                text.append(" …");
                break;
            }
        }
        return text.append(".").toString();
    }

    /** A numeric literal argument field, validated before storing. */
    private static String numericField(JsonObject op, String field)
            throws AgentTools.ToolException {
        String value = stringOf(op, field);
        try {
            Double.parseDouble(value);
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "This block needs a numeric \"" + field + "\".");
        }
        return value;
    }

    /** A numeric "index" argument, stored the way Fx casts it. */
    private static String indexParam(JsonObject op)
            throws AgentTools.ToolException {
        String index = stringOf(op, "index");
        try {
            double value = Double.parseDouble(index);
            if (value == Math.floor(value)
                    && !Double.isInfinite(value)) {
                return String.valueOf((long) value);
            }
            return index;
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "This block needs a numeric \"index\" (the item's "
                            + "position in the list, starting at 0).");
        }
    }

    /** A literal number for a list-of-numbers slot. */
    private static String numberParam(JsonObject op, String list)
            throws AgentTools.ToolException {
        String value = stringOf(op, "value");
        try {
            Double.parseDouble(value);
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "List “" + list + "” holds numbers, so \"value\" "
                            + "must be a number (got \"" + value
                            + "\").");
        }
        return value;
    }

    /** Text for a list-of-text slot: literal or a string variable. */
    private static String listTextParam(JsonObject op,
                                        Map<String, Integer> variables,
                                        String list)
            throws AgentTools.ToolException {
        String fromVariable = stringOf(op, "from_variable");
        if (!fromVariable.isEmpty()) {
            requireVariable(variables, fromVariable);
            requireVariableKind(variables, fromVariable, 2,
                    "Only a string variable's value can go into a "
                            + "list of text.");
            return "@var:" + fromVariable;
        }
        String text = stringOf(op, "text");
        if (text.isEmpty()) {
            text = stringOf(op, "value");
        }
        return text;
    }

    /** A colour argument in the editor's stored form, by field. */
    private static String colourField(JsonObject op, String field)
            throws AgentTools.ToolException {
        String colour = stringOf(op, field);
        if (colour.isEmpty()
                || !colour.matches("(?i)#([0-9a-f]{6}|[0-9a-f]{8})")) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "This block needs \"" + field + "\" as #RRGGBB or "
                            + "#AARRGGBB.");
        }
        String hex = colour.substring(1).toUpperCase(Locale.ROOT);
        if (hex.length() == 6) hex = "FF" + hex;
        return "0x" + hex;
    }

    /**
     * Builds one registry widget action (see {@link #WIDGET_ACTIONS}):
     * the target widget first, then the declared argument slots, each
     * validated the way the palette itself constrains it.
     */
    private static void buildWidgetAction(JsonObject op,
                                          Map<String, ViewBean> byId,
                                          Map<String, Integer> lists,
                                          WA action, Stmt stmt)
            throws AgentTools.ToolException {
        ViewBean widget = requireWidget(op, byId);
        String typeName = AgentEdits.typeName(widget.type);
        boolean familyOk = switch (action.widget) {
            case "" -> true;
            case "edittext" -> "EditText".equals(typeName);
            case "textview" -> TEXT_TYPES.contains(typeName);
            case "webview" -> "WebView".equals(typeName);
            case "seekbar" -> "SeekBar".equals(typeName);
            case "ratingbar" -> "RatingBar".equals(typeName);
            case "checkbox" -> "CheckBox".equals(typeName);
            case "imageview" -> "ImageView".equals(typeName)
                    || "CircleImageView".equals(typeName);
            case "progressbar" -> "ProgressBar".equals(typeName);
            case "actv" -> "AutoCompleteTextView".equals(typeName);
            case "mactv" -> "MultiAutoCompleteTextView".equals(typeName);
            case "videoview" -> "VideoView".equals(typeName);
            case "listview" -> "ListView".equals(typeName);
            case "recyclerview" -> "RecyclerView".equals(typeName);
            case "gridview" -> "GridView".equals(typeName);
            case "spinner" -> "Spinner".equals(typeName);
            case "viewpager" -> "ViewPager".equals(typeName);
            case "tablayout" -> "TabLayout".equals(typeName);
            case "cardview" -> "CardView".equals(typeName);
            case "timepicker" -> "TimePicker".equals(typeName);
            case "calendarview" -> "CalendarView".equals(typeName);
            case "textinputlayout" -> "TextInputLayout".equals(typeName);
            case "badgeview" -> "BadgeView".equals(typeName);
            case "patternview" -> "PatternLockView".equals(typeName);
            case "otpview" -> "OTPView".equals(typeName);
            case "lottie" -> "LottieAnimationView".equals(typeName);
            case "codeview" -> "CodeView".equals(typeName);
            case "mapview" -> "MapView".equals(typeName);
            case "swiperefresh" -> "SwipeRefreshLayout".equals(typeName);
            case "bottomnavigation" ->
                    "BottomNavigationView".equals(typeName);
            case "bubblelayout" -> "BubbleLayout".equals(typeName);
            case "signinbutton" -> "SignInButton".equals(typeName);
            case "adview" -> "AdView".equals(typeName);
            default -> false;
        };
        if (!familyOk) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    widget.id + " is a " + typeName + ", and this "
                            + "block needs a " + action.widget
                            + " widget.");
        }
        stmt.opCode = action.opCode;
        stmt.type = " ";
        stmt.params.add(widget.id);
        String sentence = action.sentence.replace("{t}", widget.id);
        int numberField = 0;
        int colourField = 0;
        int textField = 0;
        for (int i = 0; i < action.args.length(); i++) {
            char arg = action.args.charAt(i);
            String shown;
            switch (arg) {
                case 'd' -> {
                    String[] fields = {"value", "value2", "value3",
                            "value4"};
                    String field = fields[Math.min(numberField++,
                            fields.length - 1)];
                    String value = stringOf(op, field);
                    try {
                        Double.parseDouble(value);
                    } catch (Exception e) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block needs a numeric \"" + field
                                        + "\".");
                    }
                    stmt.params.add(value);
                    shown = value;
                }
                case 's' -> {
                    String[] fields = {"text", "text2", "text3"};
                    String field = fields[Math.min(textField++,
                            fields.length - 1)];
                    String text = stringOf(op, field);
                    if (text.isEmpty() && "text".equals(field)) {
                        text = stringOf(op, "url");
                    }
                    stmt.params.add(text);
                    shown = text;
                }
                case 'b' -> {
                    boolean flag = op.has("flag")
                            ? boolOf(op, "flag", true)
                            : boolOf(op, "enabled", true);
                    stmt.params.add(String.valueOf(flag));
                    shown = String.valueOf(flag);
                }
                case 'c' -> {
                    String field = colourField++ == 0 ? "color"
                            : "color2";
                    String stored = colourField(op, field);
                    stmt.params.add(stored);
                    shown = stringOf(op, field);
                }
                case 'r' -> {
                    String image = stringOf(op, "image");
                    if (!image.matches("(?i)[a-z0-9_]+")) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block needs \"image\": the name "
                                        + "of an image in the project "
                                        + "(letters, digits, _).");
                    }
                    stmt.params.add(image.toLowerCase(Locale.ROOT));
                    shown = image;
                }
                case 'g' -> {
                    String field = stmt.params.size() == 2 ? "gravity2"
                            : "gravity";
                    String gravity = stringOf(op, field)
                            .toUpperCase(Locale.ROOT);
                    if (!gravity.matches("[A-Z0-9_]+")) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block needs \"" + field + "\" "
                                        + "as a gravity constant like "
                                        + "CENTER, LEFT, TOP, BOTTOM, "
                                        + "CENTER_VERTICAL or "
                                        + "CENTER_HORIZONTAL.");
                    }
                    stmt.params.add(gravity);
                    shown = gravity;
                }
                case 'm' -> {
                    String mode = stringOf(op, "mode")
                            .toUpperCase(Locale.ROOT);
                    if (!mode.matches("[A-Z0-9_]+")) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block needs \"mode\" as a "
                                        + "constant like LOAD_DEFAULT, "
                                        + "LOAD_NO_CACHE or "
                                        + "LOAD_CACHE_ELSE_NETWORK.");
                    }
                    stmt.params.add(mode);
                    shown = mode;
                }
                case 'l' -> {
                    String list = stringOf(op, "list");
                    Integer listKind = lists.get(list);
                    if (listKind == null || listKind != 2) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block fills suggestions from a "
                                        + "list of text; “" + list
                                        + "” is not one on this "
                                        + "screen.");
                    }
                    stmt.params.add(list);
                    shown = list;
                }
                case 'w' -> {
                    String childId = stringOf(op, "child");
                    ViewBean child = findWidget(byId, childId);
                    if (child == null) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "There is no widget called " + childId
                                        + " on this screen to use as "
                                        + "\"child\".");
                    }
                    stmt.params.add(child.id);
                    shown = child.id;
                }
                default -> throw new AgentTools.ToolException(
                        "E_INTERNAL", "Bad widget-action slot " + arg);
            }
            sentence = sentence.replace("{" + (i + 1) + "}", shown);
        }
        stmt.sentence = sentence;
        stmt.expectCode = widget.id + action.expect;
    }

    /**
     * The value of a text slot in a component action: a quoted literal,
     * or {@code {"var":"draft"}} to store what a variable holds.
     *
     * <p>This is what made "save what the user typed" impossible: the
     * slot took a literal only, so {@code save_pref} could store a
     * fixed word but never an entered note — which is why a model asked
     * to persist data ended up showing a toast and finishing the
     * screen instead. The variable is written as the same
     * {@code "@var:"} placeholder the getVar pass already resolves, so
     * no new block shape is introduced.
     *
     * <p>Only text variables are accepted. A number in a text slot
     * would generate {@code putString("key", (int) n)}, which does not
     * compile, and the model can convert with
     * {@code {"fn":"to_text","a":{"var":"n"}}}.
     */
    private static String textSlotValue(JsonObject op, String field,
                                        Map<String, Integer> variables,
                                        String opCode)
            throws AgentTools.ToolException {
        if (!op.has(field) || !op.get(field).isJsonObject()) {
            return stringOf(op, field);
        }
        JsonObject value = op.get(field).getAsJsonObject();
        String name = stringOf(value, "var");
        if (name.isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "\"" + field + "\" must be a quoted text, or "
                            + "{\"var\":\"name\"} to use the value a "
                            + "variable holds.");
        }
        Integer kind = variables.get(name);
        if (kind == null) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "There is no variable called \"" + name
                            + "\" on this screen, so " + opCode
                            + " has nothing to store. "
                            + "Declare it with create_variable, or give "
                            + "\"" + field + "\" a quoted text instead.");
        }
        if (kind != 2) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "\"" + name + "\" is a "
                            + SketchwareProjects.variableTypeName(kind)
                            + " variable, and this slot stores text. "
                            + "Turn it into text with "
                            + "{\"fn\":\"to_text\",\"a\":{\"var\":\""
                            + name + "\"}}.");
        }
        return "@var:" + name;
    }

    /** Fails when a registry entry restricts a mode slot's values. */
    private static void checkMode(CA action, String mode)
            throws AgentTools.ToolException {
        if (action.modes == null) {
            return;
        }
        for (String allowed : action.modes) {
            if (allowed.equals(mode)) {
                return;
            }
        }
        throw new AgentTools.ToolException("E_BAD_BLOCK",
                "This block's \"mode\" must be one of: "
                        + String.join(", ", action.modes) + ".");
    }

    /**
     * Builds one registry component action (see
     * {@link #COMPONENT_ACTIONS}): the component first (validated
     * against the screen's component list and its type), then the
     * declared argument slots.
     */
    private static void buildComponentAction(JsonObject op,
                                             Map<String, ViewBean> byId,
                                             Map<String, Integer> variables,
                                             Map<String, Integer> lists,
                                             Map<String, Integer> components,
                                             String activityName,
                                             CA action, Stmt stmt)
            throws AgentTools.ToolException {
        stmt.opCode = action.opCode;
        stmt.type = " ";
        String componentId = "";
        if (action.componentType != 0) {
            componentId = stringOf(op, "component");
            Integer type = components.get(componentId);
            if (type == null) {
                String correction = describe("This screen has: ",
                        "It has no components yet — add one with "
                                + "create_component.",
                        components.keySet());
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        (componentId.isEmpty()
                                ? "This block acts on a component and "
                                        + "none was named. "
                                : "There is no component called "
                                        + componentId + " on this screen. ")
                                + correction);
            }
            if (type != action.componentType) {
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        componentId + " is a "
                                + ComponentBean.getComponentName(null,
                                        type)
                                + " component, and this block needs a "
                                + ComponentBean.getComponentName(null,
                                        action.componentType)
                                + " component.");
            }
            stmt.params.add(componentId);
        }
        String sentence = action.sentence.replace("{c}", componentId);
        int numberField = 0;
        int textField = 0;
        int colourField = 0;
        for (int i = 0; i < action.args.length(); i++) {
            char arg = action.args.charAt(i);
            String shown;
            switch (arg) {
                case 'd' -> {
                    String[] fields = {"value", "value2", "value3"};
                    String field = fields[Math.min(numberField++,
                            fields.length - 1)];
                    String value = stringOf(op, field);
                    try {
                        Double.parseDouble(value);
                    } catch (Exception e) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block needs a numeric \"" + field
                                        + "\".");
                    }
                    stmt.params.add(value);
                    shown = value;
                }
                case 's' -> {
                    String[] fields = {"text", "text2", "text3"};
                    String field = fields[Math.min(textField++,
                            fields.length - 1)];
                    String text = textSlotValue(op, field, variables,
                            action.opCode);
                    stmt.params.add(text);
                    shown = text.startsWith("@var:")
                            ? text.substring("@var:".length()) : text;
                }
                case 'b' -> {
                    boolean flag = boolOf(op, "flag", true);
                    stmt.params.add(String.valueOf(flag));
                    shown = String.valueOf(flag);
                }
                case 'm' -> {
                    String mode = stringOf(op, "mode")
                            .toUpperCase(Locale.ROOT);
                    if (!mode.matches("[A-Z0-9_]+")) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block needs \"mode\" as a "
                                        + "constant name like GET, "
                                        + "YEAR or GPS_PROVIDER.");
                    }
                    checkMode(action, mode);
                    stmt.params.add(mode);
                    shown = mode;
                }
                case 'M' -> {
                    // A mode stored exactly as given (the animator
                    // easing names are mixed-case in Fx's table).
                    String mode = stringOf(op, "mode");
                    checkMode(action, mode);
                    stmt.params.add(mode);
                    shown = mode;
                }
                case 'F' -> {
                    // An intent-flag suffix: Fx itself prepends
                    // "Intent.FLAG_ACTIVITY_".
                    String flag = stringOf(op, "mode")
                            .toUpperCase(Locale.ROOT);
                    if (!flag.matches("[A-Z0-9_]+")) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block needs \"mode\" as a flag "
                                        + "name like NEW_TASK, "
                                        + "CLEAR_TOP or SINGLE_TOP.");
                    }
                    stmt.params.add(flag);
                    shown = flag;
                }
                case 'r' -> {
                    String image = stringOf(op, "image");
                    if (!image.matches("(?i)[a-z0-9_]+")) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block needs \"image\": the name "
                                        + "of an image in the project "
                                        + "(letters, digits, _).");
                    }
                    stmt.params.add(image.toLowerCase(Locale.ROOT));
                    shown = image;
                }
                case 'c' -> {
                    String field = colourField++ == 0 ? "color"
                            : "color2";
                    stmt.params.add(colourField(op, field));
                    shown = stringOf(op, field);
                }
                case 'L' -> {
                    String name = stringOf(op, "variable");
                    Integer listKind = lists.get(name);
                    if (listKind == null || listKind != 3) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block fills a list of maps; “"
                                        + name + "” is not one on "
                                        + "this screen. Declare it with "
                                        + "create_variable (list_map).");
                    }
                    stmt.params.add(name);
                    shown = name;
                }
                case 'v' -> {
                    String name = stringOf(op, "variable");
                    requireVariable(variables, name);
                    requireVariableKind(variables, name, 3,
                            "This block needs a map variable.");
                    stmt.params.add(name);
                    shown = name;
                }
                case 'R' -> {
                    String sound = stringOf(op, "sound");
                    if (!sound.matches("(?i)[a-z0-9_]+")) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "This block needs \"sound\": the name "
                                        + "of a sound in the project's "
                                        + "raw resources.");
                    }
                    stmt.params.add(sound.toLowerCase(Locale.ROOT));
                    shown = sound;
                }
                case 'A' -> {
                    stmt.params.add(activityName);
                    shown = activityName;
                }
                case 'w' -> {
                    String childId = stringOf(op, "target");
                    ViewBean child = findWidget(byId, childId);
                    if (child == null) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "There is no widget called " + childId
                                        + " on this screen to animate.");
                    }
                    stmt.params.add(child.id);
                    shown = child.id;
                }
                default -> throw new AgentTools.ToolException(
                        "E_INTERNAL", "Bad component-action slot "
                                + arg);
            }
            sentence = sentence.replace("{" + (i + 1) + "}", shown);
        }
        stmt.sentence = sentence;
        stmt.expectCode = (action.componentType != 0 ? componentId
                : "") + action.expect;
    }

    // -- stored-shape helpers ----------------------------------------------

    /**
     * The variables one activity declares: name to the editor's type
     * code (0 boolean, 1 number, 2 string, 3 map), read through the
     * same accessor ({@code eC.k}) the logic editor and the code
     * generator use. Other stored entries (raw fields, imports) are
     * not variables and stay invisible here.
     */
    static LinkedHashMap<String, Integer> variablesOf(eC store,
                                                      String javaName) {
        LinkedHashMap<String, Integer> variables = new LinkedHashMap<>();
        try {
            ArrayList<android.util.Pair<Integer, String>> stored =
                    store.k(javaName);
            if (stored != null) {
                for (android.util.Pair<Integer, String> pair : stored) {
                    if (pair.first >= 0 && pair.first <= 3
                            && pair.second != null) {
                        variables.put(pair.second, pair.first);
                    }
                }
            }
        } catch (Exception ignored) {
            // Unreadable logic reads as "no variables declared".
        }
        return variables;
    }

    /**
     * The lists one activity declares: name to the editor's list
     * type code (1 numbers, 2 text, 3 maps), through {@code eC.j} —
     * the accessor the logic editor and the code generator use.
     */
    static LinkedHashMap<String, Integer> listsOf(eC store,
                                                  String javaName) {
        LinkedHashMap<String, Integer> lists = new LinkedHashMap<>();
        try {
            ArrayList<android.util.Pair<Integer, String>> stored =
                    store.j(javaName);
            if (stored != null) {
                for (android.util.Pair<Integer, String> pair : stored) {
                    if (pair.first >= 1 && pair.first <= 3
                            && pair.second != null) {
                        lists.put(pair.second, pair.first);
                    }
                }
            }
        } catch (Exception ignored) {
            // Unreadable logic reads as "no lists declared".
        }
        return lists;
    }

    /** The statement opCode that assigns to a variable of this kind. */
    private static String setVarOpCode(int kind) {
        return switch (kind) {
            case 0 -> "setVarBoolean";
            case 2 -> "setVarString";
            default -> "setVarInt";
        };
    }

    /** The palette type letter for a variable's value block. */
    private static String variableTypeLetter(Integer kind) {
        if (kind == null) {
            return "s";
        }
        return switch (kind) {
            case 0 -> "b";
            case 1 -> "d";
            case 3 -> "a";
            default -> "s";
        };
    }

    /** Fails unless the activity declares a variable with this name. */
    /**
     * Where variables really live: one screen each. A model that
     * declares "task" on MainActivity and then wires it on
     * AuthActivity needs to be told the name is not global — and
     * where it actually is.
     */
    private static String variablesByScreenHint(String scId,
                                                ProjectFileBean current) {
        if (scId == null || scId.isEmpty() || current == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try {
            eC store = jC.a(scId);
            for (ProjectFileBean activity
                    : SketchwareProjects.activities(scId)) {
                if (activity.getJavaName()
                        .equals(current.getJavaName())) {
                    continue;
                }
                LinkedHashMap<String, Integer> variables =
                        variablesOf(store, activity.getJavaName());
                if (variables.isEmpty()) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append("; ");
                }
                out.append(SketchwareProjects.activityName(activity))
                        .append(" declares ")
                        .append(String.join(", ", variables.keySet()));
            }
        } catch (Exception ignored) {
            return "";
        }
        if (out.length() == 0) {
            return "";
        }
        return "Variables belong to ONE screen — a variable declared "
                + "elsewhere does not exist here ("
                + out + "). Declare the one you need on this screen "
                + "with create_variable first.";
    }

    /**
     * The activity event a model means, even when it names it the
     * Android way. Sketchware's own screen-created event is
     * {@code initializeLogic}; models say {@code onCreate} almost
     * every time, and a bare refusal just makes them try again.
     * Returns null when the name is not a near-miss of one.
     */
    private static String activityEventAlias(String asked) {
        if (asked == null) {
            return null;
        }
        String key = asked.trim().toLowerCase(Locale.ROOT)
                .replace("_", "").replace(" ", "");
        return switch (key) {
            case "oncreate", "oncreateview", "init", "initialize",
                    "onviewcreated", "createlogic" -> "initializeLogic";
            case "onfinish", "finish" -> "onDestroy";
            default -> null;
        };
    }

    private static void requireVariable(Map<String, Integer> variables,
                                        String name)
            throws AgentTools.ToolException {
        if (name == null || name.isEmpty()
                || !variables.containsKey(name)) {
            StringBuilder declared = new StringBuilder();
            for (Map.Entry<String, Integer> entry : variables.entrySet()) {
                if (declared.length() > 0) {
                    declared.append(", ");
                }
                declared.append(entry.getKey()).append(" (")
                        .append(SketchwareProjects.variableTypeName(
                                entry.getValue())).append(")");
            }
            throw new AgentTools.ToolException("E_NO_VARIABLE",
                    "This screen has no variable named \"" + name
                            + "\"."
                            + (variables.isEmpty()
                                    ? " It declares no variables yet — "
                                            + "declare one with "
                                            + "create_variable first."
                                    : " Its variables are: "
                                            + declared + "."));
        }
    }

    /** Fails unless the named variable is of the wanted editor kind. */
    private static void requireVariableKind(
            Map<String, Integer> variables, String name, int kind,
            String message) throws AgentTools.ToolException {
        Integer actual = variables.get(name);
        if (actual == null || actual != kind) {
            throw new AgentTools.ToolException("E_BAD_BLOCK", message);
        }
    }

    /** The event's blocks, or an empty list when it has none. */
    private static ArrayList<BlockBean> readBlocks(eC store,
                                                   String javaName,
                                                   Plan plan) {
        ArrayList<BlockBean> blocks = store.a(javaName,
                plan.targetId + "_" + plan.eventName);
        return blocks == null ? new ArrayList<>() : blocks;
    }

    private static boolean eventRegistered(eC store, String javaName,
                                           Plan plan) {
        ArrayList<EventBean> events = store.g(javaName);
        if (events == null) {
            return false;
        }
        for (EventBean event : events) {
            if (plan.activityEvent) {
                if (event.eventType == EventBean.EVENT_TYPE_ACTIVITY
                        && plan.eventName.equals(event.eventName)) {
                    return true;
                }
            } else if (plan.componentEvent) {
                if (event.eventType == EventBean.EVENT_TYPE_COMPONENT
                        && plan.targetId.equals(event.targetId)
                        && plan.eventName.equals(event.eventName)) {
                    return true;
                }
            } else if (event.eventType == EventBean.EVENT_TYPE_VIEW
                    && plan.targetId.equals(event.targetId)
                    && plan.eventName.equals(event.eventName)) {
                return true;
            }
        }
        return false;
    }

    /** The widget's numeric type, for registering a view event. */
    private static int targetTypeOf(Plan plan) {
        ArrayList<ViewBean> beans =
                SketchwareProjects.screenBeans(plan.scId, plan.activity);
        for (ViewBean bean : beans) {
            if (plan.targetId.equals(bean.id)) {
                return bean.type;
            }
        }
        return 0;
    }

    /**
     * A stored list reordered the way the editor writes it: main
     * chain first (head at index 0), then everything else —
     * container bodies and reporters — in its existing order.
     * Both Fx ({@code eventBlocks.get(0)}) and the editor loader
     * start from element 0.
     */
    private static ArrayList<BlockBean> editorOrder(
            ArrayList<BlockBean> beans) {
        ArrayList<BlockBean> ordered = new ArrayList<>();
        java.util.Set<Integer> inChain = new java.util.HashSet<>();
        for (int index : chainIndices(beans)) {
            inChain.add(index);
            ordered.add(beans.get(index));
        }
        for (int i = 0; i < beans.size(); i++) {
            if (!inChain.contains(i)) {
                ordered.add(beans.get(i));
            }
        }
        return ordered;
    }

    /**
     * The fresh beans in generation order: chain statements first
     * (each followed through its own nextBlock links, since a
     * statement can be two blocks), then every reporter. Fx
     * generates from list index 0, so the head must come first.
     */
    private static ArrayList<BlockBean> freshGenerationList(Plan plan) {
        ArrayList<BlockBean> ordered = new ArrayList<>();
        java.util.Set<String> added = new java.util.HashSet<>();
        HashMap<String, BlockBean> byId = new HashMap<>();
        for (BlockBean bean : plan.fresh) {
            byId.put(bean.id, bean);
        }
        for (BlockBean head : plan.chain) {
            BlockBean cursor = head;
            while (cursor != null && added.add(cursor.id)) {
                ordered.add(cursor);
                cursor = cursor.nextBlock >= 0
                        ? byId.get(String.valueOf(cursor.nextBlock))
                        : null;
            }
        }
        for (BlockBean bean : plan.fresh) {
            if (added.add(bean.id)) {
                ordered.add(bean);
            }
        }
        return ordered;
    }

    /** Existing blocks plus the fresh chain joined to the tail. */
    private static ArrayList<BlockBean> joined(
            ArrayList<BlockBean> existing, ArrayList<BlockBean> fresh) {
        ArrayList<BlockBean> combined = new ArrayList<>(existing);
        if (!fresh.isEmpty() && !combined.isEmpty()) {
            Map<Integer, BlockBean> byId = new HashMap<>();
            for (BlockBean bean : combined) {
                byId.put(parseId(bean.id), bean);
            }
            // Walk the main chain from the head; its tail is where
            // new statements belong.
            BlockBean cursor = combined.get(0);
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            while (cursor != null && seen.add(parseId(cursor.id))) {
                int next = cursor.nextBlock;
                if (next < 0 || !byId.containsKey(next)) {
                    break;
                }
                cursor = byId.get(next);
            }
            if (cursor != null) {
                cursor.nextBlock = Integer.parseInt(fresh.get(0).id);
            }
        }
        combined.addAll(fresh);
        return combined;
    }

    /** Structural read-back check against what was meant to be stored. */
    private static String verify(ArrayList<BlockBean> readBack,
                                 ArrayList<BlockBean> combined,
                                 Plan plan) {
        if (readBack == null || readBack.size() != combined.size()) {
            return "the event's blocks did not read back";
        }
        Map<Integer, BlockBean> byId = new HashMap<>();
        for (BlockBean bean : readBack) {
            byId.put(parseId(bean.id), bean);
        }
        int expectedFirst = Integer.parseInt(plan.chain.get(0).id);
        // The chain from the head must pass through every fresh bean,
        // in proposal order, and end after the last one.
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        BlockBean cursor = readBack.get(0);
        List<Integer> chain = new ArrayList<>();
        while (cursor != null && seen.add(parseId(cursor.id))) {
            chain.add(parseId(cursor.id));
            cursor = cursor.nextBlock >= 0
                    ? byId.get(cursor.nextBlock) : null;
        }
        int at = chain.indexOf(expectedFirst);
        if (at < 0) {
            return "the new blocks are not on the event's chain";
        }
        for (int i = 0; i < plan.chain.size(); i++) {
            if (at + i >= chain.size()
                    || chain.get(at + i)
                            != Integer.parseInt(plan.chain.get(i).id)) {
                return "the new blocks are out of order";
            }
        }
        for (BlockBean extra : plan.extras) {
            BlockBean stored = byId.get(parseId(extra.id));
            if (stored == null
                    || !"getVar".equals(stored.opCode)
                    || !extra.spec.equals(stored.spec)) {
                return "a variable value (\"" + extra.spec
                        + "\") did not read back";
            }
        }
        return null;
    }

    /**
     * Runs Sketchware's own code generator over a candidate event, the
     * same preview the logic editor makes. Null when generation is not
     * possible (e.g. older blocks it cannot walk) — callers then fall
     * back to the structural checks.
     */
    private static String generate(Context context, String scId,
                                   ProjectFileBean activity,
                                   ArrayList<BlockBean> blocks) {
        if (blocks.isEmpty()) {
            return "";
        }
        try {
            jq config = new jq();
            config.sc_id = scId;
            return new Fx(activity.getActivityName(), config, blocks,
                    false).a();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The editor's canonical spec text for a block, or a synthesis. */
    private static String specFor(Context context, String opCode) {
        try {
            String spec = xB.b().a(context, opCode);
            if (spec != null && !spec.isEmpty()) {
                return spec;
            }
        } catch (Throwable ignored) {
            // Fall through to the Pro palette below.
        }
        // The Pro palette's own spec text (BlocksHandler), the same
        // one the editor stamps on a bean when a user drags the
        // block in — right by construction for Pro-only blocks.
        String pro = proSpec(opCode);
        if (pro != null && !pro.isEmpty()) {
            return pro;
        }
        // Slots from the app's table with the block's own words between
        // them; the generator only reads the slot tokens, and the
        // editor re-derives the display from the same tokens.
        ArrayList<String> slots = lq.a(opCode);
        String label = "";
        try {
            label = lq.d(opCode).replace('_', ' ');
        } catch (Exception ignored) {
            // The opCode itself is the label.
        }
        if (label.isEmpty()) {
            label = opCode;
        }
        if (slots == null || slots.isEmpty()) {
            return label;
        }
        StringBuilder spec = new StringBuilder();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) {
                spec.append(' ');
            }
            spec.append(slots.get(i));
            if (i == 0 && slots.size() > 1) {
                spec.append(' ').append(label);
            }
        }
        return spec.toString();
    }

    private static ArrayList<HashMap<String, Object>> proBlocksCache;

    /** A Pro palette block's own spec, from BlocksHandler, or null. */
    private static String proSpec(String opCode) {
        try {
            if (proBlocksCache == null) {
                proBlocksCache = new ArrayList<>();
                mod.hilal.saif.blocks.BlocksHandler
                        .builtInBlocks(proBlocksCache);
            }
            for (HashMap<String, Object> block : proBlocksCache) {
                if (block != null && opCode.equals(block.get("name"))) {
                    Object spec = block.get("spec");
                    return spec == null ? null : spec.toString();
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the lq synthesis in specFor.
        }
        return null;
    }

    private static int nextFreeId(ArrayList<BlockBean> existing) {
        int max = 0;
        for (BlockBean bean : existing) {
            max = Math.max(max, parseId(bean.id));
        }
        return max + 1;
    }

    private static int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (Exception e) {
            return -1;
        }
    }

    private static ViewBean findWidget(Map<String, ViewBean> byId,
                                       String target) {
        if (target == null) {
            return null;
        }
        ViewBean exact = byId.get(target.trim());
        if (exact != null) {
            return exact;
        }
        // Models copy the user's words ("linear 1") as readily as the
        // real id ("linear1"): compare space-free, case aside.
        String wanted = target.replaceAll("[\\s_]+", "");
        for (ViewBean bean : byId.values()) {
            if (bean.id.replaceAll("[\\s_]+", "")
                    .equalsIgnoreCase(wanted)) {
                return bean;
            }
        }
        return null;
    }

    private static ViewBean requireWidget(JsonObject op,
                                          Map<String, ViewBean> byId)
            throws AgentTools.ToolException {
        String target = stringOf(op, "target");
        ViewBean widget = findWidget(byId, target);
        if (widget == null) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "A block targets widget \"" + target
                            + "\", which is not on this screen. Use "
                            + "get_screen to see the widget ids.");
        }
        return widget;
    }

    /** Colour literals in the editor's stored form ("0xAARRGGBB"). */
    private static String colourParam(JsonObject op)
            throws AgentTools.ToolException {
        String colour = stringOf(op, "color");
        if (colour.isEmpty()) colour = stringOf(op, "colour");
        if (colour.isEmpty()
                || !colour.matches("(?i)#([0-9a-f]{6}|[0-9a-f]{8})")) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "A colour block needs \"color\" as #RRGGBB or "
                            + "#AARRGGBB (opaque when the alpha part is "
                            + "left out).");
        }
        String hex = colour.substring(1).toUpperCase(Locale.ROOT);
        if (hex.length() == 6) hex = "FF" + hex;
        return "0x" + hex;
    }

    /** A JSON fragment shortened for an error message. */
    private static String abbrev(String json) {
        if (json == null) {
            return "";
        }
        return json.length() <= 300 ? json : json.substring(0, 300) + "…";
    }

    private static String stringOf(JsonObject object, String key) {
        if (object == null || !object.has(key)
                || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean boolOf(JsonObject object, String key,
                                  boolean fallback) {
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    // -- block editing -----------------------------------------------------

    /** Editable value kinds the replace path understands. */
    public static final String EDIT_TEXT = "text";
    public static final String EDIT_MESSAGE = "message";
    public static final String EDIT_VALUE = "value";
    public static final String EDIT_NUMBER = "number";

    /** One editable value on one stored block. */
    public static final class EditableValue {
        public int beanIndex;
        public String kind;
        public String currentValue;
        String newValue;
        int paramIdx;
    }

    /** A validated edit (replace or remove) against one event. */
    public static final class EditPlan {
        public String scId;
        public ProjectFileBean activity;
        public String targetId;
        public String eventName;
        public boolean activityEvent;
        public String header;
        public boolean remove;
        public String blockOpCode;
        public int[] targetPath;
        public int targetBeanIndex;
        public final ArrayList<EditableValue> values = new ArrayList<>();
        public int existingCount;
        public String blockDescription;
        public String eventRendering;
    }

    /**
     * The check that catches "it builds, then dies the moment it
     * opens": a block that names a widget which is not on its own
     * screen. Sketchware compiles that happily — the generated code
     * calls a view the layout never created, and the app crashes on
     * launch with a null view. Every event of every screen is read
     * the way the editor stores it and each widget reference is
     * matched against the widgets that really exist there.
     *
     * <p>Pure: reads, changes nothing. Returns plain, one-per-line
     * findings; empty when every reference resolves.
     */
    public static List<String> danglingWidgetReferences(String scId) {
        ArrayList<String> findings = new ArrayList<>();
        if (scId == null || scId.isEmpty()) {
            return findings;
        }
        try {
            for (ProjectFileBean activity
                    : SketchwareProjects.activities(scId)) {
                if (activity.fileType
                        != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
                    continue;
                }
                String javaName = activity.getJavaName();
                eC store = jC.a(scId);
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (ViewBean bean
                        : SketchwareProjects.screenBeans(scId, activity)) {
                    if (bean != null && bean.id != null) {
                        ids.add(bean.id);
                    }
                }
                ArrayList<EventBean> events = store.g(javaName);
                if (events == null) {
                    continue;
                }
                for (EventBean event : events) {
                    ArrayList<BlockBean> blocks = store.a(javaName,
                            event.targetId + "_" + event.eventName);
                    if (blocks == null) {
                        continue;
                    }
                    for (BlockBean bean : blocks) {
                        String referenced = widgetReferenceOf(bean);
                        if (referenced == null || ids.contains(referenced)) {
                            continue;
                        }
                        String finding =
                                SketchwareProjects.activityName(activity)
                                        + ": a block in "
                                        + event.targetId + " "
                                        + event.eventName
                                        + " uses the widget \""
                                        + referenced
                                        + "\", which is not on that "
                                        + "screen — the app would crash "
                                        + "when it opens. Fix it: change "
                                        + "the block to a widget that is "
                                        + "there, or add the widget.";
                        if (!findings.contains(finding)) {
                            findings.add(finding);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // A check must never stop the work it is checking.
        }
        return findings;
    }

    /**
     * The widget id a block acts on, or null: the first parameter of
     * a block whose first slot holds a view (the app's own slot table
     * marks them {@code %v} and {@code %m.<kind>}). Parameters that
     * point at another value block ({@code @id}) are not references.
     */
    private static String widgetReferenceOf(BlockBean bean) {
        if (bean == null || bean.opCode == null
                || bean.parameters == null
                || bean.parameters.isEmpty()) {
            return null;
        }
        String first = bean.parameters.get(0);
        if (first == null || first.isEmpty() || first.startsWith("@")) {
            return null;
        }
        ArrayList<String> slots;
        try {
            slots = lq.a(bean.opCode);
        } catch (Throwable e) {
            return null;
        }
        if (slots == null || slots.isEmpty()) {
            return null;
        }
        String slot = slots.get(0);
        if (slot == null) {
            return null;
        }
        if (!("%v".equals(slot) || slot.startsWith("%m."))) {
            return null;
        }
        return first;
    }

    /**
     * Read-only view of one event: its numbered statement rendering,
     * with no edit proposed. Backs {@code get_event_logic}.
     */
    public static EditPlan describeEvent(String scId,
                                         ProjectFileBean activity,
                                         String target, String event)
            throws AgentTools.ToolException {
        if (activity.fileType != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
            throw new AgentTools.ToolException("E_NOT_ACTIVITY",
                    "Custom views have no logic to read. Pick an "
                            + "activity screen instead.");
        }
        String javaName = activity.getJavaName();
        eC store = jC.a(scId);
        Plan probe = probeEvent(scId, activity, target, event);
        EditPlan plan = new EditPlan();
        plan.scId = scId;
        plan.activity = activity;
        plan.targetId = probe.targetId;
        plan.eventName = probe.eventName;
        plan.activityEvent = probe.activityEvent;
        plan.header = probe.header;
        ArrayList<BlockBean> blocks = store.a(javaName,
                plan.targetId + "_" + plan.eventName);
        if (blocks == null) blocks = new ArrayList<>();
        plan.existingCount = blocks.size();
        plan.eventRendering = renderEvent(blocks, chainIndices(blocks));
        return plan;
    }

    /**
     * Validates a replace or remove against one event's stored blocks.
     * Pure: reads, changes nothing. The target block is identified by
     * its statement path — the 1-based positions the proposal
     * rendering showed ([2], or [1,2] inside a container); a block
     * count drift or a failed generation proof refuses the edit.
     */
    public static EditPlan planEdit(Context context, String scId,
                                    ProjectFileBean activity,
                                    String target, String event,
                                    JsonObject edit)
            throws AgentTools.ToolException {
        if (activity.fileType != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
            throw new AgentTools.ToolException("E_NOT_ACTIVITY",
                    "Custom views have no logic to edit. Pick an activity "
                            + "screen instead.");
        }
        String javaName = activity.getJavaName();
        eC store = jC.a(scId);
        Plan probe = probeEvent(scId, activity, target, event);
        EditPlan plan = new EditPlan();
        plan.scId = scId;
        plan.activity = activity;
        plan.targetId = probe.targetId;
        plan.eventName = probe.eventName;
        plan.activityEvent = probe.activityEvent;
        plan.header = probe.header;

        plan.remove = false;
        try {
            plan.remove = edit != null && edit.has("remove")
                    && !edit.get("remove").isJsonNull()
                    && edit.get("remove").getAsBoolean();
        } catch (Exception ignored) {
            // A malformed remove flag reads as "not removing".
        }
        plan.targetPath = intArrayOf(edit, "block_path");
        if (plan.targetPath.length == 0) {
            throw new AgentTools.ToolException("E_BAD_EDIT",
                    "An edit needs \"block_path\": the 1-based position "
                            + "of a statement in the event, e.g. [2], or "
                            + "[1,2] for a statement nested in a "
                            + "container. Read the event with "
                            + "get_event_logic first.");
        }
        ArrayList<BlockBean> blocks = store.a(javaName,
                plan.targetId + "_" + plan.eventName);
        if (blocks == null) blocks = new ArrayList<>();
        plan.existingCount = blocks.size();
        ArrayList<Integer> chain = chainIndices(blocks);
        plan.eventRendering = renderEvent(blocks, chain);
        plan.targetBeanIndex = resolvePath(blocks, chain, plan.targetPath);
        if (plan.targetBeanIndex < 0) {
            throw new AgentTools.ToolException("E_NO_BLOCK",
                    "This event has no statement at position "
                            + pathText(plan.targetPath) + ". Its blocks "
                            + "are:\n" + plan.eventRendering + "\n"
                            + "Nothing was changed.");
        }
        BlockBean block = blocks.get(plan.targetBeanIndex);
        plan.blockOpCode = block.opCode;
        plan.blockDescription = describeBlock(block);

        if (plan.remove) {
            if (plan.targetBeanIndex == 0 && !blocks.isEmpty()) {
                // Fx generates from stored index 0; removing the head
                // bean would orphan every other statement.
                throw new AgentTools.ToolException("E_HEAD_BLOCK",
                        "This block is the event's first statement; "
                                + "removing it outright would break the "
                                + "stored chain Sketchware generates "
                                + "from. It can be changed in place "
                                + "instead. Nothing was changed.");
            }
            return plan;
        }

        JsonElement replacement = edit.get("replace_with");
        if (replacement == null || !replacement.isJsonObject()) {
            throw new AgentTools.ToolException("E_BAD_EDIT",
                    "To change a value, give \"replace_with\" with the "
                            + "new text, message, value or number, e.g. "
                            + "{\"text\":\"Saved\"}.");
        }
        JsonObject with = replacement.getAsJsonObject();
        ArrayList<EditableValue> editable = describeEditableValues(block);
        if (editable.isEmpty()) {
            throw new AgentTools.ToolException("E_NOT_EDITABLE",
                    "The block at " + pathText(plan.targetPath) + " ("
                            + plan.blockOpCode + ") has no value the "
                            + "agent can safely rewrite yet — only a "
                            + "statement's text, message, value or "
                            + "number can be changed in place. Remove "
                            + "it and add the replacement instead.");
        }
        for (EditableValue value : editable) {
            if (with.has(value.kind)
                    && !with.get(value.kind).isJsonNull()) {
                value.newValue = with.get(value.kind).getAsString();
                value.beanIndex = plan.targetBeanIndex;
                plan.values.add(value);
            }
        }
        if (plan.values.isEmpty()) {
            StringBuilder kinds = new StringBuilder();
            for (EditableValue value : editable) {
                if (kinds.length() > 0) kinds.append(", ");
                kinds.append(value.kind);
            }
            throw new AgentTools.ToolException("E_BAD_EDIT",
                    "The block at " + pathText(plan.targetPath)
                            + " can change its " + kinds
                            + ". Give \"replace_with\" with one of "
                            + "those keys and the new value.");
        }
        // Prove the after-state still generates: clone, rewrite, run
        // the app's own generator, require the fresh code to differ.
        ArrayList<BlockBean> clones = new ArrayList<>();
        for (BlockBean bean : blocks) clones.add(bean.clone());
        for (EditableValue value : plan.values) {
            clones.get(value.beanIndex).parameters.set(value.paramIdx,
                    value.newValue);
        }
        String before = generate(context, scId, activity, blocks);
        String after = generate(context, scId, activity, clones);
        if (before != null && after != null && before.equals(after)) {
            throw new AgentTools.ToolException("E_GENERATE",
                    "Sketchware's code generator produced the same "
                            + "code after the change — the new value "
                            + "would have no effect. Nothing was "
                            + "changed.");
        }
        return plan;
    }

    /**
     * Applies an approved edit: backs up, rewrites or removes the
     * statement in a fresh list, saves through the data manager,
     * commits, and verifies by reading the event back. Any failure
     * restores the event's blocks as they were.
     */
    public static JsonObject applyEdit(Context context, EditPlan plan)
            throws AgentTools.ToolException {
        String javaName = plan.activity.getJavaName();
        eC store = jC.a(plan.scId);
        String key = plan.targetId + "_" + plan.eventName;
        ArrayList<BlockBean> current = store.a(javaName, key);
        if (current == null) current = new ArrayList<>();
        if (current.size() != plan.existingCount
                || resolvePath(current, chainIndices(current),
                        plan.targetPath) != plan.targetBeanIndex) {
            throw new AgentTools.ToolException("E_LOGIC_CHANGED",
                    "This event's blocks changed since the edit was "
                            + "proposed, so it was not applied. Look at "
                            + "the event again and propose a fresh edit.");
        }
        ArrayList<BlockBean> previous = new ArrayList<>();
        for (BlockBean bean : current) previous.add(bean.clone());
        BlockBean target = current.get(plan.targetBeanIndex);
        String targetId = target.id;
        try {
            SketchwareActions.backupProjectOnce(plan.scId);
            ArrayList<BlockBean> combined = new ArrayList<>();
            for (BlockBean bean : current) combined.add(bean.clone());
            if (plan.remove) {
                BlockBean removed = combined.get(plan.targetBeanIndex);
                int removedNumId = parseId(removed.id);
                int removedNext = removed.nextBlock;
                for (BlockBean bean : combined) {
                    if (bean.nextBlock == removedNumId) {
                        bean.nextBlock = removedNext;
                    }
                    if (bean.subStack1 == removedNumId) {
                        bean.subStack1 = removedNext;
                    }
                    if (bean.subStack2 == removedNumId) {
                        bean.subStack2 = removedNext;
                    }
                }
                final String removedId = removed.id;
                combined.removeIf(bean -> removedId.equals(bean.id));
                dropOrphanReporters(combined);
            } else {
                for (EditableValue value : plan.values) {
                    combined.get(value.beanIndex).parameters.set(
                            value.paramIdx, value.newValue);
                }
            }
            ArrayList<String> editProblems =
                    editorLoadProblems(combined);
            if (!editProblems.isEmpty()) {
                throw new IllegalStateException(
                        "The result would not open in the editor ("
                                + editProblems.get(0) + ")");
            }
            store.a(javaName, key, combined);
            SketchwareActions.commitProject(plan.scId);

            ArrayList<BlockBean> readBack = store.a(javaName, key);
            if (readBack == null) readBack = new ArrayList<>();
            String problem = null;
            if (plan.remove) {
                for (BlockBean bean : readBack) {
                    if (targetId.equals(bean.id)) {
                        problem = "the removed block is still there";
                        break;
                    }
                }
            } else {
                BlockBean stored = findById(readBack, targetId);
                if (stored == null) {
                    problem = "the edited block did not read back";
                } else {
                    for (EditableValue value : plan.values) {
                        if (value.paramIdx >= stored.parameters.size()
                                || !value.newValue.equals(
                                        stored.parameters.get(
                                                value.paramIdx))) {
                            problem = "the new value did not read back";
                            break;
                        }
                    }
                }
            }
            if (problem != null) {
                throw new IllegalStateException(problem);
            }
            JsonObject result = new JsonObject();
            result.addProperty("applied", true);
            result.addProperty("removed", plan.remove);
            result.addProperty("verified",
                    (plan.remove ? "Removed " : "Changed ")
                            + plan.blockDescription + " — "
                            + plan.header + " (read back and checked).");
            return result;
        } catch (Exception e) {
            try {
                store.a(javaName, key, previous);
                SketchwareActions.commitProject(plan.scId);
            } catch (Exception ignored) {
                // The restore is best-effort.
            }
            if (e instanceof AgentTools.ToolException toolException) {
                throw toolException;
            }
            throw new AgentTools.ToolException("E_APPLY_FAILED",
                    "The edit could not be applied ("
                            + String.valueOf(e.getMessage())
                            + "); the event's blocks were restored as "
                            + "they were.");
        }
    }

    /**
     * Drops beans nothing references any more: reporter beans a
     * removed statement owned (including any holding an unresolved
     * "@var:" placeholder, which crashes the logic editor) stay
     * behind as orphans otherwise. Reachability walks the main
     * chain, container stacks, and "@id" parameter references.
     */
    private static void dropOrphanReporters(ArrayList<BlockBean> beans) {
        if (beans.isEmpty()) {
            return;
        }
        HashMap<Integer, Integer> indexById = new HashMap<>();
        for (int i = 0; i < beans.size(); i++) {
            indexById.put(parseId(beans.get(i).id), i);
        }
        java.util.Set<Integer> keep = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        for (int index : chainIndices(beans)) {
            stack.push(index);
        }
        while (!stack.isEmpty()) {
            int index = stack.pop();
            if (index < 0 || index >= beans.size() || !keep.add(index)) {
                continue;
            }
            BlockBean bean = beans.get(index);
            for (int link : new int[]{bean.nextBlock, bean.subStack1,
                    bean.subStack2}) {
                Integer next = indexById.get(link);
                if (next != null) {
                    stack.push(next);
                }
            }
            for (String param : bean.parameters) {
                if (param != null && param.startsWith("@")) {
                    try {
                        Integer ref = indexById.get(
                                Integer.parseInt(param.substring(1)));
                        if (ref != null) {
                            stack.push(ref);
                        }
                    } catch (Exception ignored) {
                        // An unparseable reference keeps nothing.
                    }
                }
            }
        }
        for (int i = beans.size() - 1; i >= 0; i--) {
            if (!keep.contains(i)) {
                beans.remove(i);
            }
        }
    }

    /**
     * What would break the logic editor's own loader
     * ({@code LogicEditorActivity.loadEventBlocks}) in a stored
     * block list: it runs {@code Integer.valueOf} on every bean id
     * and every "@..." parameter, indexes one value holder per
     * spec slot, and crashes on any mismatch. Returns one problem
     * per offending bean ("block <id> (<opCode>): ..."), so a save
     * can be refused — or an older problem named — before writing.
     */
    static ArrayList<String> editorLoadProblems(
            List<BlockBean> beans) {
        ArrayList<String> problems = new ArrayList<>();
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (BlockBean bean : beans) {
            if (bean != null && bean.id != null) {
                ids.add(parseId(bean.id));
            }
        }
        java.util.regex.Pattern slots = java.util.regex.Pattern
                .compile("%\\w+(?:\\.\\w+)?|%\\w");
        for (BlockBean bean : beans) {
            if (bean == null) {
                continue;
            }
            String who = "block " + bean.id + " (" + bean.opCode + ")";
            if (parseId(bean.id) < 0) {
                problems.add(who + ": its id is not a number");
                continue;
            }
            java.util.regex.Matcher matcher =
                    slots.matcher(bean.spec == null ? "" : bean.spec);
            int slotCount = 0;
            while (matcher.find()) {
                slotCount++;
            }
            // getVar stores the variable name as its spec and holds
            // no parameters; its shape is the editor's own.
            if (!"getVar".equals(bean.opCode)
                    && bean.parameters.size() != slotCount) {
                problems.add(who + ": it holds "
                        + bean.parameters.size() + " values but its "
                        + "shape takes " + slotCount);
                continue;
            }
            for (int i = 0; i < bean.parameters.size(); i++) {
                String param = bean.parameters.get(i);
                if (param != null && param.startsWith("@")) {
                    int ref;
                    try {
                        ref = Integer.parseInt(param.substring(1));
                    } catch (Exception e) {
                        problems.add(who + ": value " + (i + 1)
                                + " is \"" + param + "\", which the "
                                + "editor cannot read as a block "
                                + "reference");
                        break;
                    }
                    if (!ids.contains(ref)) {
                        problems.add(who + ": value " + (i + 1)
                                + " points at block " + ref + ", which "
                                + "is not in this event");
                        break;
                    }
                }
            }
        }
        return problems;
    }

    /** Event resolution shared by wiring and edits (target + event). */
    private static Plan probeEvent(String scId, ProjectFileBean activity,
                                   String target, String event)
            throws AgentTools.ToolException {
        Plan plan = new Plan();
        plan.scId = scId;
        plan.activity = activity;
        plan.eventName = event == null ? "" : event.trim();
        if ("activity".equalsIgnoreCase(target)) {
            plan.activityEvent = true;
            plan.targetId = plan.eventName;
            if (!Arrays.asList(oq.getAllActivityEvents())
                    .contains(plan.eventName)) {
                throw new AgentTools.ToolException("E_BAD_EVENT",
                        "\"" + plan.eventName + "\" is not an "
                                + "activity event. Activity events are: "
                                + String.join(", ",
                                        oq.getAllActivityEvents()) + ".");
            }
            plan.header = "When " + SketchwareProjects.activityName(activity)
                    + " runs " + plan.eventName + ":";
            return plan;
        }
        ArrayList<ViewBean> beans =
                SketchwareProjects.screenBeans(scId, activity);
        Map<String, ViewBean> byId = new LinkedHashMap<>();
        for (ViewBean bean : beans) byId.put(bean.id, bean);
        ViewBean widget = findWidget(byId, target);
        if (widget == null) {
            String wanted = target == null ? "" : target.trim();
            if (Arrays.asList(oq.getAllActivityEvents())
                    .contains(wanted)) {
                throw new AgentTools.ToolException("E_BAD_TARGET",
                        "\"" + wanted + "\" is an activity event, not "
                                + "a widget. Call again with target "
                                + "\"activity\" and event \"" + wanted
                                + "\".");
            }
            Integer componentType = componentsOf(jC.a(scId),
                    activity.getJavaName()).get(wanted);
            if (componentType == null) {
                throw new AgentTools.ToolException("E_NO_WIDGET",
                        "There is no widget or component with id \""
                                + target + "\" on this screen. Use "
                                + "get_screen to see the widget ids.");
            }
            plan.componentEvent = true;
            plan.componentType = componentType;
            plan.targetId = wanted;
            plan.header = "When " + wanted + " "
                    + plan.eventName + ":";
            return plan;
        }
        plan.targetId = widget.id;
        String text = widget.text == null ? "" : widget.text.text;
        String label = text != null && !text.isEmpty()
                ? " (" + text + ")" : "";
        plan.header = "When " + widget.id + label + " "
                + plan.eventName + ":";
        return plan;
    }

    /** Main-chain bean indices (head first), following nextBlock. */
    public static ArrayList<Integer> chainIndices(
            ArrayList<BlockBean> blocks) {
        ArrayList<Integer> chain = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) return chain;
        Map<Integer, Integer> indexByNumericId = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            indexByNumericId.put(parseId(blocks.get(i).id), i);
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        Integer cursor = 0;
        while (cursor != null && cursor >= 0 && cursor < blocks.size()
                && seen.add(cursor)) {
            chain.add(cursor);
            Integer next = indexByNumericId.get(
                    blocks.get(cursor).nextBlock);
            cursor = next;
        }
        return chain;
    }

    /** index-lookup table for a stored block list. */
    private static Map<Integer, Integer> indexByNumericId(
            ArrayList<BlockBean> blocks) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            map.put(parseId(blocks.get(i).id), i);
        }
        return map;
    }

    /**
     * Walks a statement path to a stored bean index: the first number
     * picks a main-chain statement (1-based), further numbers descend
     * into substacks. -1 when the path doesn't land on a statement.
     */
    static int resolvePath(ArrayList<BlockBean> blocks,
                           ArrayList<Integer> chain, int[] path) {
        if (path.length == 0 || chain.isEmpty()) return -1;
        if (path[0] < 1 || path[0] > chain.size()) return -1;
        int beanIndex = chain.get(path[0] - 1);
        Map<Integer, Integer> byIdNum = indexByNumericId(blocks);
        for (int depth = 1; depth < path.length; depth++) {
            BlockBean parent = blocks.get(beanIndex);
            int headId = depth == 1 ? parent.subStack1
                    : parent.subStack2;
            Integer cursor = headId >= 0 ? byIdNum.get(headId) : null;
            if (cursor == null) return -1;
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            int sibling = 1;
            while (sibling < path[depth]) {
                if (!seen.add(cursor)) return -1;
                Integer next = byIdNum.get(blocks.get(cursor).nextBlock);
                if (next == null) return -1;
                cursor = next;
                sibling++;
            }
            beanIndex = cursor;
        }
        return beanIndex;
    }

    /** Plain-words rendering of an event's statement tree. */
    public static String renderEvent(ArrayList<BlockBean> blocks,
                                     ArrayList<Integer> chain) {
        StringBuilder out = new StringBuilder();
        renderChain(blocks, indexByNumericId(blocks), chain, "", out);
        return out.toString().trim();
    }

    private static void renderChain(ArrayList<BlockBean> blocks,
                                    Map<Integer, Integer> byIdNum,
                                    ArrayList<Integer> chain,
                                    String indent, StringBuilder out) {
        for (int i = 0; i < chain.size(); i++) {
            BlockBean bean = blocks.get(chain.get(i));
            out.append(indent).append(i + 1).append(". ")
                    .append(describeBlock(bean)).append("\n");
            if (bean.subStack1 >= 0) {
                renderChain(blocks, byIdNum,
                        subChain(blocks, byIdNum, bean.subStack1),
                        indent + "  ", out);
            }
            if (bean.subStack2 >= 0) {
                out.append(indent).append("  else:\n");
                renderChain(blocks, byIdNum,
                        subChain(blocks, byIdNum, bean.subStack2),
                        indent + "  ", out);
            }
        }
    }

    private static ArrayList<Integer> subChain(
            ArrayList<BlockBean> blocks,
            Map<Integer, Integer> byIdNum, int headId) {
        ArrayList<Integer> sub = new ArrayList<>();
        Integer cursor = byIdNum.get(headId);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        while (cursor != null && seen.add(cursor)) {
            sub.add(cursor);
            cursor = byIdNum.get(blocks.get(cursor).nextBlock);
        }
        return sub;
    }

    /** One block in plain words, from its stored spec and values. */
    public static String describeBlock(BlockBean bean) {
        String spec = bean.spec == null ? "" : bean.spec;
        ArrayList<String> values = bean.parameters == null
                ? new ArrayList<>() : bean.parameters;
        StringBuilder words = new StringBuilder();
        java.util.regex.Matcher matcher = SLOT_PATTERN.matcher(spec);
        int slotOrdinal = 0;
        int lastEnd = 0;
        while (matcher.find()) {
            words.append(spec, lastEnd, matcher.start());
            String slot = matcher.group();
            String value = slotOrdinal < values.size()
                    ? values.get(slotOrdinal) : "";
            words.append(describeSlotValue(slot, value));
            slotOrdinal++;
            lastEnd = matcher.end();
        }
        words.append(spec.substring(lastEnd));
        String text = words.toString().replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? String.valueOf(bean.opCode) : text;
    }

    private static String describeSlotValue(String slot, String value) {
        if (value == null) value = "";
        if (value.startsWith("@")) {
            // "@42" points at a value block whose spec is its meaning.
            return "(a value)";
        }
        if ("%s".equals(slot) || slot.startsWith("%s")) {
            return "\"" + value + "\"";
        }
        return value;
    }

    /**
     * The editable values of one stored block: the value slots the
     * agent can rewrite without touching structure, matched by the
     * opCodes whose semantics the wiring engine already proves.
     */
    static ArrayList<EditableValue> describeEditableValues(
            BlockBean bean) {
        ArrayList<EditableValue> out = new ArrayList<>();
        List<String> slots = slotsOf(bean.spec);
        int count = Math.min(slots.size(),
                bean.parameters == null ? 0 : bean.parameters.size());
        for (int i = 0; i < count; i++) {
            String slot = slots.get(i);
            String kind = switch (String.valueOf(bean.opCode)) {
                case "doToast" -> i == 0 && "%s".equals(slot)
                        ? EDIT_MESSAGE : null;
                case "setText" -> i == 1 && "%s".equals(slot)
                        ? EDIT_TEXT : null;
                case "setVarString" -> i == 1 && "%s".equals(slot)
                        ? EDIT_TEXT : null;
                case "setVarInt" -> i == 1 && "%d".equals(slot)
                        ? EDIT_NUMBER : null;
                case "setVarBoolean" -> i == 1 && "%b".equals(slot)
                        ? EDIT_VALUE : null;
                default -> null;
            };
            if (kind != null) {
                EditableValue value = new EditableValue();
                value.kind = kind;
                value.paramIdx = i;
                value.currentValue = bean.parameters.get(i);
                out.add(value);
            }
        }
        return out;
    }

    /** Slot tokens of a spec, in order. */
    private static List<String> slotsOf(String spec) {
        ArrayList<String> slots = new ArrayList<>();
        if (spec == null) return slots;
        java.util.regex.Matcher matcher = SLOT_PATTERN.matcher(spec);
        while (matcher.find()) slots.add(matcher.group());
        return slots;
    }

    private static String pathText(int[] path) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < path.length; i++) {
            if (i > 0) text.append(".");
            text.append(path[i]);
        }
        return text.toString();
    }

    // -- project theme -----------------------------------------------------

    /** The project-setting screen's own theme keys, in label order. */
    public static final String[] THEME_KEYS = {
            "color_accent", "color_primary", "color_primary_dark",
            "color_control_highlight", "color_control_normal"};

    /** Plain-words names for the theme slots. */
    public static String themeLabel(String key) {
        return switch (String.valueOf(key)) {
            case "color_primary" -> "Primary colour";
            case "color_primary_dark" -> "Dark primary colour";
            case "color_accent" -> "Accent colour";
            case "color_control_highlight" -> "Control highlight colour";
            case "color_control_normal" -> "Control normal colour";
            default -> String.valueOf(key);
        };
    }

    /** One proposed theme change. */
    public static final class ThemePlan {
        public String scId;
        public final LinkedHashMap<String, Integer> before =
                new LinkedHashMap<>();
        public final LinkedHashMap<String, Integer> after =
                new LinkedHashMap<>();
        public String sentence;
    }

    /**
     * Reads the project's theme colours from the same metadata map
     * the project-settings screen writes ({@code lC.b(scId)}), which
     * the builder turns into colors.xml. {@code colourOrNull} is
     * {@code null} when a key has never been stored.
     */
    public static Integer themeColour(String scId, String key) {
        try {
            HashMap<String, Object> metadata = lC.b(scId);
            if (metadata == null) return null;
            Object value = metadata.get(key);
            return value instanceof Number ? ((Number) value).intValue()
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates a theme change: known colour keys with hex values,
     * before → after read from the live registry. Pure.
     */
    public static ThemePlan planTheme(String scId, JsonObject colours)
            throws AgentTools.ToolException {
        ThemePlan plan = new ThemePlan();
        plan.scId = scId;
        StringBuilder sentence = new StringBuilder();
        if (colours == null || colours.entrySet().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_THEME",
                    "Give at least one theme colour to change — "
                            + "color_primary, color_primary_dark, "
                            + "color_accent, color_control_highlight or "
                            + "color_control_normal, each #RRGGBB or "
                            + "#AARRGGBB.");
        }
        for (java.util.Map.Entry<String, JsonElement> entry
                : colours.entrySet()) {
            String key = entry.getKey();
            boolean known = false;
            for (String allowed : THEME_KEYS) {
                if (allowed.equals(key)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new AgentTools.ToolException("E_BAD_THEME",
                        "\"" + key + "\" is not one of the project's "
                                + "theme colours (color_primary, "
                                + "color_primary_dark, color_accent, "
                                + "color_control_highlight, "
                                + "color_control_normal).");
            }
            if (entry.getValue() == null
                    || entry.getValue().isJsonNull()) {
                throw new AgentTools.ToolException("E_BAD_THEME",
                        "Give a hex colour for " + themeLabel(key) + ".");
            }
            String hex = entry.getValue().getAsString().trim();
            int colour = parseThemeHex(key, hex);
            Integer stored = themeColour(scId, key);
            plan.before.put(key, stored == null ? 0 : stored);
            plan.after.put(key, colour);
            if (sentence.length() > 0) sentence.append("\n");
            sentence.append(themeLabel(key)).append(": ")
                    .append(stored == null
                            ? "(the default colour)"
                            : String.format(Locale.US, "#%06X",
                                    stored & 0xFFFFFF))
                    .append(" → ")
                    .append(String.format(Locale.US, "#%06X",
                            colour & 0xFFFFFF));
        }
        plan.sentence = sentence.toString();
        return plan;
    }

    /** Applies a theme change through the app's own metadata write. */
    public static JsonObject applyTheme(ThemePlan plan)
            throws AgentTools.ToolException {
        HashMap<String, Object> current = lC.b(plan.scId);
        HashMap<String, Object> updated = new HashMap<>();
        if (current != null) updated.putAll(current);
        // Refuse to write over a live registry whose theme values
        // drifted from what the card showed.
        for (java.util.Map.Entry<String, Integer> entry
                : plan.before.entrySet()) {
            Integer live = themeColour(plan.scId, entry.getKey());
            if (live != null
                    && !live.equals(entry.getValue())) {
                throw new AgentTools.ToolException("E_THEME_CHANGED",
                        "The project's theme colours changed since "
                                + "this was proposed; nothing was "
                                + "changed. Ask for a fresh proposal.");
            }
            updated.put(entry.getKey(), plan.after.get(entry.getKey()));
        }
        try {
            SketchwareActions.backupProjectOnce(plan.scId);
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_BACKUP_FAILED",
                    "The safety backup could not be written, so the "
                            + "theme was not changed.");
        }
        lC.b(plan.scId, updated);
        // yq generates colors.xml from this registry at build time
        // (there is no on-disk copy to sync), so read-back here is
        // the whole verification.
        for (java.util.Map.Entry<String, Integer> entry
                : plan.after.entrySet()) {
            Integer live = themeColour(plan.scId, entry.getKey());
            if (!entry.getValue().equals(live)) {
                // Roll back to the pre-change registry copy.
                if (current != null) {
                    lC.b(plan.scId, new HashMap<>(current));
                }
                throw new AgentTools.ToolException("E_VERIFY_FAILED",
                        "The theme change did not survive Sketchware's "
                                + "own save — the previous colours were "
                                + "restored.");
            }
        }
        SketchwareActions.commitProject(plan.scId);
        JsonObject result = new JsonObject();
        result.addProperty("applied", true);
        JsonObject readBack = new JsonObject();
        for (java.util.Map.Entry<String, Integer> entry
                : plan.after.entrySet()) {
            readBack.addProperty(entry.getKey(),
                    String.format(Locale.US, "#%06X",
                            entry.getValue() & 0xFFFFFF));
        }
        result.add("theme", readBack);
        result.addProperty("verified",
                "Theme colours saved and read back: "
                        + plan.sentence.replace("\n", " "));
        return result;
    }

    /** Parses #RRGGBB / #AARRGGBB into a colour int. */
    private static int parseThemeHex(String key, String hex)
            throws AgentTools.ToolException {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            long value = Long.parseLong(digits, 16);
            if ((digits.length() != 6 && digits.length() != 8)
                    || value < 0 || value > 0xFFFFFFFFL) {
                throw new NumberFormatException(hex);
            }
            return (int) (digits.length() == 6
                    ? value | 0xFF000000L : value);
        } catch (NumberFormatException e) {
            throw new AgentTools.ToolException("E_BAD_THEME",
                    "\"" + hex + "\" is not a colour "
                            + (key == null ? "" : "for " + themeLabel(key))
                            + " — use #RRGGBB or #AARRGGBB.");
        }
    }

    private static BlockBean findById(ArrayList<BlockBean> blocks,
                                      String id) {
        for (BlockBean bean : blocks) {
            if (id.equals(bean.id)) return bean;
        }
        return null;
    }

    /** JSON int array from a named argument. */
    private static int[] intArrayOf(JsonObject object, String key) {
        try {
            com.google.gson.JsonArray array =
                    object.getAsJsonArray(key);
            int[] out = new int[array.size()];
            for (int i = 0; i < array.size(); i++) {
                out[i] = array.get(i).getAsInt();
            }
            return out;
        } catch (Exception e) {
            return new int[0];
        }
    }
}
