FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN printf '%s\n' \
      "allprojects { tasks.register('resolveDependencies') { doLast {" \
      "  configurations.findAll { it.canBeResolved }.each { it.files } } } }" > /tmp/resolve.gradle \
 && ./gradlew --no-daemon -q -I /tmp/resolve.gradle resolveDependencies
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
