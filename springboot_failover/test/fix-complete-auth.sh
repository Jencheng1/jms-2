#!/bin/bash

echo "Complete Authentication Fix for Spring Boot Tests"
echo "=================================================="

# Function to fix a single QM
fix_qm() {
    local qm_lower=$1
    local qm_upper=$2
    echo ""
    echo "=== Fixing $qm_upper ==="
    
    # Remove all channel auth rules first
    docker exec $qm_lower bash -c "echo \"SET CHLAUTH('*') TYPE(ADDRESSMAP) ADDRESS('*') USERSRC(NOACCESS) ACTION(REMOVE)\" | runmqsc $qm_upper" 2>/dev/null
    
    # Set MCAUSER to mqm
    docker exec $qm_lower bash -c "echo \"ALTER CHANNEL('APP.SVRCONN') CHLTYPE(SVRCONN) MCAUSER('mqm')\" | runmqsc $qm_upper"
    
    # Disable channel auth completely
    docker exec $qm_lower bash -c "echo \"ALTER QMGR CHLAUTH(DISABLED)\" | runmqsc $qm_upper"
    
    # Set connection auth to optional
    docker exec $qm_lower bash -c "echo \"ALTER QMGR CONNAUTH(' ')\" | runmqsc $qm_upper"
    
    # Refresh security
    docker exec $qm_lower bash -c "echo \"REFRESH SECURITY\" | runmqsc $qm_upper"
    
    # Verify settings
    echo "Verifying settings:"
    docker exec $qm_lower bash -c "echo \"DIS CHANNEL('APP.SVRCONN') MCAUSER\" | runmqsc $qm_upper" | grep MCAUSER
    docker exec $qm_lower bash -c "echo \"DIS QMGR CHLAUTH\" | runmqsc $qm_upper" | grep CHLAUTH
}

# Fix all QMs
fix_qm qm1 QM1
fix_qm qm2 QM2
fix_qm qm3 QM3

echo ""
echo "=================================================="
echo "Authentication fix complete!"
echo "All Queue Managers configured for Spring Boot tests"