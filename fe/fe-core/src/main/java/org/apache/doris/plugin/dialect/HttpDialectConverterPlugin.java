// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.plugin.dialect;

import org.apache.doris.analysis.StatementBase;
import org.apache.doris.common.Config;
import org.apache.doris.common.util.DigitalVersion;
import org.apache.doris.nereids.parser.Dialect;
import org.apache.doris.plugin.DialectConverterPlugin;
import org.apache.doris.plugin.Plugin;
import org.apache.doris.plugin.PluginContext;
import org.apache.doris.plugin.PluginException;
import org.apache.doris.plugin.PluginInfo;
import org.apache.doris.plugin.PluginInfo.PluginType;
import org.apache.doris.plugin.PluginMgr;
import org.apache.doris.qe.GlobalVariable;
import org.apache.doris.qe.SessionVariable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Currently, there are many frameworks and services that support SQL dialect conversion,
 * but they may not be implemented in Java.
 * Therefore, we can encapsulate these SQL dialect conversion frameworks or services into an HTTP service,
 * and combine them with this plugin to provide dialect conversion capabilities.
 * Note that the protocol request/response for the wrapped HTTP service must comply with the following rules:
 * <pre>
 * Request body:
 * {
 *     "version": "v1",
 *     "sql": "select * from t",
 *     "from": "presto",
 *     "to": "doris",
 *     "source": "text",
 *     "case_sensitive": "0"
 * }
 *
 * Response body:
 * {
 *     "version": "v1",
 *     "data": "select * from t",
 *     "code": 0,
 *     "message": ""
 * }
 * </pre>
 * */
public class HttpDialectConverterPlugin extends Plugin implements DialectConverterPlugin {

    private static final Logger LOG = LogManager.getLogger(HttpDialectConverterPlugin.class);

    private volatile boolean isInit = false;
    private volatile ImmutableSet<Dialect> acceptDialects;
    private final PluginInfo pluginInfo;
    private static int chooseServerIdx = 0;
    private static List<String> dialectConverterSerInfo = Lists.newArrayList();

    public HttpDialectConverterPlugin() {
        pluginInfo = new PluginInfo(PluginMgr.BUILTIN_PLUGIN_PREFIX + "SqlDialectConverter", PluginType.DIALECT,
                "builtin sql dialect converter", DigitalVersion.fromString("2.1.0"),
                DigitalVersion.fromString("1.8.31"), HttpDialectConverterPlugin.class.getName(), null, null);
        acceptDialects = ImmutableSet.copyOf(Arrays.asList(Dialect.PRESTO, Dialect.TRINO, Dialect.HIVE,
                Dialect.SPARK, Dialect.POSTGRES, Dialect.CLICKHOUSE));
        analyzeConverterSevicesInfo();
    }

    public PluginInfo getPluginInfo() {
        return pluginInfo;
    }

    private static void analyzeConverterSevicesInfo() {
        String  dialectConverterServices = Config.dialect_converter_services;
        if (Strings.isNullOrEmpty(dialectConverterServices)) {
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("configured dialect_converter_services value: {}", dialectConverterServices);
        }

        String[] serviceList = dialectConverterServices.split(";");
        dialectConverterSerInfo = Lists.newArrayList(serviceList);
    }

    @Override
    public void init(PluginInfo info, PluginContext ctx) throws PluginException {
        super.init(info, ctx);
    }

    @Override
    public void close() throws IOException {
        super.close();
    }

    @Override
    public ImmutableSet<Dialect> acceptDialects() {
        return acceptDialects;
    }

    @Override
    public @Nullable String convertSql(String originSql, SessionVariable sessionVariable) {
        String targetURL = GlobalVariable.sqlConverterServiceUrl;

        if (Strings.isNullOrEmpty(targetURL) && dialectConverterSerInfo.isEmpty()) {
            return null;
        }

        if (!Strings.isNullOrEmpty(targetURL)) {
            String resultStr = HttpDialectUtils.convertSql(targetURL, originSql, sessionVariable.getSqlDialect());
            if (Strings.isNullOrEmpty(resultStr)) {
                return originSql;
            } else {
                return resultStr;
            }
        } else {
            if (!sessionVariable.isEnableMultiDialectConvertService()) {
                return originSql;
            }

            int retryCount = 0;
            while (retryCount < dialectConverterSerInfo.size()) {
                String chooseServer = dialectConverterSerInfo.get(chooseServerIdx++ % dialectConverterSerInfo.size());
                retryCount++;
                if (LOG.isDebugEnabled()) {
                    LOG.debug("convertsql server is : {}", chooseServer);
                }

                targetURL = "http://" + chooseServer + "/api/v1/convert";
                String resultStr = HttpDialectUtils.convertSql(targetURL, originSql,
                        sessionVariable.getSqlDialect());

                if (Strings.isNullOrEmpty(resultStr)) {
                    continue;
                } else {
                    return resultStr;
                }
            }
            return originSql;
        }
    }

    // no need to override parseSqlWithDialect, just return null
    @Override
    public List<StatementBase> parseSqlWithDialect(String sql, SessionVariable sessionVariable) {
        return null;
    }
}
