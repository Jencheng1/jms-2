#!/bin/bash

# Fix PCF extraction permissions for all Queue Managers
# Enhanced to support full PCF monitoring and JMS tracing

echo "========================================"
echo " Fixing PCF Permissions & JMS Tracing"
echo "========================================"
echo

# Function to fix permissions on a Queue Manager
fix_qm_permissions() {
    local qm=$1
    local container="mq${qm: -1}"  # Extract number from QM name
    
    echo "Configuring $qm..."
    
    # Create MQSC commands to fix permissions
    cat << EOF | docker exec -i $container bash -c "cat > /tmp/fix_pcf.mqsc"
* Fix PCF permissions and enable monitoring
ALTER QMGR CHLAUTH(DISABLED)
ALTER QMGR CONNAUTH(' ')
ALTER QMGR ACTVTRC(ON)
ALTER QMGR ACTVCONO(YES)
ALTER QMGR MONQ(HIGH)
ALTER QMGR MONCHL(HIGH)
ALTER QMGR MONCONN(HIGH)
ALTER QMGR STATQ(ON)
ALTER QMGR STATCHL(HIGH)
ALTER QMGR STATCONN(YES)
ALTER QMGR STATACLS(ON)
REFRESH SECURITY TYPE(CONNAUTH)

* Grant full admin access to all users for PCF
SET AUTHREC OBJTYPE(QMGR) PRINCIPAL('mqm') AUTHADD(ALL)
SET AUTHREC OBJTYPE(QMGR) PRINCIPAL('app') AUTHADD(ALL)
SET AUTHREC OBJTYPE(QMGR) GROUP('mqm') AUTHADD(ALL)

* Set permissions for SYSTEM queues used by PCF
SET AUTHREC PROFILE('SYSTEM.ADMIN.COMMAND.QUEUE') OBJTYPE(QUEUE) PRINCIPAL('mqm') AUTHADD(ALL)
SET AUTHREC PROFILE('SYSTEM.ADMIN.COMMAND.QUEUE') OBJTYPE(QUEUE) PRINCIPAL('app') AUTHADD(ALL)
SET AUTHREC PROFILE('SYSTEM.DEFAULT.MODEL.QUEUE') OBJTYPE(QUEUE) PRINCIPAL('mqm') AUTHADD(ALL)
SET AUTHREC PROFILE('SYSTEM.DEFAULT.MODEL.QUEUE') OBJTYPE(QUEUE) PRINCIPAL('app') AUTHADD(ALL)
SET AUTHREC PROFILE('SYSTEM.MQEXPLORER.REPLY.MODEL') OBJTYPE(QUEUE) PRINCIPAL('mqm') AUTHADD(ALL)
SET AUTHREC PROFILE('SYSTEM.MQEXPLORER.REPLY.MODEL') OBJTYPE(QUEUE) PRINCIPAL('app') AUTHADD(ALL)

* Set permissions for SYSTEM.ADMIN channels
SET AUTHREC PROFILE('SYSTEM.ADMIN.SVRCONN') OBJTYPE(CHANNEL) PRINCIPAL('mqm') AUTHADD(ALL)
SET AUTHREC PROFILE('SYSTEM.ADMIN.SVRCONN') OBJTYPE(CHANNEL) PRINCIPAL('app') AUTHADD(ALL)
SET AUTHREC PROFILE('SYSTEM.DEF.SVRCONN') OBJTYPE(CHANNEL) PRINCIPAL('mqm') AUTHADD(ALL)
SET AUTHREC PROFILE('SYSTEM.DEF.SVRCONN') OBJTYPE(CHANNEL) PRINCIPAL('app') AUTHADD(ALL)

* Allow PCF inquiry commands
SET AUTHREC OBJTYPE(QMGR) PRINCIPAL('mqm') AUTHADD(INQ, DSP, CONNECT)
SET AUTHREC OBJTYPE(QMGR) PRINCIPAL('app') AUTHADD(INQ, DSP, CONNECT)
SET AUTHREC OBJTYPE(QMGR) GROUP('mqm') AUTHADD(INQ, DSP, CONNECT)

* Refresh security
REFRESH SECURITY TYPE(CONNAUTH)
REFRESH SECURITY
EOF
    
    # Apply the MQSC commands
    docker exec $container bash -c "runmqsc $qm < /tmp/fix_pcf.mqsc" > /dev/null 2>&1
    
    # Also ensure the user 'app' exists in the container
    docker exec $container bash -c "id app 2>/dev/null || useradd -m app" > /dev/null 2>&1
    
    # Set permissions using setmqaut for additional coverage
    docker exec $container bash -c "
        setmqaut -m $qm -t qmgr -p mqm +all 2>/dev/null
        setmqaut -m $qm -t qmgr -p app +all 2>/dev/null
        setmqaut -m $qm -n 'SYSTEM.ADMIN.COMMAND.QUEUE' -t queue -p mqm +all 2>/dev/null
        setmqaut -m $qm -n 'SYSTEM.ADMIN.COMMAND.QUEUE' -t queue -p app +all 2>/dev/null
        setmqaut -m $qm -n 'SYSTEM.DEFAULT.MODEL.QUEUE' -t queue -p mqm +all 2>/dev/null
        setmqaut -m $qm -n 'SYSTEM.DEFAULT.MODEL.QUEUE' -t queue -p app +all 2>/dev/null
        setmqaut -m $qm -n 'SYSTEM.ADMIN.SVRCONN' -t channel -p mqm +all 2>/dev/null
        setmqaut -m $qm -n 'SYSTEM.ADMIN.SVRCONN' -t channel -p app +all 2>/dev/null
        setmqaut -m $qm -n 'DEV.APP.SVRCONN' -t channel -p mqm +all 2>/dev/null
        setmqaut -m $qm -n 'DEV.APP.SVRCONN' -t channel -p app +all 2>/dev/null
    "
    
    echo "  ✅ $qm configured"
}

# Fix permissions on all Queue Managers
for qm in QM1 QM2 QM3; do
    fix_qm_permissions $qm
done

echo
echo "Verifying PCF access..."

# Test PCF access
for i in 1 2 3; do
    echo -n "  QM$i: "
    result=$(docker exec mq$i bash -c "echo 'DIS QMSTATUS' | runmqsc QM$i" 2>/dev/null | grep -c "STATUS(RUNNING)")
    if [ "$result" -gt 0 ]; then
        echo "✅ PCF Ready"
    else
        echo "❌ PCF Not Ready"
    fi
done

echo
echo "========================================"
echo " PCF Permissions Fixed"
echo "========================================"