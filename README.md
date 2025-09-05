# IBM MQ Uniform Cluster Demo

A complete demonstration of IBM MQ Uniform Cluster with automatic connection and session distribution using CCDT (Client Channel Definition Table) and Java JMS.

## 🎯 What is IBM MQ Uniform Cluster?

**IBM MQ Uniform Cluster** is MQ's native, application-aware load balancer that automatically distributes client connections and sessions across multiple queue managers. Unlike traditional Layer-4 load balancers (like AWS NLB), it understands MQ protocols, sessions, and transactions.

### Key Features:
- **Automatic Application Balancing**: Spreads connections evenly and can rebalance when needed
- **Session/Transaction Aware**: Understands MQ semantics, not just TCP connections
- **Auto-Reconnect**: Clients automatically reconnect to available queue managers
- **CCDT-Based**: Uses Client Channel Definition Table for intelligent routing
- **No External LB Required**: Built into MQ itself

## 🏗️ Architecture

```
┌─────────────────────────────────────────────┐
│         IBM MQ UNIFORM CLUSTER              │
│                                              │
│  ┌─────┐     ┌─────┐     ┌─────┐           │
│  │ QM1 │◄────┤ QM2 │────►│ QM3 │           │
│  └──┬──┘     └──┬──┘     └──┬──┘           │
│     └───────────┼───────────┘               │
│                 │                            │
│            CCDT (JSON)                       │
│                 │                            │
│     ┌──────────┼──────────┐                │
│     ▼          ▼          ▼                │
│ Producer1  Producer2  Producer3             │
│ Consumer1  Consumer2  Consumer3             │
└─────────────────────────────────────────────┘
```

## 📋 Prerequisites

- Docker and Docker Compose
- Maven (for building Java apps) - automatically handled in container
- 8GB RAM minimum
- Ports 1414-1416 and 9443-9445 available

## 🚀 Quick Start

### 1. Clone/Create the Project

```bash
cd mq-uniform-cluster
```

### 2. Start the MQ Cluster

```bash
# Start all three queue managers
docker-compose up -d qm1 qm2 qm3

# Wait for initialization (about 30-45 seconds)
sleep 45

# Verify cluster is running
docker-compose ps
```

### 3. Build Java Applications

```bash
# Build the Java JMS applications
docker-compose run --rm app-builder
```

### 4. Run Demo

```bash
# Terminal 1: Start monitoring
./monitoring/monitor_connections.sh

# Terminal 2: Run producers (sends 1000 messages using 3 producers)
docker run --rm \
  --network mq-uniform-cluster_mqnet \
  -v $(pwd)/mq/ccdt:/workspace/ccdt:ro \
  -v $(pwd)/java-app/target:/workspace:ro \
  -e CCDT_URL=file:/workspace/ccdt/ccdt.json \
  openjdk:17 \
  java -jar /workspace/producer.jar 1000 3 100

# Terminal 3: Run consumers (3 consumers processing messages)
docker run --rm \
  --network mq-uniform-cluster_mqnet \
  -v $(pwd)/mq/ccdt:/workspace/ccdt:ro \
  -v $(pwd)/java-app/target:/workspace:ro \
  -e CCDT_URL=file:/workspace/ccdt/ccdt.json \
  openjdk:17 \
  java -jar /workspace/consumer.jar 3 5000 false
```

### 5. Check Distribution

```bash
# Analyze connection and message distribution
./monitoring/check_distribution.sh
```

## 📊 Monitoring Connection Distribution

### Live Monitoring
```bash
./monitoring/monitor_connections.sh
```

Shows real-time:
- Active connections per queue manager
- Queue depths
- Cluster status

### Distribution Analysis
```bash
./monitoring/check_distribution.sh
```

Provides:
- Connection distribution percentages
- Message distribution
- Evenness score (100% = perfect distribution)
- Statistical analysis

### Manual Checks

```bash
# Check connections on QM1
docker exec qm1 bash -c "echo 'DIS CONN(*) WHERE(CHANNEL EQ APP.SVRCONN)' | runmqsc QM1"

# Check queue depth
docker exec qm1 bash -c "echo 'DIS QL(UNIFORM.QUEUE) CURDEPTH' | runmqsc QM1"

# Check cluster status
docker exec qm1 bash -c "echo 'DIS CLUSQMGR(*)' | runmqsc QM1"
```

## 🔧 Configuration Details

### CCDT Configuration (`mq/ccdt/ccdt.json`)

Key settings for uniform distribution:
```json
{
  "connectionManagement": {
    "affinity": "none",        // No sticky sessions
    "clientWeight": 1,         // Equal weight across QMs
    "reconnect": {
      "enabled": true,         // Auto-reconnect enabled
      "timeout": 1800          // 30-minute timeout
    }
  }
}
```

