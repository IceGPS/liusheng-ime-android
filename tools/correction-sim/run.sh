#!/bin/sh
# JVM verification for the voice-correction capture (phase 1).
# Compiles the VERBATIM app sources CorrectionTracker + CharPinyinTable
# (both Android-free by design) with scenario drivers, judging against the
# real shipped asset char_pinyin.tsv.
set -e
cd "$(dirname "$0")"
export PATH=/home/hatch/jdk/jdk-17.0.20.1+1/bin:$PATH
APP=../../app/src/main/java/com/icegps/voiceime
/home/hatch/kotlinc/bin/kotlinc \
  $APP/ime/CorrectionTracker.kt \
  $APP/util/CharPinyin.kt \
  src/SimMain.kt \
  -include-runtime -d sim.jar
java -DcharPinyinTsv="$HOME/workspace/voice-ime-android/app/src/main/assets/char_pinyin.tsv" \
  -jar sim.jar
