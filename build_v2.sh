#!/usr/bin/env bash
set -e
cd /home/user/Doubao/chats/38445171688291330/local-dream
export JAVA_HOME="$HOME/jdk17"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleBasicRelease --no-daemon \
  -Dorg.gradle.jvmargs="-Xmx1500m -XX:MaxMetaspaceSize=640m -Dfile.encoding=UTF-8" \
  -Dkotlin.daemon.jvm.options="-Xmx1200m"
echo "GRADLE_RELEASE_EXIT=$?"
ls -lh app/build/outputs/apk/basic/release/*.apk
