/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.itests.common.csw;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.codice.ddf.itests.common.AbstractIntegrationTest.CSW_REQUEST_RESOURCE_PATH;
import static org.codice.ddf.itests.common.AbstractIntegrationTest.getFileContent;

import com.google.common.collect.ImmutableMap;
import io.restassured.response.Response;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.codice.ddf.itests.common.ServiceManager;
import org.xml.sax.InputSource;

public class CswTestCommons {

  public static final String CSW_SOURCE_SYMBOLIC_NAME = "spatial-csw-source";

  public static final String CSW_FEDERATED_SOURCE_FACTORY_PID = "Csw_Federated_Source";

  public static final String CSW_CONNECTED_SOURCE_FACTORY_PID = "Csw_Connected_Source";

  public static final String CSW_REGISTRY_STORE_SYMBOLIC_NAME = "catalog-registry-api-impl";

  public static final String CSW_REGISTRY_STORE_FACTORY_PID = "Csw_Registry_Store";

  public static final String GMD_CSW_FEDERATED_SOURCE_FACTORY_PID = "Gmd_Csw_Federated_Source";

  private CswTestCommons() {}

  public static Map<String, Object> getCswSourceProperties(
      String sourceId, String cswUrl, ServiceManager serviceManager) {
    return getCswSourceProperties(
        sourceId, CSW_FEDERATED_SOURCE_FACTORY_PID, cswUrl, serviceManager);
  }

  public static Map<String, Object> getCswConnectedSourceProperties(
      String sourceId, String cswUrl, ServiceManager serviceManager) {
    return getCswSourceProperties(
        sourceId, CSW_CONNECTED_SOURCE_FACTORY_PID, cswUrl, serviceManager);
  }

  public static Map<String, Object> getCswSourceProperties(
      String sourceId, String factoryPid, String cswUrl, ServiceManager serviceManager) {
    Map<String, Object> cswSourceProperties = new HashMap<>();
    cswSourceProperties.putAll(
        serviceManager.getMetatypeDefaults(CSW_SOURCE_SYMBOLIC_NAME, factoryPid));
    cswSourceProperties.put("id", sourceId);
    cswSourceProperties.put("cswUrl", cswUrl);
    cswSourceProperties.put("pollInterval", 1);
    return cswSourceProperties;
  }

  public static Map<String, Object> getCswRegistryStoreProperties(
      String sourceId, String cswUrl, ServiceManager serviceManager) {
    Map<String, Object> cswSourceProperties = new HashMap<>();
    cswSourceProperties.putAll(
        serviceManager.getMetatypeDefaults(
            CSW_REGISTRY_STORE_SYMBOLIC_NAME, CSW_REGISTRY_STORE_FACTORY_PID));
    cswSourceProperties.put("id", sourceId);
    cswSourceProperties.put("registryUrl", cswUrl);
    return cswSourceProperties;
  }

  public static String getCswQuery(
      String propertyName, String literalValue, String outputFormat, String outputSchema) {

    String schema = "";
    if (StringUtils.isNotBlank(outputSchema)) {
      schema = "outputSchema=\"" + outputSchema + "\" ";
    }

    return getFileContent(
        "/csw-query.xml",
        ImmutableMap.of(
            "propertyName",
            propertyName,
            "literal",
            literalValue,
            "outputFormat",
            outputFormat,
            "outputSchema",
            schema));
  }

  public static String getCswFunctionQuery(
      String propertyName,
      Object literalValue,
      String outputFormat,
      String outputSchema,
      String functionName,
      Object... additionalArguments) {

    String schema = "";
    if (StringUtils.isNotBlank(outputSchema)) {
      schema = "outputSchema=\"" + outputSchema + "\" ";
    }
    String argsStr = "";
    if (additionalArguments != null) {
      argsStr =
          Arrays.stream(additionalArguments)
              .map(arg -> String.format("<ns2:Literal>%s</ns2:Literal>", arg.toString()))
              .collect(Collectors.joining());
    }
    return getFileContent(
        "/csw-function-query.xml",
        ImmutableMap.<String, String>builder()
            .put("propertyName", propertyName)
            .put("literal", literalValue.toString())
            .put("outputFormat", outputFormat)
            .put("outputSchema", schema)
            .put("functionName", functionName)
            .put("additionalArguments", argsStr)
            .build());
  }

