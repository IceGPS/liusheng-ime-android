#!/bin/sh
# Controller-level simulation for the "empty backspace" investigation.
# Compiles the VERBATIM app sources (PinyinInputController, EngineAdapter,
# KotlinPinyinEngine, PinyinDictionary, PinyinSyllables, ShuangpinMapper,
# PinyinMode, StubPinyinEngine, SymbolWidth) plus harness-only stubs
# (Android view/inputmethod surface, view stand-ins, editor with documented
# AOSP InputConnection semantics) and drives the hjzi / hanzi scenarios.
# Requires JDK 17 + kotlinc 2.0.20 (same toolchain as tools/eval-baseline).
set -e
cd "$(dirname "$0")"
export PATH=/home/hatch/jdk/jdk-17.0.20.1+1/bin:$PATH
APP=../../app/src/main/java/com/icegps/voiceime/ime/pinyin
/home/hatch/kotlinc/bin/kotlinc \
  $APP/PinyinEngine.kt $APP/PinyinInputController.kt $APP/EngineAdapter.kt \
  $APP/KotlinPinyinEngine.kt $APP/PinyinDictionary.kt \
  $APP/PinyinSyllables.kt $APP/ShuangpinMapper.kt \
  $APP/PinyinMode.kt $APP/StubPinyinEngine.kt $APP/SymbolWidth.kt \
  src/android/content/Context.kt \
  src/android/content/res/AssetManager.kt \
  src/android/view/View.kt \
  src/android/view/inputmethod/EditorInfo.kt \
  src/android/view/inputmethod/InputConnection.kt \
  src/com/icegps/voiceime/ime/pinyin/ViewStubs.kt \
  src/com/icegps/voiceime/ime/pinyin/UserDictAssocStubs.kt \
  src/com/icegps/voiceime/service/clip/ClipStore.kt \
  src/com/icegps/voiceime/util/Prefs.kt \
  src/FakeEditor.kt src/SimMain.kt \
  -include-runtime -d sim.jar
java -Xmx2g -Deval.assetsDir="$HOME/workspace/voice-ime-android/app/src/main/assets" \
  -jar sim.jar
