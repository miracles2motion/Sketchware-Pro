# Stock block knowledge base (complete)

Every stock block in Sketchware Pro, extracted 2026-10-19 from the app's own sources: palette builders (`ExtraPaletteBlock`, `BlocksHandler.primaryBlocksA-D`), the stock slot table in the bundled runtime classes, the Pro block definitions (`BlocksHandler.builtInBlocks`, `ExtraBlocks` — 258 blocks), the code generator (`Fx.getBlockCode`, 333 cases + extra-block fallback), and the import collector (`Jx.addImportsForBlocks`). Machine-readable twin: `docs/stock_blocks_v2.json`.

## Totals

- Palette blocks: **584**
- Statements: 374 · number/text/boolean values: 168 · containers: 29 · end/stop: 6
- Blocks with a generator case in `Fx`: 316; with spec/code in Pro block data: 279; with a known slot layout: 554
- Blocks gated behind a component on the screen's component list: 109
- Agent-known today (opCode emitted by `AgentLogic`/`AgentTools`): 79

Params use the app's slot tokens: `%s` text, `%d` number, `%b` boolean, `%m.x` picker (widget/variable/component of kind x). Emission = the Java the app generates (truncated). ✔ = agent can already build it.


## Variable (26)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `decreaseInt` | statement | %m.varInt | — | `%s--;` | ✔ have |
| `hashmapGetBoolean` | boolean value | %m.varMap %s | — | `(boolean)%s.get(%s)` |  |
| `hashmapGetListmap` | list value | %m.varMap %s | — | `(ArrayList<HashMap<String,Object>>)%s.get(%s)` |  |
| `hashmapGetMap` | map value | %m.varMap %s | — | `(HashMap<String,Object>)%s.get(%s)` |  |
| `hashmapGetNumber` | number value | %m.varMap %s | — | `(double)%s.get(%s)` |  |
| `hashmapListstr` | list value | %m.varMap %s | — | `(ArrayList<String>)%s.get(%s)` |  |
| `hashmapPutBoolean` | statement | %m.varMap %s %b | — | `%s.put(%s, %s);` |  |
| `hashmapPutListmap` | statement | %m.varMap %s %m.listMap | — | `%s.put(%s, %s);` |  |
| `hashmapPutListstr` | statement | %m.varMap %s %m.listStr | — | `%s.put(%s, %s);` |  |
| `hashmapPutMap` | statement | %m.varMap %s %m.varMap | — | `%s.put(%s, %s);` |  |
| `hashmapPutNumber` | statement | %m.varMap %s %d | — | `%s.put(%s, (int)(%s));` |  |
| `hashmapPutNumber2` | statement | %m.varMap %s %d | — | `%s.put(%s, (double)(%s));` |  |
| `increaseInt` | statement | %m.varInt | — | `%s++;` | ✔ have |
| `mapClear` | statement | %m.varMap | — | `app logic: %s.clear();` | ✔ have |
| `mapContainKey` | boolean value | %m.varMap %s | — | `%s.containsKey(%s)` | ✔ have |
| `mapContainValue` | boolean value | %m.varMap %s | — | `%s.containsValue(%s)` |  |
| `mapCreateNew` | statement | %m.varMap | — | `app logic: %s = new HashMap<>();` | ✔ have |
| `mapGet` | text value | %m.varMap %s | — | `%s.get(%s).toString()` | ✔ have |
| `mapGetAllKeys` | statement | %m.varMap %m.listStr | — | `app logic: SketchwareUtil.getAllKeysFromMap(%s, %s);` |  |
| `mapIsEmpty` | boolean value | %m.varMap | — | `%s.isEmpty()` |  |
| `mapPut` | statement | %m.varMap %s %s | — | `app logic: %s.put(%s, %s);` | ✔ have |
| `mapRemoveKey` | statement | %m.varMap %s | — | `app logic: %s.remove(%s);` |  |
| `mapSize` | number value | %m.varMap | — | `%s.size()` |  |
| `setVarBoolean` | statement | %m.varBool %b | — | `%s = %s;` | ✔ have |
| `setVarInt` | statement | %m.varInt %d | — | `%s = %s;` | ✔ have |
| `setVarString` | statement | %m.varStr %s | — | `%s = %s;` | ✔ have |

## List (33)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `addListInt` | statement | %d %m.listInt | — | `app logic: %s.add(Double.valueOf(%s));` | ✔ have |
| `addListMap` | statement | %s %s %m.listMap | — | `app logic: { HashMap<String, Object> _item = new HashMap<>(); _item.put(%s, %s); ` |  |
| `addListStr` | statement | %s %m.listStr | — | `app logic: %s.add(%s);` | ✔ have |
| `addMapToList` | statement | %m.varMap %m.listMap | — | `app logic: %s.add(%s);` |  |
| `clearList` | statement | %m.list | — | `app logic: %s.clear();` | ✔ have |
| `containListInt` | boolean value | %m.listInt %d | — | `%s.contains(%s)` | ✔ have |
| `containListMap` | boolean value | %m.listMap %d %s | — | `%s.get((int)%s).containsKey(%s)` |  |
| `containListStr` | boolean value | %m.listStr %s | — | `%s.contains(%s)` | ✔ have |
| `deleteList` | statement | %d %m.list | — | `app logic: %s.remove((int)(%s));` |  |
| `deleteMapFromListmap` | map value | %m.varMap %m.listMap | — | `%2$s.remove(%1$s);` |  |
| `getAtListInt` | number value | %d %m.listInt | — | `%s.get((int)(%s)).doubleValue()` | ✔ have |
| `getAtListMap` | text value | %d %s %m.listMap | — | `%s.get((int)%s).get(%s).toString()` |  |
| `getAtListStr` | text value | %d %m.listStr | — | `%s.get((int)(%s))` | ✔ have |
| `getMapAtPosListmap` | map value | %d %m.listMap | — | `%2$s.get((int)(%1$s))` |  |
| `getMapInList` | statement | %d %m.listMap %m.varMap | — | `app logic: %s = %s.get((int)%s);` |  |
| `indexListInt` | number value | %d %m.listInt | — | `%s.indexOf(%s)` |  |
| `indexListStr` | number value | %s %m.listStr | — | `%s.indexOf(%s)` |  |
| `insertListInt` | statement | %d %d %m.listInt | — | `app logic: %s.add((int)(%s), Double.valueOf(%s));` |  |
| `insertListMap` | statement | %s %s %d %m.listMap | — | `app logic: { HashMap<String, Object> _item = new HashMap<>(); _item.put(%s, %s); ` |  |
| `insertListStr` | statement | %s %d %m.listStr | — | `app logic: %s.add((int)(%s), %s);` |  |
| `insertMapToList` | statement | %m.varMap %d %m.listMap | — | `app logic: %s.add((int)%s, %s);` |  |
| `lengthList` | number value | %m.list | — | `%s.size()` | ✔ have |
| `listAddAll` | statement | %m.listStr %m.list | — | `%1$s.addAll(%2$s);` |  |
| `reverseList` | statement | %m.list | — | `Collections.reverse(%s);` |  |
| `setAtPosListnum` | statement | %d %d %m.listInt | — | `%3$s.set((int)%2$s, %1$s);` |  |
| `setAtPosListstr` | statement | %s %d %m.listStr | — | `%3$s.set((int)%2$s, %1$s);` |  |
| `setListMap` | statement | %s %s %d %m.listMap | — | `app logic: %s.get((int)%s).put(%s, %s);` |  |
| `setMapAtPosListmap` | statement | %m.varMap %d %m.listMap | — | `%3$s.set((int)(%2$s), %1$s);` |  |
| `shuffleList` | statement | %m.list | — | `Collections.shuffle(%s);` |  |
| `sortList` | statement | %m.listStr | — | `Collections.sort(%s);` |  |
| `sortListmap` | statement | %m.listMap %s %b %b | — | `SketchwareUtil.sortListMap(%s, %s, %s, %s);` |  |
| `sortListnum` | statement | %m.listInt | — | `Collections.sort(%s);` |  |
| `swapInList` | statement | %m.list %d %d | — | `Collections.swap(%s, (int)(%s), (int)(%s));` |  |

## Control (28)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `RepeatKnownNumDescending` | container | %d %s.inputOnly | — | `for (int %2$s = ((int) %1$s - 1); %2$s > -1; %2$s--) { %3$s }` |  |
| `break` | end/stop | — | — | `app logic: break;` | ✔ have |
| `caseNum` | container | %d | — | `case ((int)%s): { %s break; }` |  |
| `caseNumAnd` | statement | %d | — | `case ((int)%s):` |  |
| `caseStr` | container | %s | — | `case %s: { %s break; }` |  |
| `caseStrAnd` | statement | %s | — | `case %s:` |  |
| `continue` | end/stop | — | — | `continue;` |  |
| `defaultSwitch` | container | — | — | `default: { %s break; }` |  |
| `forever` | container | — | — | `while(true) { %s }` | ✔ have |
| `if` | container | %b | — | `if (%s) { %s }` | ✔ have |
| `ifElse` | if-else | %b | — | `if (%s) { %s } else { %s }` | ✔ have |
| `instanceOfOperator` | boolean value | %s %s | — | `%1$s instanceof %2$s` |  |
| `isEmpty` | boolean value | %s | — | `%s.isEmpty()` |  |
| `repeat` | container | %d | — | `app logic: for(int _repeat%s = 0; _repeat%s < (int)(%s); _repeat%s++) { %s }` | ✔ have |
| `repeatKnownNum` | container | %d %s.inputOnly | — | `for (int %2$s = 0; %2$s < (int)(%1$s); %2$s++) { %3$s }` |  |
| `returnBoolean` | statement | %b | — | `return (%s);` |  |
| `returnListMap` | statement | %m.listMap | — | `return %s;` |  |
| `returnListStr` | statement | %m.listStr | — | `return %s;` |  |
| `returnMap` | statement | %m.varMap | — | `return %s;` |  |
| `returnNumber` | statement | %d | — | `return (%s);` |  |
| `returnString` | statement | %s | — | `return (%s);` |  |
| `returnView` | statement | %m.view | — | `return %s;` |  |
| `switchNum` | container | %d | — | `switch((int)%s) { %s }` |  |
| `switchStr` | container | %s | — | `switch(%s) { %s }` |  |
| `ternaryNumber` | number value | %b %d %d | — | `%s ? (int)%s : (int)%s` |  |
| `ternaryString` | text value | %b %s %s | — | `%s ? %s : %s` |  |
| `tryCatch` | if-else | — | — | `try { %s } catch (Exception e) { %s }` |  |
| `whileLoop` | container | %b | — | `while(%s) { %s }` |  |

