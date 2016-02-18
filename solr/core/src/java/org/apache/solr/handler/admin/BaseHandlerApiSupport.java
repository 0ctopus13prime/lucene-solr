package org.apache.solr.handler.admin;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.collect.ImmutableList;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.Map2;
import org.apache.solr.common.util.Utils;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.util.CommandOperation;
import org.apache.solr.api.Api;
import org.apache.solr.api.ApiBag;
import org.apache.solr.api.ApiSupport;

import static org.apache.solr.client.solrj.SolrRequest.METHOD.POST;
import static org.apache.solr.common.SolrException.ErrorCode.BAD_REQUEST;
import static org.apache.solr.common.util.StrUtils.splitSmart;

public abstract class BaseHandlerApiSupport implements ApiSupport {
  protected final Map<SolrRequest.METHOD, Map<V2EndPoint, List<ApiCommand>>> commandsMapping;

  protected BaseHandlerApiSupport() {
    commandsMapping = new HashMap<>();
    for (ApiCommand cmd : getCommands()) {
      Map<V2EndPoint, List<ApiCommand>> m = commandsMapping.get(cmd.getHttpMethod());
      if (m == null) commandsMapping.put(cmd.getHttpMethod(), m = new HashMap<>());
      List<ApiCommand> list = m.get(cmd.getEndPoint());
      if (list == null) m.put(cmd.getEndPoint(), list = new ArrayList<>());
      list.add(cmd);
    }
  }

  @Override
  public synchronized Collection<Api> getApis() {
    ImmutableList.Builder<Api> l = ImmutableList.builder();
    for (V2EndPoint op : getEndPoints()) l.add(getApi(op));
    return l.build();
  }


  private Api getApi(final V2EndPoint op) {
    final Map2 spec = ApiBag.getSpec(op.getSpecName());
    return new Api(spec) {
      @Override
      public void call(SolrQueryRequest req, SolrQueryResponse rsp) {
        SolrParams params = req.getParams();
        SolrRequest.METHOD method = SolrRequest.METHOD.valueOf(req.getHttpMethod());
        List<ApiCommand> commands = commandsMapping.get(method).get(op);
        try {
          if (method == POST) {
            List<CommandOperation> cmds = req.getCommands(true);
            if (cmds.size() > 1)
              throw new SolrException(BAD_REQUEST, "Only one command is allowed");
            CommandOperation c = cmds.size() == 0 ? null : cmds.get(0);
            ApiCommand command = null;
            String commandName = c == null ? null : c.name;
            for (ApiCommand cmd : commands) {
              if (Objects.equals(cmd.getName(), commandName)) {
                command = cmd;
                break;
              }
            }

            if (command == null) {
              throw new SolrException(BAD_REQUEST, " no such command " + c);
            }
            wrapParams(req, c, command, false);
            invokeCommand(req, rsp,command, c);

          } else {
            if (commands == null || commands.isEmpty()) {
              rsp.add("error", "No support for : " + method + " at :" + req.getPath());
              return;
            }
            wrapParams(req, new CommandOperation("", Collections.EMPTY_MAP), commands.get(0), true);
            invokeUrl(commands.get(0), req, rsp);
          }

        } catch (SolrException e) {
          throw e;
        } catch (Exception e) {
          throw new SolrException(BAD_REQUEST, e);
        } finally {
          req.setParams(params);
        }

      }
    };

  }

  private static void wrapParams(final SolrQueryRequest req, final CommandOperation co, final ApiCommand cmd, final boolean useRequestParams) {
    final Map<String, String> pathValues = req.getPathValues();
    final Map<String, Object> map = co == null || !(co.getCommandData() instanceof Map) ?
        Collections.emptyMap() : co.getDataMap();
    final SolrParams origParams = req.getParams();

    req.setParams(
        new SolrParams() {
          @Override
          public String get(String param) {
            Object vals = getParams0(param);
            if (vals == null) return null;
            if (vals instanceof String) return (String) vals;
            if (vals instanceof String[] && ((String[]) vals).length > 0) return ((String[]) vals)[0];
            return null;
          }

          private Object getParams0(String param) {
            param = cmd.getParamSubstitute(param);
            Object o = param.indexOf('.') > 0 ?
                Utils.getObjectByPath(map, true, splitSmart(param, '.')) :
                map.get(param);
            if (o == null) o = pathValues.get(param);
            if (o == null && useRequestParams) o = origParams.getParams(param);
            if (o instanceof List) {
              List l = (List) o;
              return l.toArray(new String[l.size()]);
            }

            return o;
          }

          @Override
          public String[] getParams(String param) {
            Object vals = getParams0(param);
            return vals == null || vals instanceof String[] ?
                (String[]) vals :
                new String[]{vals.toString()};
          }

          @Override
          public Iterator<String> getParameterNamesIterator() {
            return cmd.getParamNames(co).iterator();

          }


        });

  }


  public static Collection<String> getParamNames(CommandOperation op, ApiCommand command) {
    List<String> result = new ArrayList<>();
    Object o = op.getCommandData();
    if (o instanceof Map) {
      Map map = (Map) o;
      collectKeyNames(map, result, "");
    }
    return result;

  }

  public static void collectKeyNames(Map<String, Object> map, List<String> result, String prefix) {
    for (Map.Entry<String, Object> e : map.entrySet()) {
      if (e.getValue() instanceof Map) {
        collectKeyNames((Map) e.getValue(), result, prefix + e.getKey() + ".");
      } else {
        result.add(prefix + e.getKey());
      }
    }
  }

  @Override
  public boolean registerApi() {
    return true;
  }

  protected abstract void invokeCommand(SolrQueryRequest  req, SolrQueryResponse rsp, ApiCommand command, CommandOperation c) throws Exception;

  protected abstract void invokeUrl(ApiCommand command, SolrQueryRequest req, SolrQueryResponse rsp) throws Exception;

  protected abstract List<ApiCommand> getCommands();

  protected abstract List<V2EndPoint> getEndPoints();


}
