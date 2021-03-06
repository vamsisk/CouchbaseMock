/*
 * Copyright 2017 Couchbase, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.couchbase.mock.http.query;

import com.couchbase.mock.JsonUtils;
import com.couchbase.mock.httpio.HandlerUtil;
import com.couchbase.mock.util.Base64;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Designed to handle fake N1QL queries
 */
public class QueryServer implements HttpRequestHandler {
    static private volatile int randNumber = new Random().nextInt();
    private static ErrorState errorState = null;

    public QueryServer() {
    }

    /**
     * This is the equivalent to 'dropping' indexes
     */
    public static void resetIndexState() {
        randNumber = new Random().nextInt();
    }

    static void doError(HttpResponse response, String msg, int code) {
        Map<String,Object> mm = new HashMap<String, Object>();
        List<Object> ll = new ArrayList<Object>();
        Map<String,Object> err = new HashMap<String, Object>();
        err.put("msg", msg);
        err.put("code", code);
        ll.add(err);
        mm.put("errors", ll);
        String json = JsonUtils.encode(mm);
        HandlerUtil.makeJsonResponse(response, json);
        response.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
    }

    static private Map<String,Object> resultMeta() {
        Map<String,Object> payload = new HashMap<String, Object>();
        payload.put("status", "success");
        payload.put("results", new ArrayList<Object>());
        return payload;
    }

    static private void addResult(Map<String,Object> meta, Map<String,Object> row) {
        List<Object> results = (List)meta.get("results");
        results.add(row);
    }

    private static Map<String,Object> okRow() {
        Map<String,Object> mm = new HashMap<String, Object>();
        mm.put("row", "value");
        return mm;
    }

    private void handleString(String query, HttpResponse response) {
        query = query.toLowerCase();

        Map<String,Object> result;

        if (query.startsWith("prepare")) {
            if (!query.equals("prepare select mockrow")) {
                doError(response, "keyspace not found", 12003);
                return;
            }

            // Return the plan and the encoded form...
            Map<String, Object> mm = new HashMap<String, Object>();

            // This is our "plan"
            mm.put("randomNumber", randNumber);
            String encoded = Base64.encode(JsonUtils.encode(mm));
            mm.put("encoded_plan", encoded);
            mm.put("name", "blah-blah-" + new Random().nextLong());
            result = mm;
        } else if (query.equals("select mockrow")) {
            result = okRow();
        } else if (query.equals("select emptyrow")) {
            // No rows!
            result = null;
        } else {
            doError(response, "keyspace not found", 12003);
            return;
        }
        Map<String,Object> payload = resultMeta();
        if (result != null) {
            addResult(payload, result);
        }
        String resStr = JsonUtils.encode(payload);
        HandlerUtil.makeJsonResponse(response, resStr);
    }
    private void handlePrepared(Map<String,Object> body, HttpResponse response) {
        // Check the encoded plan
        String name = (String) body.get("prepared");
        String encoded = (String) body.get("encoded_plan");
        if (name == null || encoded == null) {
            System.err.println(body.toString());
            doError(response, "missing field", 4040);
            return;
        }
        String decoded = Base64.decode(encoded);
        if (decoded.isEmpty()) {
            doError(response, "could not decode base64", 4070);
            return;
        }
        Map<String,Object> mm = JsonUtils.decodeAsMap(decoded);
        int randNum = ((Number) mm.get("randomNumber")).intValue();
        if (randNum == randNumber) {
            Map<String,Object> payload = resultMeta();
            addResult(payload, okRow());
            String resStr = JsonUtils.encode(payload);
            HandlerUtil.makeJsonResponse(response, resStr);
        } else {
            doError(response, "index deleted or node hosting the index is down - cause: queryport.indexNotFound", 5000);
        }
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        if (!request.getRequestLine().getMethod().equals("POST")) {
            response.setStatusCode(HttpStatus.SC_METHOD_NOT_ALLOWED);
            return;
        }
        HttpEntity entity = ((HttpEntityEnclosingRequest)request).getEntity();
        String txt = EntityUtils.toString(entity);
        Map<String, Object> mm;

        try {
            mm = JsonUtils.decodeAsMap(txt);
            if (mm == null) {
                throw new RuntimeException("Body is empty: " + txt);
            }
        } catch (Exception ex) {
            HandlerUtil.make400Response(response, ex.toString());
            return;
        }
        if (errorState != null && errorState.shouldReturnError(mm)) {
            doError(response, errorState.getMessage(), errorState.getCode());
            return;
        }
        if (!mm.containsKey("statement")) {
            handlePrepared(mm, response);
        } else {
            handleString((String)mm.get("statement"), response);
        }
    }

    public static void setErrorState(ErrorState state) {
        errorState = state;
    }

    public static void resetErrorState() {
        errorState = null;
    }
}
