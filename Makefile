.PHONY: build app

build:
	cd zig-src && zig build -Doptimize=ReleaseSmall jni

app:
	cd android && ./gradlew assembleDebug
	cd android && ./gradlew installDebug
	adb shell am start -n com.jossephus.chuchu/.MainActivity
