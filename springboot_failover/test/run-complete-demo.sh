#!/bin/bash

echo "=========================================="
echo "Spring Boot MQ Failover Complete Demo"
echo "=========================================="
echo ""
echo "This demo will:"
echo "1. Create 10 connections (C1: 6, C2: 4)"
echo "2. Display FULL UNTRUNCATED CONNTAG for all"
echo "3. Monitor for failover for 2 minutes"
echo "4. Show complete before/after tables"
echo ""

# Run the test
docker run --rm \
    --network mq-uniform-cluster_mqnet \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/springboot_failover:/app" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/springboot_failover/libs:/libs" \
    -v "/home/ec2-user/unified/demo5/mq-uniform-cluster/springboot_failover/ccdt:/workspace/ccdt" \
    --name springboot-demo \
    openjdk:17 \
    java -cp "/app:/app/src/main/java:/libs/*" \
    SpringBootFailoverCompleteDemo