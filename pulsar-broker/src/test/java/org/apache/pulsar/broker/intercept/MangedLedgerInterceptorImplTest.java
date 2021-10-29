/**
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
 */
package org.apache.pulsar.broker.intercept;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Cleanup;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedLedger;
import org.apache.bookkeeper.mledger.ManagedLedgerConfig;
import org.apache.bookkeeper.mledger.impl.ManagedCursorImpl;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerFactoryImpl;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.bookkeeper.mledger.intercept.ManagedLedgerInterceptor;
import org.apache.bookkeeper.test.MockedBookKeeperTestCase;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.common.api.proto.BrokerEntryMetadata;
import org.apache.pulsar.common.intercept.BrokerEntryMetadataInterceptor;
import org.apache.pulsar.common.intercept.BrokerEntryMetadataUtils;
import org.apache.pulsar.common.intercept.MessagePayloadProcessor;
import org.apache.pulsar.common.protocol.Commands;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;

@Test(groups = "broker")
public class MangedLedgerInterceptorImplTest  extends MockedBookKeeperTestCase {
    private static final Logger log = LoggerFactory.getLogger(MangedLedgerInterceptorImplTest.class);

    public class TestPayloadProcessor implements MessagePayloadProcessor {
        public ByteBuf interceptIn(String clusterName, ByteBuf headersAndPayload, Map<String,Object> contextMap){
            byte[] newMessage = (new String("Modified Test Message")).getBytes();
            return Unpooled.wrappedBuffer(newMessage, 0, newMessage.length);
        }
        public ByteBuf interceptOut(ByteBuf headersAndPayload){
            byte[] bytes = new byte[headersAndPayload.readableBytes()];
            headersAndPayload.readBytes(bytes);
            String storedMessage = new String(bytes);
            Assert.assertTrue(storedMessage.equals("Modified Test Message"));

            byte[] newMessage = (new String("Test Message")).getBytes();
            return Unpooled.wrappedBuffer(newMessage, 0, newMessage.length);
        }
    }

    protected static ServiceConfiguration getDefaultConf() {
        ServiceConfiguration configuration = new ServiceConfiguration();
        configuration.setAdvertisedAddress("localhost");
        configuration.setClusterName("testcluster");
        configuration.setManagedLedgerCacheSizeMB(8);
        configuration.setActiveConsumerFailoverDelayTimeMillis(0);
        configuration.setDefaultNumberOfNamespaceBundles(1);
        configuration.setZookeeperServers("localhost:2181");
        configuration.setConfigurationStoreServers("localhost:3181");
        configuration.setAllowAutoTopicCreationType("non-partitioned");
        configuration.setBrokerShutdownTimeoutMs(0L);
        configuration.setBookkeeperClientExposeStatsToPrometheus(true);
        configuration.setNumExecutorThreadPoolSize(5);
        configuration.setBrokerMaxConnections(0);
        configuration.setBrokerMaxConnectionsPerIp(0);
        return configuration;
    }

    @Test
    public void testAddBrokerEntryMetadata() throws Exception {
        final int MOCK_BATCH_SIZE = 2;
        int numberOfEntries = 10;
        final String ledgerAndCursorName = "topicEntryMetadataSequenceId";

        ManagedLedgerInterceptor interceptor = new ManagedLedgerInterceptorImpl(getBrokerEntryMetadataInterceptors());

        ManagedLedgerConfig config = new ManagedLedgerConfig();
        config.setMaxEntriesPerLedger(2);
        config.setManagedLedgerInterceptor(interceptor);

        ManagedLedger ledger = factory.open(ledgerAndCursorName, config);
        ManagedCursorImpl cursor = (ManagedCursorImpl) ledger.openCursor(ledgerAndCursorName);

        for ( int i = 0 ; i < numberOfEntries; i ++) {
            ledger.addEntry(("message" + i).getBytes(), MOCK_BATCH_SIZE);
        }

        assertEquals(19, ((ManagedLedgerInterceptorImpl) ledger.getManagedLedgerInterceptor()).getIndex());
        List<Entry> entryList = cursor.readEntries(numberOfEntries);
        for (int i = 0 ; i < numberOfEntries; i ++) {
            BrokerEntryMetadata metadata =
                    Commands.parseBrokerEntryMetadataIfExist(entryList.get(i).getDataBuffer());
            assertNotNull(metadata);
            assertEquals(metadata.getIndex(), (i + 1) * MOCK_BATCH_SIZE - 1);
        }

        cursor.close();
        ledger.close();
        factory.shutdown();
    }