## Operator (49)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `%` | number value | %d %d | — | `app generator case (dynamic)` | ✔ have |
| `&&` | boolean value | %b %b | — | `app generator case (dynamic)` | ✔ have |
| `*` | number value | %d %d | — | `app generator case (dynamic)` | ✔ have |
| `+` | number value | %d %d | — | `app generator case (dynamic)` | ✔ have |
| `-` | number value | %d %d | — | `app generator case (dynamic)` | ✔ have |
| `/` | number value | %d %d | — | `app generator case (dynamic)` | ✔ have |
| `<` | boolean value | %d %d | — | `%s %s %s` | ✔ have |
| `=` | boolean value | %d %d | — | `%s == %s` | ✔ have |
| `>` | boolean value | %d %d | — | `app generator case (dynamic)` | ✔ have |
| `GsonListTojsonString` | text value | %m.list | — | `new Gson().toJson(%s)` |  |
| `GsonStringToListNumber` | statement | %s %m.listInt | — | `%2$s = new Gson().fromJson(%1$s, new TypeToken<ArrayList<Double>>(){}.getType());` |  |
| `GsonStringToListString` | statement | %s %m.listStr | — | `%2$s = new Gson().fromJson(%1$s, new TypeToken<ArrayList<String>>(){}.getType());` |  |
| `addSourceDirectly` | statement | %s.inputOnly | — | `%s` |  |
| `asdBoolean` | boolean value | %s.inputOnly | — | `%s` |  |
| `asdNumber` | number value | %s.inputOnly | — | `%s` |  |
| `asdString` | text value | %s.inputOnly | — | `%s` |  |
| `false` | boolean value | — | — | `app generator case (dynamic)` | ✔ have |
| `html` | text value | %s | — | `Html.fromHtml(%s)` |  |
| `listMapToStr` | text value | %m.listMap | — | `new Gson().toJson(%s)` |  |
| `mapToStr` | text value | %m.varMap | — | `new Gson().toJson(%s)` |  |
| `not` | boolean value | %b | — | `!%s` | ✔ have |
| `random` | number value | %d %d | — | `SketchwareUtil.getRandom((int)(%s), (int)(%s))` | ✔ have |
| `reverse` | text value | %s | — | `new StringBuilder(%s).reverse().toString()` |  |
| `strParseInteger` | number value | %s | — | `Integer.parseInt(%s)` |  |
| `strToListMap` | statement | %s %m.listMap | — | `app logic: %s = new Gson().fromJson(%s, new TypeToken<ArrayList<HashMap<String, O` |  |
| `strToMap` | statement | %s %m.varMap | — | `app logic: %s = new Gson().fromJson(%s, new TypeToken<HashMap<String, Object>>(){` |  |
| `stringContains` | boolean value | %s %s | — | `%s.contains(%s)` | ✔ have |
| `stringEquals` | boolean value | %s %s | — | `%s.equals(%s)` | ✔ have |
| `stringIndex` | number value | %s %s | — | `%s.indexOf(%s)` |  |
| `stringJoin` | text value | %s %s | — | `%s.concat(%s)` | ✔ have |
| `stringLastIndex` | number value | %s %s | — | `%s.lastIndexOf(%s)` |  |
| `stringLength` | number value | %s | — | `%s.length()` | ✔ have |
| `stringMatches` | boolean value | %s %s | — | `%s.matches(%s)` |  |
| `stringReplace` | text value | %s %s %s | — | `%s.replace(%s, %s)` |  |
| `stringReplaceAll` | text value | %s %s %s | — | `%s.replaceAll(%s, %s)` |  |
| `stringReplaceFirst` | text value | %s %s %s | — | `%s.replaceFirst(%s, %s)` |  |
| `stringSplitToList` | statement | %s %s %m.listStr | — | `%3$s = new ArrayList<String>(Arrays.asList(%1$s.split(%2$s)));` |  |
| `stringSub` | text value | %s %d %d | — | `%s.substring((int)(%s), (int)(%s))` | ✔ have |
| `stringSubSingle` | text value | %s %d | — | `%1$s.substring(%2$s)` |  |
| `toHashCode` | number value | %s | — | `%s.hashCode()` |  |
| `toLowerCase` | text value | %s | — | `%s.toLowerCase()` | ✔ have |
| `toNumber` | number value | %s | — | `Double.parseDouble(%s)` | ✔ have |
| `toString` | text value | %d | — | `String.valueOf((long)(%s))` |  |
| `toStringFormat` | text value | %d %s | — | `new DecimalFormat(%s).format(%s)` |  |
| `toStringWithDecimal` | text value | %d | — | `String.valueOf(%s)` | ✔ have |
| `toUpperCase` | text value | %s | — | `%s.toUpperCase()` | ✔ have |
| `trim` | text value | %s | — | `%s.trim()` | ✔ have |
| `true` | boolean value | — | — | `app generator case (dynamic)` | ✔ have |
| `||` | boolean value | %b %b | — | `%s %s %s` | ✔ have |

## Math (24)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `mathAbs` | number value | %d | — | `Math.abs(%s)` | ✔ have |
| `mathAcos` | number value | %d | — | `Math.acos(%s)` |  |
| `mathAsin` | number value | %d | — | `Math.asin(%s)` |  |
| `mathAtan` | number value | %d | — | `Math.atan(%s)` |  |
| `mathCeil` | number value | %d | — | `Math.ceil(%s)` | ✔ have |
| `mathCos` | number value | %d | — | `Math.cos(%s)` |  |
| `mathE` | number value | — | — | `app logic: Math.E` |  |
| `mathExp` | number value | %d | — | `Math.exp(%s)` |  |
| `mathFloor` | number value | %d | — | `Math.floor(%s)` | ✔ have |
| `mathGetDip` | number value | %d | — | `SketchwareUtil.getDip(getApplicationContext(), (int)(%s))` |  |
| `mathGetDisplayHeight` | number value | — | — | `app logic: SketchwareUtil.getDisplayHeightPixels(getApplicationContext())` |  |
| `mathGetDisplayWidth` | number value | — | — | `app logic: SketchwareUtil.getDisplayWidthPixels(getApplicationContext())` |  |
| `mathLog` | number value | %d | — | `Math.log(%s)` |  |
| `mathLog10` | number value | %d | — | `Math.log10(%s)` |  |
| `mathMax` | number value | %d %d | — | `Math.max(%s, %s)` | ✔ have |
| `mathMin` | number value | %d %d | — | `Math.min(%s, %s)` | ✔ have |
| `mathPi` | number value | — | — | `app logic: Math.PI` | ✔ have |
| `mathPow` | number value | %d %d | — | `Math.pow(%s, %s)` | ✔ have |
| `mathRound` | number value | %d | — | `Math.round(%s)` | ✔ have |
| `mathSin` | number value | %d | — | `Math.sin(%s)` |  |
| `mathSqrt` | number value | %d | — | `Math.sqrt(%s)` | ✔ have |
| `mathTan` | number value | %d | — | `Math.tan(%s)` |  |
| `mathToDegree` | number value | %d | — | `Math.toDegrees(%s)` |  |
| `mathToRadian` | number value | %d | — | `Math.toRadians(%s)` |  |

## File (33)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `copyAssetFile` | text value | %m.inputstream | — | `SketchwareUtil.copyFromInputStream(%s)` |  |
| `cropBitmapFileFromCenter` | statement | %s %s %d %d | — | `app logic: ""` |  |
| `fileutilEndsWith` | boolean value | %s %s | — | `%s.endsWith(%s)` |  |
| `fileutilGetLastSegmentPath` | text value | %s | — | `Uri.parse(%s).getLastPathSegment()` |  |
| `fileutilStartsWith` | boolean value | %s %s | — | `%s.startsWith(%s)` |  |
| `fileutilcopy` | statement | %s %s | — | `app logic: ""` |  |
| `fileutilcopydir` | statement | %s %s | — | `FileUtil.copyDir(%1$s, %2$s);` |  |
| `fileutildelete` | statement | %s | — | `app logic: ""` |  |
| `fileutilisdir` | boolean value | %s | — | `FileUtil.isDirectory(%s)` |  |
| `fileutilisexist` | boolean value | %s | — | `FileUtil.isExistFile(%s)` |  |
| `fileutilisfile` | boolean value | %s | — | `FileUtil.isFile(%s)` |  |
| `fileutillength` | number value | %s | — | `FileUtil.getFileLength(%s)` |  |
| `fileutillistdir` | statement | %s %m.listStr | — | `app logic: ""` |  |
| `fileutilmakedir` | statement | %s | — | `app logic: ""` |  |
| `fileutilmove` | statement | %s %s | — | `app logic: ""` |  |
| `fileutilread` | text value | %s | — | `FileUtil.readFile(%s)` |  |
| `fileutilwrite` | statement | %s %s | — | `app logic: ""` |  |
| `getAssetFile` | statement | %m.inputstream %s | — | `java.io.InputStream %s = getAssets().open(%s);` |  |
| `getExternalStorageDir` | text value | — | — | `app logic: FileUtil.getExternalStorageDir()` |  |
| `getJpegRotate` | number value | %s | — | `FileUtil.getJpegRotate(%s)` |  |
| `getPackageDataDir` | text value | — | — | `app logic: FileUtil.getPackageDataDir(getApplicationContext())` |  |
| `getPublicDir` | text value | %m.directoryType | — | `FileUtil.getPublicDir(Environment.%s)` |  |
| `renameFile` | statement | %s %s | — | `{ java.io.File dYx4Y = new java.io.File(%1$s); java.io.File e5Cyk = new java.io.File(%2$s)` |  |
| `resizeBitmapFileRetainRatio` | statement | %s %s %d | — | `app logic: ""` |  |
| `resizeBitmapFileToCircle` | statement | %s %s | — | `app logic: ""` |  |
| `resizeBitmapFileToSquare` | statement | %s %s %d | — | `app logic: ""` |  |
| `resizeBitmapFileWithRoundedBorder` | statement | %s %s %d | — | `app logic: ""` |  |
| `rotateBitmapFile` | statement | %s %s %d | — | `app logic: ""` |  |
| `scaleBitmapFile` | statement | %s %s %d %d | — | `app logic: ""` |  |
| `setBitmapFileBrightness` | statement | %s %s %d | — | `app logic: ""` |  |
| `setBitmapFileColorFilter` | statement | %s %s %m.color | — | `app logic: ""` |  |
| `setBitmapFileContrast` | statement | %s %s %d | — | `app logic: ""` |  |
| `skewBitmapFile` | statement | %s %s %d %d | — | `app logic: ""` |  |

