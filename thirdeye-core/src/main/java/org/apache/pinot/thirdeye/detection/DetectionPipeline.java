/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pinot.thirdeye.detection;

import static org.apache.pinot.thirdeye.detection.DetectionUtils.getSpecClassName;
import static org.apache.pinot.thirdeye.spi.util.SpiUtils.optional;

import com.google.common.base.Preconditions;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections4.MapUtils;
import org.apache.pinot.thirdeye.spi.common.dimension.DimensionMap;
import org.apache.pinot.thirdeye.spi.dataframe.BooleanSeries;
import org.apache.pinot.thirdeye.spi.dataframe.DataFrame;
import org.apache.pinot.thirdeye.spi.dataframe.LongSeries;
import org.apache.pinot.thirdeye.spi.dataframe.util.MetricSlice;
import org.apache.pinot.thirdeye.spi.datalayer.dto.AlertDTO;
import org.apache.pinot.thirdeye.spi.datalayer.dto.DatasetConfigDTO;
import org.apache.pinot.thirdeye.spi.datalayer.dto.MergedAnomalyResultDTO;
import org.apache.pinot.thirdeye.spi.datalayer.dto.MetricConfigDTO;
import org.apache.pinot.thirdeye.spi.detection.ConfigUtils;
import org.apache.pinot.thirdeye.spi.detection.DataProvider;
import org.apache.pinot.thirdeye.spi.detection.InputDataFetcher;
import org.apache.pinot.thirdeye.spi.detection.spec.AbstractSpec;
import org.apache.pinot.thirdeye.spi.detection.spi.components.BaseComponent;
import org.apache.pinot.thirdeye.spi.rootcause.impl.MetricEntity;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Period;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DetectionPipeline forms the root of the detection class hierarchy. It represents a wireframe
 * for implementing (intermittently stateful) executable pipelines on top of it.
 */
public abstract class DetectionPipeline {

  private static final String PROP_CLASS_NAME = "className";
  private static final Logger LOG = LoggerFactory.getLogger(DetectionPipeline.class);

  protected final DataProvider provider;
  protected final AlertDTO config;
  protected final long startTime;
  protected final long endTime;

  private DetectionPipelineFactory mockDetectionPipelineFactory;

  /**
   * Only used for testing. To be refactored. Please do not use.
   *
   * @param mockDetectionPipelineFactory
   * @return
   */
  @Deprecated
  public DetectionPipeline setMockDetectionPipelineFactory(
      final DetectionPipelineFactory mockDetectionPipelineFactory) {
    this.mockDetectionPipelineFactory = mockDetectionPipelineFactory;
    return this;
  }

  protected DetectionPipeline(DataProvider provider, AlertDTO config, long startTime,
      long endTime) {
    this.provider = provider;
    this.config = config;
    this.startTime = startTime;
    this.endTime = endTime;
    this.initComponents();
  }

  /**
   * Returns a detection result for the time range between {@code startTime} and {@code endTime}.
   *
   * @return detection result
   */
  public abstract DetectionPipelineResultV1 run() throws Exception;

  /**
   * Initialize all components in the pipeline
   */
  private void initComponents() {
    InputDataFetcher dataFetcher = new DefaultInputDataFetcher(this.provider, this.config.getId());
    Map<String, BaseComponent> instancesMap = config.getComponents();
    Map<String, Object> componentSpecs = config.getComponentSpecs();
    if (componentSpecs != null) {
      for (String componentKey : componentSpecs.keySet()) {
        Map<String, Object> componentSpec = ConfigUtils.getMap(componentSpecs.get(componentKey));
        if (!instancesMap.containsKey(componentKey)) {
          instancesMap.put(componentKey, createComponent(componentSpec));
        }
      }

      for (String componentKey : componentSpecs.keySet()) {
        Map<String, Object> componentSpec = ConfigUtils.getMap(componentSpecs.get(componentKey));
        for (Map.Entry<String, Object> entry : componentSpec.entrySet()) {
          if (entry.getValue() != null && DetectionUtils
              .isReferenceName(entry.getValue().toString())) {
            componentSpec.put(entry.getKey(),
                instancesMap.get(DetectionUtils.getComponentKey(entry.getValue().toString())));
          }
        }
        // Initialize the components
        instancesMap.get(componentKey).init(getComponentSpec(componentSpec), dataFetcher);
      }
    }
    config.setComponents(instancesMap);
  }

