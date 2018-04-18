/*
 * Copyright 2018 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.lib.fragment;

import com.streamsets.pipeline.api.ConfigDef;

public class FragmentConfigBean {

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "FragmentId",
      displayPosition = 10
  )
  public String fragmentId;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Fragment Instance Id",
      displayPosition = 20
  )
  public String fragmentInstanceId;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Pipeline Id",
      displayPosition = 30
  )
  public String pipelineId;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Pipeline Commit Id",
      displayPosition = 40
  )
  public String pipelineCommitId;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Pipeline Version",
      displayPosition = 50
  )
  public String pipelineVersion;
}