## View (228)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `EditTextLines` | statement | %m.edittext %d | — | `%s.setLines(%s);` |  |
| `EditTextSelectAll` | statement | %m.edittext | — | `((EditText)%s).selectAll();` |  |
| `EditTextSetMaxLines` | statement | %m.edittext %d | — | `((EditText)%s).setMaxLines((int)%s);` |  |
| `EditTextSetSelection` | statement | %m.edittext %d %d | — | `((EditText)%s).setSelection((int)%s, (int)%s);` |  |
| `EditTextShowError` | statement | %m.edittext %s | — | `((EditText)%s).setError(%s);` |  |
| `EditTextSingleLine` | statement | %m.edittext %b | — | `%s.setSingleLine(%2$s);` |  |
| `EditTextdiableSuggestion` | statement | %m.edittext | — | `%s.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);` |  |
| `EdittextGetselectionEnd` | number value | %m.edittext | — | `%s.getSelectionEnd()` |  |
| `EdittextGetselectionStart` | number value | %m.edittext | — | `%s.getSelectionStart()` |  |
| `ListViewAddFooter` | statement | %m.listview %m.view %s %b | widget:ListView | `%s.addFooterView(%s,%s,%s);` |  |
| `ListViewAddHeader` | statement | %m.listview %m.view %s %b | widget:ListView | `%s.addHeaderView(%s,%s,%s);` |  |
| `ViewPagerNotifyOnDtatChange` | statement | %m.viewpager | widget:ViewPager | `((PagerAdapter)%s.getAdapter()).notifyDataSetChanged();` |  |
| `YTPVLifecycle` | statement | %m.youtubeview | widget:YoutubePlayerView | `getLifecycle().addObserver(%1$s);` |  |
| `YTPVSetListener` | container | %m.youtubeview %s | widget:YoutubePlayerView | `%1$s.addYouTubePlayerListener(new AbstractYouTubePlayerListener() { @Override public void ` |  |
| `addTab` | statement | %m.tablayout %s | widget:TabLayout | `%1$s.addTab(%1$s.newTab().setText(%2$s));` |  |
| `addView` | statement | %m.view %m.view | — | `%s.addView(%s);` |  |
| `addViews` | statement | %m.view %m.view %d | — | `%s.addView(%s, %s);` |  |
| `autoComSetData` | statement | %m.actv %m.listStr | — | `%s.setAdapter(new ArrayAdapter<String>(getBaseContext(), android.R.layout.simple_list_item` |  |
| `bannerAdViewLoadAd` | statement | %m.adview | widget: | `{ AdRequest adRequest = new AdRequest.Builder().build(); %1$s.loadAd(adRequest); }` |  |
| `bottomMenuAddItem` | statement | %m.bottomnavigation %d %s %m.resource | widget:BottomNavigationView | `%s.getMenu().add(0, %s, 0, %s).setIcon(R.drawable.%s);` |  |
| `calendarViewSetDate` | statement | %m.calendarview %d | widget:CalendarView | `app logic: %s.setDate((long)(%s), true, true);` |  |
| `calendarViewSetMinDate` | statement | %m.calendarview %d | widget:CalendarView | `app logic: %s.setMinDate((long)(%s));` |  |
| `calnedarViewSetMaxDate` | statement | %m.calendarview %d | widget:CalendarView | `app logic: %s.setMaxDate((long)(%s));` |  |
| `checkViewVisibility` | boolean value | %m.view %m.visible | — | `%s.getVisibility() == View.%s` |  |
| `closeDrawer` | statement | — | widget: | `app logic: binding.Drawer.closeDrawer(GravityCompat.START);` | ✔ have |
| `codeviewApply` | statement | %m.codeview | widget:CodeView | `%s.apply();` |  |
| `codeviewSetCode` | statement | %m.codeview %s | widget:CodeView | `%s.setCode(%s);` |  |
| `codeviewSetLanguage` | statement | %m.codeview %m.cv | widget:CodeView | `%s.setLanguage(Language.%s);` |  |
| `codeviewSetTheme` | statement | %m.codeview %m.cv | widget:CodeView | `%s.setTheme(Theme.%s);` |  |
| `fabIcon` | statement | %m.resource | widget: | `app logic: \.9` |  |
| `fabSize` | statement | %m.fabsize | widget: | `app logic: %s.setSize(FloatingActionButton.SIZE_%s);` |  |
| `fabVisibility` | statement | %m.fabvisible | widget: | `app logic: %s.%s();` |  |
| `getAlpha` | number value | %m.view | — | `%s.getAlpha()` |  |
| `getBadgeCount` | number value | %m.badgeview | widget:BadgeView | `%s.getBadgeCount();` |  |
| `getChecked` | boolean value | %m.checkbox | — | `%s.isChecked()` | ✔ have |
| `getEnable` | boolean value | %m.view | — | `%s.isEnabled()` | ✔ have |
| `getHeight` | number value | %m.view | — | `%s.getHeight()` |  |
| `getLocationX` | number value | %m.view | — | `SketchwareUtil.getLocationX(%s)` |  |
| `getLocationY` | number value | %m.view | — | `SketchwareUtil.getLocationY(%s)` |  |
| `getRating` | number value | %m.ratingbar | — | `%s.getRating()` |  |
| `getRotate` | number value | %m.view | — | `%s.getRotation()` |  |
| `getScaleX` | number value | %m.view | — | `%s.getScaleX()` |  |
| `getScaleY` | number value | %m.view | — | `%s.getScaleY()` |  |
| `getText` | text value | %m.textview | — | `%s.getText().toString()` | ✔ have |
| `getTranslationX` | number value | %m.view | — | `%s.getTranslationX()` |  |
| `getTranslationY` | number value | %m.view | — | `%s.getTranslationY()` |  |
| `getWidth` | number value | %m.view | — | `%s.getWidth()` |  |
| `gridSetColumnWidth` | statement | %m.gridview %d | widget:GridView/GridView | `%s.setColumnWidth((int)%s);` |  |
| `gridSetCustomViewData` | statement | %m.gridview %m.listMap | widget:GridView/GridView | `app logic: binding.` |  |
| `gridSetHorizontalSpacing` | statement | %m.gridview %d | widget:GridView/GridView | `%s.setHorizontalSpacing((int)%s);` |  |
| `gridSetNumColumns` | statement | %m.gridview %d | widget:GridView/GridView | `%s.setNumColumns((int)%s);` |  |
| `gridSetStretchMode` | statement | %m.gridview %m.gridstretchmode | widget:GridView/GridView | `%s.setStretchMode(GridView.%s);` |  |
| `gridSetVerticalSpacing` | statement | %m.gridview %d | widget:GridView/GridView | `%s.setVerticalSpacing((int)%s);` |  |
| `isDrawerOpen` | boolean value | — | widget: | `app logic: binding.Drawer.isDrawerOpen(GravityCompat.START)` |  |
| `listRefresh` | statement | %m.listview | widget:ListView | `app logic: ((BaseAdapter)%s.getAdapter()).notifyDataSetChanged();` |  |
| `listSetCustomViewData` | statement | %m.listview %m.listMap | widget:ListView | `app generator case (dynamic)` |  |
| `listSetData` | statement | %m.listview %m.listStr | widget:ListView | `app logic: %s.setAdapter(new ArrayAdapter<String>(getBaseContext(), android.R.lay` | ✔ have |
| `listSetStackFromBottom` | statement | %m.listview %b | widget:ListView | `%s.setStackFromBottom(%s);` |  |
| `listSetTranscriptMode` | statement | %m.listview %m.transcriptmode | widget:ListView | `%s.setTranscriptMode(ListView.%s);` |  |
| `listSmoothScrollTo` | statement | %m.listview %d | widget:ListView | `app logic: %s.smoothScrollToPosition((int)(%s));` |  |
| `listViewRemoveFooter` | statement | %m.listview %m.view | widget:ListView | `%s.removeFooterView(%s);` |  |
| `listViewRemoveHeader` | statement | %m.listview %m.view | widget:ListView | `%s.removeHeaderView(%s);` |  |
| `listViewSetSelection` | statement | %m.listview %d | widget:ListView | `%s.setSelection((int)%s);` |  |
| `lottieSetAnimationFromAsset` | statement | %m.lottie %s | widget:LottieAnimationView | `%s.setAnimation(%s);` |  |
| `lottieSetAnimationFromJson` | statement | %m.lottie %s | widget:LottieAnimationView | `%s.setAnimationFromJson(%s);` |  |
| `lottieSetAnimationFromUrl` | statement | %m.lottie %s | widget:LottieAnimationView | `%s.setAnimationFromUrl(%s);` |  |
| `lottieSetRepeatCount` | statement | %m.lottie %d | widget:LottieAnimationView | `%s.setRepeatCount((int)%s);` |  |
| `lottieSetSpeed` | statement | %m.lottie %d | widget:LottieAnimationView | `%s.setSpeed((float)%s);` |  |
| `mapViewAddMarker` | statement | %m.mapview %s %d %d | widget:MapView | `app logic: _%s_controller.addMarker(%s, %s, %s);` |  |
| `mapViewMoveCamera` | statement | %m.mapview %d %d | widget:MapView | `app logic: _%s_controller.moveCamera(%s, %s);` |  |
| `mapViewSetMapType` | statement | %m.mapview %m.mapType | widget:MapView | `app logic: _%s_controller.setMapType(GoogleMap.%s);` |  |
| `mapViewSetMarkerColor` | statement | %m.mapview %s %m.markerColor %d | widget:MapView | `app logic: _%s_controller.setMarkerColor(%s, BitmapDescriptorFactory.%s, %s);` |  |
| `mapViewSetMarkerIcon` | statement | %m.mapview %s %m.resource | widget:MapView | `app logic: .9` |  |
| `mapViewSetMarkerInfo` | statement | %m.mapview %s %s %s | widget:MapView | `app logic: _%s_controller.setMarkerInfo(%s, %s, %s);` |  |
| `mapViewSetMarkerPosition` | statement | %m.mapview %s %d %d | widget:MapView | `app logic: _%s_controller.setMarkerPosition(%s, %s, %s);` |  |
| `mapViewSetMarkerVisible` | statement | %m.mapview %s %b | widget:MapView | `app logic: _%s_controller.setMarkerVisible(%s, %s);` |  |
| `mapViewZoomIn` | statement | %m.mapview | widget:MapView | `app logic: _%s_controller.zoomIn();` |  |
| `mapViewZoomOut` | statement | %m.mapview | widget:MapView | `app logic: _%s_controller.zoomOut();` |  |
| `mapViewZoomTo` | statement | %m.mapview %d | widget:MapView | `app logic: _%s_controller.zoomTo(%s);` |  |
| `multiAutoComSetData` | statement | %m.mactv %m.listStr | — | `%s.setAdapter(new ArrayAdapter<String>(getBaseContext(), android.R.layout.simple_list_item` |  |
| `onSwipeRefreshLayout` | container | %m.swiperefreshlayout | widget:SwipeRefreshLayout | `%s.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() { @Override public void` |  |
| `openDrawer` | statement | — | widget: | `app logic: binding.Drawer.openDrawer(GravityCompat.START);` | ✔ have |
| `otpViewGetOTPText` | text value | %m.otpview | widget:OTPView | `%1$s.getOTPText()` |  |
| `otpViewSetFieldCount` | statement | %m.otpview %d | widget:OTPView | `%1$s.setFieldCount(%2$s);` |  |
| `otpViewSetOTPListener` | container | %m.otpview | widget:OTPView | `%1$s.setOTPListener(new OTPListener() { public void onOTPEntered(String _otp) { %2$s } });` |  |
| `otpViewSetOTPText` | statement | %m.otpview %s | widget:OTPView | `%1$s.setOTPText(%2$s);` |  |
| `pagerGetCurrentItem` | number value | %m.viewpager | widget:ViewPager | `%s.getCurrentItem()` |  |
| `pagerGetOffscreenPageLimit` | number value | %m.viewpager | widget:ViewPager | `%s.getOffscreenPageLimit()` |  |
| `pagerSetCurrentItem` | statement | %m.viewpager %d | widget:ViewPager | `%s.setCurrentItem((int)%s);` |  |
| `pagerSetCustomViewData` | statement | %m.viewpager %m.listMap | widget:ViewPager | `app generator case (dynamic)` |  |
| `pagerSetFragmentAdapter` | statement | %m.viewpager %m.fragmentAdapter %d | widget:ViewPager | `%2$s.setTabCount(%3$s); %1$s.setAdapter(%2$s);` |  |
| `pagerSetOffscreenPageLimit` | statement | %m.viewpager %d | widget:ViewPager | `%s.setOffscreenPageLimit((int)%s);` |  |
| `patternLockClear` | statement | %m.patternview | widget:PatternLockView | `%s.clearPattern();` |  |
| `patternSetCorrectStateColor` | statement | %m.patternview %m.color | widget:PatternLockView | `%s.setCorrectStateColor(%s);` |  |
| `patternSetDotCount` | statement | %m.patternview %d | widget:PatternLockView | `%s.setDotCount((int)%s);` |  |
| `patternSetNormalStateColor` | statement | %m.patternview %m.color | widget:PatternLockView | `%s.setNormalStateColor(%s);` |  |
| `patternSetViewMode` | statement | %m.patternview %m.patternviewmode | widget:PatternLockView | `%s.setViewMode(PatternLockView.PatternViewMode.%s);` |  |
| `patternSetWrongStateColor` | statement | %m.patternview %m.color | widget:PatternLockView | `%s.setWrongStateColor(%s);` |  |
| `patternToMD5` | text value | %m.patternview %m.listStr | widget:PatternLockView | `PatternLockUtils.patternToMD5(%s, %s)` |  |
| `patternToSha1` | text value | %m.patternview %m.listStr | widget:PatternLockView | `PatternLockUtils.patternToSha1(%s, %s)` |  |
| `patternToString` | text value | %m.patternview %m.listStr | widget:PatternLockView | `PatternLockUtils.patternToString(%s, %s)` |  |
| `performClick` | statement | %m.view | — | `%s.performClick();` |  |
| `progressBarSetIndeterminate` | statement | %m.progressbar %b | — | `app logic: %s.setIndeterminate(%s);` |  |
| `recyclerScrollToPositionWithOffset` | statement | %m.recyclerview %d %d | widget:RecyclerView | `((LinearLayoutManager) %s.getLayoutManager()).scrollToPositionWithOffset((int)%s, (int)%s)` |  |
| `recyclerSetCustomViewData` | statement | %m.recyclerview %m.listMap | widget:RecyclerView | `app generator case (dynamic)` |  |
| `recyclerSetHasFixedSize` | statement | %m.recyclerview %b | widget:RecyclerView | `%s.setHasFixedSize(%s);` |  |
| `recyclerSetLayoutManager` | statement | %m.recyclerview | widget:RecyclerView | `%s.setLayoutManager(new LinearLayoutManager(this));` |  |
| `recyclerSetLayoutManagerHorizontal` | statement | %m.recyclerview | widget:RecyclerView | `%s.setLayoutManager(new LinearLayoutManager(this,LinearLayoutManager.HORIZONTAL, false));` |  |
| `recyclerSmoothScrollToPosition` | statement | %m.recyclerview %d | widget:RecyclerView | `%s.smoothScrollToPosition((int)%s);` |  |
| `refreshingList` | statement | %m.listview | widget:ListView | `%s.invalidateViews();` |  |
| `removeView` | statement | %m.view %m.view | — | `%s.removeView(%s);` |  |
| `removeViews` | statement | %m.view | — | `%s.removeAllViews();` |  |
| `requestFocus` | statement | %m.view | — | `app logic: %s.requestFocus();` |  |
| `seekBarGetMax` | number value | %m.seekbar | — | `%s.getMax()` |  |
| `seekBarGetProgress` | number value | %m.seekbar | — | `%s.getProgress()` | ✔ have |
| `seekBarSetMax` | statement | %m.seekbar %d | — | `app logic: %s.setMax((int)%s);` |  |
| `seekBarSetProgress` | statement | %m.seekbar %d | — | `app logic: %s.setProgress((int)%s);` | ✔ have |
| `setAlpha` | statement | %m.view %d | — | `app logic: %s.setAlpha((float)(%s));` |  |
| `setBadgeBackground` | statement | %m.badgeview %m.color | widget:BadgeView | `%s.setBadgeBackground(%s);` |  |
| `setBadgeNumber` | statement | %m.badgeview %d | widget:BadgeView | `%s.setBadgeCount(%s);` |  |
| `setBadgeString` | statement | %m.badgeview %s | widget:BadgeView | `%s.setBadgeCount(%s);` |  |
| `setBadgeTextColor` | statement | %m.badgeview %m.color | widget:BadgeView | `%s.setTextColor(%s);` |  |
| `setBadgeTextSize` | statement | %m.badgeview %d | widget:BadgeView | `%s.setTextSize((int)%s);` |  |
| `setBgColor` | statement | %m.view %m.color | — | `app logic: %s.setBackgroundColor(%s);` |  |
| `setBgDrawable` | statement | %m.view %m.drawable | — | `%s.setBackgroundDrawable(getResources().getDrawable(R.drawable.%s));` |  |
| `setBgResource` | statement | %m.view %m.resource_bg | — | `app logic: NONE` |  |
| `setBubbleArrowHeight` | statement | %m.view %d | widget:BubbleLayout | `%s.setArrowHeight((float)%s);` |  |
| `setBubbleArrowPosition` | statement | %m.view %d | widget:BubbleLayout | `%s.setArrowPosition((float)%s);` |  |
| `setBubbleArrowWidth` | statement | %m.view %d | widget:BubbleLayout | `%s.setArrowWidth((float)%s);` |  |
| `setBubbleColor` | statement | %m.view %m.color | widget:BubbleLayout | `%s.setBubbleColor(%s);` |  |
| `setBubbleCornerRadius` | statement | %m.view %d | widget:BubbleLayout | `%s.setCornersRadius((float)%s);` |  |
| `setBubbleStrokeColor` | statement | %m.view %m.color | widget:BubbleLayout | `%s.setStrokeColor(%s);` |  |
| `setBubbleStrokeWidth` | statement | %m.view %d | widget:BubbleLayout | `%s.setStrokeWidth((float)%s);` |  |
| `setCardBackgroundColor` | statement | %m.cardview %m.color | widget:CardView | `%s.setCardBackgroundColor(%s);` |  |
| `setCardElevation` | statement | %m.cardview %d | widget:CardView | `%s.setCardElevation((float)%s);` |  |
| `setCardRadius` | statement | %m.cardview %d | widget:CardView | `%s.setRadius((float)%s);` |  |
| `setChecked` | statement | %m.checkbox %b | — | `app logic: %s.setChecked(%s);` |  |
| `setColorFilter` | statement | %m.imageview %m.color | — | `app logic: %s.setColorFilter(%s, PorterDuff.Mode.MULTIPLY);` |  |
| `setColorFilterView` | statement | %m.view %m.color %m.porterduff | — | `%s.getBackground().setColorFilter(%s, PorterDuff.Mode.%s);` |  |
| `setCornerRadiusView` | statement | %m.view %d %m.color | — | `%s.setBackground(new GradientDrawable() { public GradientDrawable getIns(int a, int b) { t` |  |
| `setCustomLetter` | statement | %m.sidebar %s.inputOnly | widget:WaveSideBar | `%s.setCustomLetter(new String[]%s);` |  |
| `setElevation` | statement | %m.view %d | — | `%s.setElevation((float)%s);` |  |
| `setEnable` | statement | %m.view %b | — | `app logic: %s.setEnabled(%s);` | ✔ have |
| `setGradientBackground` | statement | %m.view %m.color %m.color | — | `%s.setBackground(new GradientDrawable(GradientDrawable.Orientation.BR_TL, new int[] {%s,%s` |  |
| `setGravity` | statement | %m.view %m.gravity %m.gravity | — | `%s.setGravity(Gravity.%s / Gravity.%s);` |  |
| `setHint` | statement | %m.edittext %s | — | `app logic: ""` | ✔ have |
| `setHintTextColor` | statement | %m.edittext %m.color | — | `app logic: ""` |  |
| `setImage` | statement | %m.imageview %m.resource | — | `app logic: \.9` |  |
| `setImageCustomRes` | statement | %m.imageview %m.image | — | `%s.setImageResource(R.drawable.%s);` |  |
| `setImageFilePath` | statement | %m.imageview %s | — | `app logic: ""` |  |
| `setImageIdentifier` | statement | %m.imageview %s | — | `%s.setImageResource(getResources().getIdentifier(%s, drawable, getPackageName()));` |  |
| `setImageUrl` | statement | %m.imageview %s | — | `app logic: ""` | ✔ have |
| `setInlineLabel` | statement | %m.tablayout %b | widget:TabLayout | `%s.setInlineLabel(%s);` |  |
| `setNumStars` | statement | %m.ratingbar %d | — | `%s.setNumStars((int)%s);` |  |
| `setPreventCornerOverlap` | statement | %m.cardview %b | widget:CardView | `%s.setPreventCornerOverlap(%s);` |  |
| `setRadiusAndStrokeView` | statement | %m.view %d %d %m.color %m.color | — | `%s.setBackground(new GradientDrawable() { public GradientDrawable getIns(int a, int b, int` |  |
| `setRating` | statement | %m.ratingbar %d | — | `%s.setRating((float)%s);` |  |
| `setRecyclerViewLayoutParams` | statement | %m.LayoutParam %m.LayoutParam | — | `RecyclerView.LayoutParams _lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.%s, V` |  |
| `setRefreshing` | statement | %m.swiperefreshlayout %b | widget:SwipeRefreshLayout | `%s.setRefreshing(%s);` |  |
| `setRotate` | statement | %m.view %d | — | `app logic: %s.setRotation((float)(%s));` |  |
| `setScaleX` | statement | %m.view %d | — | `app logic: %s.setScaleX((float)(%s));` |  |
| `setScaleY` | statement | %m.view %d | — | `app logic: %s.setScaleY((float)(%s));` |  |
| `setSelectedTabIndicatorColor` | statement | %m.tablayout %m.color | widget:TabLayout | `%s.setSelectedTabIndicatorColor(%s);` |  |
| `setSelectedTabIndicatorHeight` | statement | %m.tablayout %d | widget:TabLayout | `%s.setSelectedTabIndicatorHeight(%s);` |  |
| `setStepSize` | statement | %m.ratingbar %d | — | `%s.setStepSize((float)%s);` |  |
| `setStrokeView` | statement | %m.view %d %m.color %m.color | — | `%s.setBackground(new GradientDrawable() { public GradientDrawable getIns(int a, int b, int` |  |
| `setTabRippleColor` | statement | %m.tablayout %m.color | widget:TabLayout | `%s.setTabRippleColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.` |  |
| `setTabTextColors` | statement | %m.tablayout %m.color %m.color | widget:TabLayout | `%s.setTabTextColors(%s, %s);` |  |
| `setText` | statement | %m.textview %s | — | `app logic: %s.setText(%s);` | ✔ have |
| `setTextColor` | statement | %m.textview %m.color | — | `app logic: %s.setTextColor(%s);` | ✔ have |
| `setTextSize` | statement | %m.textview %d | — | `%s.setTextSize((int)%s);` |  |
| `setThreshold` | statement | %m.mactv %d | — | `%s.setThreshold(%s);` |  |
| `setTokenizer` | statement | %m.mactv | — | `%s.setTokenizer(new MultiAutoCompleteTextView.CommaTokenizer());` |  |
| `setTranslationX` | statement | %m.view %d | — | `app logic: %s.setTranslationX((float)(%s));` |  |
| `setTranslationY` | statement | %m.view %d | — | `app logic: %s.setTranslationY((float)(%s));` |  |
| `setTypeface` | statement | %m.textview %m.font %m.typeface | — | `app logic: property_text_style` |  |
| `setUseCompatPadding` | statement | %m.cardview %b | widget:CardView | `%s.setUseCompatPadding(%s);` |  |
| `setVisible` | statement | %m.view %m.visible | — | `app logic: %s.setVisibility(View.%s);` | ✔ have |
| `setupWithViewPager` | statement | %m.tablayout %m.viewpager | widget:TabLayout | `%s.setupWithViewPager(%s);` |  |
| `showSnackbar` | container | %m.view %s %s | — | `com.google.android.material.snackbar.Snackbar.make(%s, %s, com.google.android.material.sna` |  |
| `signInButtonSetColorScheme` | statement | %m.signinbutton %m.SignButtonColor | widget:SignInButton | `%1$s.setColorScheme(SignInButton.%2$s);` |  |
| `signInButtonSetSize` | statement | %m.signinbutton %m.SignButtonSize | widget:SignInButton | `%1$s.setSize(SignInButton.%2$s);` |  |
| `spnGetSelection` | number value | %m.spinner | widget:Spinner | `%s.getSelectedItemPosition()` |  |
| `spnRefresh` | statement | %m.spinner | widget:Spinner | `app logic: ((ArrayAdapter)%s.getAdapter()).notifyDataSetChanged();` |  |
| `spnSetCustomViewData` | statement | %m.spinner %m.listMap | widget:Spinner | `app generator case (dynamic)` |  |
| `spnSetData` | statement | %m.spinner %m.listStr | widget:Spinner | `app logic: %s.setAdapter(new ArrayAdapter<String>(getBaseContext(), android.R.lay` |  |
| `spnSetSelection` | statement | %m.spinner %d | widget:Spinner | `app logic: %s.setSelection((int)(%s));` |  |
| `tilGetCounterMaxLength` | number value | %m.textinputlayout | widget:TextInputLayout/TextInputLayout | `%s.getCounterMaxLength()` |  |
| `tilSetBoxBgColor` | statement | %m.textinputlayout %m.color | widget:TextInputLayout/TextInputLayout | `%s.setBoxBackgroundColor(%s);` |  |
| `tilSetBoxBgMode` | statement | %m.textinputlayout %m.til | widget:TextInputLayout/TextInputLayout | `%s.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_%s);` |  |
| `tilSetBoxCornerRadii` | statement | %m.textinputlayout %d %d %d %d | widget:TextInputLayout/TextInputLayout | `%s.setBoxCornerRadii((float)%s, (float)%s, (float)%s, (float)%s);` |  |
| `tilSetBoxStrokeColor` | statement | %m.textinputlayout %m.color | widget:TextInputLayout/TextInputLayout | `%s.setBoxStrokeColor(%s);` |  |
| `tilSetCounterEnabled` | statement | %m.textinputlayout %b | widget:TextInputLayout/TextInputLayout | `%s.setCounterEnabled(%s);` |  |
| `tilSetCounterMaxLength` | statement | %m.textinputlayout %d | widget:TextInputLayout/TextInputLayout | `%s.setCounterMaxLength(%s);` |  |
| `tilSetError` | statement | %m.textinputlayout %s | widget:TextInputLayout/TextInputLayout | `%s.setError(%s);` |  |
| `tilSetErrorEnabled` | statement | %m.textinputlayout %b | widget:TextInputLayout/TextInputLayout | `%s.setErrorEnabled(%s);` |  |
| `timepickerSetCurrentHour` | statement | %m.timepicker %d | widget:TimePicker | `%s.setCurrentHour((int)%s);` |  |
| `timepickerSetCurrentMinute` | statement | %m.timepicker %d | widget:TimePicker | `%s.setCurrentMinute((int)%s);` |  |
| `timepickerSetHour` | statement | %m.timepicker %d | widget:TimePicker | `%s.setHour((int)%s);` |  |
| `timepickerSetIs24Hour` | statement | %m.timepicker %b | widget:TimePicker | `%s.setIs24HourView(%s);` |  |
| `timepickerSetMinute` | statement | %m.timepicker %d | widget:TimePicker | `%s.setMinute((int)%s);` |  |
| `videoviewCanPause` | boolean value | %m.videoview | — | `%s.canPause()` |  |
| `videoviewCanSeekBackward` | boolean value | %m.videoview | — | `%s.canSeekBackward()` |  |
| `videoviewCanSeekForward` | boolean value | %m.videoview | — | `%s.canSeekForward()` |  |
| `videoviewGetCurrentPosition` | number value | %m.videoview | — | `%s.getCurrentPosition()` |  |
| `videoviewGetDuration` | number value | %m.videoview | — | `%s.getDuration()` |  |
| `videoviewIsPlaying` | boolean value | %m.videoview | — | `%s.isPlaying()` |  |
| `videoviewPause` | statement | %m.videoview | — | `%s.pause();` |  |
| `videoviewSetVideoUri` | statement | %m.videoview %s | — | `%s.setVideoURI(Uri.parse(%s));` |  |
| `videoviewStart` | statement | %m.videoview | — | `%s.start();` |  |
| `videoviewStop` | statement | %m.videoview | — | `%s.stopPlayback();` |  |
| `viewGetChildAt` | variable value | %m.view %d | — | `%1$s.getChildAt(%2$s)` |  |
| `viewOnClick` | container | %m.view | — | `%s.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View _vie` |  |
| `viewOnLongClick` | container | %m.view | — | `%s.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLong` |  |
| `viewOnTouch` | container | %m.view | — | `%s.setOnTouchListener(new View.OnTouchListener(){ @Override public boolean onTouch(View _v` |  |
| `webViewCanGoBack` | boolean value | %m.webview | — | `%s.canGoBack()` |  |
| `webViewCanGoForward` | boolean value | %m.webview | — | `%s.canGoForward()` |  |
| `webViewClearCache` | statement | %m.webview | — | `app logic: %s.clearCache(true);` |  |
| `webViewClearHistory` | statement | %m.webview | — | `app logic: %s.clearHistory();` |  |
| `webViewGetUrl` | text value | %m.webview | — | `%s.getUrl()` | ✔ have |
| `webViewGoBack` | statement | %m.webview | — | `app logic: %s.goBack();` |  |
| `webViewGoForward` | statement | %m.webview | — | `app logic: %s.goForward();` |  |
| `webViewLoadUrl` | statement | %m.webview %s.url | — | `app logic: %s.loadUrl(%s);` | ✔ have |
| `webViewSetCacheMode` | statement | %m.webview %m.cacheMode | — | `app logic: %s.getSettings().setCacheMode(WebSettings.%s);` |  |
| `webViewStopLoading` | statement | %m.webview | — | `app logic: %s.stopLoading();` |  |
| `webViewZoomIn` | statement | %m.webview | — | `app logic: %s.zoomIn();` |  |
| `webViewZoomOut` | statement | %m.webview | — | `app logic: %s.zoomOut();` |  |
| `webviewGetProgress` | number value | %m.webview | — | `%1$s.getProgress()` |  |

