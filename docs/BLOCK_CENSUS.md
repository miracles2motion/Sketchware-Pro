# Sketchware Pro block census

Every block the app itself offers, extracted from the app's own sources on 2026-10-18 — palette builders (`ExtraPaletteBlock`, `BlocksHandler.primaryBlocksA–D`), the stock block table recovered from the app's runtime classes (`lq`), the Pro block definitions (`BlocksHandler.builtInBlocks`, `ExtraBlocks`), and the code generator (`Fx`). Nothing hand-copied from memory; the machine-readable twin of this table is `docs/block_census.json`.

## Totals

- Distinct palette blocks: **584**
- Statements (do something): 370 · Value blocks (reporters): 171 · Containers (hold other blocks): 27 · End/return: 13 · If-else shape: 2
- Blocks the stock table fully describes (slots + spec): **290**; blocks with spec/code in the app's Pro block data: **279**
- Event names the app can wire: **58** (activity + component events table)
- Blocks the agent can already build: **10 of 584** — decreaseInt, doToast, finishActivity, increaseInt, setEnable, setText, setVarBoolean, setVarInt, setVarString, setVisible

Per-category counts:

| Category | Blocks | Statements | Values | Containers |
|---|---:|---:|---:|---:|
| Variable | 26 | 18 | 8 | 0 |
| List | 33 | 23 | 10 | 0 |
| Control | 28 | 2 | 4 | 11 |
| Operator | 49 | 6 | 43 | 0 |
| Math | 24 | 0 | 24 | 0 |
| File | 33 | 20 | 13 | 0 |
| View | 228 | 182 | 39 | 7 |
| Component | 140 | 108 | 24 | 6 |
| Events | 11 | 4 | 4 | 1 |
| Custom views | 3 | 1 | 1 | 1 |
| More | 7 | 6 | 0 | 1 |
| Strings | 2 | 0 | 1 | 0 |

## What this census changes

The agent must not grow block-by-block by hand. The tables above prove nearly every
block's exact stored shape is machine-readable from the app itself (stock slot
table, Pro spec/code data, the generator's own emission cases). The translator can
therefore become **data-driven**: a vetted allowlist of opCodes whose parameters
are validated token-by-token against the app's own slots, with approval sentences
derived from the app's spec text — instead of one hand-written case per block.

Engine capabilities gate the waves, in dependency order:

1. **Editing existing blocks** (separate axis; fixes "change means append"):
   replace a block's parameter value, remove a block — same chain machinery,
   Fx dry-run, read-back verify; cards show before → after. Small, highest
   user pain today.
2. **More statements** (data-driven): statement blocks whose parameters are
   literals/pickers only — most of View (182 statements) and the un-gated
   Component statements (copyToClipboard, setTitle…). Also needs
   colour/number/text literal typing per slot.
3. **Values everywhere**: general reporter nesting — any `%d`/`%s`/`%b` slot may
   hold a value block (`@id`), recursively (operators, math, string ops, widget
   getters). Without this, `if` conditions can only be literals.
4. **Control flow**: containers via `subStack1`/`subStack2` (`if`, `ifElse`,
   `repeat`, `forever`, `break`) — the chain becomes a tree; cards show
   indented bodies.
5. **Components**: declare components (`Intent` first — it opens other screens —
   then Vibrator, Timer containers, Dialog, SharedPreferences, MediaPlayer…)
   through the same manager call the palette's Add Component uses; their gated
   families (most of the 140 Component blocks) unlock behind them.
6. **Data families**: map + list variables and their ~90 Variable/List blocks.

Deliberately out, with reasons: raw-source blocks (`addSourceDirectly`, `asd*`,
CommandBlockJava/XML — they break the no-raw-code rule); Firebase/ads/camera/
network families until project config exists to bind them; Pro custom blocks
(defined per-user inside projects); the 14 stock blocks that never reached
the palette.

Theme colours are not blocks but were requested beside this: the project's
theme colours live in its metadata map (`lC.b(scId)`:
`color_primary`, `color_primary_dark`, `color_accent`, `color_control_highlight`,
`color_control_normal`) and are editable in the app's own project settings
(`MyProjectSettingActivity.themeColorKeys`) — a small `edit_project_theme`
slice with named-swatch approval cards, riding with wave 1.

Legend: **Params** are the app's own slot tokens — `%s` text, `%d` number, `%b` true/false, `%m.x` a picker (widget, variable, component…). **Shape** = how the app stores the block (slot-proven means the app's stock table defines its parameters and a value block of the named kind results). **Agent** marks blocks already proven on device.


## Variable (26)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `decreaseInt` | statement | %m.varInt | stock table | ✔ have |
| `hashmapGetBoolean` | boolean value | app-defined spec | Pro block data |  |
| `hashmapGetListmap` | statement | app-defined spec | Pro block data |  |
| `hashmapGetMap` | map value | app-defined spec | Pro block data |  |
| `hashmapGetNumber` | number value | app-defined spec | Pro block data |  |
| `hashmapListstr` | statement | app-defined spec | Pro block data |  |
| `hashmapPutBoolean` | statement | app-defined spec | Pro block data |  |
| `hashmapPutListmap` | statement | app-defined spec | Pro block data |  |
| `hashmapPutListstr` | statement | app-defined spec | Pro block data |  |
| `hashmapPutMap` | statement | app-defined spec | Pro block data |  |
| `hashmapPutNumber` | statement | app-defined spec | Pro block data |  |
| `hashmapPutNumber2` | statement | app-defined spec | Pro block data |  |
| `increaseInt` | statement | %m.varInt | stock table | ✔ have |
| `mapClear` | statement | %m.varMap | stock table |  |
| `mapContainKey` | boolean value | %m.varMap %s | stock table |  |
| `mapContainValue` | boolean value | app-defined spec | Pro block data |  |
| `mapCreateNew` | statement | %m.varMap | stock table |  |
| `mapGet` | text value | %m.varMap %s | stock table |  |
| `mapGetAllKeys` | statement | %m.varMap %m.listStr | stock table |  |
| `mapIsEmpty` | boolean value | %m.varMap | stock table |  |
| `mapPut` | statement | %m.varMap %s %s | stock table |  |
| `mapRemoveKey` | statement | %m.varMap %s | stock table |  |
| `mapSize` | number value | %m.varMap | stock table |  |
| `setVarBoolean` | statement | %m.varBool %b | stock table | ✔ have |
| `setVarInt` | statement | %m.varInt %d | stock table | ✔ have |
| `setVarString` | statement | %m.varStr %s | stock table | ✔ have |

