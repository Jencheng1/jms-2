#!/bin/bash

# Wait for QM to be fully started
sleep 5

# Get the queue manager name from environment
QMNAME=${MQ_QMGR_NAME:-QM1}

echo "Setting up cluster configuration for $QMNAME"

# Apply MQSC configuration
if [ -f /etc/mqm/${QMNAME,,}_setup.mqsc ]; then
    echo "Applying MQSC configuration..."
    runmqsc $QMNAME < /etc/mqm/${QMNAME,,}_setup.mqsc
    if [ $? -eq 0 ]; then
        echo "MQSC configuration applied successfully"
    else
        echo "Failed to apply MQSC configuration"
    fi
else
    echo "No MQSC file found for $QMNAME"
fi

echo "Cluster setup completed for $QMNAME"