FROM adham541/java-kubectl-azurecli
WORKDIR /app
COPY target/metrics-to-db-1-jar-with-dependencies.jar /app
CMD ["java", "-jar", "metrics-to-db-1-jar-with-dependencies.jar"]