## List (33)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `addListInt` | statement | %d %m.listInt | stock table |  |
| `addListMap` | statement | %s %s %m.listMap | stock table |  |
| `addListStr` | statement | %s %m.listStr | stock table |  |
| `addMapToList` | statement | %m.varMap %m.listMap | stock table |  |
| `clearList` | statement | %m.list | stock table |  |
| `containListInt` | boolean value | %m.listInt %d | stock table |  |
| `containListMap` | boolean value | %m.listMap %d %s | stock table |  |
| `containListStr` | boolean value | %m.listStr %s | stock table |  |
| `deleteList` | statement | %d %m.list | stock table |  |
| `deleteMapFromListmap` | statement | app-defined spec | Pro block data |  |
| `getAtListInt` | number value | %d %m.listInt | stock table |  |
| `getAtListMap` | text value | %d %s %m.listMap | stock table |  |
| `getAtListStr` | text value | %d %m.listStr | stock table |  |
| `getMapAtPosListmap` | map value | app-defined spec | Pro block data |  |
| `getMapInList` | statement | %d %m.listMap %m.varMap | stock table |  |
| `indexListInt` | number value | %d %m.listInt | stock table |  |
| `indexListStr` | number value | %s %m.listStr | stock table |  |
| `insertListInt` | statement | %d %d %m.listInt | stock table |  |
| `insertListMap` | statement | %s %s %d %m.listMap | stock table |  |
| `insertListStr` | statement | %s %d %m.listStr | stock table |  |
| `insertMapToList` | statement | %m.varMap %d %m.listMap | stock table |  |
| `lengthList` | number value | %m.list | stock table |  |
| `listAddAll` | statement | app-defined spec | Pro block data |  |
| `reverseList` | statement | app-defined spec | Pro block data |  |
| `setAtPosListnum` | statement | app-defined spec | Pro block data |  |
| `setAtPosListstr` | statement | app-defined spec | Pro block data |  |
| `setListMap` | statement | %s %s %d %m.listMap | stock table |  |
| `setMapAtPosListmap` | statement | app-defined spec | Pro block data |  |
| `shuffleList` | statement | app-defined spec | Pro block data |  |
| `sortList` | statement | app-defined spec | Pro block data |  |
| `sortListmap` | statement | app-defined spec | Pro block data |  |
| `sortListnum` | statement | app-defined spec | Pro block data |  |
| `swapInList` | statement | app-defined spec | Pro block data |  |

## Control (28)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `break` | end/return | — | generator only |  |
| `caseNum` | container | app-defined spec | Pro block data |  |
| `caseNumAnd` | statement | app-defined spec | Pro block data |  |
| `caseStr` | container | app-defined spec | Pro block data |  |
| `caseStrAnd` | statement | app-defined spec | Pro block data |  |
| `continue` | end/return | app-defined spec | Pro block data |  |
| `defaultSwitch` | container | app-defined spec | Pro block data |  |
| `forever` | container | — | generator only |  |
| `if` | container | %b | stock table |  |
| `ifElse` | if-else | %b | stock table |  |
| `instanceOfOperator` | boolean value | app-defined spec | Pro block data |  |
| `isEmpty` | boolean value | app-defined spec | Pro block data |  |
| `repeat` | container | %d | stock table |  |
| `repeatKnownNum` | container | app-defined spec | Pro block data |  |
| `RepeatKnownNumDescending` | container | app-defined spec | Pro block data |  |
| `returnBoolean` | end/return | app-defined spec | Pro block data |  |
| `returnListMap` | end/return | app-defined spec | Pro block data |  |
| `returnListStr` | end/return | app-defined spec | Pro block data |  |
| `returnMap` | end/return | app-defined spec | Pro block data |  |
| `returnNumber` | end/return | app-defined spec | Pro block data |  |
| `returnString` | end/return | app-defined spec | Pro block data |  |
| `returnView` | end/return | app-defined spec | Pro block data |  |
| `switchNum` | container | app-defined spec | Pro block data |  |
| `switchStr` | container | app-defined spec | Pro block data |  |
| `ternaryNumber` | number value | app-defined spec | Pro block data |  |
| `ternaryString` | text value | app-defined spec | Pro block data |  |
| `tryCatch` | if-else | app-defined spec | Pro block data |  |
| `whileLoop` | container | app-defined spec | Pro block data |  |