### MQSC Configuration

Each queue manager is configured with:
- **Cluster membership**: `REPOS(UNICLUSTER)` for full repos
- **Workload balancing**: `CLWLDATA('UniformCluster=true')`
- **Shared queue**: `UNIFORM.QUEUE` available on all QMs
- **Auto-balancing**: `DEFCLXQ(SCTQ) MONACLS(HIGH)`

## 🔄 Uniform Cluster vs AWS NLB

| Feature | Uniform Cluster | AWS NLB |
|---------|----------------|---------|
| **Balancing Unit** | MQ connections & sessions | TCP flows |
| **Protocol Awareness** | Yes (MQ-native) | No (Layer-4 only) |
| **Auto-Rebalancing** | Yes | No |
| **Session/Transaction Aware** | Yes | No |
| **Failover** | Automatic via CCDT | Basic health checks |
| **Best For** | MQ applications | Network ingress |

## 📝 Java Code Explanation

### Producer (`JmsProducer.java`)
- Creates multiple producer threads
- Each connects via CCDT to a random QM
- Sends messages with metadata (producer ID, QM name)
- Demonstrates connection distribution

### Consumer (`JmsConsumer.java`)
- Creates multiple consumer threads
- Each connects via CCDT
- Tracks message source QM
- Shows session distribution

### Key JMS/CCDT Integration
```java
// Configure factory to use CCDT
factory.setStringProperty(WMQConstants.WMQ_CCDTURL, ccdtUrl);
factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);

// Enable auto-reconnect for uniform cluster
factory.setStringProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, 
                         WMQConstants.WMQ_CLIENT_RECONNECT);
```

## 🧪 Testing Scenarios

### Test 1: Basic Distribution
```bash
# Run 3 producers sending 100 messages each
java -jar producer.jar 300 3 0

# Check distribution
./monitoring/check_distribution.sh
```

### Test 2: Failover
```bash
# Start producers/consumers
java -jar producer.jar 1000 3 100 &
java -jar consumer.jar 3 5000 true &

# Stop one QM
docker-compose stop qm3

# Watch automatic reconnection in monitor
./monitoring/monitor_connections.sh

# Restart QM3
docker-compose start qm3
```

### Test 3: Load Balancing
```bash
# Start many producers to see distribution
for i in {1..10}; do
  java -jar producer.jar 100 1 50 &
done

# Monitor distribution
./monitoring/check_distribution.sh
```

## 📈 Expected Results

With proper uniform cluster configuration:
- **Connection Distribution**: ~33% per QM (±5%)
- **Evenness Score**: >85%
- **Automatic Rebalancing**: Occurs within 30 seconds
- **Failover Time**: <5 seconds with auto-reconnect

## 🛠️ Troubleshooting

### Uneven Distribution
- Check CCDT `affinity` is set to `"none"`
- Verify all QMs are in the cluster: `DIS CLUSQMGR(*)`
- Ensure `CLWLUSEQ(LOCAL)` is set

### Connection Failures
- Verify network connectivity: `docker network ls`
- Check QM logs: `docker logs qm1`
- Validate CCDT path in Java apps

### No Auto-Rebalancing
- Confirm `WMQ_CLIENT_RECONNECT` is enabled
- Check `DEFCLXQ(SCTQ)` is set on all QMs
- Verify cluster channels are active

## 📚 References

- [IBM MQ Uniform Clusters Documentation](https://www.ibm.com/docs/en/ibm-mq/9.3.x?topic=clusters-uniform-clusters)
- [CCDT Configuration Guide](https://www.ibm.com/docs/SSFKSJ_9.2.0/com.ibm.mq.con.doc/q132905_.htm)
- [Automatic Application Balancing](https://www.ibm.com/docs/en/ibm-mq/9.2.x?topic=clusters-automatic-application-balancing)
- [IBM Developer - Uniform Clusters Cheat Sheet](https://developer.ibm.com/articles/awb-ibm-mq-uniform-clusters-cheat-sheet/)

## 🎓 Key Takeaways

1. **Uniform Clusters** provide MQ-native load balancing without external LBs
2. **CCDT** enables intelligent client routing with policies
3. **Auto-reconnect** ensures high availability and rebalancing
4. **Session awareness** makes it superior to Layer-4 load balancers for MQ
5. **Monitoring** is crucial to verify even distribution

## 📧 Support

For issues or questions about this demo:
1. Check the troubleshooting section
2. Review the architecture diagrams in `docs/`
3. Examine container logs: `docker-compose logs`

---

**Note**: This is a demonstration environment. For production use, add:
- TLS/SSL configuration
- Persistent volumes for QM data
- Enhanced security (LDAP, certificates)
- Monitoring integration (Prometheus, Grafana)
- Backup and recovery procedures