    @Test
    public void testMessagePayloadProcessor() throws Exception {
        final int MOCK_BATCH_SIZE = 2;
        int numberOfEntries = 10;
        final String ledgerAndCursorName = "topicEntryWithPayloadProcessed";

        ManagedLedgerInterceptor interceptor = new ManagedLedgerPayloadProcessor(getDefaultConf(), new TestPayloadProcessor());

        ManagedLedgerConfig config = new ManagedLedgerConfig();
        config.setMaxEntriesPerLedger(2);
        config.setManagedLedgerPayloadProcessor(interceptor);

        ManagedLedger ledger = factory.open(ledgerAndCursorName, config);
        ManagedCursorImpl cursor = (ManagedCursorImpl) ledger.openCursor(ledgerAndCursorName);
        ledger.addEntry("Test Message".getBytes());
        factory.getEntryCacheManager().clear();
        List<Entry> entryList = cursor.readEntries(1);
        String message = new String(entryList.get(0).getData());
        Assert.assertTrue(message.equals("Test Message"));
        cursor.close();
        ledger.close();
        factory.shutdown();
        config.setManagedLedgerPayloadProcessor(null);
    }


    @Test(timeOut = 20000)
    public void testRecoveryIndex() throws Exception {
        final int MOCK_BATCH_SIZE = 2;
        ManagedLedgerInterceptor interceptor = new ManagedLedgerInterceptorImpl(getBrokerEntryMetadataInterceptors());

        ManagedLedgerConfig config = new ManagedLedgerConfig();
        config.setManagedLedgerInterceptor(interceptor);
        ManagedLedger ledger = factory.open("my_recovery_index_test_ledger", config);

        ledger.addEntry("dummy-entry-1".getBytes(StandardCharsets.UTF_8), MOCK_BATCH_SIZE);

        ManagedCursor cursor = ledger.openCursor("c1");

        ledger.addEntry("dummy-entry-2".getBytes(StandardCharsets.UTF_8), MOCK_BATCH_SIZE);

        assertEquals(((ManagedLedgerInterceptorImpl) ledger.getManagedLedgerInterceptor()).getIndex(), MOCK_BATCH_SIZE * 2 - 1);

        ledger.close();

        log.info("Closing ledger and reopening");

        // / Reopen the same managed-ledger
        @Cleanup("shutdown")
        ManagedLedgerFactoryImpl factory2 = new ManagedLedgerFactoryImpl(metadataStore, bkc);
        ledger = factory2.open("my_recovery_index_test_ledger", config);

        cursor = ledger.openCursor("c1");

        assertEquals(ledger.getNumberOfEntries(), 2);
        assertEquals(((ManagedLedgerInterceptorImpl) ledger.getManagedLedgerInterceptor()).getIndex(), MOCK_BATCH_SIZE * 2 - 1);


        List<Entry> entries = cursor.readEntries(100);
        assertEquals(entries.size(), 1);
        entries.forEach(e -> e.release());

        cursor.close();
        ledger.close();
    }