  private BaseComponent createComponent(Map<String, Object> componentSpec) {
    String className = MapUtils.getString(componentSpec, PROP_CLASS_NAME);
    try {
      Class<BaseComponent> clazz = (Class<BaseComponent>) Class.forName(className);
      return clazz.newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to create component for " + className,
          e.getCause());
    }
  }

  private AbstractSpec getComponentSpec(Map<String, Object> componentSpec) {
    String className = MapUtils.getString(componentSpec, PROP_CLASS_NAME);
    try {
      Class clazz = Class.forName(className);
      Class<AbstractSpec> specClazz = (Class<AbstractSpec>) Class.forName(getSpecClassName(clazz));
      return AbstractSpec.fromProperties(componentSpec, specClazz);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to get component spec for " + className, e);
    }
  }

  /**
   * Helper for creating an anomaly for a given metric slice. Injects properties such as
   * metric name, filter dimensions, etc.
   *
   * @param slice metric slice
   * @return anomaly template
   */
  protected final MergedAnomalyResultDTO makeAnomaly(MetricSlice slice) {
    Map<Long, MetricConfigDTO> metrics = this.provider
        .fetchMetrics(Collections.singleton(slice.getMetricId()));
    if (!metrics.containsKey(slice.getMetricId())) {
      throw new IllegalArgumentException(
          String.format("Could not resolve metric id %d", slice.getMetricId()));
    }

    MetricConfigDTO metric = metrics.get(slice.getMetricId());

    return makeAnomaly(slice, metric);
  }

  /**
   * Helper for creating an anomaly for a given metric slice. Injects properties such as
   * metric name, filter dimensions, etc.
   *
   * @param slice metric slice
   * @param metric metric config dto related to slice
   * @return anomaly template
   */
  protected final MergedAnomalyResultDTO makeAnomaly(MetricSlice slice, MetricConfigDTO metric) {
    MergedAnomalyResultDTO anomaly = new MergedAnomalyResultDTO();
    anomaly.setStartTime(slice.getStart());
    anomaly.setEndTime(slice.getEnd());
    anomaly.setMetric(metric.getName());
    anomaly.setCollection(metric.getDataset());
    anomaly.setMetricUrn(MetricEntity.fromSlice(slice, 1.0).getUrn());
    anomaly.setDimensions(toFilterMap(slice.getFilters()));
    anomaly.setDetectionConfigId(this.config.getId());
    anomaly.setChildren(new HashSet<MergedAnomalyResultDTO>());

    return anomaly;
  }

  /**
   * Helper for creating a list of anomalies from a boolean series. Injects properties via
   * {@code makeAnomaly(MetricSlice, MetricConfigDTO)}.
   *
   * @param slice metric slice
   * @param df time series with COL_TIME and at least one boolean value series
   * @param seriesName name of the value series
   * @return list of anomalies
   * @see DetectionPipeline#makeAnomaly(MetricSlice, MetricConfigDTO)
   */
  protected final List<MergedAnomalyResultDTO> makeAnomalies(MetricSlice slice, DataFrame df,
      String seriesName) {
    if (df.isEmpty()) {
      return Collections.emptyList();
    }

    df = df.filter(df.getLongs(DataFrame.COL_TIME).between(slice.getStart(), slice.getEnd()))
        .dropNull(
            DataFrame.COL_TIME);

    if (df.isEmpty()) {
      return Collections.emptyList();
    }

    Map<Long, MetricConfigDTO> metrics = this.provider
        .fetchMetrics(Collections.singleton(slice.getMetricId()));
    if (!metrics.containsKey(slice.getMetricId())) {
      throw new IllegalArgumentException(
          String.format("Could not resolve metric id %d", slice.getMetricId()));
    }

    MetricConfigDTO metric = metrics.get(slice.getMetricId());

    List<MergedAnomalyResultDTO> anomalies = new ArrayList<>();
    LongSeries sTime = df.getLongs(DataFrame.COL_TIME);
    BooleanSeries sVal = df.getBooleans(seriesName);

    int lastStart = -1;
    for (int i = 0; i < df.size(); i++) {
      if (sVal.isNull(i) || !BooleanSeries.booleanValueOf(sVal.get(i))) {
        // end of a run
        if (lastStart >= 0) {
          long start = sTime.get(lastStart);
          long end = sTime.get(i);
          anomalies.add(makeAnomaly(slice.withStart(start).withEnd(end), metric));
        }
        lastStart = -1;
      } else {
        // start of a run
        if (lastStart < 0) {
          lastStart = i;
        }
      }
    }

    // end of current run
    if (lastStart >= 0) {
      long start = sTime.get(lastStart);
      long end = start + 1;

      // guess-timate of next time series timestamp
      DatasetConfigDTO dataset = this.provider
          .fetchDatasets(Collections.singleton(metric.getDataset())).get(metric.getDataset());
      if (dataset != null) {
        Period period = dataset.bucketTimeGranularity().toPeriod();
        DateTimeZone timezone = DateTimeZone.forID(dataset.getTimezone());

        long lastTimestamp = sTime.getLong(sTime.size() - 1);

        end = new DateTime(lastTimestamp, timezone).plus(period).getMillis();
      }

      // truncate at analysis end time
      end = Math.min(end, this.endTime);

      anomalies.add(makeAnomaly(slice.withStart(start).withEnd(end), metric));
    }

    return anomalies;
  }

  /**
   * Helper to initialize and run the next level wrapper
   *
   * @param nestedProps nested properties
   * @return intermediate result of a detection pipeline
   */
  protected DetectionPipelineResultV1 runNested(
      Map<String, Object> nestedProps, final long startTime, final long endTime) throws Exception {
    Preconditions.checkArgument(nestedProps.containsKey(PROP_CLASS_NAME),
        "Nested missing " + PROP_CLASS_NAME);
    Map<String, Object> properties = new HashMap<>(nestedProps);
    AlertDTO nestedConfig = new AlertDTO();
    nestedConfig.setId(this.config.getId());
    nestedConfig.setName(this.config.getName());
    nestedConfig.setDescription(this.config.getDescription());
    nestedConfig.setComponents(this.config.getComponents());
    nestedConfig.setProperties(properties);

    final DetectionPipelineFactory detectionPipelineFactory = optional(mockDetectionPipelineFactory)
        .orElse(new DetectionPipelineFactory(provider));

    final DetectionPipeline pipeline = detectionPipelineFactory.get(
        new DetectionPipelineContext()
            .setAlert(nestedConfig)
            .setStart(startTime)
            .setEnd(endTime)
    );
    return pipeline.run();
  }

  // TODO anomaly should support multimap
  private DimensionMap toFilterMap(Multimap<String, String> filters) {
    DimensionMap map = new DimensionMap();
    for (Map.Entry<String, String> entry : filters.entries()) {
      map.put(entry.getKey(), entry.getValue());
    }
    return map;
  }

  public DataProvider getProvider() {
    return provider;
  }

  public AlertDTO getConfig() {
    return config;
  }

  public long getStartTime() {
    return startTime;
  }

  public long getEndTime() {
    return endTime;
  }
}
