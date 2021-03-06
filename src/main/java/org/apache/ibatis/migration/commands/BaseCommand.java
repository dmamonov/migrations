package org.apache.ibatis.migration.commands;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.ExternalResources;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.jdbc.SqlRunner;
import org.apache.ibatis.migration.Change;
import org.apache.ibatis.migration.MigrationException;
import org.apache.ibatis.migration.options.SelectedOptions;
import org.apache.ibatis.migration.options.SelectedPaths;
import org.apache.ibatis.parsing.PropertyParser;

import java.io.*;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.logging.Logger;

import static org.apache.ibatis.migration.utils.Util.file;

public abstract class BaseCommand implements Command {
  private static final String DATE_FORMAT = "yyyyMMddHHmmss";

  private ClassLoader driverClassLoader;

  protected PrintStream printStream = System.out;
  protected final SelectedOptions options;
  protected final SelectedPaths paths;

  protected BaseCommand(SelectedOptions selectedOptions) {
    this.options = selectedOptions;
    this.paths = selectedOptions.getPaths();
  }

  public void setDriverClassLoader(ClassLoader aDriverClassLoader) {
    driverClassLoader = aDriverClassLoader;
  }

  public void setPrintStream(PrintStream aPrintStream) {
    printStream = aPrintStream;
  }

  protected boolean paramsEmpty(String... params) {
    return params == null || params.length < 1 || params[0] == null || params[0].length() < 1;
  }

  protected List<Change> getMigrations() {
    final File myScriptPath = paths.getScriptPath();
    String[] filenames = myScriptPath.list();
    if (filenames == null) {
      throw new MigrationException(myScriptPath + " does not exist.");
    }
    Arrays.sort(filenames);
    List<Change> migrations = new ArrayList<Change>();
    for (String filename : filenames) {
      if (filename.endsWith(".sql") && !"bootstrap.sql".equals(filename)) {
        Change change = parseChangeFromFilename(filename);
        migrations.add(change);
      }
    }
    return migrations;
  }

  protected List<Change> getChangelog() {
    SqlRunner runner = getSqlRunner();
    try {
      List<Map<String, Object>> changelog =
          runner.selectAll("select ID, APPLIED_AT, DESCRIPTION from " + changelogTable() + " order by ID");
      List<Change> changes = new ArrayList<Change>();
      for (Map<String, Object> change : changelog) {
        String id = change.get("ID") == null ? null : change.get("ID").toString();
        String appliedAt = change.get("APPLIED_AT") == null ? null : change.get("APPLIED_AT").toString();
        String description = change.get("DESCRIPTION") == null ? null : change.get("DESCRIPTION").toString();
        changes.add(new Change(new BigDecimal(id), appliedAt, description));
      }
      return changes;
    } catch (SQLException e) {
      throw new MigrationException("Error querying last applied migration.  Cause: " + e, e);
    } finally {
      runner.closeConnection();
    }
  }

  protected String changelogTable() {
    String changelog = environmentProperties().getProperty("changelog");
    if (changelog == null) {
      changelog = "CHANGELOG";
    }
    return changelog;
  }

  protected Change getLastAppliedChange() {
    List<Change> changelog = getChangelog();
    return changelog.get(changelog.size() - 1);
  }

  protected boolean changelogExists() {
    SqlRunner runner = getSqlRunner();
    try {
      runner.selectAll("select ID, APPLIED_AT, DESCRIPTION from " + changelogTable());
      return true;
    } catch (SQLException e) {
      return false;
    } finally {
      runner.closeConnection();
    }
  }

  protected String horizontalLine(String caption, int length) {
    StringBuilder builder = new StringBuilder();
    builder.append("==========");
    if (caption.length() > 0) {
      caption = " " + caption + " ";
      builder.append(caption);
    }
    for (int i = 0; i < length - caption.length() - 10; i++) {
      builder.append("=");
    }
    return builder.toString();
  }

