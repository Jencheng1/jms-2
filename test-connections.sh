#!/bin/bash

# Script to create test connections to demonstrate monitoring

echo "Creating test connections to MQ Uniform Cluster..."

# Test using amqsputc (MQ sample program)
for i in 1 2 3; do
    echo "Testing connection to QM$i..."
    docker exec qm$i bash -c "echo 'TEST MESSAGE' | /opt/mqm/samp/bin/amqsputc UNIFORM.QUEUE QM$i" &
done

wait

echo "Connections established. Run ./monitor-realtime.sh to see distribution"