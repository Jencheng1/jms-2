#!/bin/bash

# Run PCF Realtime Monitor

echo "========================================"
echo " PCF REALTIME CONNECTION MONITOR"
echo "========================================"
echo

# Parse arguments
TAG_FILTER=""
REFRESH_INTERVAL="5"

if [ "$1" != "" ]; then
    TAG_FILTER="$1"
    echo "Filtering by tag: $TAG_FILTER"
fi

if [ "$2" != "" ]; then
    REFRESH_INTERVAL="$2"
    echo "Refresh interval: $REFRESH_INTERVAL seconds"
fi

# Check if containers are running
echo
echo "Checking Queue Managers..."
for i in 1 2 3; do
    if docker ps | grep -q "qm$i"; then
        echo "  ✓ QM$i is running"
    else
        echo "  ✗ QM$i is not running"
        echo "  Please start with: docker-compose -f docker-compose-simple.yml up -d"
        exit 1
    fi
done

# Compile monitor if needed
if [ ! -f "PCFRealtimeMonitor.class" ] || [ "PCFRealtimeMonitor.java" -nt "PCFRealtimeMonitor.class" ]; then
    echo
    echo "Compiling PCF monitor..."
    javac -cp "libs/*:." PCFRealtimeMonitor.java
    if [ $? -ne 0 ]; then
        echo "  ✗ Compilation failed"
        exit 1
    fi
    echo "  ✓ Compilation successful"
fi

echo
echo "Starting PCF monitor..."
echo "Press Ctrl+C to stop"
echo

# Run the monitor
java -cp "libs/*:." PCFRealtimeMonitor "$TAG_FILTER" "$REFRESH_INTERVAL"