## Operator (49)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `%` | number value | %d %d | stock table |  |
| `&&` | boolean value | %b %b | stock table |  |
| `*` | number value | %d %d | stock table |  |
| `+` | number value | %d %d | stock table |  |
| `-` | number value | %d %d | stock table |  |
| `/` | number value | %d %d | stock table |  |
| `<` | boolean value | %d %d | stock table |  |
| `=` | boolean value | %d %d | stock table |  |
| `>` | boolean value | %d %d | stock table |  |
| `addSourceDirectly` | statement | %s.inputOnly | stock table |  |
| `asdBoolean` | boolean value | app-defined spec | Pro block data |  |
| `asdNumber` | number value | app-defined spec | Pro block data |  |
| `asdString` | text value | app-defined spec | Pro block data |  |
| `false` | boolean value | — | generator only |  |
| `GsonListTojsonString` | text value | app-defined spec | Pro block data |  |
| `GsonStringToListNumber` | statement | app-defined spec | Pro block data |  |
| `GsonStringToListString` | statement | app-defined spec | Pro block data |  |
| `html` | text value | app-defined spec | Pro block data |  |
| `listMapToStr` | text value | %m.listMap | stock table |  |
| `mapToStr` | text value | %m.varMap | stock table |  |
| `not` | boolean value | %b | stock table |  |
| `random` | number value | %d %d | stock table |  |
| `reverse` | text value | app-defined spec | Pro block data |  |
| `stringContains` | boolean value | %s %s | stock table |  |
| `stringEquals` | boolean value | %s %s | stock table |  |
| `stringIndex` | number value | %s %s | stock table |  |
| `stringJoin` | text value | %s %s | stock table |  |
| `stringLastIndex` | number value | %s %s | stock table |  |
| `stringLength` | number value | %s | stock table |  |
| `stringMatches` | boolean value | app-defined spec | Pro block data |  |
| `stringReplace` | text value | %s %s %s | stock table |  |
| `stringReplaceAll` | text value | %s %s %s | stock table |  |
| `stringReplaceFirst` | text value | %s %s %s | stock table |  |
| `stringSplitToList` | statement | app-defined spec | Pro block data |  |
| `stringSub` | text value | %s %d %d | stock table |  |
| `stringSubSingle` | text value | app-defined spec | Pro block data |  |
| `strParseInteger` | number value | app-defined spec | Pro block data |  |
| `strToListMap` | statement | %s %m.listMap | stock table |  |
| `strToMap` | statement | %s %m.varMap | stock table |  |
| `toHashCode` | number value | app-defined spec | Pro block data |  |
| `toLowerCase` | text value | %s | stock table |  |
| `toNumber` | number value | %s | stock table |  |
| `toString` | text value | %d | stock table |  |
| `toStringFormat` | text value | %d %s | stock table |  |
| `toStringWithDecimal` | text value | %d | stock table |  |
| `toUpperCase` | text value | %s | stock table |  |
| `trim` | text value | %s | stock table |  |
| `true` | boolean value | — | generator only |  |
| `||` | boolean value | %b %b | stock table |  |

## Math (24)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `mathAbs` | number value | %d | stock table |  |
| `mathAcos` | number value | %d | stock table |  |
| `mathAsin` | number value | %d | stock table |  |
| `mathAtan` | number value | %d | stock table |  |
| `mathCeil` | number value | %d | stock table |  |
| `mathCos` | number value | %d | stock table |  |
| `mathE` | number value | — | generator only |  |
| `mathExp` | number value | %d | stock table |  |
| `mathFloor` | number value | %d | stock table |  |
| `mathGetDip` | number value | %d | stock table |  |
| `mathGetDisplayHeight` | number value | — | generator only |  |
| `mathGetDisplayWidth` | number value | — | generator only |  |
| `mathLog` | number value | %d | stock table |  |
| `mathLog10` | number value | %d | stock table |  |
| `mathMax` | number value | %d %d | stock table |  |
| `mathMin` | number value | %d %d | stock table |  |
| `mathPi` | number value | — | generator only |  |
| `mathPow` | number value | %d %d | stock table |  |
| `mathRound` | number value | %d | stock table |  |
| `mathSin` | number value | %d | stock table |  |
| `mathSqrt` | number value | %d | stock table |  |
| `mathTan` | number value | %d | stock table |  |
| `mathToDegree` | number value | %d | stock table |  |
| `mathToRadian` | number value | %d | stock table |  |

## File (33)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `copyAssetFile` | text value | app-defined spec | Pro block data |  |
| `cropBitmapFileFromCenter` | statement | %s %s %d %d | stock table |  |
| `fileutilcopy` | statement | %s %s | stock table |  |
| `fileutilcopydir` | statement | app-defined spec | Pro block data |  |
| `fileutildelete` | statement | %s | stock table |  |
| `fileutilEndsWith` | boolean value | %s %s | stock table |  |
| `fileutilGetLastSegmentPath` | text value | %s | stock table |  |
| `fileutilisdir` | boolean value | %s | stock table |  |
| `fileutilisexist` | boolean value | %s | stock table |  |
| `fileutilisfile` | boolean value | %s | stock table |  |
| `fileutillength` | number value | %s | stock table |  |
| `fileutillistdir` | statement | %s %m.listStr | stock table |  |
| `fileutilmakedir` | statement | %s | stock table |  |
| `fileutilmove` | statement | %s %s | stock table |  |
| `fileutilread` | text value | %s | stock table |  |
| `fileutilStartsWith` | boolean value | %s %s | stock table |  |
| `fileutilwrite` | statement | %s %s | stock table |  |
| `getAssetFile` | statement | app-defined spec | Pro block data |  |
| `getExternalStorageDir` | text value | — | generator only |  |
| `getJpegRotate` | number value | %s | stock table |  |
| `getPackageDataDir` | text value | — | generator only |  |
| `getPublicDir` | text value | %m.directoryType | stock table |  |
| `renameFile` | statement | app-defined spec | Pro block data |  |
| `resizeBitmapFileRetainRatio` | statement | %s %s %d | stock table |  |
| `resizeBitmapFileToCircle` | statement | %s %s | stock table |  |
| `resizeBitmapFileToSquare` | statement | %s %s %d | stock table |  |
| `resizeBitmapFileWithRoundedBorder` | statement | %s %s %d | stock table |  |
| `rotateBitmapFile` | statement | %s %s %d | stock table |  |
| `scaleBitmapFile` | statement | %s %s %d %d | stock table |  |
| `setBitmapFileBrightness` | statement | %s %s %d | stock table |  |
| `setBitmapFileColorFilter` | statement | %s %s %m.color | stock table |  |
| `setBitmapFileContrast` | statement | %s %s %d | stock table |  |
| `skewBitmapFile` | statement | %s %s %d %d | stock table |  |

