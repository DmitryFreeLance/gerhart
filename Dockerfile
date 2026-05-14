FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
COPY media ./media
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/telegram-mlm-bot-1.0.0.jar /app/app.jar
COPY --from=build /app/media /app/media
RUN mkdir -p /app/data
ENV DB_PATH=/app/data/bot.db
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
