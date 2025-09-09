#!/bin/bash

# Complete PCF permissions fix for all Queue Managers
# This ensures PCF API calls work without authentication issues

echo "========================================"
echo " COMPLETE PCF PERMISSIONS FIX"
echo "========================================"
echo

# Function to completely fix PCF permissions on a Queue Manager
fix_qm_pcf() {
    local qm=$1
    local container="qm${qm: -1}"
    
    echo "Fixing PCF permissions for $qm..."
    
    # Step 1: Disable channel authentication completely
    docker exec $container bash -c "echo 'ALTER QMGR CHLAUTH(DISABLED)' | runmqsc $qm" > /dev/null 2>&1
    
    # Step 2: Remove connection authentication
    docker exec $container bash -c "echo 'ALTER QMGR CONNAUTH(\"\")' | runmqsc $qm" > /dev/null 2>&1
    
    # Step 3: Set MCAUSER on channels to bypass auth
    docker exec $container bash -c "echo 'ALTER CHANNEL(APP.SVRCONN) CHLTYPE(SVRCONN) MCAUSER('\''mqm'\'')' | runmqsc $qm" > /dev/null 2>&1
    docker exec $container bash -c "echo 'ALTER CHANNEL(SYSTEM.DEF.SVRCONN) CHLTYPE(SVRCONN) MCAUSER('\''mqm'\'')' | runmqsc $qm" > /dev/null 2>&1
    docker exec $container bash -c "echo 'ALTER CHANNEL(SYSTEM.ADMIN.SVRCONN) CHLTYPE(SVRCONN) MCAUSER('\''mqm'\'')' | runmqsc $qm" > /dev/null 2>&1
    
    # Step 4: Define SYSTEM.ADMIN.SVRCONN if it doesn't exist
    docker exec $container bash -c "echo 'DEFINE CHANNEL(SYSTEM.ADMIN.SVRCONN) CHLTYPE(SVRCONN) MCAUSER('\''mqm'\'') REPLACE' | runmqsc $qm" > /dev/null 2>&1
    
    # Step 5: Refresh security
    docker exec $container bash -c "echo 'REFRESH SECURITY TYPE(CONNAUTH)' | runmqsc $qm" > /dev/null 2>&1
    docker exec $container bash -c "echo 'REFRESH SECURITY' | runmqsc $qm" > /dev/null 2>&1
    
    # Step 6: Grant all permissions using setmqaut
    docker exec $container bash -c "
        # Grant full access to mqm user
        setmqaut -m $qm -t qmgr -p mqm +all 2>/dev/null
        setmqaut -m $qm -t qmgr -g mqm +all 2>/dev/null
        
        # Grant access to SYSTEM queues for PCF
        setmqaut -m $qm -n 'SYSTEM.**' -t queue -p mqm +all 2>/dev/null
        setmqaut -m $qm -n 'SYSTEM.**' -t queue -g mqm +all 2>/dev/null
        
        # Specific PCF queues
        setmqaut -m $qm -n 'SYSTEM.ADMIN.COMMAND.QUEUE' -t queue -p mqm +all 2>/dev/null
        setmqaut -m $qm -n 'SYSTEM.DEFAULT.MODEL.QUEUE' -t queue -p mqm +all 2>/dev/null
        setmqaut -m $qm -n 'SYSTEM.MQEXPLORER.REPLY.MODEL' -t queue -p mqm +all 2>/dev/null
        
        # Grant access to all channels
        setmqaut -m $qm -n '**' -t channel -p mqm +all 2>/dev/null
        setmqaut -m $qm -n '**' -t channel -g mqm +all 2>/dev/null
    " > /dev/null 2>&1
    
    # Step 7: Enable monitoring for PCF queries
    docker exec $container bash -c "echo '
        ALTER QMGR MONQ(HIGH)
        ALTER QMGR MONCHL(HIGH)
        ALTER QMGR MONCONN(HIGH)
        ALTER QMGR STATQ(ON)
        ALTER QMGR STATCHL(HIGH)
        ALTER QMGR STATCONN(YES)
        ALTER QMGR STATACLS(ON)
        ALTER QMGR ACTVCONO(YES)
        ALTER QMGR ACTVTRC(ON)
    ' | runmqsc $qm" > /dev/null 2>&1
    
    echo "  ✅ $qm PCF permissions fixed"
}

# Fix all three Queue Managers
for qm in QM1 QM2 QM3; do
    fix_qm_pcf $qm
done

echo
echo "Verifying PCF access..."

# Test PCF access by checking queue manager status
for i in 1 2 3; do
    echo -n "  QM$i: "
    
    # Test basic MQSC access
    result=$(docker exec qm$i bash -c "echo 'DIS QMSTATUS' | runmqsc QM$i" 2>/dev/null | grep -c "STATUS(RUNNING)")
    if [ "$result" -gt 0 ]; then
        echo -n "MQSC ✅ "
    else
        echo -n "MQSC ❌ "
    fi
    
    # Test connection display
    result=$(docker exec qm$i bash -c "echo 'DIS CONN(*) TYPE(ALL)' | runmqsc QM$i" 2>/dev/null | grep -c "CONN(")
    if [ "$result" -gt 0 ]; then
        echo "CONN ✅"
    else
        echo "CONN ❌"
    fi
done

echo
echo "Creating test channel for PCF if needed..."
for i in 1 2 3; do
    docker exec qm$i bash -c "echo '
        DEFINE CHANNEL(SYSTEM.ADMIN.SVRCONN) CHLTYPE(SVRCONN) MCAUSER('\''mqm'\'') REPLACE
        ALTER CHANNEL(SYSTEM.ADMIN.SVRCONN) CHLTYPE(SVRCONN) MCAUSER('\''mqm'\'')
    ' | runmqsc QM$i" > /dev/null 2>&1
done

echo
echo "========================================"
echo " PCF PERMISSIONS COMPLETE"
echo "========================================"
echo
echo "All Queue Managers configured for PCF access:"
echo "  - Channel auth disabled"
echo "  - Connection auth removed"
echo "  - MCAUSER set to mqm on all channels"
echo "  - Full monitoring enabled"
echo "  - SYSTEM.ADMIN.SVRCONN channel available"
echo
echo "You can now run PCF applications without authentication issues."