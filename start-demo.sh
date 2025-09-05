#!/bin/bash

echo "================================================"
echo "IBM MQ Uniform Cluster Demo - Startup Script"
echo "================================================"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to check if Docker is running
check_docker() {
    if ! docker info > /dev/null 2>&1; then
        echo -e "${RED}Error: Docker is not running${NC}"
        echo "Please start Docker and try again"
        exit 1
    fi
    echo -e "${GREEN}✓ Docker is running${NC}"
}

# Function to check if ports are available
check_ports() {
    local ports=(1414 1415 1416 9443 9444 9445)
    local all_clear=true
    
    for port in "${ports[@]}"; do
        if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
            echo -e "${RED}✗ Port $port is already in use${NC}"
            all_clear=false
        fi
    done
    
    if [ "$all_clear" = true ]; then
        echo -e "${GREEN}✓ All required ports are available${NC}"
    else
        echo -e "${RED}Please free up the ports and try again${NC}"
        exit 1
    fi
}

# Step 1: Pre-flight checks
echo "Step 1: Pre-flight checks"
echo "-------------------------"
check_docker
check_ports
echo ""

# Step 2: Start Queue Managers
echo "Step 2: Starting Queue Managers"
echo "--------------------------------"
echo "Starting QM1, QM2, and QM3..."
docker-compose up -d qm1 qm2 qm3

# Wait for QMs to initialize
echo -e "${YELLOW}Waiting for Queue Managers to initialize (45 seconds)...${NC}"
for i in {1..45}; do
    echo -n "."
    sleep 1
done
echo ""

# Check if QMs are running
echo "Checking Queue Manager status..."
for qm in qm1 qm2 qm3; do
    if docker ps | grep -q $qm; then
        echo -e "${GREEN}✓ $qm is running${NC}"
    else
        echo -e "${RED}✗ $qm failed to start${NC}"
        echo "Check logs with: docker logs $qm"
        exit 1
    fi
done
echo ""

# Step 3: Generate CCDT
echo "Step 3: CCDT Configuration"
echo "--------------------------"
if [ -f "mq/ccdt/ccdt.json" ]; then
    echo -e "${GREEN}✓ CCDT file exists${NC}"
else
    echo -e "${RED}✗ CCDT file not found${NC}"
    exit 1
fi
echo ""

# Step 4: Build Java Applications
echo "Step 4: Building Java Applications"
echo "-----------------------------------"
echo "Running Maven build..."
docker-compose run --rm app-builder

if [ -f "java-app/target/producer.jar" ] && [ -f "java-app/target/consumer.jar" ]; then
    echo -e "${GREEN}✓ Java applications built successfully${NC}"
else
    echo -e "${RED}✗ Build failed${NC}"
    exit 1
fi
echo ""

# Step 5: Verify Cluster
echo "Step 5: Verifying Cluster Configuration"
echo "----------------------------------------"
echo "Checking cluster status on QM1..."
docker exec qm1 bash -c "echo 'DIS CLUSQMGR(*)' | runmqsc QM1" | grep -q "CLUSQMGR"
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Cluster is configured${NC}"
else
    echo -e "${YELLOW}⚠ Cluster might not be fully configured${NC}"
fi
echo ""

# Step 6: Ready to run
echo "================================================"
echo -e "${GREEN}✓ IBM MQ Uniform Cluster is ready!${NC}"
echo "================================================"
echo ""
echo "Next steps:"
echo "-----------"
echo ""
echo "1. Monitor connections (new terminal):"
echo "   ./monitoring/monitor_connections.sh"
echo ""
echo "2. Run producers (new terminal):"
echo "   docker run --rm \\"
echo "     --network mq-uniform-cluster_mqnet \\"
echo "     -v \$(pwd)/mq/ccdt:/workspace/ccdt:ro \\"
echo "     -v \$(pwd)/java-app/target:/workspace:ro \\"
echo "     -e CCDT_URL=file:/workspace/ccdt/ccdt.json \\"
echo "     openjdk:17 \\"
echo "     java -jar /workspace/producer.jar 1000 3 100"
echo ""
echo "3. Run consumers (new terminal):"
echo "   docker run --rm \\"
echo "     --network mq-uniform-cluster_mqnet \\"
echo "     -v \$(pwd)/mq/ccdt:/workspace/ccdt:ro \\"
echo "     -v \$(pwd)/java-app/target:/workspace:ro \\"
echo "     -e CCDT_URL=file:/workspace/ccdt/ccdt.json \\"
echo "     openjdk:17 \\"
echo "     java -jar /workspace/consumer.jar 3 5000 false"
echo ""
echo "4. Check distribution:"
echo "   ./monitoring/check_distribution.sh"
echo ""
echo "5. Stop the cluster:"
echo "   docker-compose down"
echo ""
echo "================================================"