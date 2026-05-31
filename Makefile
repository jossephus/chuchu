.PHONY: build app

build:
	cd zig-src && zig build -Doptimize=ReleaseSmall jni

kmp-app:
	cd android && ./gradlew assembleDebug
	cd android && ./gradlew installDebug
	adb shell am start -n com.jossephus.chuchu/.MainActivity

app:
	cd kmp && ./gradlew assembleDebug
	cd kmp && ./gradlew installDebug
	adb shell am start -n com.jossephus.chuchu/.MainActivity

fmt:
	ktfmt --kotlinlang-style $(shell find $(KT_SRC) -name '*.kt')

lint:
	ktlint --editorconfig=android/.editorconfig "android/app/src/**/*.kt" "android/**/*.gradle.kts"

ios:   
	./kmp/gradlew -p kmp :shared:linkDebugFrameworkIosSimulatorArm64
	xcodebuild -project kmp/iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination "platform=iOS Simulator,name=iPhone 17" build 
	xcrun simctl boot "iPhone 17" 2>&1; sleep 5; xcrun simctl install booted "/Users/jossephus/Library/Developer/Xcode/DerivedData/iosApp-clcgwcnhbwsoitczpujrqdkeacjh/Build/Products/Debug-iphonesimulator/KotlinProject.app" 2>&1; xcrun simctl launch booted com.jossephus.chuchu.KotlinProject
