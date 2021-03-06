/*
 * Copyright 2019 StreamSets Inc.
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
package com.streamsets.pipeline.stage.processor.controlHub;

import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.lib.el.RecordEL;
import com.streamsets.pipeline.lib.el.TimeEL;
import com.streamsets.pipeline.lib.el.TimeNowEL;
import com.streamsets.pipeline.lib.el.VaultEL;
import com.streamsets.pipeline.lib.http.GrizzlyClientCustomizer;
import com.streamsets.pipeline.lib.http.JerseyClientUtil;
import com.streamsets.pipeline.lib.util.ExceptionUtils;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.client.filter.EncodingFilter;
import org.glassfish.jersey.grizzly.connector.GrizzlyConnectorProvider;
import org.glassfish.jersey.logging.LoggingFeature;
import org.glassfish.jersey.message.GZipEncoder;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class HttpClientCommon {
  private static final String REQUEST_LOGGER_NM = "com.streamsets.http.RequestLogger";
  private static final java.util.logging.Logger REQUEST_LOGGER = java.util.logging.Logger.getLogger(REQUEST_LOGGER_NM);

  private static final String RESOURCE_CONFIG_NAME = "resourceUrl";
  private static final String HTTP_METHOD_CONFIG_NAME = "httpMethod";
  private static final String HEADER_CONFIG_NAME = "headers";
  private static final String SSL_CONFIG_PREFIX = "conf.tlsConfig.";
  private static final String VAULT_EL_PREFIX = VaultEL.PREFIX + ":read";

  private final HttpClientConfigBean jerseyClientConfig;

  private Stage.Context context;
  private Client client = null;
  private ELVars resourceVars;
  private ELVars methodVars;
  private ELVars headerVars;
  private ELEval resourceEval;
  private ELEval methodEval;
  private ELEval headerEval;
  private ClientBuilder clientBuilder;

  public HttpClientCommon(HttpClientConfigBean jerseyClientConfig) {
    this.jerseyClientConfig = jerseyClientConfig;
  }

  public List<Stage.ConfigIssue> init(List<Stage.ConfigIssue> issues, Stage.Context context) {
    this.context = context;
    if (jerseyClientConfig.tlsConfig.isEnabled()) {
      jerseyClientConfig.tlsConfig.init(
          context,
          Groups.TLS.name(),
          SSL_CONFIG_PREFIX,
          issues
      );
    }

    resourceVars = context.createELVars();
    resourceEval = context.createELEval(RESOURCE_CONFIG_NAME);

    methodVars = context.createELVars();
    methodEval = context.createELEval(HTTP_METHOD_CONFIG_NAME);

    headerVars = context.createELVars();
    headerEval = context.createELEval(HEADER_CONFIG_NAME);

    String proxyUsername = null;
    String proxyPassword = null;
    if(jerseyClientConfig.useProxy) {
      proxyUsername = jerseyClientConfig.proxy.resolveUsername(context, Groups.HTTP.name(), "conf.controlHubConfig.client.proxy.", issues);
      proxyPassword = jerseyClientConfig.proxy.resolvePassword(context, Groups.HTTP.name(), "conf.controlHubConfig.client.proxy.", issues);
    }

    // Validation succeeded so configure the client.
    if (issues.isEmpty()) {
      ClientConfig clientConfig = new ClientConfig()
          .property(ClientProperties.CONNECT_TIMEOUT, jerseyClientConfig.connectTimeoutMillis)
          .property(ClientProperties.READ_TIMEOUT, jerseyClientConfig.readTimeoutMillis)
          .property(ClientProperties.ASYNC_THREADPOOL_SIZE, jerseyClientConfig.numThreads);

      if(jerseyClientConfig.useProxy) {
          clientConfig = clientConfig.connectorProvider(new GrizzlyConnectorProvider(new GrizzlyClientCustomizer(
            jerseyClientConfig.useProxy,
            proxyUsername,
            proxyPassword
          )));
      }

      clientBuilder = ClientBuilder.newBuilder().withConfig(clientConfig);

      if (jerseyClientConfig.requestLoggingConfig.enableRequestLogging) {
        Feature feature = new LoggingFeature(
            REQUEST_LOGGER,
            Level.parse(jerseyClientConfig.requestLoggingConfig.logLevel),
            jerseyClientConfig.requestLoggingConfig.verbosity, jerseyClientConfig.requestLoggingConfig.maxEntitySize
        );
        clientBuilder = clientBuilder.register(feature);
      }

      configureCompression(clientBuilder);

      if (jerseyClientConfig.useProxy) {
        JerseyClientUtil.configureProxy(
          jerseyClientConfig.proxy.uri,
          proxyUsername,
          proxyPassword, clientBuilder
        );
      }

      JerseyClientUtil.configureSslContext(jerseyClientConfig.tlsConfig, clientBuilder);

      configureAuthAndBuildClient(clientBuilder, issues);
    }

    return issues;
  }

  private void configureCompression(ClientBuilder clientBuilder) {
    clientBuilder.register(GZipEncoder.class);
    clientBuilder.register(EncodingFilter.class);
  }

  /**
   * Helper to apply authentication properties to Jersey client.
   *
   * @param clientBuilder Jersey Client builder to configure
   */
  private void configureAuthAndBuildClient(
      ClientBuilder clientBuilder,
      List<Stage.ConfigIssue> issues
  ) {
    try {
      buildNewAuthenticatedClient(issues, false);
    } catch (Exception e) {
      // should not happen, since we passed throwExceptions as false above
      ExceptionUtils.throwUndeclared(e);
    }
  }

  private void buildNewAuthenticatedClient(
      List<Stage.ConfigIssue> issues,
      boolean throwExceptions
  ) {
    if (!throwExceptions && issues == null) {
      throw new IllegalArgumentException("issues list must be non-null if not throwing exceptions");
    }
    client = clientBuilder.build();
    client.property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true);
  }

  /**
   * Returns true if the request contains potentially sensitive information such as a vault:read EL.
   *
   * @return whether or not the request had sensitive information detected.
   */
  public boolean requestContainsSensitiveInfo(Map<String, String> headers, String requestBody) {
    boolean sensitive = false;
    for (Map.Entry<String, String> header : headers.entrySet()) {
      if (header.getKey().contains(VAULT_EL_PREFIX) || header.getValue().contains(VAULT_EL_PREFIX)) {
        sensitive = true;
        break;
      }
    }

    if (requestBody != null && requestBody.contains(VAULT_EL_PREFIX)) {
      sensitive = true;
    }

    return sensitive;
  }

  /**
   * Evaluates any EL expressions in the headers section of the stage configuration.
   *
   * @param record current record in context for EL evaluation
   * @return Map of headers that can be added to the Jersey Client request
   * @throws StageException if an expression could not be evaluated
   */
  public MultivaluedMap<String, Object> resolveHeaders(
      Map<String, String> headers,
      Record record
  ) throws StageException {
    RecordEL.setRecordInContext(headerVars, record);

    MultivaluedMap<String, Object> requestHeaders = new MultivaluedHashMap<>();
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      List<Object> header = new ArrayList<>(1);
      Object resolvedValue = headerEval.eval(headerVars, entry.getValue(), String.class);
      header.add(resolvedValue);
      requestHeaders.put(entry.getKey(), header);
    }

    return requestHeaders;
  }

  /**
   * Determines the HTTP method to use for the next request. It may include an EL expression to evaluate.
   *
   * @param record Current record to set in context.
   * @return the {@link HttpMethod} to use for the request
   * @throws ELEvalException if an expression is supplied that cannot be evaluated
   */
  public HttpMethod getHttpMethod(
      HttpMethod httpMethod,
      String methodExpression,
      Record record
  ) throws ELEvalException {
    if (httpMethod != HttpMethod.EXPRESSION) {
      return httpMethod;
    }
    RecordEL.setRecordInContext(methodVars, record);
    return HttpMethod.valueOf(methodEval.eval(methodVars, methodExpression, String.class));
  }

  public Client getClient() {
    return client;
  }

  public String getResolvedUrl(String resourceUrl, Record record) throws ELEvalException {
    RecordEL.setRecordInContext(resourceVars, record);
    TimeEL.setCalendarInContext(resourceVars, Calendar.getInstance());
    TimeNowEL.setTimeNowInContext(resourceVars, new Date());

    return resourceEval.eval(resourceVars, resourceUrl, String.class);
  }
  public void destroy() {
    if (client != null) {
      client.close();
    }
  }

}
