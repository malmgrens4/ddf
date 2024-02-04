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
package ddf.catalog.source.solr;

import static ddf.catalog.Constants.ADDITIONAL_SORT_BYS;
import static ddf.catalog.Constants.EXPERIMENTAL_FACET_PROPERTIES_KEY;
import static ddf.catalog.Constants.EXPERIMENTAL_FACET_RESULTS_KEY;
import static ddf.catalog.Constants.SUGGESTION_BUILD_KEY;
import static ddf.catalog.Constants.SUGGESTION_CONTEXT_KEY;
import static ddf.catalog.Constants.SUGGESTION_DICT_KEY;
import static ddf.catalog.Constants.SUGGESTION_QUERY_KEY;
import static ddf.catalog.Constants.SUGGESTION_RESULT_KEY;
import static ddf.catalog.source.solr.DynamicSchemaResolver.FIRST_CHAR_OF_SUFFIX;
import static org.apache.solr.spelling.suggest.SuggesterParams.SUGGEST_BUILD;
import static org.apache.solr.spelling.suggest.SuggesterParams.SUGGEST_CONTEXT_FILTER_QUERY;
import static org.apache.solr.spelling.suggest.SuggesterParams.SUGGEST_DICT;
import static org.apache.solr.spelling.suggest.SuggesterParams.SUGGEST_Q;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import ddf.catalog.data.Attribute;
import ddf.catalog.data.AttributeType;
import ddf.catalog.data.ContentType;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.MetacardCreationException;
import ddf.catalog.data.MetacardType;
import ddf.catalog.data.Result;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.data.impl.ContentTypeImpl;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.data.impl.ResultImpl;
import ddf.catalog.filter.FilterAdapter;
import ddf.catalog.operation.FacetAttributeResult;
import ddf.catalog.operation.Query;
import ddf.catalog.operation.QueryRequest;
import ddf.catalog.operation.SourceResponse;
import ddf.catalog.operation.TermFacetProperties;
import ddf.catalog.operation.impl.FacetAttributeResultImpl;
import ddf.catalog.operation.impl.QueryResponseImpl;
import ddf.catalog.operation.impl.SourceResponseImpl;
import ddf.catalog.source.UnsupportedQueryException;
import ddf.measure.Distance;
import java.io.IOException;
import java.io.Serializable;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest.METHOD;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.PivotField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse.Collation;
import org.apache.solr.client.solrj.response.SuggesterResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.codice.solr.client.solrj.SolrClient;
import org.opengis.filter.sort.SortBy;
import org.opengis.filter.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SolrMetacardClientImpl implements SolrMetacardClient {

  protected static final String RELEVANCE_SORT_FIELD = "score";

  private static final String DISTANCE_SORT_FUNCTION = "geodist()";

  private static final String DISTANCE_SORT_FIELD = "_distance_";

  private static final String GEOMETRY_FIELD = Metacard.GEOGRAPHY + SchemaFields.GEO_SUFFIX;

  private static final Logger LOGGER = LoggerFactory.getLogger(SolrMetacardClientImpl.class);

  private static final String QUOTE = "\"";

  private static final String ZERO_PAGESIZE_COMPATIBILITY_PROPERTY =
      "catalog.zeroPageSizeCompatibility";

  private static final String GET_QUERY_HANDLER = "/get";

  private static final String IDS_KEY = "ids";

  public static final String SORT_FIELD_KEY = "sfield";

  public static final String POINT_KEY = "pt";

  public static final String SHOWING_RESULTS_FOR_KEY = "showingResultsFor";

  public static final String DID_YOU_MEAN_KEY = "didYouMean";

  public static final String SPELLCHECK_KEY = "spellcheck";

  public static final int GET_BY_ID_LIMIT = 100;

  public static final String EXCLUDE_ATTRIBUTES = "excludeAttributes";

  public static final String DO_REALTIME_GET = "doRealtimeGet";

  public static final String SKIP_REALTIME_GET = "skipRealtimeGet";

  private static final String RESOURCE_ATTRIBUTE = "resource";

  private final SolrClient client;

  private final SolrFilterDelegateFactory filterDelegateFactory;

  private final FilterAdapter filterAdapter;

  private final DynamicSchemaResolver resolver;

  private static final Supplier<Boolean> ZERO_PAGESIZE_COMPATIBILTY =
      () -> Boolean.valueOf(System.getProperty(ZERO_PAGESIZE_COMPATIBILITY_PROPERTY));

  private static final String SOLR_COMMIT_NRT_METACARDTYPES = "solr.commit.nrt.metacardTypes";

  private final Set<String> commitNrtMetacardType =
      Sets.newHashSet(accessProperty(SOLR_COMMIT_NRT_METACARDTYPES, "").split("\\s*,\\s*"));

  private static final String SOLR_COMMIT_NRT_COMMITWITHINMS = "solr.commit.nrt.commitWithinMs";

  private final int commitNrtCommitWithinMs =
      Math.max(NumberUtils.toInt(accessProperty(SOLR_COMMIT_NRT_COMMITWITHINMS, "1000")), 0);

  private static final String SOLR_QUERY_TIMEALLOWEDMS = "solr.query.timeAllowed";

  private final int queryTimeAllowedMs =
      Math.max(NumberUtils.toInt(accessProperty(SOLR_QUERY_TIMEALLOWEDMS, "0")), 0);

  private static final String SQCMB = "qm.sc."; // query metric base for solr client
  private static final String QM_TRACEID = "qm.trace-id";
  private static final String QM_ELAPSED = ".elapsed";

  protected ResultHighlighter highlighter;

  public SolrMetacardClientImpl(
      SolrClient client,
      FilterAdapter catalogFilterAdapter,
      SolrFilterDelegateFactory solrFilterDelegateFactory,
      DynamicSchemaResolver dynamicSchemaResolver) {
    this.client = client;
    filterDelegateFactory = solrFilterDelegateFactory;
    filterAdapter = catalogFilterAdapter;
    resolver = dynamicSchemaResolver;
    highlighter = new ResultHighlighter(resolver, filterAdapter);
  }

  public SolrClient getClient() {
    return client;
  }

  @Override
  public SourceResponse query(QueryRequest request) throws UnsupportedQueryException {
    if (request == null || request.getQuery() == null) {
      return new QueryResponseImpl(request, new ArrayList<>(), true, 0L);
    }
    Map<String, Serializable> metrics = new HashMap<String, Serializable>();

    long totalHits = 0;
    Serializable traceId = request.getProperties().get(QM_TRACEID);
    Set<String> ids = request.getSourceIds();
    String sourceId = "<empty>";
    if (ids != null) {
      sourceId = ids.stream().collect(Collectors.joining(","));
    }
    String traceSource = "trace-id " + traceId + "source " + sourceId;
    Map<String, Serializable> responseProps = new HashMap<>();
    List<Result> results = new ArrayList<>();

    SolrFilterDelegate solrFilterDelegate =
        filterDelegateFactory.newInstance(resolver, request.getProperties());

    LOGGER.trace("Generate solr query for {}: query request {}", traceSource, request);
    long startQuery = System.nanoTime();
    long start = startQuery;
    SolrQuery query = getSolrQuery(request, solrFilterDelegate);
    long timer1 = System.nanoTime();
    LOGGER.trace("Done generating solr query for {}: solr query {}", traceSource, query);
    metrics.put(SQCMB + "getsolrquery" + QM_ELAPSED, timer1 - startQuery);
    boolean isFacetedQuery = handleFacetRequest(query, request);
    long timer2 = System.nanoTime();
    metrics.put(SQCMB + "handleFacetReq" + QM_ELAPSED, timer2 - timer1);
    query = handleSuggestionQuery(query, request);
    metrics.put(SQCMB + "handleSuggestion" + QM_ELAPSED, System.nanoTime() - timer2);
    boolean userSpellcheckIsOn = userSpellcheckIsOn(request);

    try {
      QueryResponse solrResponse;
      start = System.nanoTime();
      LOGGER.trace("Begin executing solr query for {}", traceSource);
      if (!isFacetedQuery && shouldDoRealTimeGet(request)) {
        LOGGER.debug("Performing real time query for {}", traceSource);
        SolrQuery realTimeQuery = getRealTimeQuery(query, solrFilterDelegate.getIds());
        solrResponse = client.query(realTimeQuery, METHOD.POST);
        metrics.put(SQCMB + "realtimeQuery" + QM_ELAPSED, System.nanoTime() - start);
      } else {
        if (userSpellcheckIsOn) {
          query.setParam("spellcheck", true);
        }
        LOGGER.debug("Highlighter pre-query processing for {}", traceSource);
        highlighter.processPreQuery(request, query);
        long currentTime = System.nanoTime();
        metrics.put(SQCMB + "hilighterPreQuery" + QM_ELAPSED, currentTime - start);
        start = currentTime;
        LOGGER.debug("Performing query for {}", traceSource);
        solrResponse = client.query(query, METHOD.POST);
        metrics.put(SQCMB + "normalQuery" + QM_ELAPSED, System.nanoTime() - start);
      }
      LOGGER.trace("End executing solr query for {}", traceSource);

      if (isFacetedQuery) {
        start = System.nanoTime();
        handleFacetResponse(solrResponse, responseProps);
        LOGGER.trace("Completed handleFacetResponse for {}", traceSource);
        metrics.put(SQCMB + "facetHandling" + QM_ELAPSED, System.nanoTime() - start);
      }

      start = System.nanoTime();
      handleSuggestionResponse(solrResponse, responseProps);
      LOGGER.trace("Complete handling suggestions for {}", traceSource);
      long currentTime = System.nanoTime();
      metrics.put(SQCMB + "suggestionHandling" + QM_ELAPSED, currentTime - start);

      handlePartialResults(solrResponse, responseProps);
      LOGGER.trace("Complete handling partial results for {}", traceSource);
      start = System.nanoTime();
      metrics.put(SQCMB + "partialHandling" + QM_ELAPSED, start - currentTime);

      SolrDocumentList docs = solrResponse.getResults();
      docs =
          handleSpellcheck(request, solrResponse, responseProps, query, docs, userSpellcheckIsOn);
      LOGGER.trace("Handled spellcheck for {}", traceSource);
      metrics.put(SQCMB + "spellcheckHandling" + QM_ELAPSED, System.nanoTime() - start);
      if (docs != null) {
        addDocsToResults(docs, results);
        totalHits = docs.getNumFound();
      }

    } catch (SolrServerException | IOException | SolrException e) {
      throw new UnsupportedQueryException("Could not complete solr query.", e);
    }

    // add in all the solr query metrics to return
    metrics.put(SQCMB + "solrquery" + QM_ELAPSED, System.nanoTime() - startQuery);
    responseProps.putAll(metrics);
    return new SourceResponseImpl(request, responseProps, results, totalHits);
  }

  private boolean shouldDoRealTimeGet(QueryRequest request) throws UnsupportedQueryException {

    if ((boolean) request.getProperties().getOrDefault(SKIP_REALTIME_GET, false)) {
      return false;
    }

    Query query = request.getQuery();
    if (query.getStartIndex() > 1) {
      // solr doesn't support paging of real time get requests so if a paging request is received
      // here, it is safe to assume that we should not be doing a real time get to solr
      return false;
    }

    return (boolean) request.getProperties().getOrDefault(DO_REALTIME_GET, false)
        || BooleanUtils.toBoolean(filterAdapter.adapt(query, new RealTimeGetDelegate()));
  }

  private List<SolrDocument> getSolrDocs(Set<String> ids) throws UnsupportedQueryException {
    List<SolrDocument> solrDocs = new ArrayList<>(ids.size());
    List<List<String>> partitions = Lists.partition(new ArrayList<>(ids), GET_BY_ID_LIMIT);
    for (List<String> partition : partitions) {
      try {
        SolrDocumentList page = client.getById(partition);
        page.iterator().forEachRemaining(solrDocs::add);
      } catch (SolrServerException | SolrException | IOException e) {
        throw new UnsupportedQueryException("Could not complete solr query.", e);
      }
    }
    return solrDocs;
  }

  private boolean handleFacetRequest(SolrQuery query, QueryRequest request) {
    boolean isFacetedQuery = false;
    Serializable textFacetPropRaw = request.getPropertyValue(EXPERIMENTAL_FACET_PROPERTIES_KEY);
    if (textFacetPropRaw instanceof TermFacetProperties) {
      TermFacetProperties textFacetProp = (TermFacetProperties) textFacetPropRaw;
      isFacetedQuery = true;
      if (LOGGER.isDebugEnabled()) {
        Serializable traceId = request.getProperties().get(QM_TRACEID);
        String sourceId = request.getSourceIds().stream().collect(Collectors.joining(","));
        LOGGER.debug(
            "Enabling faceted query for trace-id {} source {} request [{}] on field {}",
            traceId,
            sourceId,
            request,
            textFacetProp);
      }

      textFacetProp.getFacetAttributes().stream()
          .map(this::addAttributeTypeSuffix)
          .filter(attr -> attr.contains(String.valueOf(FIRST_CHAR_OF_SUFFIX)))
          .forEach(query::addFacetField);

      query.setFacetSort(textFacetProp.getSortKey().name().toLowerCase());
      query.setFacetLimit(textFacetProp.getFacetLimit());
      query.setFacetMinCount(textFacetProp.getMinFacetCount());
    }
    return isFacetedQuery;
  }

  private SolrQuery handleSuggestionQuery(SolrQuery query, QueryRequest request) {
    Serializable suggestQuery = request.getPropertyValue(SUGGESTION_QUERY_KEY);
    Serializable suggestContext = request.getPropertyValue(SUGGESTION_CONTEXT_KEY);
    Serializable suggestDict = request.getPropertyValue(SUGGESTION_DICT_KEY);

    if (suggestQuery instanceof String
        && suggestContext instanceof String
        && suggestDict instanceof String) {
      query = new SolrQuery();
      query.setRequestHandler("/suggest");
      query.setParam(SUGGEST_Q, (String) suggestQuery);
      query.setParam(SUGGEST_CONTEXT_FILTER_QUERY, (String) suggestContext);
      query.setParam(SUGGEST_DICT, (String) suggestDict);

      Serializable buildSuggesterIndex = request.getPropertyValue(SUGGESTION_BUILD_KEY);
      if (buildSuggesterIndex instanceof Boolean) {
        query.setParam(SUGGEST_BUILD, (Boolean) buildSuggesterIndex);
      }
    }

    return query;
  }

  private void handleFacetResponse(
      QueryResponse solrResponse, Map<String, Serializable> responseProps) {
    List<FacetField> facetFields = solrResponse.getFacetFields();
    if (CollectionUtils.isNotEmpty(facetFields)) {
      List<FacetAttributeResult> facetedAttributeResults =
          facetFields.stream().map(this::convertFacetField).collect(Collectors.toList());
      responseProps.put(EXPERIMENTAL_FACET_RESULTS_KEY, (Serializable) facetedAttributeResults);
    }
  }

  private void handleSuggestionResponse(
      QueryResponse solrResponse, Map<String, Serializable> responseProps) {
    SuggesterResponse suggesterResponse = solrResponse.getSuggesterResponse();
    if (suggesterResponse != null) {
      List<Map.Entry<String, String>> suggestionResults =
          suggesterResponse.getSuggestions().values().stream()
              .flatMap(List::stream)
              .map(
                  suggestion ->
                      new AbstractMap.SimpleImmutableEntry<>(
                          suggestion.getPayload(), suggestion.getTerm()))
              .collect(Collectors.toList());

      responseProps.put(SUGGESTION_RESULT_KEY, (Serializable) suggestionResults);
    }
  }

  private void handlePartialResults(
      QueryResponse solrResponse, Map<String, Serializable> responseProps) {
    boolean partialResults =
        Optional.of(solrResponse)
            .map(QueryResponse::getResponseHeader)
            .map(header -> header.get("partialResults"))
            .filter(Boolean.class::isInstance)
            .map(Boolean.class::cast)
            .orElse(false);

    if (!partialResults) {
      return;
    }

    responseProps.put("partial-results", true);

    if (LOGGER.isDebugEnabled()) {
      String q =
          Optional.of(solrResponse)
              .map(QueryResponse::getResponseHeader)
              .map(header -> header.get("params"))
              .filter(SimpleOrderedMap.class::isInstance)
              .map(SimpleOrderedMap.class::cast)
              .map(params -> params.get("q"))
              .map(String::valueOf)
              .orElse("unknown");

      LOGGER.debug(
          "Found {} partial results for query [{}] that took {}ms.",
          solrResponse.getResults().getNumFound(),
          q,
          solrResponse.getQTime());
    }
  }

  private SolrDocumentList handleSpellcheck(
      QueryRequest request,
      QueryResponse solrResponse,
      Map<String, Serializable> responseProps,
      SolrQuery query,
      SolrDocumentList originalDocs,
      boolean userSpellcheckIsOn)
      throws IOException, SolrServerException {

    QueryResponse highlightResponse = solrResponse;
    SolrDocumentList resultDocs = originalDocs;
    if (userSpellcheckIsOn && solrSpellcheckHasResults(solrResponse)) {
      LOGGER.trace("Begin solr spellcheck: query request {}", request);
      long time0 = System.nanoTime();
      Collation collation = getCollationToResend(query, solrResponse);
      query.set("q", collation.getCollationQueryString());
      query.set("spellcheck", false);
      long time1 = System.nanoTime();
      responseProps.put(SQCMB + "collation" + QM_ELAPSED, time1 - time0);
      highlighter.processPreQuery(request, query);
      long time2 = System.nanoTime();
      responseProps.put(SQCMB + "highlighter.processPreQuery" + QM_ELAPSED, time2 - time1);
      QueryResponse solrResponseRequery = client.query(query, METHOD.POST);
      SolrDocumentList docs = solrResponseRequery.getResults();
      long time3 = System.nanoTime();
      responseProps.put(SQCMB + "highlighter.respRequery" + QM_ELAPSED, time3 - time2);
      if (docs != null && docs.size() > originalDocs.size()) {
        resultDocs = docs;
        highlightResponse = solrResponseRequery;

        Set<String> originals = new HashSet<>();
        Set<String> corrections = new HashSet<>();
        collation.getMisspellingsAndCorrections().stream()
            .forEach(
                correction -> {
                  originals.add(correction.getOriginal());
                  corrections.add(correction.getCorrection());
                });
        responseProps.put(DID_YOU_MEAN_KEY, new ArrayList<>(originals));
        responseProps.put(SHOWING_RESULTS_FOR_KEY, new ArrayList<>(corrections));
      }
      LOGGER.trace("End solr spellcheck: query request {}", request);
    }
    if (highlightResponse.getHighlighting() != null) {
      LOGGER.trace("Starting highlight extraction.");
      long time0 = System.nanoTime();
      highlighter.processPostQuery(highlightResponse, responseProps);
      responseProps.put(
          SQCMB + "highlighter.processPostQuery" + QM_ELAPSED, System.nanoTime() - time0);
      LOGGER.trace("Ending highlight extraction.");
    }
    return resultDocs;
  }

  private Boolean userSpellcheckIsOn(QueryRequest request) {
    Boolean userSpellcheckChoice = false;
    if (request.getProperties().get("spellcheck") != null) {
      userSpellcheckChoice = (Boolean) request.getProperties().get("spellcheck");
    }
    return userSpellcheckChoice;
  }

  private boolean solrSpellcheckHasResults(QueryResponse solrResponse) {
    return solrResponse.getSpellCheckResponse() != null
        && CollectionUtils.isNotEmpty(solrResponse.getSpellCheckResponse().getCollatedResults());
  }

  private Collation getCollationToResend(SolrQuery query, QueryResponse solrResponse) {
    long maxHits = Integer.MIN_VALUE;
    Collation bestCollation = null;
    for (Collation collation : solrResponse.getSpellCheckResponse().getCollatedResults()) {
      if (maxHits < collation.getNumberOfHits()) {
        maxHits = collation.getNumberOfHits();
        bestCollation = collation;
      }
    }
    return bestCollation;
  }

  private void addDocsToResults(SolrDocumentList docs, List<Result> results)
      throws UnsupportedQueryException {
    for (SolrDocument doc : docs) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("SOLR DOC: {}", doc.getFieldValue(Metacard.ID + SchemaFields.TEXT_SUFFIX));
      }
      ResultImpl tmpResult;
      try {
        tmpResult = createResult(doc);
      } catch (MetacardCreationException e) {
        throw new UnsupportedQueryException("Could not create result metacard(s).", e);
      }

      results.add(tmpResult);
    }
  }

  private String addAttributeTypeSuffix(String attribute) {
    return resolver.getAnonymousField(attribute).stream().findFirst().orElse(attribute);
  }

  private FacetAttributeResult convertFacetField(FacetField facetField) {
    List<String> values = new ArrayList<>();
    List<Long> counts = new ArrayList<>();

    facetField
        .getValues()
        .forEach(
            val -> {
              values.add(val.getName());
              counts.add(val.getCount());
            });

    return new FacetAttributeResultImpl(
        resolver.resolveFieldName(facetField.getName()), values, counts);
  }

  @Override
  public List<Metacard> query(String queryString) throws UnsupportedQueryException {
    SolrQuery query = new SolrQuery();
    query.setQuery(queryString);
    try {
      QueryResponse solrResponse = client.query(query, METHOD.POST);
      SolrDocumentList docs = solrResponse.getResults();

      return createMetacards(docs);
    } catch (SolrServerException | SolrException | IOException e) {
      throw new UnsupportedQueryException("Could not complete solr query.", e);
    }
  }

  @Override
  public List<Metacard> getIds(Set<String> ids) throws UnsupportedQueryException {
    List<SolrDocument> solrDocs = getSolrDocs(ids);
    return createMetacards(solrDocs);
  }

  private List<Metacard> createMetacards(List<SolrDocument> docs) throws UnsupportedQueryException {
    List<Metacard> results = new ArrayList<>(docs.size());
    for (SolrDocument doc : docs) {
      try {
        Metacard metacard = createMetacard(doc);
        if (metacard != null) {
          results.add(metacard);
        }
      } catch (MetacardCreationException e) {
        throw new UnsupportedQueryException("Could not create metacard(s).", e);
      }
    }
    return results;
  }

  @Override
  public Set<ContentType> getContentTypes() {
    Set<ContentType> finalSet = new HashSet<>();

    String contentTypeField =
        resolver.getField(
            Metacard.CONTENT_TYPE,
            AttributeType.AttributeFormat.STRING,
            true,
            Collections.EMPTY_MAP);
    String contentTypeVersionField =
        resolver.getField(
            Metacard.CONTENT_TYPE_VERSION,
            AttributeType.AttributeFormat.STRING,
            true,
            Collections.EMPTY_MAP);

    /*
     * If we didn't find the field, it most likely means it does not exist. If it does not
     * exist, then we can safely say that no content types are in this catalog provider
     */
    if (contentTypeField == null || contentTypeVersionField == null) {
      return finalSet;
    }

    SolrQuery query = new SolrQuery(contentTypeField + ":[* TO *]");
    query.setFacet(true);
    query.addFacetField(contentTypeField);
    query.addFacetPivotField(contentTypeField + "," + contentTypeVersionField);

    long timer0 = System.nanoTime();
    try {
      QueryResponse solrResponse = client.query(query, METHOD.POST);
      List<FacetField> facetFields = solrResponse.getFacetFields();
      for (Map.Entry<String, List<PivotField>> entry : solrResponse.getFacetPivot()) {

        // if no content types have an associated version, the list of pivot fields will be
        // empty.
        // however, the content type names can still be obtained via the facet fields.
        if (CollectionUtils.isEmpty(entry.getValue())) {
          LOGGER.debug(
              "No content type versions found associated with any available content types.");

          if (CollectionUtils.isNotEmpty(facetFields)) {
            // Only one facet field was added. That facet field may contain multiple
            // values (content type names).
            for (FacetField.Count currContentType : facetFields.get(0).getValues()) {
              // unknown version, so setting it to null
              ContentType contentType = new ContentTypeImpl(currContentType.getName(), null);

              finalSet.add(contentType);
            }
          }
        } else {
          for (PivotField pf : entry.getValue()) {

            String contentTypeName = pf.getValue().toString();
            LOGGER.debug("contentTypeName: {}", contentTypeName);

            if (CollectionUtils.isEmpty(pf.getPivot())) {
              // if there are no sub-pivots, that means that there are no content type
              // versions
              // associated with this content type name
              LOGGER.debug(
                  "Content type does not have associated contentTypeVersion: {}", contentTypeName);
              ContentType contentType = new ContentTypeImpl(contentTypeName, null);

              finalSet.add(contentType);

            } else {
              for (PivotField innerPf : pf.getPivot()) {

                LOGGER.debug(
                    "contentTypeVersion: {}. For contentTypeName: {}",
                    innerPf.getValue(),
                    contentTypeName);

                ContentType contentType =
                    new ContentTypeImpl(contentTypeName, innerPf.getValue().toString());

                finalSet.add(contentType);
              }
            }
          }
        }
      }
    } catch (SolrServerException | SolrException | IOException e) {
      LOGGER.info("Solr exception getting content types", e);
    }
    LOGGER.debug("Solr client - get content types - elapsed time: {}", System.nanoTime() - timer0);

    return finalSet;
  }

  protected SolrQuery getSolrQuery(QueryRequest request, SolrFilterDelegate solrFilterDelegate)
      throws UnsupportedQueryException {
    List<SortBy> sortBys = new ArrayList<>();

    if (request.getQuery() != null) {
      SortBy sortBy = request.getQuery().getSortBy();
      if (sortBy != null) {
        sortBys.add(sortBy);
      }
    }

    Serializable sortBySer = request.getPropertyValue(ADDITIONAL_SORT_BYS);
    if (sortBySer instanceof SortBy[]) {
      SortBy[] extSortBys = (SortBy[]) sortBySer;
      sortBys.addAll(Arrays.asList(extSortBys));
    }

    if (CollectionUtils.isNotEmpty(sortBys)) {
      solrFilterDelegate.setSortPolicy(sortBys.toArray(new SortBy[0]));
    }

    SolrQuery query = filterAdapter.adapt(request.getQuery(), solrFilterDelegate);

    return postAdapt(request, solrFilterDelegate, query);
  }

  protected SolrQuery getRealTimeQuery(SolrQuery originalQuery, Collection<String> ids) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("originalQuery: {}", getQueryParams(originalQuery));
    }
    SolrQuery realTimeQuery = new SolrQuery();
    for (Map.Entry<String, String[]> entry : originalQuery.getMap().entrySet()) {
      if (CommonParams.Q.equals(entry.getKey())) {
        realTimeQuery.set(CommonParams.FQ, entry.getValue());
      } else {
        realTimeQuery.set(entry.getKey(), entry.getValue());
      }
    }
    realTimeQuery.set(CommonParams.QT, GET_QUERY_HANDLER);
    realTimeQuery.set(IDS_KEY, ids.toArray(new String[ids.size()]));

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("realTimeQuery: {}", getQueryParams(realTimeQuery));
    }

    return realTimeQuery;
  }

  private String getQueryParams(SolrQuery query) {
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<String, String[]> entry : query.getMap().entrySet()) {
      builder.append(
          String.format(
              "param: %s; value: %s%n", entry.getKey(), Arrays.toString(entry.getValue())));
    }
    return builder.toString();
  }

  protected SolrQuery postAdapt(
      QueryRequest request, SolrFilterDelegate filterDelegate, SolrQuery query)
      throws UnsupportedQueryException {
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Prepared Query: {}", query.getQuery());
      if (query.getFilterQueries() != null && query.getFilterQueries().length > 0) {
        LOGGER.trace("Filter Queries: {}", Arrays.toString(query.getFilterQueries()));
      }
    }

    /* Start Index */
    if (request.getQuery().getStartIndex() < 1) {
      throw new UnsupportedQueryException("Start index must be greater than 0");
    }

    // Solr is 0-based
    query.setStart(request.getQuery().getStartIndex() - 1);

    if (queryingForAllRecords(request)) {
      try {
        query.setRows(queryForNumberOfRows(query));
      } catch (SolrServerException | IOException | SolrException | ArithmeticException exception) {
        throw new UnsupportedQueryException("Could not retrieve number of records.", exception);
      }
    } else {
      query.setRows(request.getQuery().getPageSize());
    }

    setSortProperty(request, query, filterDelegate);

    if (queryTimeAllowedMs > 0) {
      query.setTimeAllowed(queryTimeAllowedMs);
    }

    return query;
  }

  private boolean queryingForAllRecords(QueryRequest request) {
    if (ZERO_PAGESIZE_COMPATIBILTY.get()) {
      return request.getQuery().getPageSize() < 1;
    }

    return request.getQuery().getPageSize() < 0;
  }

  private int queryForNumberOfRows(SolrQuery query) throws SolrServerException, IOException {
    long timer0 = System.nanoTime();
    int numRows;
    query.setRows(0);
    QueryResponse solrResponse = client.query(query, METHOD.POST);
    numRows = Math.toIntExact(solrResponse.getResults().getNumFound());
    LOGGER.debug(
        "Solr client - query for num rows - elapsed time: {}ns", System.nanoTime() - timer0);
    return numRows;
  }

  private void addDistanceSort(
      SolrQuery query, String sortField, SolrQuery.ORDER order, SolrFilterDelegate delegate) {
    if (delegate.isSortedByDistance()) {
      query.addSort(DISTANCE_SORT_FUNCTION, order);
      query.setFields("*", DISTANCE_SORT_FIELD + ":" + DISTANCE_SORT_FUNCTION);
      query.add(SORT_FIELD_KEY, sortField);
      query.add(POINT_KEY, delegate.getSortedDistancePoint());
    }
  }

  protected String setSortProperty(
      QueryRequest request, SolrQuery query, SolrFilterDelegate solrFilterDelegate) {

    List<SortBy> sortBys = new ArrayList<>();
    String sortProperty = "";
    if (request.getQuery() != null) {
      SortBy querySortBy = request.getQuery().getSortBy();
      if (querySortBy != null && querySortBy.getPropertyName() != null) {
        sortBys.add(querySortBy);
      }
    }

    Serializable sortBySer = request.getPropertyValue(ADDITIONAL_SORT_BYS);
    if (sortBySer instanceof SortBy[]) {
      SortBy[] extSortBys = (SortBy[]) sortBySer;
      if (extSortBys.length > 0) {
        sortBys.addAll(Arrays.asList(extSortBys));
      }
    }

    for (SortBy sortBy : sortBys) {
      sortProperty = sortBy.getPropertyName().getPropertyName();
      SolrQuery.ORDER order = SolrQuery.ORDER.desc;

      if (sortBy.getSortOrder() == SortOrder.ASCENDING) {
        order = SolrQuery.ORDER.asc;
      }

      if (Result.RELEVANCE.equals(sortProperty)) {
        query.setFields("*", RELEVANCE_SORT_FIELD);
        query.addSort(RELEVANCE_SORT_FIELD, order);
      } else if (Result.DISTANCE.equals(sortProperty)) {
        addDistanceSort(query, resolver.getSortKey(GEOMETRY_FIELD), order, solrFilterDelegate);
      } else if (sortProperty.equals(Result.TEMPORAL)) {
        query.addSort(
            resolver.getSortKey(
                resolver.getField(
                    Metacard.EFFECTIVE,
                    AttributeType.AttributeFormat.DATE,
                    false,
                    Collections.EMPTY_MAP)),
            order);
      } else {
        List<String> resolvedProperties = resolver.getAnonymousField(sortProperty);

        if (!resolvedProperties.isEmpty()) {
          for (String sortField : resolvedProperties) {
            if (sortField.endsWith(SchemaFields.GEO_SUFFIX)) {
              addDistanceSort(query, resolver.getSortKey(sortField), order, solrFilterDelegate);
            } else if (!(sortField.endsWith(SchemaFields.BINARY_SUFFIX)
                || sortField.endsWith(SchemaFields.OBJECT_SUFFIX))) {
              query.addSort(resolver.getSortKey(sortField), order);
            }
          }
        } else {
          LOGGER.debug(
              "No schema field was found for sort property [{}]. No sort field was added to the query.",
              sortProperty);
        }
      }
    }
    return resolver.getSortKey(sortProperty);
  }

  private ResultImpl createResult(SolrDocument doc) throws MetacardCreationException {
    ResultImpl result = new ResultImpl(createMetacard(doc));

    if (doc.get(RELEVANCE_SORT_FIELD) != null) {
      result.setRelevanceScore(((Float) (doc.get(RELEVANCE_SORT_FIELD))).doubleValue());
    }

    if (doc.get(DISTANCE_SORT_FIELD) != null) {
      Object distance = doc.getFieldValue(DISTANCE_SORT_FIELD);

      if (distance != null) {
        LOGGER.debug("Distance returned from Solr [{}]", distance);
        double convertedDistance =
            new Distance(Double.valueOf(distance.toString()), Distance.LinearUnit.KILOMETER)
                .getAs(Distance.LinearUnit.METER);

        result.setDistanceInMeters(convertedDistance);
      }
    }

    return result;
  }

  public MetacardImpl createMetacard(SolrDocument doc) throws MetacardCreationException {
    MetacardType metacardType = resolver.getMetacardType(doc);
    MetacardImpl metacard = new MetacardImpl(metacardType);

    for (String solrFieldName : doc.getFieldNames()) {
      if (!resolver.isPrivateField(solrFieldName)) {
        Collection<Object> fieldValues = doc.getFieldValues(solrFieldName);
        Attribute attr =
            new AttributeImpl(
                resolver.resolveFieldName(solrFieldName),
                resolver.getDocValues(solrFieldName, fieldValues));
        metacard.setAttribute(attr);
      }
    }

    return metacard;
  }

  @Override
  public List<SolrInputDocument> add(List<Metacard> metacards, boolean forceAutoCommit)
      throws IOException, SolrServerException, MetacardCreationException {
    if (CollectionUtils.isEmpty(metacards)) {
      return Collections.emptyList();
    }

    boolean isNrtCommit = false;
    List<SolrInputDocument> docs = new ArrayList<>();
    for (Metacard metacard : metacards) {
      docs.add(getSolrInputDocument(metacard));
      if (commitNrtMetacardType.contains(metacard.getMetacardType().getName())) {
        isNrtCommit = true;
      }
    }

    if (!forceAutoCommit) {
      if (isNrtCommit) {
        client.add(docs, commitNrtCommitWithinMs);
      } else {
        client.add(docs);
      }
    } else {
      softCommit(docs);
    }

    return docs;
  }

  protected SolrInputDocument getSolrInputDocument(Metacard metacard)
      throws MetacardCreationException {
    SolrInputDocument solrInputDocument = new SolrInputDocument();

    resolver.addFields(metacard, solrInputDocument);

    return solrInputDocument;
  }

  @Override
  public void deleteByIds(
      String fieldName, List<? extends Serializable> identifiers, boolean forceCommit)
      throws IOException, SolrServerException {
    if (CollectionUtils.isEmpty(identifiers)) {
      return;
    }

    if (Metacard.ID.equals(fieldName)) {
      CollectionUtils.transform(identifiers, Object::toString);
      client.deleteById((List<String>) identifiers);
    } else {
      if (identifiers.size() < SolrCatalogProviderImpl.MAX_BOOLEAN_CLAUSES) {
        client.deleteByQuery(getIdentifierQuery(fieldName, identifiers));
      } else {
        int i;
        for (i = SolrCatalogProviderImpl.MAX_BOOLEAN_CLAUSES;
            i < identifiers.size();
            i += SolrCatalogProviderImpl.MAX_BOOLEAN_CLAUSES) {
          client.deleteByQuery(
              getIdentifierQuery(
                  fieldName,
                  identifiers.subList(i - SolrCatalogProviderImpl.MAX_BOOLEAN_CLAUSES, i)));
        }
        client.deleteByQuery(
            getIdentifierQuery(
                fieldName,
                identifiers.subList(
                    i - SolrCatalogProviderImpl.MAX_BOOLEAN_CLAUSES, identifiers.size())));
      }
    }

    if (forceCommit) {
      client.commit();
    }
  }

  @Override
  public void deleteByQuery(String query) throws IOException, SolrServerException {
    client.deleteByQuery(query);
  }

  public String getIdentifierQuery(String fieldName, List<? extends Serializable> identifiers) {
    StringBuilder queryBuilder = new StringBuilder();
    for (Serializable id : identifiers) {
      if (queryBuilder.length() > 0) {
        queryBuilder.append(" OR ");
      }

      queryBuilder.append(fieldName).append(":").append(QUOTE).append(id).append(QUOTE);
    }
    return queryBuilder.toString();
  }

  private static String accessProperty(String key, String defaultValue) {
    String value =
        AccessController.doPrivileged(
            (PrivilegedAction<String>) () -> System.getProperty(key, defaultValue));
    LOGGER.debug("Read system property [{}] with value [{}]", key, value);
    return value;
  }

  private org.apache.solr.client.solrj.response.UpdateResponse softCommit(
      List<SolrInputDocument> docs) throws SolrServerException, IOException {
    return new org.apache.solr.client.solrj.request.UpdateRequest()
        .add(docs)
        .setAction(
            AbstractUpdateRequest.ACTION.COMMIT,
            /* waitForFlush */ true,
            /* waitToMakeVisible */ true,
            /* softCommit */ true)
        .process(client.getClient());
  }

  private ddf.catalog.operation.QueryResponse putMetricsDuration(
      ddf.catalog.operation.QueryResponse response, String key, long value) {
    putMetricsDuration(response.getRequest(), key, value);
    return response;
  }

  private QueryRequest putMetricsDuration(QueryRequest request, String key, long value) {
    request.getProperties().put(key, value);
    return request;
  }
}
