import javax.jms.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.jms.JmsPropertyContext;
import com.ibm.msg.client.wmq.WMQConstants;

import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.CMQCFC;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.MQDataException;
import java.io.IOException;
import java.util.Hashtable;

/**
 * UcJmsPcfCorrelator
 *
 * Proves Uniform Cluster grouping by:
 *  1) Creating one JMS Connection and N Sessions with a shared APPLTAG
 *  2) PCF snapshot BEFORE sessions -> parent HCONNs
 *  3) PCF snapshot AFTER  sessions -> parent + children HCONNs
 *  4) Set difference  -> child HCONNs
 *  5) Print per-HCONN: QM name/id, CONNID, PID/TID, channel, conname, APPLTAG
 *
 * Notes:
 *  - APPLTAG is set with WMQ_APPLICATIONNAME; ALL HCONNs from this app share it.
 *  - Each Session creates its own HCONN; parent is the HCONN observed before sessions exist.
 *  - If your Uniform Cluster spans multiple QMs, run this PCF against the resolved QM
 *    and, if needed, against others in the cluster too (same APPLTAG filter).
 */
public class UcJmsPcfCorrelator {

  // CLI args
  static class Args {
    String host = null;
    int port = 1414;
    String channel = null;
    String qmgr = "QM1";
    String ccdt = null;

    String applTag = "UC-DIAG";
    int sessions = 5;

    String user = null;
    String pass = null;
    int settleMillis = 1200;    // wait after creating sessions before 2nd snapshot
    int connectTimeoutMs = 8000;
  }

