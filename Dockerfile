FROM gradle:8.10-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/install/kotlin-warehouse-inventory-fulfillment-control-platform ./app
ENV PORT=10500
EXPOSE 10500
CMD ["./app/bin/kotlin-warehouse-inventory-fulfillment-control-platform"]

