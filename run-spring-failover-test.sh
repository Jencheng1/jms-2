#!/bin/bash

# Script to build and run Spring Boot MQ Failover Test
# This demonstrates parent-child session grouping with failover and rehydration

set -e

echo "=========================================="
echo "Spring Boot MQ Failover Test with Jakarta JMS"
echo "=========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}Docker is not running. Please start Docker first.${NC}"
    exit 1
fi

# Check if QMs are running
echo -e "${YELLOW}Checking Queue Managers...${NC}"
for qm in qm1 qm2 qm3; do
    if docker ps | grep -q $qm; then
        echo -e "${GREEN}✓ $qm is running${NC}"
    else
        echo -e "${RED}✗ $qm is not running${NC}"
        echo "Starting $qm..."
        docker start $qm
        sleep 2
    fi
done

# Navigate to Spring Boot project
cd spring-mq-failover

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo -e "${YELLOW}Maven not found locally. Using Docker to build...${NC}"
    
    # Build using Docker
    docker run --rm \
        -v "$(pwd)":/app \
        -v "$HOME/.m2":/root/.m2 \
        -w /app \
        maven:3.8-openjdk-17 \
        mvn clean package -DskipTests
else
    echo -e "${YELLOW}Building Spring Boot application...${NC}"
    mvn clean package -DskipTests
fi

# Check if JAR was created
if [ ! -f target/spring-mq-failover-1.0.0.jar ]; then
    echo -e "${RED}Build failed. JAR file not found.${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Build successful${NC}"

# Copy IBM MQ libraries if needed
if [ ! -d libs ]; then
    echo -e "${YELLOW}Copying IBM MQ libraries...${NC}"
    cp -r ../libs .
fi

# Run the Spring Boot application
echo -e "${YELLOW}Starting Spring Boot Failover Test Application...${NC}"
echo ""

docker run --rm \
    --name spring-mq-failover \
    --network mq-uniform-cluster_mqnet \
    -p 8080:8080 \
    -v "$(pwd)/target:/app/target" \
    -v "$(pwd)/../mq/ccdt:/workspace/ccdt" \
    -v "$(pwd)/libs:/libs" \
    -e SPRING_PROFILES_ACTIVE=default \
    -e IBM_MQ_CCDT_URL=file:///workspace/ccdt/ccdt.json \
    openjdk:17 \
    java -cp "/app/target/spring-mq-failover-1.0.0.jar:/libs/*" \
    -Dspring.main.banner-mode=console \
    org.springframework.boot.loader.JarLauncher &

APP_PID=$!

# Wait for application to start
echo -e "${YELLOW}Waiting for application to start...${NC}"
sleep 10

# Check if application started
if ps -p $APP_PID > /dev/null; then
    echo -e "${GREEN}✓ Application started successfully${NC}"
    echo ""
    echo "=========================================="
    echo "Available Endpoints:"
    echo "=========================================="
    echo "POST http://localhost:8080/api/failover/test/start         - Start failover test"
    echo "POST http://localhost:8080/api/failover/test/rehydration   - Start rehydration test"
    echo "GET  http://localhost:8080/api/failover/connections        - View connection status"
    echo "GET  http://localhost:8080/api/failover/correlation        - Get correlation report"
    echo ""
    echo -e "${YELLOW}Starting automated test sequence...${NC}"
    echo ""
    
    # Wait a bit more for full initialization
    sleep 5
    
    # Run rehydration test
    echo -e "${YELLOW}Running Queue Manager Rehydration Test...${NC}"
    curl -X POST http://localhost:8080/api/failover/test/rehydration
    
    echo ""
    echo -e "${GREEN}Test initiated. Check application logs for details.${NC}"
    echo ""
    echo "To view logs: docker logs spring-mq-failover"
    echo "To stop: kill $APP_PID"
    
    # Keep script running to show logs
    wait $APP_PID
else
    echo -e "${RED}✗ Application failed to start${NC}"
    exit 1
fi