## View (228)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `addTab` | statement | app-defined spec | Pro block data |  |
| `addView` | statement | app-defined spec | Pro block data |  |
| `addViews` | statement | app-defined spec | Pro block data |  |
| `autoComSetData` | statement | app-defined spec | Pro block data |  |
| `bannerAdViewLoadAd` | statement | app-defined spec | Pro block data |  |
| `bottomMenuAddItem` | statement | app-defined spec | Pro block data |  |
| `calendarViewSetDate` | statement | %m.calendarview %d | stock table |  |
| `calendarViewSetMinDate` | statement | %m.calendarview %d | stock table |  |
| `calnedarViewSetMaxDate` | statement | %m.calendarview %d | stock table |  |
| `checkViewVisibility` | boolean value | app-defined spec | Pro block data |  |
| `closeDrawer` | statement | — | generator only |  |
| `codeviewApply` | statement | app-defined spec | Pro block data |  |
| `codeviewSetCode` | statement | app-defined spec | Pro block data |  |
| `codeviewSetLanguage` | statement | app-defined spec | Pro block data |  |
| `codeviewSetTheme` | statement | app-defined spec | Pro block data |  |
| `EditTextdiableSuggestion` | statement | app-defined spec | Pro block data |  |
| `EdittextGetselectionEnd` | number value | app-defined spec | Pro block data |  |
| `EdittextGetselectionStart` | number value | app-defined spec | Pro block data |  |
| `EditTextLines` | statement | app-defined spec | Pro block data |  |
| `EditTextSelectAll` | statement | app-defined spec | Pro block data |  |
| `EditTextSetMaxLines` | statement | app-defined spec | Pro block data |  |
| `EditTextSetSelection` | statement | app-defined spec | Pro block data |  |
| `EditTextShowError` | statement | app-defined spec | Pro block data |  |
| `EditTextSingleLine` | statement | app-defined spec | Pro block data |  |
| `fabIcon` | statement | app-defined spec | Pro block data |  |
| `fabSize` | statement | app-defined spec | Pro block data |  |
| `fabVisibility` | statement | app-defined spec | Pro block data |  |
| `getAlpha` | number value | %m.view | stock table |  |
| `getBadgeCount` | number value | app-defined spec | Pro block data |  |
| `getChecked` | boolean value | %m.checkbox | stock table |  |
| `getEnable` | boolean value | %m.view | stock table |  |
| `getHeight` | number value | app-defined spec | Pro block data |  |
| `getLocationX` | number value | %m.view | stock table |  |
| `getLocationY` | number value | %m.view | stock table |  |
| `getRating` | number value | app-defined spec | Pro block data |  |
| `getRotate` | number value | %m.view | stock table |  |
| `getScaleX` | number value | %m.view | stock table |  |
| `getScaleY` | number value | %m.view | stock table |  |
| `getText` | text value | %m.textview | stock table |  |
| `getTranslationX` | number value | %m.view | stock table |  |
| `getTranslationY` | number value | %m.view | stock table |  |
| `getWidth` | number value | app-defined spec | Pro block data |  |
| `gridSetColumnWidth` | statement | app-defined spec | Pro block data |  |
| `gridSetCustomViewData` | statement | app-defined spec | Pro block data |  |
| `gridSetHorizontalSpacing` | statement | app-defined spec | Pro block data |  |
| `gridSetNumColumns` | statement | app-defined spec | Pro block data |  |
| `gridSetStretchMode` | statement | app-defined spec | Pro block data |  |
| `gridSetVerticalSpacing` | statement | app-defined spec | Pro block data |  |
| `isDrawerOpen` | boolean value | — | generator only |  |
| `listRefresh` | statement | %m.listview | stock table |  |
| `listSetCustomViewData` | statement | %m.listview %m.listMap | stock table |  |
| `listSetData` | statement | %m.listview %m.listStr | stock table |  |
| `listSetStackFromBottom` | statement | app-defined spec | Pro block data |  |
| `listSetTranscriptMode` | statement | app-defined spec | Pro block data |  |
| `listSmoothScrollTo` | statement | %m.listview %d | stock table |  |
| `ListViewAddFooter` | statement | app-defined spec | Pro block data |  |
| `ListViewAddHeader` | statement | app-defined spec | Pro block data |  |
| `listViewRemoveFooter` | statement | app-defined spec | Pro block data |  |
| `listViewRemoveHeader` | statement | app-defined spec | Pro block data |  |
| `listViewSetSelection` | statement | app-defined spec | Pro block data |  |
| `lottieSetAnimationFromAsset` | statement | app-defined spec | Pro block data |  |
| `lottieSetAnimationFromJson` | statement | app-defined spec | Pro block data |  |
| `lottieSetAnimationFromUrl` | statement | app-defined spec | Pro block data |  |
| `lottieSetRepeatCount` | statement | app-defined spec | Pro block data |  |
| `lottieSetSpeed` | statement | app-defined spec | Pro block data |  |
| `mapViewAddMarker` | statement | %m.mapview %s %d %d | stock table |  |
| `mapViewMoveCamera` | statement | %m.mapview %d %d | stock table |  |
| `mapViewSetMapType` | statement | %m.mapview %m.mapType | stock table |  |
| `mapViewSetMarkerColor` | statement | %m.mapview %s %m.markerColor %d | stock table |  |
| `mapViewSetMarkerIcon` | statement | %m.mapview %s %m.resource | stock table |  |
| `mapViewSetMarkerInfo` | statement | %m.mapview %s %s %s | stock table |  |
| `mapViewSetMarkerPosition` | statement | %m.mapview %s %d %d | stock table |  |
| `mapViewSetMarkerVisible` | statement | %m.mapview %s %b | stock table |  |
| `mapViewZoomIn` | statement | %m.mapview | stock table |  |
| `mapViewZoomOut` | statement | %m.mapview | stock table |  |
| `mapViewZoomTo` | statement | %m.mapview %d | stock table |  |
| `multiAutoComSetData` | statement | app-defined spec | Pro block data |  |
| `onSwipeRefreshLayout` | container | app-defined spec | Pro block data |  |
| `openDrawer` | statement | — | generator only |  |
| `otpViewGetOTPText` | text value | app-defined spec | Pro block data |  |
| `otpViewSetFieldCount` | statement | app-defined spec | Pro block data |  |
| `otpViewSetOTPListener` | container | app-defined spec | Pro block data |  |
| `otpViewSetOTPText` | statement | app-defined spec | Pro block data |  |
| `pagerGetCurrentItem` | number value | app-defined spec | Pro block data |  |
| `pagerGetOffscreenPageLimit` | number value | app-defined spec | Pro block data |  |
| `pagerSetCurrentItem` | statement | app-defined spec | Pro block data |  |
| `pagerSetCustomViewData` | statement | app-defined spec | Pro block data |  |
| `pagerSetFragmentAdapter` | statement | app-defined spec | Pro block data |  |
| `pagerSetOffscreenPageLimit` | statement | app-defined spec | Pro block data |  |
| `patternLockClear` | statement | app-defined spec | Pro block data |  |
| `patternSetCorrectStateColor` | statement | app-defined spec | Pro block data |  |
| `patternSetDotCount` | statement | app-defined spec | Pro block data |  |
| `patternSetNormalStateColor` | statement | app-defined spec | Pro block data |  |
| `patternSetViewMode` | statement | app-defined spec | Pro block data |  |
| `patternSetWrongStateColor` | statement | app-defined spec | Pro block data |  |
| `patternToMD5` | text value | app-defined spec | Pro block data |  |
| `patternToSha1` | text value | app-defined spec | Pro block data |  |
| `patternToString` | text value | app-defined spec | Pro block data |  |
| `performClick` | statement | app-defined spec | Pro block data |  |
| `progressBarSetIndeterminate` | statement | %m.progressbar %b | stock table |  |
| `recyclerScrollToPositionWithOffset` | statement | app-defined spec | Pro block data |  |
| `recyclerSetCustomViewData` | statement | app-defined spec | Pro block data |  |
| `recyclerSetHasFixedSize` | statement | app-defined spec | Pro block data |  |
| `recyclerSetLayoutManager` | statement | app-defined spec | Pro block data |  |
| `recyclerSetLayoutManagerHorizontal` | statement | app-defined spec | Pro block data |  |
| `recyclerSmoothScrollToPosition` | statement | app-defined spec | Pro block data |  |
| `refreshingList` | statement | app-defined spec | Pro block data |  |
| `removeView` | statement | app-defined spec | Pro block data |  |
| `removeViews` | statement | app-defined spec | Pro block data |  |
| `requestFocus` | statement | %m.view | stock table |  |
| `seekBarGetMax` | number value | %m.seekbar | stock table |  |
| `seekBarGetProgress` | number value | %m.seekbar | stock table |  |
| `seekBarSetMax` | statement | %m.seekbar %d | stock table |  |
| `seekBarSetProgress` | statement | %m.seekbar %d | stock table |  |
| `setAlpha` | statement | %m.view %d | stock table |  |
| `setBadgeBackground` | statement | app-defined spec | Pro block data |  |
| `setBadgeNumber` | statement | app-defined spec | Pro block data |  |
| `setBadgeString` | statement | app-defined spec | Pro block data |  |
| `setBadgeTextColor` | statement | app-defined spec | Pro block data |  |
| `setBadgeTextSize` | statement | app-defined spec | Pro block data |  |
| `setBgColor` | statement | %m.view %m.color | stock table |  |
| `setBgDrawable` | statement | app-defined spec | Pro block data |  |
| `setBgResource` | statement | %m.view %m.resource_bg | stock table |  |
| `setBubbleArrowHeight` | statement | app-defined spec | Pro block data |  |
| `setBubbleArrowPosition` | statement | app-defined spec | Pro block data |  |
| `setBubbleArrowWidth` | statement | app-defined spec | Pro block data |  |
| `setBubbleColor` | statement | app-defined spec | Pro block data |  |
| `setBubbleCornerRadius` | statement | app-defined spec | Pro block data |  |
| `setBubbleStrokeColor` | statement | app-defined spec | Pro block data |  |
| `setBubbleStrokeWidth` | statement | app-defined spec | Pro block data |  |
| `setCardBackgroundColor` | statement | app-defined spec | Pro block data |  |
| `setCardElevation` | statement | app-defined spec | Pro block data |  |
| `setCardRadius` | statement | app-defined spec | Pro block data |  |
| `setChecked` | statement | %m.checkbox %b | stock table |  |
| `setColorFilter` | statement | %m.imageview %m.color | stock table |  |
| `setColorFilterView` | statement | app-defined spec | Pro block data |  |
| `setCornerRadiusView` | statement | app-defined spec | Pro block data |  |
| `setCustomLetter` | statement | app-defined spec | Pro block data |  |
| `setElevation` | statement | app-defined spec | Pro block data |  |
| `setEnable` | statement | %m.view %b | stock table | ✔ have |
| `setGradientBackground` | statement | app-defined spec | Pro block data |  |
| `setGravity` | statement | app-defined spec | Pro block data |  |
| `setHint` | statement | %m.edittext %s | stock table |  |
| `setHintTextColor` | statement | %m.edittext %m.color | stock table |  |
| `setImage` | statement | %m.imageview %m.resource | stock table |  |
| `setImageCustomRes` | statement | app-defined spec | Pro block data |  |
| `setImageFilePath` | statement | %m.imageview %s | stock table |  |
| `setImageIdentifier` | statement | app-defined spec | Pro block data |  |
| `setImageUrl` | statement | %m.imageview %s | stock table |  |
| `setInlineLabel` | statement | app-defined spec | Pro block data |  |
| `setNumStars` | statement | app-defined spec | Pro block data |  |
| `setPreventCornerOverlap` | statement | app-defined spec | Pro block data |  |
| `setRadiusAndStrokeView` | statement | app-defined spec | Pro block data |  |
| `setRating` | statement | app-defined spec | Pro block data |  |
| `setRecyclerViewLayoutParams` | statement | app-defined spec | Pro block data |  |
| `setRefreshing` | statement | app-defined spec | Pro block data |  |
| `setRotate` | statement | %m.view %d | stock table |  |
| `setScaleX` | statement | %m.view %d | stock table |  |
| `setScaleY` | statement | %m.view %d | stock table |  |
| `setSelectedTabIndicatorColor` | statement | app-defined spec | Pro block data |  |
| `setSelectedTabIndicatorHeight` | statement | app-defined spec | Pro block data |  |
| `setStepSize` | statement | app-defined spec | Pro block data |  |
| `setStrokeView` | statement | app-defined spec | Pro block data |  |
| `setTabRippleColor` | statement | app-defined spec | Pro block data |  |
| `setTabTextColors` | statement | app-defined spec | Pro block data |  |
| `setText` | statement | %m.textview %s | stock table | ✔ have |
| `setTextColor` | statement | %m.textview %m.color | stock table |  |
| `setTextSize` | statement | app-defined spec | Pro block data |  |
| `setThreshold` | statement | app-defined spec | Pro block data |  |
| `setTokenizer` | statement | app-defined spec | Pro block data |  |
| `setTranslationX` | statement | %m.view %d | stock table |  |
| `setTranslationY` | statement | %m.view %d | stock table |  |
| `setTypeface` | statement | %m.textview %m.font %m.typeface | stock table |  |
| `setupWithViewPager` | statement | app-defined spec | Pro block data |  |
| `setUseCompatPadding` | statement | app-defined spec | Pro block data |  |
| `setVisible` | statement | %m.view %m.visible | stock table | ✔ have |
| `showSnackbar` | container | app-defined spec | Pro block data |  |
| `signInButtonSetColorScheme` | statement | app-defined spec | Pro block data |  |
| `signInButtonSetSize` | statement | app-defined spec | Pro block data |  |
| `spnGetSelection` | number value | %m.spinner | stock table |  |
| `spnRefresh` | statement | %m.spinner | stock table |  |
| `spnSetCustomViewData` | statement | app-defined spec | Pro block data |  |
| `spnSetData` | statement | %m.spinner %m.listStr | stock table |  |
| `spnSetSelection` | statement | %m.spinner %d | stock table |  |
| `tilGetCounterMaxLength` | number value | app-defined spec | Pro block data |  |
| `tilSetBoxBgColor` | statement | app-defined spec | Pro block data |  |
| `tilSetBoxBgMode` | statement | app-defined spec | Pro block data |  |
| `tilSetBoxCornerRadii` | statement | app-defined spec | Pro block data |  |
| `tilSetBoxStrokeColor` | statement | app-defined spec | Pro block data |  |
| `tilSetCounterEnabled` | statement | app-defined spec | Pro block data |  |
| `tilSetCounterMaxLength` | statement | app-defined spec | Pro block data |  |
| `tilSetError` | statement | app-defined spec | Pro block data |  |
| `tilSetErrorEnabled` | statement | app-defined spec | Pro block data |  |
| `timepickerSetCurrentHour` | statement | app-defined spec | Pro block data |  |
| `timepickerSetCurrentMinute` | statement | app-defined spec | Pro block data |  |
| `timepickerSetHour` | statement | app-defined spec | Pro block data |  |
| `timepickerSetIs24Hour` | statement | app-defined spec | Pro block data |  |
| `timepickerSetMinute` | statement | app-defined spec | Pro block data |  |
| `videoviewCanPause` | boolean value | app-defined spec | Pro block data |  |
| `videoviewCanSeekBackward` | boolean value | app-defined spec | Pro block data |  |
| `videoviewCanSeekForward` | boolean value | app-defined spec | Pro block data |  |
| `videoviewGetCurrentPosition` | number value | app-defined spec | Pro block data |  |
| `videoviewGetDuration` | number value | app-defined spec | Pro block data |  |
| `videoviewIsPlaying` | boolean value | app-defined spec | Pro block data |  |
| `videoviewPause` | statement | app-defined spec | Pro block data |  |
| `videoviewSetVideoUri` | statement | app-defined spec | Pro block data |  |
| `videoviewStart` | statement | app-defined spec | Pro block data |  |
| `videoviewStop` | statement | app-defined spec | Pro block data |  |
| `viewGetChildAt` | statement | app-defined spec | Pro block data |  |
| `viewOnClick` | container | %m.view | stock table |  |
| `viewOnLongClick` | container | app-defined spec | Pro block data |  |
| `viewOnTouch` | container | app-defined spec | Pro block data |  |
| `ViewPagerNotifyOnDtatChange` | statement | app-defined spec | Pro block data |  |
| `webViewCanGoBack` | boolean value | %m.webview | stock table |  |
| `webViewCanGoForward` | boolean value | %m.webview | stock table |  |
| `webViewClearCache` | statement | %m.webview | stock table |  |
| `webViewClearHistory` | statement | %m.webview | stock table |  |
| `webviewGetProgress` | number value | app-defined spec | Pro block data |  |
| `webViewGetUrl` | text value | %m.webview | stock table |  |
| `webViewGoBack` | statement | %m.webview | stock table |  |
| `webViewGoForward` | statement | %m.webview | stock table |  |
| `webViewLoadUrl` | statement | %m.webview %s.url | stock table |  |
| `webViewSetCacheMode` | statement | %m.webview %m.cacheMode | stock table |  |
| `webViewStopLoading` | statement | %m.webview | stock table |  |
| `webViewZoomIn` | statement | %m.webview | stock table |  |
| `webViewZoomOut` | statement | %m.webview | stock table |  |
| `YTPVLifecycle` | statement | app-defined spec | Pro block data |  |
| `YTPVSetListener` | container | app-defined spec | Pro block data |  |