  public static void main(String[] argv) throws Exception {
    Args a = parse(argv);

    // 1) Create a connection factory with APPLTAG (group key)
    JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
    JmsConnectionFactory cf = ff.createConnectionFactory();

    if (a.ccdt != null) {
      cf.setStringProperty(WMQConstants.WMQ_CCDTURL, a.ccdt);
    } else {
      cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, a.host);
      cf.setIntProperty(WMQConstants.WMQ_PORT, a.port);
      cf.setStringProperty(WMQConstants.WMQ_CHANNEL, a.channel);
      cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
      cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, a.qmgr);
    }

    String runId = UUID.randomUUID().toString().substring(0, 8);
    cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, a.applTag);                 // APPLTAG
    // cf.setStringProperty(WMQConstants.WMQ_CONNECTION_TAG, "RUN-" + runId);          // optional - commented out due to length limit
    cf.setIntProperty(WMQConstants.WMQ_SHARE_CONV_ALLOWED, WMQConstants.WMQ_SHARE_CONV_ALLOWED_YES);

    // Helpful: shorter connect timeout
    cf.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, a.connectTimeoutMs);

    // 2) Make JMS Connection (parent), but don't create sessions yet
    Connection conn = (a.user == null)
        ? cf.createConnection()
        : cf.createConnection(a.user, a.pass);

    conn.start();

    // Resolved details (client view)
    JmsPropertyContext cpc = (JmsPropertyContext) conn;
    String resolvedQm  = cpc.getStringProperty(WMQConstants.WMQ_RESOLVED_QUEUE_MANAGER);
    String resolvedQmId= cpc.getStringProperty(WMQConstants.WMQ_RESOLVED_QUEUE_MANAGER_ID);

    System.out.printf("Resolved JMS Connection -> QM=%s  QMID=%s  APPLTAG=%s  RUN=%s%n",
        nz(resolvedQm), nz(resolvedQmId), a.applTag, runId);

    // 3) PCF snapshot BEFORE sessions: parent(s) only
    Map<String, PCFMessage> before = inquireByApplTag(a, a.qmgr, a.host, a.port, a.channel, a.applTag);

    // 4) Create N Sessions
    List<Session> sessions = new ArrayList<>();
    for (int i = 0; i < a.sessions; i++) {
      Session s = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
      sessions.add(s);
    }

    // 5) Let the provider finish session-level connects
    sleep(a.settleMillis);

    // 6) PCF snapshot AFTER sessions
    Map<String, PCFMessage> after = inquireByApplTag(a, a.qmgr, a.host, a.port, a.channel, a.applTag);

    // 7) Identify children as AFTER \ BEFORE by CONNID
    Set<String> beforeIds = before.keySet();
    Set<String> afterIds  = after.keySet();

    Set<String> childIds = new LinkedHashSet<>(afterIds);
    childIds.removeAll(beforeIds);

    Set<String> parentIds = new LinkedHashSet<>(afterIds);
    parentIds.retainAll(beforeIds); // those seen before are the parent (and any pre-existing)

    // 8) Print report
    System.out.println();
    System.out.println("===== Uniform Cluster Group (APPLTAG=" + a.applTag + ") =====");
    System.out.printf("Found %d total CONNs (after sessions): %s%n", afterIds.size(), afterIds);
    System.out.printf("Identified %d parent CONN(s): %s%n", parentIds.size(), parentIds);
    System.out.printf("Identified %d child  CONN(s): %s%n", childIds.size(), childIds);

    System.out.println("\n--- Parent Connection(s) ---");
    for (String id : parentIds) {
      printConn(after.get(id));
    }

    System.out.println("\n--- Child Session Connection(s) ---");
    for (String id : childIds) {
      printConn(after.get(id));
    }

    // 9) Sanity: prove all are on same QM (group behavior)
    Set<String> qms = after.values().stream()
        .map(UcJmsPcfCorrelator::qmKey)
        .collect(Collectors.toCollection(LinkedHashSet::new));

    System.out.println("\n===== Summary =====");
    System.out.printf("Parent=%d  Children=%d  Total=%d%n", parentIds.size(), childIds.size(), afterIds.size());
    System.out.printf("All connections share APPLTAG=%s%n", a.applTag);
    System.out.printf("Queue Managers in use by this group: %s%n", qms);
    if (qms.size() == 1) {
      System.out.println("✔ All connections (parent+children) are on the SAME queue manager (Uniform Cluster grouping).");
    } else {
      System.out.println("⚠ Observed multiple queue managers; check APPLTAG and timing or repeat against each QM in the cluster.");
    }

    // Keep connection until done (close if you like)
    // conn.close();
  }

  // ---------- PCF helpers ----------
  private static Map<String, PCFMessage> inquireByApplTag(Args a, String qmgr, String host, int port,
                                                          String channel, String applTag) throws Exception {
    Map<String, PCFMessage> out = new LinkedHashMap<>();
    PCFMessageAgent agent = null;
    MQQueueManager queueManager = null;
    try {
      // Connect using MQQueueManager first
      Hashtable<String, Object> props = new Hashtable<>();
      props.put(com.ibm.mq.constants.CMQC.HOST_NAME_PROPERTY, host);
      props.put(com.ibm.mq.constants.CMQC.PORT_PROPERTY, port);
      props.put(com.ibm.mq.constants.CMQC.CHANNEL_PROPERTY, "APP.SVRCONN");
      props.put(com.ibm.mq.constants.CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, false);
      
      queueManager = new MQQueueManager(qmgr, props);
      agent = new PCFMessageAgent(queueManager);

      PCFMessage req = new PCFMessage(CMQCFC.MQCMD_INQUIRE_CONNECTION);
      // Filter by connection type and application tag
      req.addParameter(CMQCFC.MQIACF_CONN_INFO_TYPE, CMQCFC.MQIACF_CONN_INFO_CONN);
      // Don't use MQIACF_ALL - specify what we need
      req.addParameter(CMQCFC.MQIACF_CONNECTION_ATTRS, new int[] {
        CMQCFC.MQIACF_ALL
      });

      PCFMessage[] resp = agent.send(req);
      for (PCFMessage m : resp) {
        String connIdHex = hex(m.getBytesParameterValue(CMQCFC.MQBACF_CONNECTION_ID));
        out.put(connIdHex, m);
      }
      return out;
    } finally {
      if (agent != null) {
        try { agent.disconnect(); } catch (Exception ignore) {}
      }
      if (queueManager != null) {
        try { queueManager.disconnect(); } catch (Exception ignore) {}
      }
    }
  }

  private static String qmKey(PCFMessage m) {
    String qmn = s(m, CMQC.MQCA_Q_MGR_NAME);
    String qmi = s(m, CMQC.MQCA_Q_MGR_IDENTIFIER);
    return (nz(qmn) + "/" + nz(qmi)).trim();
  }

  private static void printConn(PCFMessage m) {
    String connIdHex = "";
    try {
      connIdHex = hex(m.getBytesParameterValue(CMQCFC.MQBACF_CONNECTION_ID));
    } catch (PCFException e) {
      connIdHex = "UNKNOWN";
    }
    String channel   = s(m, CMQCFC.MQCACH_CHANNEL_NAME);
    String conname   = s(m, CMQCFC.MQCACH_CONNECTION_NAME);
    String user      = s(m, CMQCFC.MQCACF_USER_IDENTIFIER);
    String appltag   = s(m, CMQCFC.MQCACF_APPL_TAG);
    String qmn       = s(m, CMQC.MQCA_Q_MGR_NAME);
    String qmi       = s(m, CMQC.MQCA_Q_MGR_IDENTIFIER);

    Integer pidObj = i(m, CMQCFC.MQIACF_PROCESS_ID);
    Integer tidObj = i(m, CMQCFC.MQIACF_THREAD_ID);
    String  pid = (pidObj == null ? "-" : String.valueOf(pidObj));
    String  tid = (tidObj == null ? "-" : String.valueOf(tidObj));

    System.out.printf(Locale.ROOT,
      "CONNID=%s  QM=%s(%s)  PID/TID=%s/%s  CHL=%s  CONNAME=%s  USER=%s  APPLTAG=%s%n",
      connIdHex, nz(qmn), nz(qmi), pid, tid, nz(channel), nz(conname), nz(user), nz(appltag));
  }

  // ---------- small utils ----------
  private static String nz(String s) { return (s == null) ? "" : s.trim(); }
  private static void sleep(int ms) {
    try { TimeUnit.MILLISECONDS.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
  }

  private static String s(PCFMessage m, int parm) {
    try { Object o = m.getParameterValue(parm); return (o == null) ? "" : o.toString().trim(); }
    catch (Exception e) { return ""; }
  }
  private static Integer i(PCFMessage m, int parm) {
    try { Object o = m.getParameterValue(parm); return (o instanceof Integer) ? (Integer)o : null; }
    catch (Exception e) { return null; }
  }
  private static String hex(byte[] bytes) {
    if (bytes == null) return "";
    // MQCONNID is a 24-byte structure; print as uppercase hex
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) sb.append(String.format("%02X", b));
    return sb.toString();
  }

  private static Args parse(String[] a) {
    Args r = new Args();
    for (int i = 0; i < a.length; i++) {
      switch (a[i]) {
        case "--host":     r.host = a[++i]; break;
        case "--port":     r.port = Integer.parseInt(a[++i]); break;
        case "--channel":  r.channel = a[++i]; break;
        case "--qmgr":     r.qmgr = a[++i]; break;
        case "--ccdt":     r.ccdt = a[++i]; break;
        case "--appltag":  r.applTag = a[++i]; break;
        case "--sessions": r.sessions = Integer.parseInt(a[++i]); break;
        case "--user":     r.user = a[++i]; break;
        case "--pass":     r.pass = a[++i]; break;
        case "--settle-ms":r.settleMillis = Integer.parseInt(a[++i]); break;
        default:
          throw new IllegalArgumentException("Unknown arg: " + a[i]);
      }
    }
    if (r.ccdt == null && (r.host == null || r.channel == null)) {
      throw new IllegalArgumentException("Provide either --ccdt or (--host --port --channel)");
    }
    return r;
  }
}