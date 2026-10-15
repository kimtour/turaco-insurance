FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /build/target/insurance-api-0.2.0.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
