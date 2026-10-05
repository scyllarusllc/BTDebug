.PHONY: dev-android bundle aab

dev-android:
	bash scripts/dev-android.sh

bundle: aab

aab:
	@test -s upload-keystore.jks || { echo "Missing upload-keystore.jks" >&2; exit 1; }
	@test -s upload-keystore.password || { echo "Missing upload-keystore.password" >&2; exit 1; }
	python3 scripts/bump-version.py
	./gradlew :app:bundleRelease
