TRADEFLOAT AI - GRADLE LAUNCHER FILES

Copy the contents of this ZIP into the ROOT of the TradeFloat_AI GitHub repository,
keeping the folder structure. The repository root should then contain:
  gradlew
  gradlew.bat
  gradle/wrapper/gradle-wrapper.properties

This lightweight launcher downloads Gradle 8.9 on first run and then invokes it.
It intentionally does not include gradle-wrapper.jar because it is not needed by this launcher.
A network connection is required on the first build. The GitHub Actions runner needs curl/wget
and unzip (these are available on ubuntu-latest).

After uploading, GitHub Actions can run: chmod +x ./gradlew && ./gradlew assembleDebug

This is a Gradle launcher file pack, not the entire Android project or a ready-built APK.
