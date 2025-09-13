FROM openjdk:17-jdk-slim

WORKDIR /app

# Copy the Spring Boot JAR
COPY target/spring-mq-failover-1.0.0.jar app.jar

# Copy CCDT configuration
COPY ../mq/ccdt /workspace/ccdt

# Expose port
EXPOSE 8080

# JVM options for better MQ performance
ENV JAVA_OPTS="-Xmx512m -Xms256m -Dcom.ibm.mq.cfg.useIBMCipherMappings=false"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]