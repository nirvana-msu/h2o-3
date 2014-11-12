package water.hadoop;

import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Mapper;
import water.H2O;
import water.H2OApp;

import water.util.Log;


/**
 * Interesting Configuration properties:
 * mapper	mapred.local.dir=/tmp/hadoop-tomk/mapred/local/taskTracker/tomk/jobcache/job_local1117903517_0001/attempt_local1117903517_0001_m_000000_0
 */
public class h2omapper extends Mapper<Text, Text, Text, Text> {
  final static public String H2O_JOBTRACKERNAME_KEY = "h2o.jobtrackername";
  final static public String H2O_DRIVER_IP_KEY = "h2o.driver.ip";
  final static public String H2O_DRIVER_PORT_KEY = "h2o.driver.port";
  final static public String H2O_NETWORK_KEY = "h2o.network";
  final static public String H2O_BETA_KEY = "h2o.beta";
  final static public String H2O_RANDOM_UDP_DROP_KEY = "h2o.random.udp.drop";
  final static public String H2O_NTHREADS_KEY = "h2o.nthreads";
  final static public String H2O_BASE_PORT_KEY = "h2o.baseport";
  final static public String H2O_LICENSE_DATA_KEY = "h2o.license.data";

  static EmbeddedH2OConfig _embeddedH2OConfig;

  private static class EmbeddedH2OConfig extends water.init.AbstractEmbeddedH2OConfig {
    volatile String _driverCallbackIp;
    volatile int _driverCallbackPort = -1;
    volatile int _mapperCallbackPort = -1;
    volatile String _embeddedWebServerIp = "(Unknown)";
    volatile int _embeddedWebServerPort = -1;

    void setDriverCallbackIp(String value) {
      _driverCallbackIp = value;
    }

    void setDriverCallbackPort(int value) {
      _driverCallbackPort = value;
    }

    void setMapperCallbackPort(int value) {
      _mapperCallbackPort = value;
    }

    private class BackgroundWriterThread extends Thread {
      MapperToDriverMessage _m;

      void setMessage (MapperToDriverMessage value) {
        _m = value;
      }

      public void run() {
        try {
          Socket s = new Socket(_m.getDriverCallbackIp(), _m.getDriverCallbackPort());
          _m.write(s);
          s.close();
        }
        catch (java.net.ConnectException e) {
          System.out.println("EmbeddedH2OConfig: BackgroundWriterThread could not connect to driver at " + _driverCallbackIp + ":" + _driverCallbackPort);
          System.out.println("(This is normal when the driver disowns the hadoop job and exits.)");
        }
        catch (Exception e) {
          System.out.println("EmbeddedH2OConfig: BackgroundWriterThread caught an Exception");
          e.printStackTrace();
        }
      }
    }

    @Override
    public void notifyAboutEmbeddedWebServerIpPort (InetAddress ip, int port) {
      _embeddedWebServerIp = ip.getHostAddress();
      _embeddedWebServerPort = port;

      try {
        MapperToDriverMessage msg = new MapperToDriverMessage();
        msg.setDriverCallbackIpPort(_driverCallbackIp, _driverCallbackPort);
        msg.setMessageEmbeddedWebServerIpPort(ip.getHostAddress(), port);
        BackgroundWriterThread bwt = new BackgroundWriterThread();
        System.out.printf("EmbeddedH2OConfig: notifyAboutEmbeddedWebServerIpPort called (%s, %d)\n", ip.getHostAddress(), port);
        bwt.setMessage(msg);
        bwt.start();
      }
      catch (Exception e) {
        System.out.println("EmbeddedH2OConfig: notifyAboutEmbeddedWebServerIpPort caught an Exception");
        e.printStackTrace();
      }
    }

    @Override
    public boolean providesFlatfile() {
      return true;
    }