## Component (140)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `AsyncTaskExecute` | statement | %m.asynctask %s | — | `new %s().execute(%s);` |  |
| `AsyncTaskPublishProgress` | statement | %d | — | `publishProgress((int)%s);` |  |
| `Dialog SetIcon` | statement | %m.dialog %m.resource | component: DIALOG | `%s.setIcon(R.drawable.%s);` |  |
| `LightStatusBar` | statement | — | — | `getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR); ge` |  |
| `bluetoothConnectActivateBluetooth` | statement | %m.bluetoothconnect | component: BLUETOOTH_CONNECT | `app logic: %s.activateBluetooth();` |  |
| `bluetoothConnectGetPairedDevices` | statement | %m.bluetoothconnect %m.listMap | component: BLUETOOTH_CONNECT | `app logic: %s.getPairedDevices(%s);` |  |
| `bluetoothConnectGetRandomUuid` | text value | %m.bluetoothconnect | component: BLUETOOTH_CONNECT | `app logic: .getRandomUUID()` |  |
| `bluetoothConnectIsBluetoothActivated` | boolean value | %m.bluetoothconnect | component: BLUETOOTH_CONNECT | `%s.isBluetoothActivated()` |  |
| `bluetoothConnectIsBluetoothEnabled` | boolean value | %m.bluetoothconnect | component: BLUETOOTH_CONNECT | `%s.isBluetoothEnabled()` |  |
| `bluetoothConnectReadyConnection` | statement | %m.bluetoothconnect %s | component: BLUETOOTH_CONNECT | `app logic: %s.readyConnection(_%s_bluetooth_connection_listener, %s);` |  |
| `bluetoothConnectReadyConnectionToUuid` | statement | %m.bluetoothconnect %s %s | component: BLUETOOTH_CONNECT | `app logic: %s.readyConnection(_%s_bluetooth_connection_listener, %s, %s);` |  |
| `bluetoothConnectSendData` | statement | %m.bluetoothconnect %s %s | component: BLUETOOTH_CONNECT | `app logic: %s.sendData(_%s_bluetooth_connection_listener, %s, %s);` |  |
| `bluetoothConnectStartConnection` | statement | %m.bluetoothconnect %s %s | component: BLUETOOTH_CONNECT | `app logic: %s.startConnection(_%s_bluetooth_connection_listener, %s, %s);` |  |
| `bluetoothConnectStartConnectionToUuid` | statement | %m.bluetoothconnect %s %s %s | component: BLUETOOTH_CONNECT | `app logic: %s.startConnection(_%s_bluetooth_connection_listener, %s, %s, %s);` |  |
| `bluetoothConnectStopConnection` | statement | %m.bluetoothconnect %s | component: BLUETOOTH_CONNECT | `app logic: %s.stopConnection(_%s_bluetooth_connection_listener, %s);` |  |
| `calendarAdd` | statement | %m.calendar %m.calendarField %d | component: CALENDAR | `app logic: %s.add(Calendar.%s, (int)(%s));` |  |
| `calendarDiff` | number value | %m.calendar %m.calendar | component: CALENDAR | `(long)(%s.getTimeInMillis() - %s.getTimeInMillis())` |  |
| `calendarFormat` | text value | %m.calendar %s | component: CALENDAR | `new SimpleDateFormat(%s).format(%s.getTime())` |  |
| `calendarGetNow` | statement | %m.calendar | component: CALENDAR | `app logic: %s = Calendar.getInstance();` |  |
| `calendarGetTime` | number value | %m.calendar | component: CALENDAR | `%s.getTimeInMillis()` |  |
| `calendarSet` | statement | %m.calendar %m.calendarField %d | component: CALENDAR | `app logic: %s.set(Calendar.%s, (int)(%s));` |  |
| `calendarSetTime` | statement | %m.calendar %d | component: CALENDAR | `app logic: %s.setTimeInMillis((long)(%s));` |  |
| `camerastarttakepicture` | statement | %m.camera | component: CAMERA | `app logic: startActivityForResult(%s, REQ_CD_%s);` |  |
| `changeStatebarColour` | statement | %m.activity %m.color | — | `if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) { final Window window = %s.this.ge` |  |
| `copyToClipboard` | statement | %s | — | `app logic: ((ClipboardManager) getSystemService(getApplicationContext().CLIPBOARD` | ✔ have |
| `datePickerDialogShow` | statement | — | component: DATE_PICKER_DIALOG | `DialogFragment datePicker = new DatePickerFragment(); datePicker.show(getSupportFragmentMa` |  |
| `dialogCancelButton` | container | %m.dialog %s | component: DIALOG | `app logic: %s.setNegativeButton(%s, new DialogInterface.OnClickListener() { @Over` |  |
| `dialogNeutralButton` | container | %m.dialog %s | component: DIALOG | `app logic: %s.setNeutralButton(%s, new DialogInterface.OnClickListener() { @Overr` |  |
| `dialogOkButton` | container | %m.dialog %s | component: DIALOG | `app logic: %s.setPositiveButton(%s, new DialogInterface.OnClickListener() { @Over` |  |
| `dialogSetMessage` | statement | %m.dialog %s | component: DIALOG | `app logic: %s.setMessage(%s);` |  |
| `dialogSetTitle` | statement | %m.dialog %s | component: DIALOG | `app logic: %s.setTitle(%s);` |  |
| `dialogShow` | statement | %m.dialog | component: DIALOG | `app logic: %s.create().show();` |  |
| `doToast` | statement | %s | — | `app logic: SketchwareUtil.showMessage(getApplicationContext(), %s);` | ✔ have |
| `fileContainsData` | boolean value | %m.file %s | component: SHAREDPREF | `%1$s.contains(%2$s)` |  |
| `fileGetData` | text value | %m.file %s | component: SHAREDPREF | `%s.getString(%s, "")` |  |
| `fileRemoveData` | statement | %m.file %s | component: SHAREDPREF | `app logic: %s.edit().remove(%s).commit();` |  |
| `fileSetData` | statement | %m.file %s %s | component: SHAREDPREF | `app logic: %s.edit().putString(%s, %s).commit();` |  |
| `filepickerstartpickfiles` | statement | %m.filepicker | component: FILE_PICKER | `app logic: startActivityForResult(%s, REQ_CD_%s);` |  |
| `finishActivity` | end/stop | — | — | `app logic: finish();` | ✔ have |
| `finishAffinity` | end/stop | — | — | `finishAffinity();` |  |
| `firebaseAdd` | statement | %m.firebase %s %m.varMap | component: FIREBASE | `app logic: %s.child(%s).updateChildren(%s);` |  |
| `firebaseDelete` | statement | %m.firebase %s | component: FIREBASE | `app logic: %s.child(%s).removeValue();` |  |
| `firebaseGetChildren` | container | %m.firebase %m.listMap | component: FIREBASE | `app logic: %s.addListenerForSingleValueEvent(new ValueEventListener() { @Override` |  |
| `firebaseGetPushKey` | text value | %m.firebase | component: FIREBASE | `%s.push().getKey()` |  |
| `firebasePush` | statement | %m.firebase %m.varMap | component: FIREBASE | `app logic: %s.push().updateChildren(%s);` |  |
| `firebaseStartListen` | statement | %m.firebase | component: FIREBASE | `app logic: %s.addChildEventListener(_%s_child_listener);` |  |
| `firebaseStopListen` | statement | %m.firebase | component: FIREBASE | `app logic: %s.removeEventListener(_%s_child_listener);` |  |
| `firebaseauthCreateUser` | statement | %m.firebaseauth %s %s | component: FIREBASE_AUTH | `app logic: ""` |  |
| `firebaseauthGetCurrentUser` | text value | — | component: FIREBASE_AUTH | `app logic: FirebaseAuth.getInstance().getCurrentUser().getEmail()` |  |
| `firebaseauthGetUid` | text value | — | component: FIREBASE_AUTH | `app logic: FirebaseAuth.getInstance().getCurrentUser().getUid()` |  |
| `firebaseauthIsLoggedIn` | boolean value | — | component: FIREBASE_AUTH | `app logic: (FirebaseAuth.getInstance().getCurrentUser() != null)` |  |
| `firebaseauthResetPassword` | statement | %m.firebaseauth %s | component: FIREBASE_AUTH | `app logic: ""` |  |
| `firebaseauthSignInAnonymously` | statement | %m.firebaseauth | component: FIREBASE_AUTH | `app logic: %s.signInAnonymously().addOnCompleteListener(%s.this, %s);` |  |
| `firebaseauthSignInUser` | statement | %m.firebaseauth %s %s | component: FIREBASE_AUTH | `app logic: ""` |  |
| `firebaseauthSignOutUser` | statement | — | component: FIREBASE_AUTH | `app logic: FirebaseAuth.getInstance().signOut();` |  |
| `firebasestorageDelete` | statement | %m.firebasestorage %s | component: FIREBASE_STORAGE | `app logic: ""` |  |
| `firebasestorageDownloadFile` | statement | %m.firebasestorage %s %s | component: FIREBASE_STORAGE | `app logic: ""` |  |
| `firebasestorageUploadFile` | statement | %m.firebasestorage %s %s | component: FIREBASE_STORAGE | `app logic: ""` |  |
| `gyroscopeStartListen` | statement | %m.gyroscope | component: GYROSCOPE | `app logic: %s.registerListener(_%s_sensor_listener, %s.getDefaultSensor(Sensor.TY` |  |
| `gyroscopeStopListen` | statement | %m.gyroscope | component: GYROSCOPE | `app logic: %s.unregisterListener(_%s_sensor_listener);` |  |
| `hideKeyboard` | statement | — | — | `SketchwareUtil.hideKeyboard(getApplicationContext());` |  |
| `imageCrop` | statement | %s %d | component: FILE_PICKER | `SketchwareUtil.CropImage(this, %s, (int) %s);` |  |
| `intentGetString` | text value | %s | — | `getIntent().getStringExtra(%s)` | ✔ have |
| `intentHasExtra` | boolean value | %s | — | `getIntent().hasExtra(%s)` |  |
| `intentPutExtra` | statement | %m.intent %s %s | component: INTENT | `app logic: %s.putExtra(%s, %s);` |  |
| `intentRemoveExtra` | statement | %m.intent %s | component: INTENT | `%s.removeExtra(%s);` |  |
| `intentSetAction` | statement | %m.intent %m.intentAction | component: INTENT | `app logic: %s.setAction(%s);` |  |
| `intentSetData` | statement | %m.intent %s.intentData | component: INTENT | `app logic: %s.setData(Uri.parse(%s));` |  |
| `intentSetFlags` | statement | %m.intent %m.intentFlags | component: INTENT | `app logic: %s.setFlags(%s);` |  |
| `intentSetScreen` | statement | %m.intent %m.activity | component: INTENT | `app logic: %s.setClass(getApplicationContext(), %s.class);` | ✔ have |
| `intentSetType` | statement | %m.intent %s | component: INTENT | `%s.setType(%s);` |  |
| `interstitialAdIsLoaded` | boolean value | %m.interstitialad | component: INTERSTITIAL_AD | `%1$s != null` |  |
| `interstitialAdLoad` | statement | %m.interstitialad %m.activity | component: INTERSTITIAL_AD | `{ AdRequest adRequest = new AdRequest.Builder().build(); InterstitialAd.load(%2$s.this, _a` |  |
| `interstitialAdShow` | statement | %m.interstitialad %m.activity | component: INTERSTITIAL_AD | `if (%1$s != null) { %1$s.show(%2$s.this); } else { SketchwareUtil.showMessage(getApplicati` |  |
| `isConnected` | statement | — | component: REQUEST_NETWORK | `SketchwareUtil.isConnected(getApplicationContext())` |  |
| `launchApp` | statement | %m.intent %s | component: INTENT | `%s = getPackageManager().getLaunchIntentForPackage(%s);` |  |
| `locationManagerRemoveUpdates` | statement | %m.locationmanager | component: LOCATION_MANAGER | `app logic: .removeUpdates(_` |  |
| `locationManagerRequestLocationUpdates` | statement | %m.locationmanager %m.providerType %d %d | component: LOCATION_MANAGER | `if (ContextCompat.checkSelfPermission(%s.this, Manifest.permission.ACCESS_FINE_LOCATION) =` |  |
| `mediaplayerCreate` | statement | %m.mediaplayer %m.sound | component: MEDIAPLAYER | `app logic: %s = MediaPlayer.create(getApplicationContext(), R.raw.%s);` |  |
| `mediaplayerGetCurrent` | number value | %m.mediaplayer | component: MEDIAPLAYER | `%s.getCurrentPosition()` |  |
| `mediaplayerGetDuration` | number value | %m.mediaplayer | component: MEDIAPLAYER | `%s.getDuration()` |  |
| `mediaplayerIsLooping` | boolean value | %m.mediaplayer | component: MEDIAPLAYER | `%s.isLooping()` |  |
| `mediaplayerIsPlaying` | boolean value | %m.mediaplayer | component: MEDIAPLAYER | `%s.isPlaying()` |  |
| `mediaplayerPause` | statement | %m.mediaplayer | component: MEDIAPLAYER | `app logic: %s.pause();` |  |
| `mediaplayerRelease` | statement | %m.mediaplayer | component: MEDIAPLAYER | `app logic: %s.release();` |  |
| `mediaplayerReset` | statement | %m.mediaplayer | component: MEDIAPLAYER | `app logic: %s.reset();` |  |
| `mediaplayerSeek` | statement | %m.mediaplayer %d | component: MEDIAPLAYER | `app logic: %s.seekTo((int)(%s));` |  |
| `mediaplayerSetLooping` | statement | %m.mediaplayer %b | component: MEDIAPLAYER | `app logic: %s.setLooping(%s);` |  |
| `mediaplayerStart` | statement | %m.mediaplayer | component: MEDIAPLAYER | `app logic: %s.start();` |  |
| `objectanimatorCancel` | statement | %m.objectanimator | component: OBJECTANIMATOR | `app logic: %s.cancel();` |  |
| `objectanimatorIsRunning` | boolean value | %m.objectanimator | component: OBJECTANIMATOR | `%s.isRunning()` |  |
| `objectanimatorSetDuration` | statement | %m.objectanimator %d | component: OBJECTANIMATOR | `app logic: %s.setDuration((int)(%s));` |  |
| `objectanimatorSetFromTo` | statement | %m.objectanimator %d %d | component: OBJECTANIMATOR | `app logic: %s.setFloatValues((float)(%s), (float)(%s));` |  |
| `objectanimatorSetInterpolator` | statement | %m.objectanimator %m.aniInterpolator | component: OBJECTANIMATOR | `app generator case (dynamic)` |  |
| `objectanimatorSetProperty` | statement | %m.objectanimator %m.animatorproperty | component: OBJECTANIMATOR | `app logic: %s.setPropertyName("%s");` |  |
| `objectanimatorSetRepeatCount` | statement | %m.objectanimator %d | component: OBJECTANIMATOR | `app logic: %s.setRepeatCount((int)(%s));` |  |
| `objectanimatorSetRepeatMode` | statement | %m.objectanimator %m.aniRepeatMode | component: OBJECTANIMATOR | `app logic: %s.setRepeatMode(ValueAnimator.%s);` |  |
| `objectanimatorSetTarget` | statement | %m.objectanimator %m.view | component: OBJECTANIMATOR | `app logic: %s.setTarget(%s);` |  |
| `objectanimatorSetValue` | statement | %m.objectanimator %d | component: OBJECTANIMATOR | `app logic: %s.setFloatValues((float)(%s));` |  |
| `objectanimatorStart` | statement | %m.objectanimator | component: OBJECTANIMATOR | `app logic: %s.start();` |  |
| `progressdialogCreate` | statement | %m.progressdialog %m.activity | component: PROGRESS_DIALOG | `%s = new ProgressDialog(%s.this);` |  |
| `progressdialogDismiss` | statement | %m.progressdialog | component: PROGRESS_DIALOG | `%s.dismiss();` |  |
| `progressdialogSetCancelable` | statement | %m.progressdialog %b | component: PROGRESS_DIALOG | `%s.setCancelable(%s);` |  |
| `progressdialogSetCanceledOutside` | statement | %m.progressdialog %b | component: PROGRESS_DIALOG | `%s.setCanceledOnTouchOutside(%s);` |  |
| `progressdialogSetMax` | statement | %m.progressdialog %d | component: PROGRESS_DIALOG | `%s.setMax((int)%s);` |  |
| `progressdialogSetMessage` | statement | %m.progressdialog %s | component: PROGRESS_DIALOG | `%s.setMessage(%s);` |  |
| `progressdialogSetProgress` | statement | %m.progressdialog %d | component: PROGRESS_DIALOG | `%s.setProgress((int)%s);` |  |
| `progressdialogSetStyle` | statement | %m.progressdialog %m.styleprogress | component: PROGRESS_DIALOG | `%s.setProgressStyle(ProgressDialog.%s);` |  |
| `progressdialogSetTitle` | statement | %m.progressdialog %s | component: PROGRESS_DIALOG | `%s.setTitle(%s);` |  |
| `progressdialogShow` | statement | %m.progressdialog | component: PROGRESS_DIALOG | `%s.show();` |  |
| `requestnetworkSetHeaders` | statement | %m.requestnetwork %m.varMap | component: REQUEST_NETWORK | `app logic: %s.setHeaders(%s);` |  |
| `requestnetworkSetParams` | statement | %m.requestnetwork %m.varMap %m.requestType | component: REQUEST_NETWORK | `app logic: %s.setParams(%s, RequestNetworkController.%s);` |  |
| `requestnetworkStartRequestNetwork` | statement | %m.requestnetwork %m.method %s %s | component: REQUEST_NETWORK | `app logic: %s.startRequestNetwork(RequestNetworkController.%s, %s, %s, _%s_reques` |  |
| `rewardedVideoAdLoad` | statement | %m.videoad %m.activity | component: REWARDED_VIDEO_AD | `RewardedAd.load(%2$s.this, _reward_ad_unit_id, new AdRequest.Builder().build(), _%1$s_rewa` |  |
| `rewardedVideoAdShow` | statement | %m.videoad %m.activity | component: REWARDED_VIDEO_AD | `%1s.show(%2$s.this, _%1$s_on_user_earned_reward_listener);` |  |
| `sendBroadcast` | statement | %s | — | `sendBroadcast(%s);` |  |
| `setTitle` | statement | %s | — | `app logic: setTitle(%s);` | ✔ have |
| `showKeyboard` | statement | — | — | `SketchwareUtil.showKeyboard(getApplicationContext());` |  |
| `soundpoolCreate` | statement | %m.soundpool %d | component: SOUNDPOOL | `app logic: %s = new SoundPool((int)(%s), AudioManager.STREAM_MUSIC, 0);` |  |
| `soundpoolLoad` | number value | %m.soundpool %m.sound | component: SOUNDPOOL | `app logic: %s.load(getApplicationContext(), R.raw.%s, 1);` |  |
| `soundpoolStreamPlay` | number value | %m.soundpool %d %d | component: SOUNDPOOL | `app logic: %s.play((int)(%s), 1.0f, 1.0f, 1, (int)(%s), 1.0f);` |  |
| `soundpoolStreamStop` | statement | %m.soundpool %d | component: SOUNDPOOL | `app logic: %s.stop((int)(%s));` |  |
| `speechToTextShutdown` | statement | %m.speechtotext | component: SPEECH_TO_TEXT | `app logic: %s.cancel(); %s.destroy();` |  |
| `speechToTextStartListening` | statement | %m.speechtotext | component: SPEECH_TO_TEXT | `app logic: Intent _intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);` |  |
| `speechToTextStopListening` | statement | %m.speechtotext | component: SPEECH_TO_TEXT | `app logic: %s.stopListening();` |  |
| `startActivity` | statement | %m.intent | component: INTENT | `app logic: startActivity(%s);` | ✔ have |
| `startActivityWithChooser` | statement | %m.intent %s | component: INTENT | `startActivity(Intent.createChooser(%s, %s));` |  |
| `startService` | statement | %m.activity | — | `startService(new Intent(getApplicationContext(), %s.class));` |  |
| `stopService` | statement | %m.activity | — | `stopService(new Intent(getApplicationContext(), %s.class));` |  |
| `textToSpeechIsSpeaking` | boolean value | %m.texttospeech | component: TEXT_TO_SPEECH | `%s.isSpeaking()` |  |
| `textToSpeechSetPitch` | statement | %m.texttospeech %d | component: TEXT_TO_SPEECH | `app logic: %s.setPitch((float)%s);` |  |
| `textToSpeechSetSpeechRate` | statement | %m.texttospeech %d | component: TEXT_TO_SPEECH | `app logic: %s.setSpeechRate((float)%s);` |  |
| `textToSpeechShutdown` | statement | %m.texttospeech | component: TEXT_TO_SPEECH | `app logic: %s.shutdown();` |  |
| `textToSpeechSpeak` | statement | %m.texttospeech %s | component: TEXT_TO_SPEECH | `app logic: %s.speak(%s, TextToSpeech.QUEUE_ADD, null);` |  |
| `textToSpeechStop` | statement | %m.texttospeech | component: TEXT_TO_SPEECH | `app logic: %s.stop();` |  |
| `timePickerDialogShow` | statement | %m.timepickerdialog | component: TIME_PICKER_DIALOG | `%s.show();` |  |
| `timerAfter` | container | %m.timer %d | component: TIMERTASK | `app logic: %s = new TimerTask() { @Override public void run() { runOnUiThread(new` |  |
| `timerCancel` | statement | %m.timer | component: TIMERTASK | `app logic: %s.cancel();` |  |
| `timerEvery` | container | %m.timer %d %d | component: TIMERTASK | `app logic: %s = new TimerTask() { @Override public void run() { runOnUiThread(new` |  |
| `vibratorAction` | statement | %m.vibrator %d | component: VIBRATOR | `app logic: %s.vibrate((long)(%s));` |  |

