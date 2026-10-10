# Isolated Android build environment: JDK 17 + Android SDK command-line tools.
# No Android Studio needed. Remove everything afterwards with ./build.sh --purge
FROM eclipse-temurin:17-jdk

ARG CMDLINE_TOOLS=11076708
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools \
 && curl -fsSL -o /tmp/tools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && unzip -q /tmp/tools.zip -d $ANDROID_SDK_ROOT/cmdline-tools \
 && mv $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest \
 && rm /tmp/tools.zip

RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager "platforms;android-34" "build-tools;34.0.0" > /dev/null \
 && chmod -R a+rX $ANDROID_SDK_ROOT

# Gradle cache lives in a named volume mounted here; world-writable so the
# build can run as the host user (no root-owned files in the project dir).
RUN mkdir -p /gradle && chmod 777 /gradle

WORKDIR /project