  public static String getCswSubscription(
      String propertyName, String literalValue, String responseHandler) {
    return getCswSubscription(
        propertyName,
        literalValue,
        "application/xml",
        "http://www.opengis.net/cat/csw/2.0.2",
        responseHandler);
  }

  public static String getCswSubscription(
      String propertyName,
      String literalValue,
      String ouputFormat,
      String outputSchema,
      String responseHandler) {

    String schema = "";
    if (StringUtils.isNotBlank(outputSchema)) {
      schema = "outputSchema=\"" + outputSchema + "\" ";
    }

    return "<csw:GetRecords resultType=\"results\" outputFormat=\""
        + ouputFormat
        + "\" "
        + schema
        + "startPosition=\"1\" maxRecords=\"10\" service=\"CSW\" version=\"2.0.2\" xmlns:ns2=\"http://www.opengis.net/ogc\" xmlns:csw=\"http://www.opengis.net/cat/csw/2.0.2\" xmlns:ns4=\"http://www.w3.org/1999/xlink\" xmlns:ns3=\"http://www.opengis.net/gml\" xmlns:ns9=\"http://www.w3.org/2001/SMIL20/Language\" xmlns:ns5=\"http://www.opengis.net/ows\" xmlns:ns6=\"http://purl.org/dc/elements/1.1/\" xmlns:ns7=\"http://purl.org/dc/terms/\" xmlns:ns8=\"http://www.w3.org/2001/SMIL20/\">"
        + "    <csw:ResponseHandler>"
        + responseHandler
        + "</csw:ResponseHandler>"
        + "    <ns10:Query typeNames=\"csw:Record\" xmlns=\"\" xmlns:ns10=\"http://www.opengis.net/cat/csw/2.0.2\">"
        + "        <ns10:ElementSetName>full</ns10:ElementSetName>"
        + "        <ns10:Constraint version=\"1.1.0\">"
        + "            <ns2:Filter>"
        + "                <ns2:PropertyIsLike wildCard=\"*\" singleChar=\"#\" escapeChar=\"!\">"
        + "                    <ns2:PropertyName>"
        + propertyName
        + "</ns2:PropertyName>"
        + "                    <ns2:Literal>"
        + literalValue
        + "</ns2:Literal>"
        + "                </ns2:PropertyIsLike>"
        + "            </ns2:Filter>"
        + "        </ns10:Constraint>"
        + "    </ns10:Query>"
        + "</csw:GetRecords>";
  }

  public static String getCswInsertRequest(String typeName, String record) {
    String cswStr =
        getFileContent(
            CSW_REQUEST_RESOURCE_PATH + "/CswInsertRequest",
            ImmutableMap.of("typeName", typeName, "record", record));
    if (cswStr.lastIndexOf("<?xml") > 0) {
      String header = "";
      if (cswStr.indexOf("<?xml") == 0) {
        header = cswStr.substring(0, cswStr.indexOf("?>") + 2);
        cswStr = cswStr.substring(cswStr.indexOf("?>") + 2);
      }
      cswStr = header + cswStr.replaceAll("<\\?xml.*\\?>", "");
    }
    return cswStr;
  }

  public static String getMetacardIdFromCswInsertResponse(Response response)
      throws IOException, XPathExpressionException {
    XPath xPath = XPathFactory.newInstance().newXPath();
    String idPath = "//*[local-name()='identifier']/text()";
    InputSource xml = new InputSource(IOUtils.toInputStream(response.getBody().asString(), UTF_8));
    return xPath.compile(idPath).evaluate(xml);
  }
}