## Events (11)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `getLastVisiblePosition` | number value | %m.listview | — | `%s.getLastVisiblePosition()` |  |
| `listscrollparam` | number value | %m.listscrollparam | — | `ListView.%s` |  |
| `menuAddItem` | statement | %d %s | — | `menu.add(0, %s, 0, %s);` |  |
| `menuAddMenuItem` | statement | %m.menuitem %d %s %m.resource %m.menuaction | — | `MenuItem %1$s = menu.add(Menu.NONE, %2$s, Menu.NONE, %3$s); %1$s.setIcon(R.drawable.%4$s);` |  |
| `menuAddSubmenu` | container | %m.submenu %s | — | `SubMenu %s = menu.addSubMenu(%s); %s` |  |
| `menuInflater` | statement | %m.menu | — | `getMenuInflater().inflate(R.menu.%s, menu);` |  |
| `pagerscrollparam` | number value | %m.pagerscrollparam | — | `ViewPager.%s` |  |
| `recyclerscrollparam` | number value | %m.recyclerscrollparam | — | `RecyclerView.%s` |  |
| `returnFragment` | end/stop | %m.activity | — | `return new %s();` |  |
| `returnTitle` | end/stop | %s | — | `return %s;` |  |
| `submenuAddItem` | statement | %m.submenu %d %s | — | `%s.add(0, %s, 0, %s);` |  |

