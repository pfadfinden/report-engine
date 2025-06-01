FROM eclipse-temurin:21 AS jre-build

# Create a custom Java runtime
RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base \
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

# Application deployment
RUN mkdir /opt/app
COPY ./target/preprocessor.jar /opt/app
ENTRYPOINT ["java", "-jar", "/opt/app/preprocessor.jar"]
CMD ["--help"]

RUN mkdir /project
WORKDIR /project