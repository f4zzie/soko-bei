FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY shared ./shared
COPY backend ./backend
RUN sed -i "s/, \":android\"//" settings.gradle.kts \
 && for i in 1 2 3; do ./gradlew --no-daemon :backend:installDist -x test && break; [ $i -lt 3 ] || exit 1; done

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/backend/build/install/backend ./
EXPOSE 8080
ENTRYPOINT ["./bin/backend"]