## Component (140)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `AsyncTaskExecute` | statement | app-defined spec | Pro block data |  |
| `AsyncTaskPublishProgress` | statement | app-defined spec | Pro block data |  |
| `bluetoothConnectActivateBluetooth` | statement | %m.bluetoothconnect | stock table |  |
| `bluetoothConnectGetPairedDevices` | statement | %m.bluetoothconnect %m.listMap | stock table |  |
| `bluetoothConnectGetRandomUuid` | text value | %m.bluetoothconnect | stock table |  |
| `bluetoothConnectIsBluetoothActivated` | boolean value | %m.bluetoothconnect | stock table |  |
| `bluetoothConnectIsBluetoothEnabled` | boolean value | %m.bluetoothconnect | stock table |  |
| `bluetoothConnectReadyConnection` | statement | %m.bluetoothconnect %s | stock table |  |
| `bluetoothConnectReadyConnectionToUuid` | statement | %m.bluetoothconnect %s %s | stock table |  |
| `bluetoothConnectSendData` | statement | %m.bluetoothconnect %s %s | stock table |  |
| `bluetoothConnectStartConnection` | statement | %m.bluetoothconnect %s %s | stock table |  |
| `bluetoothConnectStartConnectionToUuid` | statement | %m.bluetoothconnect %s %s %s | stock table |  |
| `bluetoothConnectStopConnection` | statement | %m.bluetoothconnect %s | stock table |  |
| `calendarAdd` | statement | %m.calendar %m.calendarField %d | stock table |  |
| `calendarDiff` | number value | %m.calendar %m.calendar | stock table |  |
| `calendarFormat` | text value | %m.calendar %s | stock table |  |
| `calendarGetNow` | statement | %m.calendar | stock table |  |
| `calendarGetTime` | number value | %m.calendar | stock table |  |
| `calendarSet` | statement | %m.calendar %m.calendarField %d | stock table |  |
| `calendarSetTime` | statement | %m.calendar %d | stock table |  |
| `camerastarttakepicture` | statement | %m.camera | stock table |  |
| `changeStatebarColour` | statement | app-defined spec | Pro block data |  |
| `copyToClipboard` | statement | %s | stock table |  |
| `datePickerDialogShow` | statement | app-defined spec | Pro block data |  |
| `Dialog SetIcon` | statement | app-defined spec | Pro block data |  |
| `dialogCancelButton` | container | %m.dialog %s | stock table |  |
| `dialogNeutralButton` | container | %m.dialog %s | stock table |  |
| `dialogOkButton` | container | %m.dialog %s | stock table |  |
| `dialogSetMessage` | statement | %m.dialog %s | stock table |  |
| `dialogSetTitle` | statement | %m.dialog %s | stock table |  |
| `dialogShow` | statement | %m.dialog | stock table |  |
| `doToast` | statement | %s | stock table | ✔ have |
| `fileContainsData` | boolean value | app-defined spec | Pro block data |  |
| `fileGetData` | text value | %m.file %s | stock table |  |
| `filepickerstartpickfiles` | statement | %m.filepicker | stock table |  |
| `fileRemoveData` | statement | %m.file %s | stock table |  |
| `fileSetData` | statement | %m.file %s %s | stock table |  |
| `finishActivity` | end/return | — | generator only | ✔ have |
| `finishAffinity` | end/return | app-defined spec | Pro block data |  |
| `firebaseAdd` | statement | %m.firebase %s %m.varMap | stock table |  |
| `firebaseauthCreateUser` | statement | %m.firebaseauth %s %s | stock table |  |
| `firebaseauthGetCurrentUser` | text value | — | generator only |  |
| `firebaseauthGetUid` | text value | — | generator only |  |
| `firebaseauthIsLoggedIn` | boolean value | — | generator only |  |
| `firebaseauthResetPassword` | statement | %m.firebaseauth %s | stock table |  |
| `firebaseauthSignInAnonymously` | statement | %m.firebaseauth | stock table |  |
| `firebaseauthSignInUser` | statement | %m.firebaseauth %s %s | stock table |  |
| `firebaseauthSignOutUser` | statement | — | generator only |  |
| `firebaseDelete` | statement | %m.firebase %s | stock table |  |
| `firebaseGetChildren` | container | %m.firebase %m.listMap | stock table |  |
| `firebaseGetPushKey` | text value | %m.firebase | stock table |  |
| `firebasePush` | statement | %m.firebase %m.varMap | stock table |  |
| `firebaseStartListen` | statement | %m.firebase | stock table |  |
| `firebaseStopListen` | statement | %m.firebase | stock table |  |
| `firebasestorageDelete` | statement | %m.firebasestorage %s | stock table |  |
| `firebasestorageDownloadFile` | statement | %m.firebasestorage %s %s | stock table |  |
| `firebasestorageUploadFile` | statement | %m.firebasestorage %s %s | stock table |  |
| `gyroscopeStartListen` | statement | %m.gyroscope | stock table |  |
| `gyroscopeStopListen` | statement | %m.gyroscope | stock table |  |
| `hideKeyboard` | statement | app-defined spec | Pro block data |  |
| `imageCrop` | statement | app-defined spec | Pro block data |  |
| `intentGetString` | text value | %s | stock table |  |
| `intentHasExtra` | boolean value | app-defined spec | Pro block data |  |
| `intentPutExtra` | statement | %m.intent %s %s | stock table |  |
| `intentRemoveExtra` | statement | app-defined spec | Pro block data |  |
| `intentSetAction` | statement | %m.intent %m.intentAction | stock table |  |
| `intentSetData` | statement | %m.intent %s.intentData | stock table |  |
| `intentSetFlags` | statement | %m.intent %m.intentFlags | stock table |  |
| `intentSetScreen` | statement | %m.intent %m.activity | stock table |  |
| `intentSetType` | statement | app-defined spec | Pro block data |  |
| `interstitialAdIsLoaded` | boolean value | app-defined spec | Pro block data |  |
| `interstitialAdLoad` | statement | app-defined spec | Pro block data |  |
| `interstitialAdShow` | statement | app-defined spec | Pro block data |  |
| `isConnected` | boolean value | app-defined spec | Pro block data |  |
| `launchApp` | statement | app-defined spec | Pro block data |  |
| `LightStatusBar` | statement | app-defined spec | Pro block data |  |
| `locationManagerRemoveUpdates` | statement | %m.locationmanager | stock table |  |
| `locationManagerRequestLocationUpdates` | statement | %m.locationmanager %m.providerType %d %d | stock table |  |
| `mediaplayerCreate` | statement | %m.mediaplayer %m.sound | stock table |  |
| `mediaplayerGetCurrent` | number value | %m.mediaplayer | stock table |  |
| `mediaplayerGetDuration` | number value | %m.mediaplayer | stock table |  |
| `mediaplayerIsLooping` | boolean value | %m.mediaplayer | stock table |  |
| `mediaplayerIsPlaying` | boolean value | %m.mediaplayer | stock table |  |
| `mediaplayerPause` | statement | %m.mediaplayer | stock table |  |
| `mediaplayerRelease` | statement | %m.mediaplayer | stock table |  |
| `mediaplayerReset` | statement | %m.mediaplayer | stock table |  |
| `mediaplayerSeek` | statement | %m.mediaplayer %d | stock table |  |
| `mediaplayerSetLooping` | statement | %m.mediaplayer %b | stock table |  |
| `mediaplayerStart` | statement | %m.mediaplayer | stock table |  |
| `objectanimatorCancel` | statement | %m.objectanimator | stock table |  |
| `objectanimatorIsRunning` | boolean value | %m.objectanimator | stock table |  |
| `objectanimatorSetDuration` | statement | %m.objectanimator %d | stock table |  |
| `objectanimatorSetFromTo` | statement | %m.objectanimator %d %d | stock table |  |
| `objectanimatorSetInterpolator` | statement | %m.objectanimator %m.aniInterpolator | stock table |  |
| `objectanimatorSetProperty` | statement | %m.objectanimator %m.animatorproperty | stock table |  |
| `objectanimatorSetRepeatCount` | statement | %m.objectanimator %d | stock table |  |
| `objectanimatorSetRepeatMode` | statement | %m.objectanimator %m.aniRepeatMode | stock table |  |
| `objectanimatorSetTarget` | statement | %m.objectanimator %m.view | stock table |  |
| `objectanimatorSetValue` | statement | %m.objectanimator %d | stock table |  |
| `objectanimatorStart` | statement | %m.objectanimator | stock table |  |
| `progressdialogCreate` | statement | app-defined spec | Pro block data |  |
| `progressdialogDismiss` | statement | app-defined spec | Pro block data |  |
| `progressdialogSetCancelable` | statement | app-defined spec | Pro block data |  |
| `progressdialogSetCanceledOutside` | statement | app-defined spec | Pro block data |  |
| `progressdialogSetMax` | statement | app-defined spec | Pro block data |  |
| `progressdialogSetMessage` | statement | app-defined spec | Pro block data |  |
| `progressdialogSetProgress` | statement | app-defined spec | Pro block data |  |
| `progressdialogSetStyle` | statement | app-defined spec | Pro block data |  |
| `progressdialogSetTitle` | statement | app-defined spec | Pro block data |  |
| `progressdialogShow` | statement | app-defined spec | Pro block data |  |
| `requestnetworkSetHeaders` | statement | %m.requestnetwork %m.varMap | stock table |  |
| `requestnetworkSetParams` | statement | %m.requestnetwork %m.varMap %m.requestType | stock table |  |
| `requestnetworkStartRequestNetwork` | statement | %m.requestnetwork %m.method %s %s | stock table |  |
| `rewardedVideoAdLoad` | statement | app-defined spec | Pro block data |  |
| `rewardedVideoAdShow` | statement | app-defined spec | Pro block data |  |
| `sendBroadcast` | statement | app-defined spec | Pro block data |  |
| `setTitle` | statement | %s | stock table |  |
| `showKeyboard` | statement | app-defined spec | Pro block data |  |
| `soundpoolCreate` | statement | %m.soundpool %d | stock table |  |
| `soundpoolLoad` | number value | %m.soundpool %m.sound | stock table |  |
| `soundpoolStreamPlay` | number value | %m.soundpool %d %d | stock table |  |
| `soundpoolStreamStop` | statement | %m.soundpool %d | stock table |  |
| `speechToTextShutdown` | statement | %m.speechtotext | stock table |  |
| `speechToTextStartListening` | statement | %m.speechtotext | stock table |  |
| `speechToTextStopListening` | statement | %m.speechtotext | stock table |  |
| `startActivity` | statement | %m.intent | stock table |  |
| `startActivityWithChooser` | statement | app-defined spec | Pro block data |  |
| `startService` | statement | app-defined spec | Pro block data |  |
| `stopService` | statement | app-defined spec | Pro block data |  |
| `textToSpeechIsSpeaking` | boolean value | %m.texttospeech | stock table |  |
| `textToSpeechSetPitch` | statement | %m.texttospeech %d | stock table |  |
| `textToSpeechSetSpeechRate` | statement | %m.texttospeech %d | stock table |  |
| `textToSpeechShutdown` | statement | %m.texttospeech | stock table |  |
| `textToSpeechSpeak` | statement | %m.texttospeech %s | stock table |  |
| `textToSpeechStop` | statement | %m.texttospeech | stock table |  |
| `timePickerDialogShow` | statement | app-defined spec | Pro block data |  |
| `timerAfter` | container | %m.timer %d | stock table |  |
| `timerCancel` | statement | %m.timer | stock table |  |
| `timerEvery` | container | %m.timer %d %d | stock table |  |
| `vibratorAction` | statement | %m.vibrator %d | stock table |  |

