/*
 * Copyright 2023 StarTree Inc
 *
 * Licensed under the StarTree Community License (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.startree.ai/legal/startree-community-license
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT * WARRANTIES OF ANY KIND,
 * either express or implied.
 * See the License for the specific language governing permissions and limitations under
 * the License.
 */
package ai.startree.thirdeye;

import static ai.startree.thirdeye.DropwizardTestUtils.alertEvaluationApi;
import static ai.startree.thirdeye.DropwizardTestUtils.buildClient;
import static ai.startree.thirdeye.DropwizardTestUtils.buildSupport;
import static ai.startree.thirdeye.DropwizardTestUtils.loadAlertApi;
import static ai.startree.thirdeye.IntegrationTestUtils.NODE_NAME_CHILD_ROOT;
import static ai.startree.thirdeye.IntegrationTestUtils.NODE_NAME_ROOT;
import static ai.startree.thirdeye.IntegrationTestUtils.combinerNode;
import static ai.startree.thirdeye.IntegrationTestUtils.enumeratorNode;
import static ai.startree.thirdeye.IntegrationTestUtils.forkJoinNode;
import static ai.startree.thirdeye.PinotDataSourceManager.PINOT_DATASET_NAME;
import static ai.startree.thirdeye.PinotDataSourceManager.PINOT_DATA_SOURCE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import ai.startree.thirdeye.config.ThirdEyeServerConfiguration;
import ai.startree.thirdeye.datalayer.MySqlTestDatabase;
import ai.startree.thirdeye.datalayer.util.DatabaseConfiguration;
import ai.startree.thirdeye.spi.api.AlertApi;
import ai.startree.thirdeye.spi.api.AlertEvaluationApi;
import ai.startree.thirdeye.spi.api.AlertInsightsApi;
import ai.startree.thirdeye.spi.api.AlertTemplateApi;
import ai.startree.thirdeye.spi.api.AnomalyApi;
import ai.startree.thirdeye.spi.api.AnomalyFeedbackApi;
import ai.startree.thirdeye.spi.api.CountApi;
import ai.startree.thirdeye.spi.api.DataSourceApi;
import ai.startree.thirdeye.spi.api.DimensionAnalysisResultApi;
import ai.startree.thirdeye.spi.api.EmailSchemeApi;
import ai.startree.thirdeye.spi.api.HeatMapResponseApi;
import ai.startree.thirdeye.spi.api.NotificationSchemesApi;
import ai.startree.thirdeye.spi.api.PlanNodeApi;
import ai.startree.thirdeye.spi.api.RcaInvestigationApi;
import ai.startree.thirdeye.spi.api.SubscriptionGroupApi;
import ai.startree.thirdeye.spi.detection.AnomalyCause;
import ai.startree.thirdeye.spi.detection.AnomalyFeedbackType;
import io.dropwizard.testing.DropwizardTestSupport;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

/**
 * Smoke tests with **Pinot** Datasource and **MySQL** persistence.
 * Test the following flow (the happy path):
 * - create a Pinot datasource
 * - create a dataset
 * - evaluate an alert
 * - create an alert
 * - create a subscription
 * - get anomalies
 * - get a single anomaly
 * - get the anomaly breakdown (heatmap)
 */
public class HappyPathTest {

  public static final GenericType<List<AnomalyApi>> ANOMALIES_LIST_TYPE = new GenericType<>() {};
  public static final GenericType<List<AlertApi>> ALERT_LIST_TYPE = new GenericType<>() {};
  public static final String THRESHOLD_TEMPLATE_NAME = "startree-threshold";
  private static final Logger log = LoggerFactory.getLogger(HappyPathTest.class);

  private static final AlertApi MAIN_ALERT_API;
  private static final long EVALUATE_END_TIME = 1596326400000L;
  private static final long PAGEVIEWS_DATASET_START_TIME_PLUS_ONE_DAY = 1580688000000L;
  private static final long PAGEVIEWS_DATASET_END_TIME_PLUS_ONE_DAY = 1596153600000L;
  private static final long PAGEVIEWS_DATASET_END_TIME = 1596067200000L;
  private static final long PAGEVIEWS_DATASET_START_TIME = 1580601600000L;