    @Override
    public String fetchFlatfile() throws Exception {
      System.out.printf("EmbeddedH2OConfig: fetchFlatfile called\n");
      MapperToDriverMessage msg = new MapperToDriverMessage();
      msg.setMessageFetchFlatfile(_embeddedWebServerIp, _embeddedWebServerPort);
      Socket s = new Socket(_driverCallbackIp, _driverCallbackPort);
      msg.write(s);
      DriverToMapperMessage msg2 = new DriverToMapperMessage();
      msg2.read(s);
      char type = msg2.getType();
      if (type != DriverToMapperMessage.TYPE_FETCH_FLATFILE_RESPONSE) {
        int typeAsInt = (int)type & 0xff;
        String str = new String("DriverToMapperMessage type unrecognized (" + typeAsInt + ")");
        Log.err(str);
        throw new Exception (str);
      }
      s.close();
      String flatfile = msg2.getFlatfile();
      System.out.printf("EmbeddedH2OConfig: fetchFlatfile returned\n");
      System.out.println("------------------------------------------------------------");
      System.out.println(flatfile);
      System.out.println("------------------------------------------------------------");
      return flatfile;
    }

    @Override
    public void notifyAboutCloudSize (InetAddress ip, int port, int size) {
      _embeddedWebServerIp = ip.getHostAddress();
      _embeddedWebServerPort = port;

      try {
        MapperToDriverMessage msg = new MapperToDriverMessage();
        msg.setDriverCallbackIpPort(_driverCallbackIp, _driverCallbackPort);
        msg.setMessageCloudSize(ip.getHostAddress(), port, size);
        BackgroundWriterThread bwt = new BackgroundWriterThread();
        System.out.printf("EmbeddedH2OConfig: notifyAboutCloudSize called (%s, %d, %d)\n", ip.getHostAddress(), port, size);
        bwt.setMessage(msg);
        bwt.start();
      }
      catch (Exception e) {
        System.out.println("EmbeddedH2OConfig: notifyAboutCloudSize caught an Exception");
        e.printStackTrace();
      }
    }

    @Override
    public void exit(int status) {
      try {
        MapperToDriverMessage msg = new MapperToDriverMessage();
        msg.setDriverCallbackIpPort(_driverCallbackIp, _driverCallbackPort);
        msg.setMessageExit(_embeddedWebServerIp, _embeddedWebServerPort, status);
        System.out.printf("EmbeddedH2OConfig: exit called (%d)\n", status);
        BackgroundWriterThread bwt = new BackgroundWriterThread();
        bwt.setMessage(msg);
        bwt.start();
        System.out.println("EmbeddedH2OConfig: after bwt.start()");
      }
      catch (Exception e) {
        System.out.println("EmbeddedH2OConfig: exit caught an exception 1");
        e.printStackTrace();
      }

      try {
        // Wait one second to deliver the message before exiting.
        Thread.sleep (1000);
        Socket s = new Socket("127.0.0.1", _mapperCallbackPort);
        byte[] b = new byte[1];
        b[0] = (byte)status;
        OutputStream os = s.getOutputStream();
        os.write(b);
        os.flush();
        s.close();
        System.out.println("EmbeddedH2OConfig: after write to mapperCallbackPort");

        Thread.sleep(60 * 1000);
        // Should never make it this far!
      }
      catch (Exception e) {
        System.out.println("EmbeddedH2OConfig: exit caught an exception 2");
        e.printStackTrace();
      }

      System.exit(111);
    }

    @Override
    public void print() {
      System.out.println("EmbeddedH2OConfig print()");
      System.out.println("    Driver callback IP: " + ((_driverCallbackIp != null) ? _driverCallbackIp : "(null)"));
      System.out.println("    Driver callback port: " + _driverCallbackPort);
      System.out.println("    Embedded webserver IP: " + ((_embeddedWebServerIp != null) ? _embeddedWebServerIp : "(null)"));
      System.out.println("    Embedded webserver port: " + _embeddedWebServerPort);
    }
  }

