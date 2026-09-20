FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:25-jre
COPY --from=build /build/target/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