  protected String getNextIDAsString() {
    try {
      // Ensure that two subsequent calls are less likely to return the same value.
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      //ignore
    }
    String timezone = environmentProperties().getProperty("time_zone");
    if (timezone == null) {
      timezone = "GMT+0:00";
    }
    final SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT);
    final Date now = new Date();
    dateFormat.setTimeZone(TimeZone.getTimeZone(timezone));
    return dateFormat.format(now);
  }

  protected void copyResourceTo(String resource, File toFile) {
    copyResourceTo(resource, toFile, null);
  }

  protected void copyResourceTo(String resource, File toFile, Properties variables) {
    printStream.println("Creating: " + toFile.getName());
    try {
      LineNumberReader reader =
          new LineNumberReader(Resources.getResourceAsReader(this.getClass().getClassLoader(), resource));
      try {
        PrintWriter writer = new PrintWriter(new FileWriter(toFile));
        try {
          String line;
          while ((line = reader.readLine()) != null) {
            line = PropertyParser.parse(line, variables);
            writer.println(line);
          }
        } finally {
          writer.close();
        }
      } finally {
        reader.close();
      }
    } catch (IOException e) {
      throw new MigrationException(
          "Error copying " + resource + " to " + toFile.getAbsolutePath() + ".  Cause: " + e, e);
    }
  }

  protected void copyExternalResourceTo(String resource, File toFile) {
    printStream.println("Creating: " + toFile.getName());
    try {
      File sourceFile = new File(resource);
      ExternalResources.copyExternalResource(sourceFile, toFile);
    } catch (Exception e) {
      throw new MigrationException(
          "Error copying " + resource + " to " + toFile.getAbsolutePath() + ".  Cause: " + e, e);
    }
  }

  protected SqlRunner getSqlRunner() {
    try {
      Properties props = environmentProperties();
      String driver = props.getProperty("driver");
      String url = props.getProperty("url");
      String username = props.getProperty("username");
      String password = props.getProperty("password");

      lazyInitializeDriver(driver);

      UnpooledDataSource dataSource = new UnpooledDataSource(driver, url, username, password);
      dataSource.setDriverClassLoader(driverClassLoader);
      dataSource.setAutoCommit(true);
      return new SqlRunner(dataSource.getConnection());
    } catch (SQLException e) {
      throw new MigrationException("Could not create SqlRunner. Cause: " + e, e);
    }
  }

  protected ScriptRunner getScriptRunner() {
    try {
      Properties props = environmentProperties();
      String driver = props.getProperty("driver");
      String url = props.getProperty("url");
      String username = props.getProperty("username");
      String password = props.getProperty("password");

      lazyInitializeDriver(driver);

      PrintWriter outWriter = new PrintWriter(printStream);
      UnpooledDataSource dataSource = new UnpooledDataSource(driver, url, username, password);
      dataSource.setDriverClassLoader(driverClassLoader);
      dataSource.setAutoCommit(false);
      ScriptRunner scriptRunner = new ScriptRunner(dataSource.getConnection());
      scriptRunner.setStopOnError(!options.isForce());
      scriptRunner.setLogWriter(outWriter);
      scriptRunner.setErrorLogWriter(outWriter);
      scriptRunner.setEscapeProcessing(false);
      setPropertiesFromFile(scriptRunner, props);
      return scriptRunner;
    } catch (Exception e) {
      throw new MigrationException("Error creating ScriptRunner.  Cause: " + e, e);
    }
  }

  private void setPropertiesFromFile(ScriptRunner scriptRunner, Properties props) {
    String delimiterString = props.getProperty("delimiter");
    scriptRunner.setAutoCommit(Boolean.valueOf(props.getProperty("auto_commit")));
    scriptRunner.setDelimiter(delimiterString == null ? ";" : delimiterString);
    scriptRunner.setFullLineDelimiter(Boolean.valueOf(props.getProperty("full_line_delimiter")));
    scriptRunner.setSendFullScript(Boolean.valueOf(props.getProperty("send_full_script")));
    scriptRunner.setRemoveCRs(Boolean.valueOf(props.getProperty("remove_crs")));
  }

  protected File environmentFile() {
    return file(paths.getEnvPath(), options.getEnvironment() + ".properties");
  }

  protected File existingEnvironmentFile() {
    File envFile = environmentFile();
    if (!envFile.exists()) {
      throw new MigrationException("Environment file missing: " + envFile.getAbsolutePath());
    }
    return envFile;
  }

  private void lazyInitializeDriver(String driver) {
    try {
      File localDriverPath = getCustomDriverPath();
      if (driverClassLoader == null && localDriverPath.exists()) {
        List<URL> urlList = new ArrayList<URL>();
        for (File file : localDriverPath.listFiles()) {
          String filename = file.getCanonicalPath();
          if (!filename.startsWith("/")) {
            filename = "/" + filename;
          }
          urlList.add(new URL("jar:file:" + filename + "!/"));
          urlList.add(new URL("file:" + filename));
        }
        URL[] urls = urlList.toArray(new URL[urlList.size()]);
        driverClassLoader = new URLClassLoader(urls);

        // see http://www.kfu.com/~nsayer/Java/dyn-jdbc.html
        Driver d = (Driver) Class.forName(driver, true, driverClassLoader).newInstance();
        DriverManager.registerDriver(new DriverProxy(d));
      }
    } catch (Exception e) {
      throw new MigrationException("Error loading JDBC drivers. Cause: " + e, e);
    }
  }

  protected Properties environmentProperties() {
    FileInputStream fileInputStream = null;
    try {
      File file = existingEnvironmentFile();
      Properties props = new Properties();
      fileInputStream = new FileInputStream(file);
      props.load(fileInputStream);
      return props;
    } catch (IOException e) {
      throw new MigrationException("Error loading environment properties.  Cause: " + e, e);
    } finally {
      if (fileInputStream != null) {
        try {
          fileInputStream.close();
        } catch (IOException e) {
          //Nothing to do here
        }
      }
    }
  }

  protected void insertChangelog(Change change) {
    SqlRunner runner = getSqlRunner();
    change.setAppliedTimestamp(generateAppliedTimeStampAsString());
    try {
      runner.insert("insert into " + changelogTable() + " (ID, APPLIED_AT, DESCRIPTION) values (?,?,?)",
          change.getId(),
          change.getAppliedTimestamp(),
          change.getDescription());
    } catch (SQLException e) {
      throw new MigrationException("Error querying last applied migration.  Cause: " + e, e);
    } finally {
      runner.closeConnection();
    }
  }

  protected String generateAppliedTimeStampAsString() {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.sql.Date(System.currentTimeMillis()));
  }

  protected int getStepCountParameter(int defaultSteps, String... params) {
    final String stringParam = params.length > 0 ? params[0] : null;
    if (stringParam == null || "".equals(stringParam)) {
      return defaultSteps;
    } else {
      try {
        return Integer.parseInt(stringParam);
      } catch (NumberFormatException e) {
        throw new MigrationException("Invalid parameter passed to command: " + params[0]);
      }
    }
  }

  private File getCustomDriverPath() {
    String customDriverPath = environmentProperties().getProperty("driver_path");
    if (customDriverPath != null && customDriverPath.length() > 0) {
      return new File(customDriverPath);
    } else {
      return options.getPaths().getDriverPath();
    }
  }

  private Change parseChangeFromFilename(String filename) {
    try {
      Change change = new Change();
      String[] parts = filename.split("\\.")[0].split("_");
      change.setId(new BigDecimal(parts[0]));
      StringBuilder builder = new StringBuilder();
      for (int i = 1; i < parts.length; i++) {
        if (i > 1) {
          builder.append(" ");
        }
        builder.append(parts[i]);
      }
      change.setDescription(builder.toString());
      change.setFilename(filename);
      return change;
    } catch (Exception e) {
      throw new MigrationException("Error parsing change from file.  Cause: " + e, e);
    }
  }

  protected Reader scriptFileReader(File scriptFile) throws FileNotFoundException, UnsupportedEncodingException {
    InputStream inputStream = new FileInputStream(scriptFile);
    String charset = environmentProperties().getProperty("script_char_set");
    if (charset == null || charset.length() == 0) {
      return new InputStreamReader(inputStream);
    } else {
      return new InputStreamReader(inputStream, charset);
    }
  }

  private static class DriverProxy implements Driver {
    private Driver driver;

    DriverProxy(Driver d) {
      this.driver = d;
    }

    public boolean acceptsURL(String u) throws SQLException {
      return this.driver.acceptsURL(u);
    }

    public Connection connect(String u, Properties p) throws SQLException {
      return this.driver.connect(u, p);
    }

    public int getMajorVersion() {
      return this.driver.getMajorVersion();
    }

    public int getMinorVersion() {
      return this.driver.getMinorVersion();
    }

    public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) throws SQLException {
      return this.driver.getPropertyInfo(u, p);
    }

    public boolean jdbcCompliant() {
      return this.driver.jdbcCompliant();
    }

    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME); // requires JDK version 1.6
    }
  }

}