  /**
   * Emit a bunch of logging output at the beginning of the map task.
   * @throws IOException
   * @throws InterruptedException
   */
  private void emitLogHeader(Context context, String mapredTaskId) throws IOException, InterruptedException {
    Configuration conf = context.getConfiguration();
    Text textId = new Text(mapredTaskId);

    for (Map.Entry<String, String> entry: conf) {
      StringBuilder sb = new StringBuilder();
      sb.append(entry.getKey());
      sb.append("=");
      sb.append(entry.getValue());
      context.write(textId, new Text(sb.toString()));
    }

    context.write(textId, new Text("----- Properties -----"));
    String[] plist = {
            "mapred.local.dir",
            "mapred.child.java.opts",
    };
    for (String k : plist) {
      String v = conf.get(k);
      if (v == null) {
        v = "(null)";
      }
      context.write(textId, new Text(k + " " + v));
    }
    String userDir = System.getProperty("user.dir");
    context.write(textId, new Text("user.dir " + userDir));

    try {
      java.net.InetAddress localMachine = java.net.InetAddress.getLocalHost();
      context.write(textId, new Text("hostname " + localMachine.getHostName()));
    }
    catch (java.net.UnknownHostException uhe) {
      // handle exception
    }
  }

  /**
   * Identify hadoop mapper counter
   */
  public static enum H2O_MAPPER_COUNTER {
    HADOOP_COUNTER_HEARTBEAT
  }

  /**
   * Hadoop heartbeat keepalive thread.  Periodically update a counter so that
   * jobtracker knows not to kill the job.
   */
  public class CounterThread extends Thread {
    Context _context;
    Counter _counter;
    final int TEN_SECONDS_MILLIS = 10 * 1000;

    CounterThread (Context context, Counter counter) {
      _context = context;
      _counter = counter;
    }

    @Override
    public void run() {
      while (true) {
        _context.progress();
        _counter.increment(1);
        try {
          Thread.sleep (TEN_SECONDS_MILLIS);
        }
        catch (Exception e) {}
      }
    }
  }