## More (7)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `CommandBlockJava` | container | %s %d %d %d %m.Command | — | `/*-JX4UA2y_f1OckjjvxWI.bQwRei-sLEsBmds7ArsRfi0xSFEP3Php97kjdMCs5ed >[%1$s] >%2$s >%3$s >%4` |  |
| `addCustomVariable` | statement | %s | — | `—` |  |
| `addInitializer` | statement | %s | — | `—` |  |
| `addPermission` | statement | %m.Permission | — | `—` |  |
| `customToast` | statement | %s %m.color %d %m.color %d %m.gravity | — | `SketchwareUtil.CustomToast(getApplicationContext(), %s, %s, %s, %s, %s, SketchwareUtil.%s)` |  |
| `customToastWithIcon` | statement | %s %m.color %d %m.color %d %m.gravity %m.resource | — | `SketchwareUtil.CustomToastWithIcon(getApplicationContext(), %s, %s, %s, %s, %s, Sketchware` |  |
| `removePermission` | statement | %m.Permission | — | `—` |  |

## Custom views (3)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `checkboxIsChecked` | boolean value | — | — | `isChecked` |  |
| `checkboxOnChecked` | container | %m.checkbox | — | `%s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { @Override pub` |  |
| `notifyDataSetChanged` | statement | — | — | `notifyDataSetChanged();` |  |

