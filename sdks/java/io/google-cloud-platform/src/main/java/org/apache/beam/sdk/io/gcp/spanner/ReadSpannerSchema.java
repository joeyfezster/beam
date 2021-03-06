/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import org.apache.beam.sdk.transforms.DoFn;

/**
 * This {@link DoFn} reads Cloud Spanner 'information_schema.*' tables to build the
 * {@link SpannerSchema}.
 */
class ReadSpannerSchema extends DoFn<Void, SpannerSchema> {

  private final SpannerConfig config;

  private transient SpannerAccessor spannerAccessor;

  public ReadSpannerSchema(SpannerConfig config) {
    this.config = config;
  }

  @Setup
  public void setup() throws Exception {
    spannerAccessor = config.connectToSpanner();
  }

  @Teardown
  public void teardown() throws Exception {
    spannerAccessor.close();
  }

  @ProcessElement
  public void processElement(ProcessContext c) throws Exception {
    SpannerSchema.Builder builder = SpannerSchema.builder();
    DatabaseClient databaseClient = spannerAccessor.getDatabaseClient();
    try (ReadOnlyTransaction tx =
        databaseClient.readOnlyTransaction()) {
      ResultSet resultSet = readTableInfo(tx);

      while (resultSet.next()) {
        String tableName = resultSet.getString(0);
        String columnName = resultSet.getString(1);
        String type = resultSet.getString(2);

        builder.addColumn(tableName, columnName, type);
      }

      resultSet = readPrimaryKeyInfo(tx);
      while (resultSet.next()) {
        String tableName = resultSet.getString(0);
        String columnName = resultSet.getString(1);
        String ordering = resultSet.getString(2);

        builder.addKeyPart(tableName, columnName, ordering.toUpperCase().equals("DESC"));
      }
    }
    c.output(builder.build());
  }

  private ResultSet readTableInfo(ReadOnlyTransaction tx) {
    return tx.executeQuery(Statement.of(
        "SELECT c.table_name, c.column_name, c.spanner_type"
            + " FROM information_schema.columns as c"
            + " WHERE where c.table_catalog = '' AND c.table_schema = ''"
            + " ORDER BY c.table_name, c.ordinal_position"));
  }

  private ResultSet readPrimaryKeyInfo(ReadOnlyTransaction tx) {
    return tx.executeQuery(Statement
        .of("SELECT t.table_name, t.column_name, t.column_ordering"
            + " FROM information_schema.index_columns AS t "
            + " WHERE t.index_name = 'PRIMARY_KEY' AND t.table_catalog = ''"
            + " AND t.table_schema = ''"
            + " ORDER BY t.table_name, t.ordinal_position"));
  }
}
