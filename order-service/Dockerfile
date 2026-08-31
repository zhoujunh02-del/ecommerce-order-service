# Package the pre-built Spring Boot fat jar into a small JRE image.
# Build the jar first: ./gradlew :order-service:bootJar
FROM eclipse-temurin:17-jre
WORKDIR /app
# *-SNAPSHOT.jar matches the bootJar but not the *-plain.jar.
COPY build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
