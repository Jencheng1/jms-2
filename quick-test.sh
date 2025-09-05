#!/bin/bash

export PATH=$PATH:/usr/local/bin:/bin:/usr/bin

echo "========================================="
echo "IBM MQ Uniform Cluster - Quick Test Demo"
echo "========================================="
echo ""

# Clean up
echo "1. Cleaning up existing containers..."
docker-compose down -v 2>/dev/null

# Start QMs
echo "2. Starting Queue Managers..."
docker-compose up -d qm1 qm2 qm3

echo "3. Waiting for initialization (45 seconds)..."
sleep 45

# Check status
echo "4. Checking Queue Manager status..."
for qm in qm1 qm2 qm3; do
    if docker ps | grep -q $qm; then
        echo "   ✓ $qm is running"
    else
        echo "   ✗ $qm failed"
    fi
done

# Build Java apps
echo "5. Building Java applications..."
docker-compose run --rm app-builder

# Test connections
echo ""
echo "6. Testing connection distribution..."
echo ""

# Show cluster status
echo "Cluster Status on QM1:"
docker exec qm1 bash -c "echo 'DIS CLUSQMGR(*)' | runmqsc QM1 | grep CLUSQMGR"

echo ""
echo "Uniform Queue Status:"
for i in 1 2 3; do
    echo -n "  QM$i: "
    docker exec qm$i bash -c "echo 'DIS QL(UNIFORM.QUEUE)' | runmqsc QM$i | grep QUEUE" | head -1
done

echo ""
echo "CCDT Configuration:"
cat mq/ccdt/ccdt.json | grep -A 3 '"connection"'

echo ""
echo "========================================="
echo "Test completed. Cluster is ready for testing."
echo ""
echo "To test producers:"
echo "  docker run --rm --network mq-uniform-cluster_mqnet \\"
echo "    -v \$(pwd)/mq/ccdt:/workspace/ccdt:ro \\"
echo "    -v \$(pwd)/java-app/target:/workspace:ro \\"
echo "    -e CCDT_URL=file:/workspace/ccdt/ccdt.json \\"
echo "    openjdk:17 java -jar /workspace/producer.jar 100 1 0"
echo ""
echo "To stop:"
echo "  docker-compose down"
echo "========================================="