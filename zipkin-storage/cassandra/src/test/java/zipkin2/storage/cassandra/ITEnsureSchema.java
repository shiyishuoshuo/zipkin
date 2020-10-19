/*
 * Copyright 2015-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.storage.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import org.junit.jupiter.api.Test;
import zipkin2.TestObjects;
import zipkin2.storage.QueryRequest;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;
import static zipkin2.TestObjects.CLIENT_SPAN;
import static zipkin2.TestObjects.DAY;
import static zipkin2.TestObjects.TODAY;
import static zipkin2.storage.cassandra.ITCassandraStorage.SEARCH_TABLES;
import static zipkin2.storage.cassandra.Schema.TABLE_DEPENDENCY;
import static zipkin2.storage.cassandra.Schema.TABLE_SPAN;

abstract class ITEnsureSchema {

  abstract protected String keyspace();

  abstract protected CqlSession session();

  abstract String contactPoint();

  @Test void installsKeyspaceWhenMissing() {
    Schema.ensureExists(keyspace(), false, session());

    KeyspaceMetadata metadata = session().getMetadata().getKeyspace(keyspace()).get();
    assertThat(metadata).isNotNull();
  }

  @Test void installsTablesWhenMissing() {
    session().execute("CREATE KEYSPACE " + keyspace()
      + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'};");

    Schema.ensureExists(keyspace(), false, session());

    KeyspaceMetadata metadata = session().getMetadata().getKeyspace(keyspace()).get();
    assertThat(metadata.getTable(TABLE_SPAN)).isPresent();
    assertThat(metadata.getTable(TABLE_DEPENDENCY)).isPresent();

    for (String searchTable : SEARCH_TABLES) {
      assertThat(metadata.getTable(searchTable))
        .withFailMessage("Expected to not find " + searchTable).isEmpty();
    }
  }

  @Test void installsSearchTablesWhenMissing() {
    session().execute("CREATE KEYSPACE " + keyspace()
      + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'};");

    Schema.ensureExists(keyspace(), true, session());

    KeyspaceMetadata metadata = session().getMetadata().getKeyspace(keyspace()).get();

    for (String searchTable : SEARCH_TABLES) {
      assertThat(metadata.getTable(searchTable))
        .withFailMessage("Expected to find " + searchTable).isPresent();
    }
  }

  @Test void upgradesOldSchema_autocomplete() {
    Schema.applyCqlFile(keyspace(), session(), "/zipkin2-schema.cql");
    Schema.applyCqlFile(keyspace(), session(), "/zipkin2-schema-indexes-original.cql");

    Schema.ensureExists(keyspace(), true, session());

    KeyspaceMetadata metadata = session().getMetadata().getKeyspace(keyspace()).get();
    assertThat(Schema.hasUpgrade1_autocompleteTags(metadata)).isTrue();
  }

  @Test void upgradesOldSchema_remoteService() {
    Schema.applyCqlFile(keyspace(), session(), "/zipkin2-schema.cql");
    Schema.applyCqlFile(keyspace(), session(), "/zipkin2-schema-indexes-original.cql");
    Schema.applyCqlFile(keyspace(), session(), "/zipkin2-schema-upgrade-1.cql");

    Schema.ensureExists(keyspace(), true, session());

    KeyspaceMetadata metadata = session().getMetadata().getKeyspace(keyspace()).get();
    assertThat(Schema.hasUpgrade2_remoteService(metadata)).isTrue();
  }

  /** This tests we don't accidentally rely on new indexes such as autocomplete tags */
  @Test void worksWithOldSchema() throws Exception {
    Schema.applyCqlFile(keyspace(), session(), "/zipkin2-schema.cql");
    Schema.applyCqlFile(keyspace(), session(), "/zipkin2-schema-indexes-original.cql");

    try (CassandraStorage storage = CassandraStorageExtension.newStorageBuilder(contactPoint())
      .ensureSchema(false)
      .autocompleteKeys(asList("environment"))
      .keyspace(keyspace()).build()) {

      storage.spanConsumer().accept(TestObjects.TRACE).execute();

      assertThat(storage.traces().getTrace(TestObjects.TRACE.get(0).traceId()).execute())
        .containsExactlyInAnyOrderElementsOf(TestObjects.TRACE);

      assertThat(storage.autocompleteTags().getValues("environment").execute())
        .isEmpty(); // instead of an exception
      String serviceName = TestObjects.TRACE.get(0).localServiceName();
      assertThat(storage.serviceAndSpanNames().getRemoteServiceNames(serviceName).execute())
        .isEmpty(); // instead of an exception

      // Make sure there is a good message if a query will return incorrectly
      try {
        storage.spanStore().getTraces(QueryRequest.newBuilder()
          .endTs(TODAY)
          .lookback(DAY)
          .limit(10)
          .serviceName(serviceName)
          .remoteServiceName(CLIENT_SPAN.remoteServiceName()).build()).execute();

        failBecauseExceptionWasNotThrown(IllegalArgumentException.class);
      } catch (IllegalArgumentException e) { // instead of returning invalid results
        assertThat(e).hasMessage(
          "remoteService=backend unsupported due to missing table remote_service_by_service");
      }
    }
  }
}