  private int run2(Context context) throws IOException, InterruptedException {
    Configuration conf = context.getConfiguration();
    String mapredTaskId = conf.get("mapred.task.id");
    Text textId = new Text(mapredTaskId);

    emitLogHeader(context, mapredTaskId);
    Log.POST(10, "After emitLogHeader");

    Counter counter = context.getCounter(H2O_MAPPER_COUNTER.HADOOP_COUNTER_HEARTBEAT);
    Thread counterThread = new CounterThread(context, counter);
    counterThread.start();

    String mapredLocalDir = conf.get("mapred.local.dir");
    String ice_root;
    if (mapredLocalDir.contains(",")) {
      ice_root = mapredLocalDir.split(",")[0];
    }
    else {
      ice_root = mapredLocalDir;
    }

    String jobtrackerName = conf.get(H2O_JOBTRACKERNAME_KEY);
    context.write(textId, new Text("mapred.local.dir is " + ice_root));
    String driverIp = conf.get(H2O_DRIVER_IP_KEY);
    String driverPortString = conf.get(H2O_DRIVER_PORT_KEY);
    int driverPort = Integer.parseInt(driverPortString);
    String network = conf.get(H2O_NETWORK_KEY);
    String nthreadsString = conf.get(H2O_NTHREADS_KEY);
    String basePortString = conf.get(H2O_BASE_PORT_KEY);
    String betaString = conf.get(H2O_BETA_KEY);
    String randomUdpDropString = conf.get(H2O_RANDOM_UDP_DROP_KEY);
    String licenseData = conf.get(H2O_LICENSE_DATA_KEY);

    ServerSocket ss = new ServerSocket();
    InetSocketAddress sa = new InetSocketAddress("127.0.0.1", 0);
    ss.bind(sa);
    int localPort = ss.getLocalPort();

    List<String> argsList = new ArrayList<String>();

    // Options used by H2O.
    argsList.add("-ice_root");
    argsList.add(ice_root);
    argsList.add("-name");
    argsList.add(jobtrackerName);
    argsList.add("-hdfs_skip");
    if (network != null) {
      if (network.length() > 0) {
        argsList.add("-network");
        argsList.add(network);
      }
    }
    if (nthreadsString != null) {
      if (nthreadsString.length() > 0) {
        argsList.add("-nthreads");
        int nthreads = Integer.parseInt(nthreadsString);
        argsList.add(Integer.toString(nthreads));
      }
    }
    if (basePortString != null) {
      if (basePortString.length() > 0) {
        argsList.add("-baseport");
        int basePort = Integer.parseInt(basePortString);
        argsList.add(Integer.toString(basePort));
      }
    }
    if (betaString != null) {
      if (betaString.length() > 0) {
        argsList.add(betaString);
      }
    }
    if (randomUdpDropString != null) {
      if (randomUdpDropString.length() > 0) {
        argsList.add(randomUdpDropString);
      }
    }
    if (licenseData != null) {
      if (licenseData.length() > 0) {
        Log.POST(100, "Before writing license file");
        Log.POST(101, ice_root);
        File f = new File(ice_root);
        boolean b = f.exists();
        Log.POST(102, b ? "exists" : "does not exist");
        if (! b) {
          Log.POST(103, "before mkdirs()");
          f.mkdirs();
          Log.POST(104, "after mkdirs()");
        }
        String fileName = ice_root + File.separator + "h2o_license.txt";
        PrintWriter out = new PrintWriter(fileName);
        out.print(licenseData);
        out.close();
        argsList.add("-license");
        argsList.add(fileName);
      }
    }

    context.write(textId, new Text("before water.H2O.main()"));
    String[] args = (String[]) argsList.toArray(new String[0]);
    try {
      _embeddedH2OConfig = new EmbeddedH2OConfig();
      _embeddedH2OConfig.setDriverCallbackIp(driverIp);
      _embeddedH2OConfig.setDriverCallbackPort(driverPort);
      _embeddedH2OConfig.setMapperCallbackPort(localPort);
      H2O.setEmbeddedH2OConfig(_embeddedH2OConfig);
      Log.POST(11, "After register");
      water.H2OApp.main(args);
      Log.POST(12, "After main");
    }
    catch (Exception e) {
      Log.POST(13, "Exception in main");
      context.write(textId, new Text("exception in water.H2O.main()"));

      String s = e.getMessage();
      if (s == null) { s = "(null exception message)"; }
      context.write(textId, new Text(s));

      s = e.toString();
      if (s == null) { s = "(null exception toString)"; }
      context.write(textId, new Text(s));

      StackTraceElement[] els = e.getStackTrace();
      for (int i = 0; i < els.length; i++) {
        StackTraceElement el = els[i];
        s = el.toString();
        context.write(textId, new Text("    " + s));
      }
    }
    finally {
      Log.POST(14, "Top of finally");
      context.write(textId, new Text("after water.H2O.main()"));
    }

    Log.POST(15, "Waiting for exit");
    // EmbeddedH2OConfig will send a one-byte exit status to this socket.
    Socket sock = ss.accept();
    System.out.println("Wait for exit woke up from accept");
    byte[] b = new byte[1];
    InputStream is = sock.getInputStream();
    int expectedBytes = 1;
    int receivedBytes = 0;
    while (receivedBytes < expectedBytes) {
      int n = is.read(b, receivedBytes, expectedBytes-receivedBytes);
      System.out.println("is.read returned " + n);
      if (n < 0) {
        System.exit(112);
      }
      receivedBytes += n;
    }

    int exitStatus = (int)b[0];
    System.out.println("Received exitStatus " + exitStatus);
    return exitStatus;
  }

  @Override
  public void run(Context context) throws IOException, InterruptedException {
    try {
      Log.POST(0, "Entered run");

      setup(context);

      // "Consume" mapped input.
      while (context.nextKeyValue()) {
      }

      int exitStatus = run2(context);
      cleanup(context);

      Log.POST(1000, "Leaving run");
      System.out.println("Exiting with status " + exitStatus);
      System.out.flush();
      if (exitStatus != 0) {
        System.exit(exitStatus);
      }
    }
    catch (Exception e) {
      Log.POST(999, e);
      System.exit(100);
    }

    System.out.println("Exiting mapper run method");
    System.out.flush();
  }

  /**
   * For debugging only.
   */
  public static void main (String[] args) {
    try {
      h2omapper m = new h2omapper();
      m.run(null);
    }
    catch (Exception e) {
      System.out.println (e);
    }
  }
}
