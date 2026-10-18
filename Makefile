.PHONY: build test lint verify coverage apk clean

build:
	./gradlew assembleDebug

test:
	./gradlew testDebugUnitTest

lint:
	./gradlew lintDebug

coverage: test
	./gradlew createDebugUnitTestCoverageReport
	@python3 scripts/coverage_summary.py || true

verify: test lint build

apk: verify
	@mkdir -p releases
	@cp app/build/outputs/apk/debug/app-debug.apk releases/ozhungu-latest.apk
	@echo "APK at releases/ozhungu-latest.apk"

clean:
	./gradlew clean
