# Build step: `yarn build` (mvn package) produces the jar in target/.
# This image generates a self-signed TLS keystore (demo only) and runs the app.
#
# NOTE: use the multi-arch (Debian-based) tag, NOT the -alpine one. The
# eclipse-temurin Alpine images are not published for arm64, so on Apple Silicon
# the build hangs on "load metadata" and fails with DeadlineExceeded.
FROM eclipse-temurin:21-jre

# Self-signed certificate for HTTPS on the ingest endpoints. Demo only — replace
# with a real certificate/keystore in any real deployment.
RUN keytool -genkeypair -alias mediator -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=localhost, OU=ENABLE, O=ENABLE demo, C=NO" \
    -keystore /keystore.p12 -storetype PKCS12 -storepass changeit -keypass changeit

COPY target/mediator-1.0.0-SNAPSHOT.jar /mediator.jar
EXPOSE 9070
ENTRYPOINT ["java", "-jar", "/mediator.jar"]