  static {
    try {
      MAIN_ALERT_API = loadAlertApi("/happypath/payloads/" + "alert.json");
    } catch (final IOException e) {
      throw new RuntimeException(String.format("Could not load alert json: %s", e));
    }
  }

  private DataSourceApi pinotDataSourceApi;
  private DropwizardTestSupport<ThirdEyeServerConfiguration> SUPPORT;
  private Client client;

  // this attribute is shared between tests
  private long anomalyId;
  private long alertId;

  @BeforeClass
  public void beforeClass() throws Exception {
    pinotDataSourceApi = PinotDataSourceManager.getPinotDataSourceApi();
    final DatabaseConfiguration dbConfiguration = MySqlTestDatabase.sharedDatabaseConfiguration();

    // Setup plugins dir so ThirdEye can load it
    IntegrationTestUtils.setupPluginsDirAbsolutePath();

    SUPPORT = buildSupport(dbConfiguration, "happypath/config/server.yaml");
    SUPPORT.before();
    client = buildClient("happy-path-test-client", SUPPORT);
  }

  @AfterClass(alwaysRun = true)
  public void afterClass() {
    log.info("Stopping Thirdeye at port: {}", SUPPORT.getLocalPort());
    SUPPORT.after();
    MySqlTestDatabase.cleanSharedDatabase();
  }

