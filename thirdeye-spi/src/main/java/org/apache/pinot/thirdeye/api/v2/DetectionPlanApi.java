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
 *
 */

package org.apache.pinot.thirdeye.api.v2;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * DetectionPlanApi is self-described detection plan node.
 */
@JsonInclude(Include.NON_NULL)
public class DetectionPlanApi {

  /**
   * Unique planNodeName been referred across the entire AlertEvaluation plan
   */
  private String planNodeName;
  /**
   * PlanNode type, which is registered by the implementation of
   * 'org.apache.pinot.thirdeye.detection.v2.spi.PlanNode'.
   */
  private String type;
  /**
   * Customized params to init the PlanNode.
   */
  private Map<String, Object> params;
  /**
   * Defines the inputs of this PlanNode are set
   */
  private List<InputApi> inputs;
  /**
   * Defines the output mapping layout
   */
  private List<OutputApi> outputs;

  public String getPlanNodeName() {
    return planNodeName;
  }

  public DetectionPlanApi setPlanNodeName(final String planNodeName) {
    this.planNodeName = planNodeName;
    return this;
  }

  public String getType() {
    return type;
  }

  public DetectionPlanApi setType(final String type) {
    this.type = type;
    return this;
  }

  public Map<String, Object> getParams() {
    return params;
  }

  public DetectionPlanApi setParams(final Map<String, Object> params) {
    this.params = params;
    return this;
  }

  public List<InputApi> getInputs() {
    return inputs;
  }

  public DetectionPlanApi setInputs(
      final List<InputApi> inputs) {
    this.inputs = inputs;
    return this;
  }

  public List<OutputApi> getOutputs() {
    return outputs;
  }

  public DetectionPlanApi setOutputs(
      final List<OutputApi> outputs) {
    this.outputs = outputs;
    return this;
  }

  @Override
  public String toString() {
    try {
      return new ObjectMapper().writeValueAsString(this);
    } catch (JsonProcessingException e) {
      return this.toString();
    }
  }

  /**
   * InputApi defines how to describe the input for a plan plan.
   * 'targetProperty' is the key used to refer to the Input object
   * 'sourcePlanNode' is the plan plan name which contains the output
   * 'sourceProperty' is the key of the output from 'sourcePlanNode'
   */
  @JsonInclude(Include.NON_NULL)
  public static class InputApi {

    private String targetProperty;
    private String sourcePlanNode;
    private String sourceProperty;

    public String getTargetProperty() {
      return targetProperty;
    }

    public InputApi setTargetProperty(final String targetProperty) {
      this.targetProperty = targetProperty;
      return this;
    }

    public String getSourcePlanNode() {
      return sourcePlanNode;
    }

    public InputApi setSourcePlanNode(final String sourcePlanNode) {
      this.sourcePlanNode = sourcePlanNode;
      return this;
    }

    public String getSourceProperty() {
      return sourceProperty;
    }

    public InputApi setSourceProperty(final String sourceProperty) {
      this.sourceProperty = sourceProperty;
      return this;
    }

    @Override
    public String toString() {
      try {
        return new ObjectMapper().writeValueAsString(this);
      } catch (JsonProcessingException e) {
        return this.toString();
      }
    }
  }

  /**
   * OutputApi defines how to describe the output for a plan node.
   * Users can set this to rename the output from `outputKey` to `outputName`.
   * E.g. By default DataFetcher always set output to key `output`. Users can rename it
   * from `output` to `baselineOutput` by set OutputApi like:
   * { "outputKey": "output", "outputName": "baselineOutput" }
   */
  @JsonInclude(Include.NON_NULL)
  public static class OutputApi {

    private String outputKey;
    private String outputName;

    public String getOutputKey() {
      return outputKey;
    }

    public OutputApi setOutputKey(final String outputKey) {
      this.outputKey = outputKey;
      return this;
    }

    public String getOutputName() {
      return outputName;
    }

    public OutputApi setOutputName(final String outputName) {
      this.outputName = outputName;
      return this;
    }

    @Override
    public String toString() {
      try {
        return new ObjectMapper().writeValueAsString(this);
      } catch (JsonProcessingException e) {
        return this.toString();
      }
    }
  }
}
