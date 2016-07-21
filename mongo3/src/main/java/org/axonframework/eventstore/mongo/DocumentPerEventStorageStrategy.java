/*
 * Copyright (c) 2010-2014. Axon Framework
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

package org.axonframework.eventstore.mongo;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.IndexOptions;
import org.axonframework.domain.DomainEventMessage;
import org.axonframework.eventstore.mongo.criteria.MongoCriteria;
import org.axonframework.serializer.SerializedDomainEventData;
import org.axonframework.serializer.SerializedMetaData;
import org.axonframework.serializer.SerializedObject;
import org.axonframework.serializer.Serializer;
import org.axonframework.serializer.SimpleSerializedObject;
import org.axonframework.upcasting.UpcasterChain;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.List;

import static org.axonframework.serializer.MessageSerializer.serializeMetaData;
import static org.axonframework.serializer.MessageSerializer.serializePayload;
import static org.axonframework.upcasting.UpcastUtils.upcastAndDeserialize;

import static com.mongodb.client.model.Sorts.*;
import static com.mongodb.client.model.Filters.*;

/**
 * Implementation of the StorageStrategy that stores each event as a separate document. This makes it easier to query
 * the event store for specific events, but does not allow for atomic storage of a single commit.
 * <p/>
 * The structure is as follows:
 * <ul>
 * <li>aggregateIdentifier => [aggregateIdentifier]</li>
 * <li>sequenceNumber => [sequenceNumber of first event]</li>
 * <li>timestamp => [timestamp of first event]</li>
 * <li>type => [aggregate type]</li>
 * <li>serializedPayload => [payload of the event]</li>
 * <li>payloadType => [type of the payload]</li>
 * <li>payloadRevision => [revision of the payload]</li>
 * <li>serializedMetaData => [meta data of the event]</li>
 * <li>eventIdentifier => [identifier of the event]</li>
 * </ul>
 * <p/>
 * <em>Note: the SerializedType of Message Meta Data is not stored. Upon retrieval, it is set to the default value
 * (name = "org.axonframework.domain.MetaData", revision = null). See {@link org.axonframework.serializer.SerializedMetaData#isSerializedMetaData(org.axonframework.serializer.SerializedObject)}</em>
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class DocumentPerEventStorageStrategy implements StorageStrategy {

    private static final int ORDER_ASC = 1;
    private static final int ORDER_DESC = -1;

    @Override
    public List<Document> createDocuments(String type, Serializer eventSerializer, List<DomainEventMessage> messages) {
        List<Document> dbObjects = new ArrayList<Document>();

        for (int i = 0, messagesSize = messages.size(); i < messagesSize; i++) {
            DomainEventMessage message = messages.get(i);
            dbObjects.add(new EventEntry(type, message, eventSerializer).asDocument());
        }
        return dbObjects;
    }

    @Override
    public MongoCursor<Document> findEvents(MongoCollection<Document> collection, String aggregateType, String aggregateIdentifier,
                                  long firstSequenceNumber) {
        return collection.find(EventEntry.forAggregate(aggregateType, aggregateIdentifier, firstSequenceNumber))
                 .sort(new BasicDBObject(EventEntry.SEQUENCE_NUMBER_PROPERTY, ORDER_ASC))
                .iterator();
    }

    @Override
    public List<DomainEventMessage> extractEventMessages(Document entry, Object aggregateIdentifier,
                                                         Serializer serializer, UpcasterChain upcasterChain,
                                                         boolean skipUnknownTypes) {
        return new EventEntry(entry).getDomainEvents(aggregateIdentifier, serializer, upcasterChain, skipUnknownTypes);
    }

    @Override
    public void ensureIndexes(MongoCollection eventsCollection, MongoCollection snapshotsCollection) {
        eventsCollection.createIndex(new BasicDBObject(EventEntry.AGGREGATE_IDENTIFIER_PROPERTY, 1)
                                             .append(EventEntry.AGGREGATE_TYPE_PROPERTY, 1)
                                             .append(EventEntry.SEQUENCE_NUMBER_PROPERTY, 1),
                                     new IndexOptions().unique(true).name("uniqueAggregateIndex"));

        eventsCollection.createIndex(new BasicDBObject(EventEntry.TIME_STAMP_PROPERTY, 1)
                                             .append(EventEntry.SEQUENCE_NUMBER_PROPERTY, 1),
                                     new IndexOptions().unique(false).name("orderedEventStreamIndex"));

        snapshotsCollection.createIndex(new BasicDBObject(EventEntry.AGGREGATE_IDENTIFIER_PROPERTY, 1)
                                             .append(EventEntry.AGGREGATE_TYPE_PROPERTY, 1)
                                             .append(EventEntry.SEQUENCE_NUMBER_PROPERTY, 1),
                                        new IndexOptions().unique(true).name("uniqueAggregateIndex"));
    }

    @Override
    public MongoCursor<Document> findEvents(MongoCollection<Document> collection, MongoCriteria criteria) {
        Bson sort = ascending(EventEntry.TIME_STAMP_PROPERTY, EventEntry.SEQUENCE_NUMBER_PROPERTY);

        return criteria == null
                ? collection.find().sort(sort).iterator()
                : collection.find(criteria.asBson()).sort(sort).iterator();
    }

    @Override
    public MongoCursor<Document> findLastSnapshot(MongoCollection<Document> collection, String aggregateType, String aggregateIdentifier) {
        return collection.find(and(
            eq(EventEntry.AGGREGATE_IDENTIFIER_PROPERTY, aggregateIdentifier),
            eq(EventEntry.AGGREGATE_TYPE_PROPERTY, aggregateType)
        )).sort(descending(EventEntry.SEQUENCE_NUMBER_PROPERTY))
                         .limit(1).iterator();
    }

    /**
     * Data needed by different types of event logs.
     *
     * @author Allard Buijze
     * @author Jettro Coenradie
     * @since 2.0 (in incubator since 0.7)
     */
    private static final class EventEntry implements SerializedDomainEventData {

        /**
         * Property name in mongo for the Aggregate Identifier.
         */
        private static final String AGGREGATE_IDENTIFIER_PROPERTY = "aggregateIdentifier";

        /**
         * Property name in mongo for the Sequence Number.
         */
        private static final String SEQUENCE_NUMBER_PROPERTY = "sequenceNumber";

        /**
         * Property name in mongo for the Aggregate's Type Identifier.
         */
        private static final String AGGREGATE_TYPE_PROPERTY = "type";

        /**
         * Property name in mongo for the Time Stamp.
         */
        private static final String TIME_STAMP_PROPERTY = "timeStamp";

        private static final String SERIALIZED_PAYLOAD_PROPERTY = "serializedPayload";
        private static final String PAYLOAD_TYPE_PROPERTY = "payloadType";
        private static final String PAYLOAD_REVISION_PROPERTY = "payloadRevision";
        private static final String META_DATA_PROPERTY = "serializedMetaData";
        private static final String EVENT_IDENTIFIER_PROPERTY = "eventIdentifier";
        /**
         * Charset used for the serialization is usually UTF-8, which is presented by this constant.
         */
        private final String aggregateIdentifier;
        private final long sequenceNumber;
        private final String timeStamp;
        private final String aggregateType;
        private final Object serializedPayload;
        private final String payloadType;
        private final String payloadRevision;
        private final Object serializedMetaData;
        private final String eventIdentifier;

        /**
         * Constructor used to create a new event entry to store in Mongo.
         *
         * @param aggregateType String containing the aggregate type of the event
         * @param event         The actual DomainEvent to store
         * @param serializer    Serializer to use for the event to store
         */
        private EventEntry(String aggregateType, DomainEventMessage event, Serializer serializer) {
            this.aggregateType = aggregateType;
            this.aggregateIdentifier = event.getAggregateIdentifier().toString();
            this.sequenceNumber = event.getSequenceNumber();
            this.eventIdentifier = event.getIdentifier();
            Class<?> serializationTarget = String.class;
            if (serializer.canSerializeTo(DBObject.class)) {
                serializationTarget = DBObject.class;
            }
            SerializedObject serializedPayloadObject = serializePayload(event, serializer, serializationTarget);
            SerializedObject serializedMetaDataObject = serializeMetaData(event, serializer, serializationTarget);

            this.serializedPayload = serializedPayloadObject.getData();
            this.payloadType = serializedPayloadObject.getType().getName();
            this.payloadRevision = serializedPayloadObject.getType().getRevision();
            this.serializedMetaData = serializedMetaDataObject.getData();
            this.timeStamp = event.getTimestamp().toString();
        }

        /**
         * Creates a new EventEntry based onm data provided by Mongo.
         *
         * @param dbObject Mongo object that contains data to represent an EventEntry
         */
        private EventEntry(Document dbObject) {
            this.aggregateIdentifier = (String) dbObject.get(AGGREGATE_IDENTIFIER_PROPERTY);
            this.sequenceNumber = ((Number) dbObject.get(SEQUENCE_NUMBER_PROPERTY)).longValue();
            this.serializedPayload = dbObject.get(SERIALIZED_PAYLOAD_PROPERTY);
            this.timeStamp = (String) dbObject.get(TIME_STAMP_PROPERTY);
            this.aggregateType = (String) dbObject.get(AGGREGATE_TYPE_PROPERTY);
            this.payloadType = (String) dbObject.get(PAYLOAD_TYPE_PROPERTY);
            this.payloadRevision = (String) dbObject.get(PAYLOAD_REVISION_PROPERTY);
            this.serializedMetaData = dbObject.get(META_DATA_PROPERTY);
            this.eventIdentifier = (String) dbObject.get(EVENT_IDENTIFIER_PROPERTY);
        }

        /**
         * Returns the actual DomainEvent from the EventEntry using the provided Serializer.
         *
         * @param actualAggregateIdentifier The actual aggregate identifier instance used to perform the lookup, or
         *                                  <code>null</code> if unknown
         * @param eventSerializer           Serializer used to de-serialize the stored DomainEvent
         * @param upcasterChain             Set of upcasters to use when an event needs upcasting before
         *                                  de-serialization
         * @param skipUnknownTypes          whether to skip unknown event types
         * @return The actual DomainEventMessage instances stored in this entry
         */
        @SuppressWarnings("unchecked")
        public List<DomainEventMessage> getDomainEvents(Object actualAggregateIdentifier, Serializer eventSerializer,
                                                        UpcasterChain upcasterChain, boolean skipUnknownTypes) {
            return upcastAndDeserialize(this, actualAggregateIdentifier, eventSerializer,
                                        upcasterChain, skipUnknownTypes);
        }

        private Class<?> getRepresentationType() {
            Class<?> representationType = String.class;
            if (serializedPayload instanceof DBObject) {
                representationType = DBObject.class;
            }
            return representationType;
        }

        @Override
        public String getEventIdentifier() {
            return eventIdentifier;
        }

        @Override
        public Object getAggregateIdentifier() {
            return aggregateIdentifier;
        }

        /**
         * getter for the sequence number of the event.
         *
         * @return long representing the sequence number of the event
         */
        public long getSequenceNumber() {
            return sequenceNumber;
        }

        @Override
        public DateTime getTimestamp() {
            return new DateTime(timeStamp);
        }

        @SuppressWarnings("unchecked")
        @Override
        public SerializedObject getMetaData() {
            return new SerializedMetaData(serializedMetaData, getRepresentationType());
        }

        @SuppressWarnings("unchecked")
        @Override
        public SerializedObject getPayload() {
            return new SimpleSerializedObject(serializedPayload, getRepresentationType(), payloadType, payloadRevision);
        }

        /**
         * Returns the current EventEntry as a mongo DBObject.
         *
         * @return Document representing the EventEntry
         */
        public Document asDocument() {
            return new Document(AGGREGATE_IDENTIFIER_PROPERTY, aggregateIdentifier)
                    .append(SEQUENCE_NUMBER_PROPERTY, sequenceNumber)
                    .append(SERIALIZED_PAYLOAD_PROPERTY, serializedPayload)
                    .append(TIME_STAMP_PROPERTY, timeStamp)
                    .append(AGGREGATE_TYPE_PROPERTY, aggregateType)
                    .append(PAYLOAD_TYPE_PROPERTY, payloadType)
                    .append(PAYLOAD_REVISION_PROPERTY, payloadRevision)
                    .append(META_DATA_PROPERTY, serializedMetaData)
                    .append(EVENT_IDENTIFIER_PROPERTY, eventIdentifier);
        }

        /**
         * Returns the mongo DBObject used to query mongo for events for specified aggregate identifier and type.
         *
         * @param type                The type of the aggregate to create the mongo DBObject for
         * @param aggregateIdentifier Identifier of the aggregate to obtain the mongo DBObject for
         * @param firstSequenceNumber number representing the first event to obtain
         * @return Created Bson based on the provided parameters to be used for a query
         */
        public static Bson forAggregate(String type, String aggregateIdentifier, long firstSequenceNumber) {
            return and(
                    eq(EventEntry.AGGREGATE_IDENTIFIER_PROPERTY, aggregateIdentifier),
                    gte(EventEntry.SEQUENCE_NUMBER_PROPERTY, firstSequenceNumber),
                    eq(EventEntry.AGGREGATE_TYPE_PROPERTY, type)
            );
        }
    }
}