.PHONY: build app

build:
	zig build jni -Dtarget=aarch64-linux-android

app:
	./gradlew assembleDebug
	./gradlew installDebug
	adb shell am start -n com.jossephus.chuchu/.MainActivity
