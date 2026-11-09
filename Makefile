.PHONY: build app

build:
	cd zig-src && zig build -Doptimize=small -Dandroid-ndk="$${ANDROID_NDK_HOME:-$$ANDROID_NDK_ROOT}" jni

app:
	cd android && ./gradlew assembleDebug
	cd android && ./gradlew installDebug
	adb shell am start -n com.jossephus.chuchu/.MainActivity

fmt:
	ktfmt --kotlinlang-style $(shell find $(KT_SRC) -name '*.kt')

lint:
	ktlint --editorconfig=android/.editorconfig "android/app/src/**/*.kt" "android/**/*.gradle.kts"