## Strings (2)

| Block | Kind | Params | Needs component | Generates (Java, truncated) | Agent |
|---|---|---|---|---|---|
| `getResStr` | app_name | — | — | `app logic: getString(R.string.` |  |
| `getResString` | text value | %m.ResString | — | `getString(%s)` |  |

## How a block earns its place in the palette (verified mechanics)

- **Palette membership** comes from `ExtraPaletteBlock.setBlock(paletteId)` cases 0–8
  (Variable, List, Control, Operator, Math, File, View, Component, then user palettes)
  plus `BlocksHandler.primaryBlocksA–D`. The 8 categories in the app's palette drawer
  match the user's screenshot exactly.
- **View blocks are widget-gated**: a block group only appears when a widget of that
  kind is on the screen (126 blocks carry a `widget:` gate in the JSON). An agent must
  apply the same rule — never offer a WebView block for a screen with no WebView.
- **Component blocks are component-gated**: 124 blocks only appear after the matching
  component is added to the activity (`isComponentUsed(...)` in palette case 7).
  Component families (blocks per family): BluetoothConnect 11, MediaPlayer 11,
  ObjectAnimator 11, Intent 10, ProgressDialog 10, FirebaseAuth 8, Dialog 7,
  Calendar 7, FirebaseDB 7, TextToSpeech 6, SharedPreferences 4, RequestNetwork 4,
  SoundPool 4, FirebaseStorage 3, InterstitialAd 3, SpeechToText 3, Timer 3,
  FilePicker 2, Gyroscope 2, LocationManager 2, RewardedVideoAd 2, Camera 1,
  DatePickerDialog 1, TimePickerDialog 1, Vibrator 1. The app has 31 stock component
  types (`ComponentBean`), plus JSON-defined custom components (`ComponentsHandler`).