    @Test
    public void testFindPositionByIndex() throws Exception {
        final int MOCK_BATCH_SIZE = 2;
        final int maxEntriesPerLedger = 5;
        int maxSequenceIdPerLedger = MOCK_BATCH_SIZE * maxEntriesPerLedger;
        ManagedLedgerInterceptor interceptor = new ManagedLedgerInterceptorImpl(getBrokerEntryMetadataInterceptors());


        ManagedLedgerConfig managedLedgerConfig = new ManagedLedgerConfig();
        managedLedgerConfig.setManagedLedgerInterceptor(interceptor);
        managedLedgerConfig.setMaxEntriesPerLedger(5);

        ManagedLedger ledger = factory.open("my_ml_broker_entry_metadata_test_ledger", managedLedgerConfig);
        ManagedCursor cursor = ledger.openCursor("c1");

        long firstLedgerId = -1;
        for (int i = 0; i < maxEntriesPerLedger; i++) {
            firstLedgerId = ledger.addEntry("dummy-entry".getBytes(StandardCharsets.UTF_8), MOCK_BATCH_SIZE).getLedgerId();
        }

        assertEquals(((ManagedLedgerInterceptorImpl) ledger.getManagedLedgerInterceptor()).getIndex(), 9);


        PositionImpl position = null;
        for (int index = 0; index <= ((ManagedLedgerInterceptorImpl) ledger.getManagedLedgerInterceptor()).getIndex(); index ++) {
            position = (PositionImpl) ledger.asyncFindPosition(new IndexSearchPredicate(index)).get();
            assertEquals(position.getEntryId(), (index % maxSequenceIdPerLedger) / MOCK_BATCH_SIZE);
        }

        // roll over ledger
        long secondLedgerId = -1;
        for (int i = 0; i < maxEntriesPerLedger; i++) {
            secondLedgerId = ledger.addEntry("dummy-entry".getBytes(StandardCharsets.UTF_8), MOCK_BATCH_SIZE).getLedgerId();
        }
        assertEquals(((ManagedLedgerInterceptorImpl) ledger.getManagedLedgerInterceptor()).getIndex(), 19);
        assertNotEquals(firstLedgerId, secondLedgerId);

        for (int index = 0; index <= ((ManagedLedgerInterceptorImpl) ledger.getManagedLedgerInterceptor()).getIndex(); index ++) {
            position = (PositionImpl) ledger.asyncFindPosition(new IndexSearchPredicate(index)).get();
            assertEquals(position.getEntryId(), (index % maxSequenceIdPerLedger) / MOCK_BATCH_SIZE);
        }

        // reopen ledger
        ledger.close();
        // / Reopen the same managed-ledger
        @Cleanup("shutdown")
        ManagedLedgerFactoryImpl factory2 = new ManagedLedgerFactoryImpl(metadataStore, bkc);
        ledger = factory2.open("my_ml_broker_entry_metadata_test_ledger", managedLedgerConfig);

        long thirdLedgerId = -1;
        for (int i = 0; i < maxEntriesPerLedger; i++) {
            thirdLedgerId = ledger.addEntry("dummy-entry".getBytes(StandardCharsets.UTF_8), MOCK_BATCH_SIZE).getLedgerId();
        }
        assertEquals(((ManagedLedgerInterceptorImpl) ledger.getManagedLedgerInterceptor()).getIndex(), 29);
        assertNotEquals(secondLedgerId, thirdLedgerId);

        for (int index = 0; index <= ((ManagedLedgerInterceptorImpl) ledger.getManagedLedgerInterceptor()).getIndex(); index ++) {
            position = (PositionImpl) ledger.asyncFindPosition(new IndexSearchPredicate(index)).get();
            assertEquals(position.getEntryId(), (index % maxSequenceIdPerLedger) / MOCK_BATCH_SIZE);
        }
        cursor.close();
        ledger.close();
    }

    public static Set<BrokerEntryMetadataInterceptor> getBrokerEntryMetadataInterceptors() {
        Set<String> interceptorNames = new HashSet<>();
        interceptorNames.add("org.apache.pulsar.common.intercept.AppendBrokerTimestampMetadataInterceptor");
        interceptorNames.add("org.apache.pulsar.common.intercept.AppendIndexMetadataInterceptor");
        return BrokerEntryMetadataUtils.loadBrokerEntryMetadataInterceptors(interceptorNames,
                Thread.currentThread().getContextClassLoader());
    }

    static class IndexSearchPredicate implements com.google.common.base.Predicate<Entry> {

        long indexToSearch = -1;
        public IndexSearchPredicate(long indexToSearch) {
            this.indexToSearch = indexToSearch;
        }

        @Override
        public boolean apply(@Nullable Entry entry) {
            try {
                BrokerEntryMetadata brokerEntryMetadata = Commands.parseBrokerEntryMetadataIfExist(entry.getDataBuffer());
                return brokerEntryMetadata.getIndex() < indexToSearch;
            } catch (Exception e) {
                log.error("Error deserialize message for message position find", e);
            } finally {
                entry.release();
            }
            return false;
        }
    }

}
