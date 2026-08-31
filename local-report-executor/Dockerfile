FROM eclipse-temurin:21 AS jre-build

# Fill/export touches AWT (font metrics/layout) and JDBC, unlike the
# preprocessor's compile-only java.base image - verified empirically against
# the modules actually exercised at runtime.
RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.desktop,java.sql,java.naming,java.xml,java.logging,java.management \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=2 \
         --output /javaruntime

# Runtime base image
FROM debian:buster-slim
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME

# Without fontconfig (+ at least one real font), java.desktop's font manager
# fails to initialize the first time a fill touches AWT font metrics, with
# "Fontconfig head is null, check your fonts or fonts configuration". That
# first failure is caught and reported as a normal FAILED execution, but it
# also poisons the font manager's static state for the life of the process:
# every fill after it throws NoClassDefFoundError instead - an Error, not an
# Exception, which escapes ReportExecutionRunner's catch (Exception e) and is
# silently dropped by the background executor, leaving the execution stuck at
# PENDING forever with nothing logged.
# buster is EOL - deb.debian.org 404s its Release files, so point at the archive instead.
RUN sed -i -e 's|deb.debian.org|archive.debian.org|g' -e 's|security.debian.org|archive.debian.org/debian-security|g' /etc/apt/sources.list \
    && apt-get -o Acquire::Check-Valid-Until=false update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

# Application deployment
RUN mkdir /opt/app
COPY ./target/local-report-executor.jar /opt/app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/app/local-report-executor.jar"]