- **Emission is fully accounted for**: every one of the 584 blocks either has a case
  in `Fx.getBlockCode` (333 case labels), or spec+code in the Pro block data
  (297 blocks; emitted through the extra-block fallback with `String.format`), or both.
  Zero blocks have an unknown emission path.
- **Slots**: 554 blocks have at least one parameter slot; the remaining 30 are
  zero-parameter blocks (constants like `mathPi`, actions like `finishActivity`,
  `openDrawer`) — slotless by design, not missing data.
- **Imports**: only 8 blocks need a per-block import (`Jx.addImportsForBlocks`);
  everything else imports through the type system (`mq.getImportsByTypeName`,
  view/component declarations). Per-block imports are recorded in the JSON.
- **Control is bigger than the agent knows**: besides `if/ifElse/repeat/forever/break`,
  the app defines `whileLoop`, `switchNum/switchStr` with `caseNum/caseStr (+And)`
  and `defaultSwitch`, `tryCatch` (try/catch, type `e`), `continue`, ternary
  reporters, and `returnX` blocks for More-block bodies — all as Pro block data with
  code templates, all container/reporter shapes the agent's chain machinery can
  in principle store.
- **Raw-source blocks stay out by design**: `addSourceDirectly`, `customImport(2)`,
  `addCustomVariable`, `addInitializer`, `CommandBlockJava/XML` inject free Java —
  the same reason user-defined custom blocks come last, after the stock set is known.

## Agent coverage today (79 of 584 opCodes referenced by the agent)

Variable 10/26 · List 8/33 · Control 5/28 · Operator 24/49 · Math 9/24 ·
File 0/33 · View 16/228 · Component 7/140 · Events 0/11 · More 0/7 ·
Custom views 0/3 · Strings 0/2. **505 blocks remain to teach**, in dependency
order: ungated statements → reporters → widget-gated View families →
component creation + their gated families → File → Events/More-block helpers.
Only after this stock set is exhausted do agent-defined custom blocks
(global Block Manager registration) enter the picture, for what stock lacks.