## Events (11)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `getLastVisiblePosition` | number value | app-defined spec | Pro block data |  |
| `listscrollparam` | number value | app-defined spec | Pro block data |  |
| `menuAddItem` | statement | app-defined spec | Pro block data |  |
| `menuAddMenuItem` | statement | app-defined spec | Pro block data |  |
| `menuAddSubmenu` | container | app-defined spec | Pro block data |  |
| `menuInflater` | statement | app-defined spec | Pro block data |  |
| `pagerscrollparam` | number value | app-defined spec | Pro block data |  |
| `recyclerscrollparam` | number value | app-defined spec | Pro block data |  |
| `returnFragment` | end/return | app-defined spec | Pro block data |  |
| `returnTitle` | end/return | app-defined spec | Pro block data |  |
| `submenuAddItem` | statement | app-defined spec | Pro block data |  |

## Custom views (3)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `checkboxIsChecked` | boolean value | app-defined spec | Pro block data |  |
| `checkboxOnChecked` | container | app-defined spec | Pro block data |  |
| `notifyDataSetChanged` | statement | app-defined spec | Pro block data |  |

## More (7)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `addCustomVariable` | statement | app-defined spec | Pro block data |  |
| `addInitializer` | statement | app-defined spec | Pro block data |  |
| `addPermission` | statement | app-defined spec | Pro block data |  |
| `CommandBlockJava` | container | app-defined spec | Pro block data |  |
| `customToast` | statement | app-defined spec | Pro block data |  |
| `customToastWithIcon` | statement | app-defined spec | Pro block data |  |
| `removePermission` | statement | app-defined spec | Pro block data |  |

## Strings (2)

| Block | Kind | Params | Shape in the app | Agent |
|---|---|---|---|---|
| `getResStr` | type app_name | — | generator only |  |
| `getResString` | text value | app-defined spec | Pro block data |  |
