package jenkinsci.plugins.influxdb;

import com.google.common.base.Strings;
import hudson.ProxyConfiguration;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkinsci.plugins.influxdb.generators.*;
import jenkinsci.plugins.influxdb.models.Target;
import jenkinsci.plugins.influxdb.renderer.MeasurementRenderer;
import jenkinsci.plugins.influxdb.renderer.ProjectNameRenderer;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDB.ConsistencyLevel;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InfluxDbPublicationService {

    /**
     * The logger.
     **/
    private static final Logger logger = Logger.getLogger(InfluxDbPublicationService.class.getName());

    /**
     * Shared HTTP client which can make use of connection and thread pooling.
     */
    private static final OkHttpClient httpClient = new OkHttpClient();

    /**
     * List of targets to write to
     */
    private List<Target> selectedTargets;

    /**
     * custom project name, overrides the project name with the specified value
     */
    private String customProjectName;

    /**
     * custom prefix, for example in multi branch pipelines, where every build is named
     * after the branch built and thus you have different builds called 'master' that report
     * different metrics.
     */
    private String customPrefix;

    /**
     * custom data, especially in pipelines, where additional information is calculated
     * or retrieved by Groovy functions which should be sent to InfluxDB
     * This can easily be done by calling
     * <p>
     * def myDataMap = [:]+
     * myDataMap['myKey'] = 'myValue'
     * step([$class: 'InfluxDbPublisher', target: myTarget, customPrefix: 'myPrefix', customData: myDataMap])
     * <p>
     * inside a pipeline script
     */
    private Map<String, Object> customData;

    /**
     * custom data tags, especially in pipelines, where additional information is calculated
     * or retrieved by Groovy functions which should be sent to InfluxDB
     * This can easily be done by calling
     *
     *   def myDataMapTags = [:]
     *   myDataMapTags['myKey'] = 'myValue'
     *   step([$class: 'InfluxDbPublisher', target: myTarget, customPrefix: 'myPrefix', customData: myDataMap, customDataTags: myDataMapTags])
     *
     * inside a pipeline script
     */
    private final Map<String, String> customDataTags;

    /**
     * custom tags that are sent to all measurements defined in customDataMaps.
     *
     * Example for a pipeline script:
     *
     * def myCustomDataMapTags = [:]
     * def myCustomTags = [:]
     * myCustomTags["buildResult"] = currentBuild.result
     * myCustomTags["NODE_LABELS"] = env.NODE_LABELS
     * myCustomDataMapTags["series1"] = myCustomTags
     * step([$class: 'InfluxDbPublisher',
     *       target: myTarget,
     *       customPrefix: 'myPrefix',
     *       customDataMap: myCustomDataMap,
     *       customDataMapTags: myCustomDataMapTags])
     */
    private final Map<String, Map<String, String>> customDataMapTags;

    /**
     * custom data maps, especially in pipelines, where additional information is calculated
     * or retrieved by Groovy functions which should be sent to InfluxDB.
     * <p>
     * This goes beyond customData since it allows to define multiple customData measurements
     * where the name of the measurement is defined as the key of the customDataMap.
     * <p>
     * Example for a pipeline script:
     * <p>
     * def myDataMap1 = [:]
     * def myDataMap2 = [:]
     * def myCustomDataMap = [:]
     * myDataMap1["myMap1Key1"] = 11 //first value of first map
     * myDataMap1["myMap1Key2"] = 12 //second value of first map
     * myDataMap2["myMap2Key1"] = 21 //first value of second map
     * myDataMap2["myMap2Key2"] = 22 //second value of second map
     * myCustomDataMap["series1"] = myDataMap1
     * myCustomDataMap["series2"] = myDataMap2
     * step([$class: 'InfluxDbPublisher', target: myTarget, customPrefix: 'myPrefix', customDataMap: myCustomDataMap])
     */
    private Map<String, Map<String, Object>> customDataMap;

    private final long timestamp;

    /**
     * Jenkins parameter/s which will be added as FieldSet to measurement 'jenkins_data'.
     * If parameter-value has a $-prefix, it will be resolved from current jenkins-job environment-properties.
     */
    private final String jenkinsEnvParameterField;

    /**
     * Jenkins parameter/s which will be added as TagSet to  measurement 'jenkins_data'.
     * If parameter-value has a $-prefix, it will be resolved from current jenkins-job environment-properties.
     */
    private final String jenkinsEnvParameterTag;

    /**
     * custom measurement name used for all measurement types
     * Overrides the default measurement names.
     * Default value is "jenkins_data"
     *
     * For custom data, prepends "custom_", i.e. "some_measurement"
     * becomes "custom_some_measurement".
     * Default custom name remains "jenkins_custom_data"
     */
    private final String measurementName;

    /**
     * Whether or not replace dashes with underscores in tags.
     * i.e. "my-custom-tag" --> "my_custom_tag"
     */
    private boolean replaceDashWithUnderscore;

    public InfluxDbPublicationService(List<Target> selectedTargets, String customProjectName, String customPrefix, Map<String, Object> customData, Map<String, String> customDataTags, Map<String, Map<String, String>> customDataMapTags, Map<String, Map<String, Object>> customDataMap, long timestamp, String jenkinsEnvParameterField, String jenkinsEnvParameterTag, String measurementName, boolean replaceDashWithUnderscore) {
        this.selectedTargets = selectedTargets;
        this.customProjectName = customProjectName;
        this.customPrefix = customPrefix;
        this.customData = customData;
        this.customDataTags = customDataTags;
        this.customDataMapTags = customDataMapTags;
        this.customDataMap = customDataMap;
        this.timestamp = timestamp;
        this.jenkinsEnvParameterField = jenkinsEnvParameterField;
        this.jenkinsEnvParameterTag = jenkinsEnvParameterTag;
        this.measurementName = measurementName;
        this.replaceDashWithUnderscore = replaceDashWithUnderscore;
    }

    public void perform(Run<?, ?> build, TaskListener listener) {
        // Logging
        listener.getLogger().println("[InfluxDB Plugin] Collecting data for publication in InfluxDB...");

        // Renderer to use for the metrics
        MeasurementRenderer<Run<?, ?>> measurementRenderer = new ProjectNameRenderer(customPrefix, customProjectName);

        // Points to write
        List<Point> pointsToWrite = new ArrayList<>();

        // Basic metrics
        JenkinsBasePointGenerator jGen = new JenkinsBasePointGenerator(measurementRenderer, customPrefix, build, timestamp, listener, jenkinsEnvParameterField, jenkinsEnvParameterTag, measurementName, replaceDashWithUnderscore);
        addPoints(pointsToWrite, jGen, listener);

        CustomDataPointGenerator cdGen = new CustomDataPointGenerator(measurementRenderer, customPrefix, build, timestamp, customData, customDataTags, measurementName, replaceDashWithUnderscore);
        if (cdGen.hasReport()) {
            listener.getLogger().println("[InfluxDB Plugin] Custom data found. Writing to InfluxDB...");
            addPoints(pointsToWrite, cdGen, listener);
        } else {
            logger.log(Level.FINE, "Data source empty: Custom Data");
        }

        CustomDataMapPointGenerator cdmGen = new CustomDataMapPointGenerator(measurementRenderer, customPrefix, build, timestamp, customDataMap, customDataMapTags, replaceDashWithUnderscore);
        if (cdmGen.hasReport()) {
            listener.getLogger().println("[InfluxDB Plugin] Custom data map found. Writing to InfluxDB...");
            addPoints(pointsToWrite, cdmGen, listener);
        } else {
            logger.log(Level.FINE, "Data source empty: Custom Data Map");
        }

        try {
            CoberturaPointGenerator cGen = new CoberturaPointGenerator(measurementRenderer, customPrefix, build, timestamp, replaceDashWithUnderscore);
            if (cGen.hasReport()) {
                listener.getLogger().println("[InfluxDB Plugin] Cobertura data found. Writing to InfluxDB...");
                addPoints(pointsToWrite, cGen, listener);
            }
        } catch (NoClassDefFoundError ignore) {
            logger.log(Level.FINE, "Plugin skipped: Cobertura");
        }

        try {
            RobotFrameworkPointGenerator rfGen = new RobotFrameworkPointGenerator(measurementRenderer, customPrefix, build, timestamp, replaceDashWithUnderscore);
            if (rfGen.hasReport()) {
                listener.getLogger().println("[InfluxDB Plugin] Robot Framework data found. Writing to InfluxDB...");
                addPoints(pointsToWrite, rfGen, listener);
            }
        } catch (NoClassDefFoundError ignore) {
            logger.log(Level.FINE, "Plugin skipped: Robot Framework");
        }

        try {
            JacocoPointGenerator jacoGen = new JacocoPointGenerator(measurementRenderer, customPrefix, build, timestamp, replaceDashWithUnderscore);
            if (jacoGen.hasReport()) {
                listener.getLogger().println("[InfluxDB Plugin] Jacoco data found. Writing to InfluxDB...");
                addPoints(pointsToWrite, jacoGen, listener);
            }
        } catch (NoClassDefFoundError ignore) {
            logger.log(Level.FINE, "Plugin skipped: JaCoCo");
        }

        try {
            PerformancePointGenerator perfGen = new PerformancePointGenerator(measurementRenderer, customPrefix, build, timestamp, replaceDashWithUnderscore);
            if (perfGen.hasReport()) {
                listener.getLogger().println("[InfluxDB Plugin] Performance data found. Writing to InfluxDB...");
                addPoints(pointsToWrite, perfGen, listener);
            }
        } catch (NoClassDefFoundError ignore) {
            logger.log(Level.FINE, "Plugin skipped: Performance");
        }

        SonarQubePointGenerator sonarGen = new SonarQubePointGenerator(measurementRenderer, customPrefix, build, timestamp, listener, replaceDashWithUnderscore);
        if (sonarGen.hasReport()) {
            listener.getLogger().println("[InfluxDB Plugin] SonarQube data found. Writing to InfluxDB...");
            addPoints(pointsToWrite, sonarGen, listener);
        } else {
            logger.log(Level.FINE, "Plugin skipped: SonarQube");
        }

        ChangeLogPointGenerator changeLogGen = new ChangeLogPointGenerator(measurementRenderer, customPrefix, build, timestamp, replaceDashWithUnderscore);
        if (changeLogGen.hasReport()) {
            listener.getLogger().println("[InfluxDB Plugin] Git ChangeLog data found. Writing to InfluxDB...");
            addPoints(pointsToWrite, changeLogGen, listener);
        } else {
            logger.log(Level.FINE, "Data source empty: Change Log");
        }

        try {
            PerfPublisherPointGenerator perfPublisherGen = new PerfPublisherPointGenerator(measurementRenderer, customPrefix, build, timestamp, replaceDashWithUnderscore);
            if (perfPublisherGen.hasReport()) {
                listener.getLogger().println("[InfluxDB Plugin] PerfPublisher data found. Writing to InfluxDB...");
                addPoints(pointsToWrite, perfPublisherGen, listener);
            }
        } catch (NoClassDefFoundError ignore) {
            logger.log(Level.FINE, "Plugin skipped: Performance Publisher");
        }

        for (Target target : selectedTargets) {
            String logMessage = "[InfluxDB Plugin] Publishing data to: " + target;
            logger.log(Level.FINE, logMessage);
            listener.getLogger().println(logMessage);

            URL url;
            try {
                url = new URL(target.getUrl());
            } catch (MalformedURLException e) {
                listener.getLogger().println("[InfluxDB Plugin] Skipping target due to invalid URL: " + target.getUrl());
                continue;
            }

            OkHttpClient.Builder httpClient = createHttpClient(url, target.isUsingJenkinsProxy());
            InfluxDB influxDB = Strings.isNullOrEmpty(target.getUsername()) ?
                    InfluxDBFactory.connect(target.getUrl(), httpClient) :
                    InfluxDBFactory.connect(target.getUrl(), target.getUsername(), Secret.toString(target.getPassword()), httpClient);
            writeToInflux(target, influxDB, pointsToWrite);
        }

        listener.getLogger().println("[InfluxDB Plugin] Completed.");
    }

    private void addPoints(List<Point> pointsToWrite, PointGenerator generator, TaskListener listener) {
        try {
            pointsToWrite.addAll(Arrays.asList(generator.generate()));
        } catch (Exception e) {
            listener.getLogger().println("[InfluxDB Plugin] Failed to collect data. Ignoring Exception:" + e);
        }
    }

    private OkHttpClient.Builder createHttpClient(URL url, boolean useProxy) {
        OkHttpClient.Builder builder = httpClient.newBuilder();
        ProxyConfiguration proxyConfig = Jenkins.getInstance().proxy;
        if (useProxy && proxyConfig != null) {
            builder.proxy(proxyConfig.createProxy(url.getHost()));
            if (proxyConfig.getUserName() != null) {
                builder.proxyAuthenticator((route, response) -> {
                    if (response.request().header("Proxy-Authorization") != null) {
                        return null; // Give up, we've already failed to authenticate.
                    }

                    String credential = Credentials.basic(proxyConfig.getUserName(), proxyConfig.getPassword());
                    return response.request().newBuilder().header("Proxy-Authorization", credential).build();
                });
            }
        }
        return builder;
    }

    private void writeToInflux(Target target, InfluxDB influxDB, List<Point> pointsToWrite) {
        /*
         * build batchpoints for a single write.
         */
        try {
            BatchPoints batchPoints = BatchPoints
                    .database(target.getDatabase())
                    .points(pointsToWrite.toArray(new Point[0]))
                    .retentionPolicy(target.getRetentionPolicy())
                    .consistency(ConsistencyLevel.ANY)
                    .build();
            influxDB.write(batchPoints);
        } catch (Exception e) {
            if (target.isExposeExceptions()) {
                throw new InfluxReportException(e);
            } else {
                //Exceptions not exposed by configuration. Just log and ignore.
                logger.log(Level.WARNING, "Could not report to InfluxDB. Ignoring Exception.", e);
            }
        }
    }
}