  @Test()
  public void testPing() {
    final Response response = request("internal/ping").get();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test(dependsOnMethods = "testPing")
  public void testCreatePinotDataSource() {

    final Response response = request("api/data-sources").post(Entity.json(List.of(
        pinotDataSourceApi)));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test(dependsOnMethods = "testCreatePinotDataSource", timeOut = 5000)
  public void testPinotDataSourceHealth() {
    final Response response = request(
        "api/data-sources/validate?name=" + pinotDataSourceApi.getName()).get();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test(dependsOnMethods = "testPing")
  public void testCreateDxTemplate() {
    final Response response = request("/api/alert-templates/name/" + THRESHOLD_TEMPLATE_NAME).get();
    assertThat(response.getStatus()).isEqualTo(200);

    final AlertTemplateApi template = response.readEntity(AlertTemplateApi.class);
    final List<PlanNodeApi> nodes = template.getNodes();
    final List<PlanNodeApi> childRootNodes = nodes
        .stream()
        .filter(node -> NODE_NAME_ROOT.equals(node.getName()))
        .collect(Collectors.toList());
    assertThat(childRootNodes).hasSize(1);

    childRootNodes.iterator().next().setName(NODE_NAME_CHILD_ROOT);
    nodes.add(enumeratorNode());
    nodes.add(forkJoinNode());
    nodes.add(combinerNode());

    template
        .setName(template.getName() + "-dx")
        .setId(null);

    final Response updateResponse = request("/api/alert-templates")
        .post(Entity.json(List.of(template)));
    assertThat(updateResponse.getStatus()).isEqualTo(200);
  }

  @Test(dependsOnMethods = "testPinotDataSourceHealth")
  public void testCreateDataset() {
    final MultivaluedMap<String, String> formData = new MultivaluedHashMap<>();
    formData.add("dataSourceName", PINOT_DATA_SOURCE_NAME);
    formData.add("datasetName", PINOT_DATASET_NAME);

    final Response response = request("api/data-sources/onboard-dataset/").post(
        Entity.form(formData));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test(dependsOnMethods = "testCreateDataset", timeOut = 7000)
  public void testEvaluateAlert() {
    final AlertEvaluationApi alertEvaluationApi = alertEvaluationApi(MAIN_ALERT_API,
        PAGEVIEWS_DATASET_START_TIME, EVALUATE_END_TIME);

    final Response response = request("api/alerts/evaluate").post(Entity.json(alertEvaluationApi));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test(dependsOnMethods = "testEvaluateAlert")
  public void testCreateAlert() {
    final Response response = request("api/alerts").post(Entity.json(List.of(MAIN_ALERT_API)));

    assertThat(response.getStatus()).isEqualTo(200);
    final List<AlertApi> alerts = response.readEntity(ALERT_LIST_TYPE);
    alertId = alerts.get(0).getId();
  }

  @DataProvider(name = "happyPathAlerts")
  public Object[][] happyPathAlerts() {
    // one alert for each template
    return new Object[][]{
        {"startree-absolute-rule-alert.json"},
        {"startree-absolute-rule-percentile-alert.json"},
        {"startree-mean-variance-alert.json"},
        {"startree-mean-variance-percentile-alert.json"},
        {"startree-percentage-rule-alert.json"},
        {"startree-percentage-rule-percentile-alert.json"},
        {"startree-threshold-alert.json"},
        {"startree-threshold-percentile-alert.json"}
    };
  }

  @Test(dependsOnMethods = "testEvaluateAlert", timeOut = 10000L, dataProvider = "happyPathAlerts")
  public void testEvaluateAllHappyPathAlerts(final String alertJson) throws IOException {
    final AlertApi alertApi = loadAlertApi("/happypath/payloads/" + alertJson);
    final AlertEvaluationApi alertEvaluationApi = alertEvaluationApi(alertApi,
        PAGEVIEWS_DATASET_START_TIME, EVALUATE_END_TIME);

    final Response response = request("api/alerts/evaluate").post(Entity.json(alertEvaluationApi));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test(dependsOnMethods = "testCreateAlert")
  public void testAlertInsights() {
    final Response response = request("api/alerts/" + alertId + "/insights").get();
    assertThat(response.getStatus()).isEqualTo(200);
    final AlertInsightsApi insights = response.readEntity(AlertInsightsApi.class);
    assertThat(insights.getTemplateWithProperties().getMetadata().getGranularity()).isEqualTo(
        "P1D");
    assertThat(insights.getDefaultStartTime()).isEqualTo(PAGEVIEWS_DATASET_START_TIME_PLUS_ONE_DAY);
    assertThat(insights.getDefaultEndTime()).isEqualTo(PAGEVIEWS_DATASET_END_TIME_PLUS_ONE_DAY);
    assertThat(insights.getDatasetStartTime()).isEqualTo(PAGEVIEWS_DATASET_START_TIME);
    assertThat(insights.getDatasetEndTime()).isEqualTo(PAGEVIEWS_DATASET_END_TIME);
  }

  @Test(dependsOnMethods = "testCreateAlert")
  public void testCreateSubscription() {
    final SubscriptionGroupApi subscriptionGroupApi = new SubscriptionGroupApi().setName(
            "testSubscription")
        .setCron("")
        .setNotificationSchemes(new NotificationSchemesApi().setEmail(
            new EmailSchemeApi().setTo(List.of("analyst@fake.mail"))))
        .setAlerts(List.of(new AlertApi().setId(alertId)));
    final Response response = request("api/subscription-groups").post(
        Entity.json(List.of(subscriptionGroupApi)));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test(dependsOnMethods = "testCreateAlert", timeOut = 50000L)
  public void testGetAnomalies() throws InterruptedException {
    // test get anomalies
    // need to wait for the taskRunner to run the onboard task - can take some time
    List<AnomalyApi> anomalies = List.of();
    while (anomalies.size() == 0) {
      // see taskDriver server config for optimization
      Thread.sleep(1000);
      final Response response = request("api/anomalies?isChild=false").get();
      assertThat(response.getStatus()).isEqualTo(200);
      anomalies = response.readEntity(ANOMALIES_LIST_TYPE);
    }
    // the third anomaly is the March 21 - March 23 anomaly
    assertThat(anomalies.get(2).getStartTime().getTime()).isEqualTo(1584748800000L);
    assertThat(anomalies.get(2).getEndTime().getTime()).isEqualTo(1584921600000L);
    anomalyId = anomalies.get(2).getId();
  }

  @Test(dependsOnMethods = "testGetAnomalies")
  public void testGetSingleAnomaly() {
    // test get a single anomaly
    final Response response = request("api/anomalies/" + anomalyId).get();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test(dependsOnMethods = "testGetAnomalies")
  public void testEvaluateWithAlertIdOnly() {
    // corresponds to the evaluate call performed from an anomaly page - only the alertId is passed
    final AlertEvaluationApi alertEvaluationApi = alertEvaluationApi(new AlertApi().setId(alertId),
        PAGEVIEWS_DATASET_START_TIME, EVALUATE_END_TIME);

    final Response response = request("api/alerts/evaluate").post(Entity.json(alertEvaluationApi));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test(dependsOnMethods = "testGetAnomalies")
  public void testAnomalyFeedback() {
    final Response responseBeforeFeedback = request("api/anomalies/" + anomalyId).get();
    assertThat(responseBeforeFeedback.getStatus()).isEqualTo(200);
    final AnomalyApi anomalyApiBefore = responseBeforeFeedback.readEntity(AnomalyApi.class);
    assertThat(anomalyApiBefore.getFeedback()).isNull();

    final AnomalyFeedbackApi feedback = new AnomalyFeedbackApi()
        .setType(AnomalyFeedbackType.ANOMALY)
        .setComment("Valid anomaly");
    final Response response = request(String.format("api/anomalies/%d/feedback", anomalyId))
        .post(Entity.json(feedback));
    assertThat(response.getStatus()).isEqualTo(200);

    // test get a single anomaly
    final Response responseAfterFeedback = request("api/anomalies/" + anomalyId).get();
    final AnomalyApi anomalyApi = responseAfterFeedback.readEntity(AnomalyApi.class);

    assertThat(responseAfterFeedback.getStatus()).isEqualTo(200);
    final AnomalyFeedbackApi actual = anomalyApi.getFeedback();
    assertThat(actual).isNotNull();
    assertThat(actual.getId()).isNotNull();
    assertThat(actual.getType()).isEqualTo(feedback.getType());
    assertThat(actual.getComment()).isEqualTo(feedback.getComment());
    assertThat(actual.getCreated()).isNotNull();
    assertThat(actual.getUpdated()).isNotNull();
    assertThat(actual.getOwner().getPrincipal()).isEqualTo("no-auth-user");
    assertThat(actual.getUpdatedBy().getPrincipal()).isEqualTo("no-auth-user");
  }

  @Test(dependsOnMethods = "testAnomalyFeedback")
  public void testAnomalyFeedback_updateFeedback() {
    final Response responseBeforeFeedback = request("api/anomalies/" + anomalyId).get();
    assertThat(responseBeforeFeedback.getStatus()).isEqualTo(200);
    final AnomalyApi anomalyApiBefore = responseBeforeFeedback.readEntity(AnomalyApi.class);
    final AnomalyFeedbackApi feedbackBefore = anomalyApiBefore.getFeedback();
    assertThat(feedbackBefore).isNotNull();

    final AnomalyFeedbackApi feedback = new AnomalyFeedbackApi()
        .setId(feedbackBefore.getId())
        .setType(AnomalyFeedbackType.NOT_ANOMALY)
        .setCause(AnomalyCause.PLATFORM_UPGRADE);
    final Response response = request(String.format("api/anomalies/%d/feedback", anomalyId))
        .post(Entity.json(feedback));
    assertThat(response.getStatus()).isEqualTo(200);

    // test get a single anomaly
    final Response responseAfterFeedback = request("api/anomalies/" + anomalyId).get();
    final AnomalyApi anomalyApi = responseAfterFeedback.readEntity(AnomalyApi.class);

    assertThat(responseAfterFeedback.getStatus()).isEqualTo(200);
    final AnomalyFeedbackApi actual = anomalyApi.getFeedback();
    assertThat(actual).isNotNull();
    assertThat(actual.getId()).isEqualTo(feedbackBefore.getId());
    assertThat(actual.getType()).isEqualTo(feedback.getType());
    assertThat(actual.getComment()).isEmpty();
    assertThat(actual.getCause()).isEqualTo(feedback.getCause());
    assertThat(actual.getUpdated()).isAfter(feedbackBefore.getUpdated());
  }

  @Test(dependsOnMethods = "testGetSingleAnomaly")
  public void testAnomalyCount() {
    // without filters
    Response response = request("api/anomalies/count").get();
    assertThat(response.getStatus()).isEqualTo(200);
    Long anomalyCount = response.readEntity(CountApi.class).getCount();
    assertThat(anomalyCount).isEqualTo(22);

    // there are only 6 parent anomalies
    response = request("api/anomalies/count?isChild=false").get();
    assertThat(response.getStatus()).isEqualTo(200);
    anomalyCount = response.readEntity(CountApi.class).getCount();
    assertThat(anomalyCount).isEqualTo(6);

    // there are only 2 anomalies that have startTime greater than or equal this value
    long startTime = 1585353600000L;

    // with filters
    response = request("api/anomalies/count?isChild=false&startTime=[gte]" + startTime).get();
    assertThat(response.getStatus()).isEqualTo(200);
    anomalyCount = response.readEntity(CountApi.class).getCount();
    assertThat(anomalyCount).isEqualTo(3);
  }

  @Test(dependsOnMethods = "testAnomalyCount")
  // FIXME CYRIL - TEST IS IGNORED UNTIL updatedTime is fixed
  @Ignore
  public void testReplayIsIdemPotent() throws InterruptedException {
    // use update time as a way to know when the replay is done
    final long lastUpdatedTime = getAlertLastUpdatedTime();
    final Response beforeReplayResponse = request("api/anomalies").get();
    assertThat(beforeReplayResponse.getStatus()).isEqualTo(200);
    final List<AnomalyApi> beforeReplayAnomalies = beforeReplayResponse.readEntity(
        ANOMALIES_LIST_TYPE);

    final MultivaluedMap<String, String> formData = new MultivaluedHashMap<>();
    formData.add("start", String.valueOf(PAGEVIEWS_DATASET_START_TIME));
    final Response replayResponse = request("api/alerts/" + alertId + "/run").post(
        Entity.form(formData));
    assertThat(replayResponse.getStatus()).isEqualTo(200);

    while (getAlertLastUpdatedTime() == lastUpdatedTime) {
      Thread.sleep(1000);
    }

    final Response afterReplayResponse = request("api/anomalies").get();
    assertThat(afterReplayResponse.getStatus()).isEqualTo(200);
    final List<AnomalyApi> afterReplayAnomalies = afterReplayResponse.readEntity(ANOMALIES_LIST_TYPE);
    // the only contract of the replay is to be user-facing idempotent - hence this test can break if we chose in the implementation to save all anomalies, even the ones at replay
    assertThat(beforeReplayAnomalies).isEqualTo(afterReplayAnomalies);
  }

  @Test(dependsOnMethods = "testGetSingleAnomaly")
  public void testGetHeatmap() {
    final Response response = request("api/rca/metrics/heatmap?id=" + anomalyId).get();
    assertThat(response.getStatus()).isEqualTo(200);
    final HeatMapResponseApi heatmap = response.readEntity(HeatMapResponseApi.class);
    assertThat(heatmap.getBaseline().getBreakdown().size()).isGreaterThan(0);
    assertThat(heatmap.getCurrent().getBreakdown().size()).isGreaterThan(0);
  }

  @Test(dependsOnMethods = "testGetSingleAnomaly")
  public void testGetTopContributors() {
    final Response response = request("api/rca/dim-analysis?id=" + anomalyId).get();
    assertThat(response.getStatus()).isEqualTo(200);
    final DimensionAnalysisResultApi dimensionAnalysisResultApi = response.readEntity(
        DimensionAnalysisResultApi.class);
    assertThat(dimensionAnalysisResultApi.getResponseRows().size()).isGreaterThan(0);
  }

  @Test(dependsOnMethods = "testGetSingleAnomaly")
  public void testSaveInvestigation() {
    final RcaInvestigationApi rcaInvestigationApi = new RcaInvestigationApi().setName(
            "investigationName")
        .setText("textDescription")
        .setAnomaly(new AnomalyApi().setId(anomalyId))
        .setUiMetadata(Map.of("uiKey1", List.of(1, 2), "uiKey2", "foo"));

    final Response response = request("api/rca/investigations").post(
        Entity.json(List.of(rcaInvestigationApi)));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  private Builder request(final String urlFragment) {
    return client.target(endPoint(urlFragment)).request();
  }

  private String endPoint(final String pathFragment) {
    return String.format("http://localhost:%d/%s", SUPPORT.getLocalPort(), pathFragment);
  }

  private long getAlertLastUpdatedTime() {
    final Response getResponse = request("api/alerts/" + alertId).get();
    assertThat(getResponse.getStatus()).isEqualTo(200);
    final AlertApi alert = getResponse.readEntity(AlertApi.class);
    return alert.getUpdated().getTime();
  }
}


