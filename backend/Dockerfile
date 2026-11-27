FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY common/build.gradle common/
COPY auth/build.gradle auth/
COPY canvas/build.gradle canvas/
RUN printf '%s\n' \
      "allprojects { tasks.register('resolveDependencies') { doLast {" \
      "  configurations.findAll { it.canBeResolved }.each { it.files } } } }" > /tmp/resolve.gradle \
 && ./gradlew --no-daemon -q -I /tmp/resolve.gradle resolveDependencies
COPY common/src common/src
COPY auth/src auth/src
COPY canvas/src canvas/src
COPY src src
ARG MODULE=
RUN if [ -z "$MODULE" ]; then task=bootJar; out=build/libs; else task=":$MODULE:bootJar"; out="$MODULE/build/libs"; fi \
 && ./gradlew "$task" -x test --no-daemon \
 && cp "$(ls "$out"/*.jar | grep -v -- '-plain')" /app/service.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
