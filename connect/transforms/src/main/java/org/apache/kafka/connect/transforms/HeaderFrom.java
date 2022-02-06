/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.transforms;

import org.apache.kafka.common.cache.Cache;
import org.apache.kafka.common.cache.LRUCache;
import org.apache.kafka.common.cache.SynchronizedCache;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.transforms.util.FieldUtil;
import org.apache.kafka.connect.transforms.util.NonEmptyListValidator;
import org.apache.kafka.connect.transforms.util.Requirements;
import org.apache.kafka.connect.transforms.util.SchemaUtil;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;
import static org.apache.kafka.common.config.ConfigDef.NO_DEFAULT_VALUE;

public abstract class HeaderFrom<R extends ConnectRecord<R>> implements Transformation<R> {

    public static final String FIELDS_FIELD = "fields";
    public static final String HEADERS_FIELD = "headers";
    public static final String OPERATION_FIELD = "operation";
    private static final String MOVE_OPERATION = "move";
    private static final String COPY_OPERATION = "copy";

    public static final String OVERVIEW_DOC =
            "Moves or copies fields in the key/value of a record into that record's headers. " +
                    "Corresponding elements of <code>" + FIELDS_FIELD + "</code> and " +
                    "<code>" + HEADERS_FIELD + "</code> together identify a field and the header it should be " +
                    "moved or copied to. " +
                    "Use the concrete transformation type designed for the record " +
                    "key (<code>" + Key.class.getName() + "</code>) or value (<code>" + Value.class.getName() + "</code>).";

    public static final ConfigDef CONFIG_DEF = new ConfigDef()
            .define(FIELDS_FIELD, ConfigDef.Type.LIST,
                    NO_DEFAULT_VALUE, new NonEmptyListValidator(),
                    ConfigDef.Importance.HIGH,
                    "Field names in the record whose values are to be copied or moved to headers.")
            .define(HEADERS_FIELD, ConfigDef.Type.LIST,
                    NO_DEFAULT_VALUE, new NonEmptyListValidator(),
                    ConfigDef.Importance.HIGH,
                    "Header names, in the same order as the field names listed in the fields configuration property.")
            .define(OPERATION_FIELD, ConfigDef.Type.STRING, NO_DEFAULT_VALUE,
                    ConfigDef.ValidString.in(MOVE_OPERATION, COPY_OPERATION), ConfigDef.Importance.HIGH,
                    "Either <code>move</code> if the fields are to be moved to the headers (removed from the key/value), " +
                            "or <code>copy</code> if the fields are to be copied to the headers (retained in the key/value).");

    enum Operation {
        MOVE(MOVE_OPERATION),
        COPY(COPY_OPERATION);

        private final String name;

        Operation(String name) {
            this.name = name;
        }

        static Operation fromName(String name) {
            switch (name) {
                case MOVE_OPERATION:
                    return MOVE;
                case COPY_OPERATION:
                    return COPY;
                default:
                    throw new IllegalArgumentException();
            }
        }

        public String toString() {
            return name;
        }
    }

    private List<String> fields;

    private List<String> headers;

    private Operation operation;

    private final Cache<Schema, Schema> moveSchemaCache = new SynchronizedCache<>(new LRUCache<>(16));

    @Override
    public R apply(R record) {
        Object operatingValue = operatingValue(record);
        Schema operatingSchema = operatingSchema(record);

        if (operatingSchema == null) {
            return applySchemaless(record, operatingValue);
        } else {
            return applyWithSchema(record, operatingValue, operatingSchema);
        }
    }

    private R applyWithSchema(R record, Object operatingValue, Schema operatingSchema) {
        Headers updatedHeaders = record.headers().duplicate();
        Struct value = Requirements.requireStruct(operatingValue, "header " + operation);
        final Schema updatedSchema;
        final Struct updatedValue;
        Map<String, List<String>> join = mapEntryHeader(fields, headers);
        if (operation == Operation.MOVE) {
            updatedSchema = moveSchema(operatingSchema, join);
            updatedValue = new Struct(updatedSchema);
            moveValue(value, updatedValue, updatedSchema);
        } else {
            updatedSchema = operatingSchema;
            updatedValue = value;
        }
        copyValue(value, operatingSchema, updatedHeaders, join);
        return newRecord(record, updatedSchema, updatedValue, updatedHeaders);
    }

    private void copyValue(Struct value, Schema operatingSchema, Headers updatedHeaders, Map<String, List<String>> join) {
        for (Map.Entry<String, List<String>> entry : join.entrySet()) {
            Object fieldValue = FieldUtil.valueFrom(value, entry.getKey());
            Schema fieldSchema = FieldUtil.schemaFrom(operatingSchema, entry.getKey());
            List<String> headers = entry.getValue();
            for (String header : headers) {
                updatedHeaders.add(header, fieldValue, fieldSchema);
            }
        }
    }

    private void moveValue(Struct value, Struct updatedValue, Schema updatedSchema) {
        for (Field field : updatedSchema.fields()) {
            Schema schema = field.schema();
            if (schema.type() == Schema.Type.STRUCT) {
                Struct struct = new Struct(schema);
                moveValue(value.getStruct(field.name()), struct, schema);
                updatedValue.put(field, struct);
            } else {
                updatedValue.put(field, value.get(field.name()));
            }
        }
    }

    private Schema moveSchema(Schema operatingSchema, Map<String, List<String>> fields) {
        Map<String, Map<String, List<String>>> other = castsEntries(fields);
        Schema moveSchema = this.moveSchemaCache.get(operatingSchema);
        if (moveSchema == null) {
            final SchemaBuilder builder = SchemaUtil.copySchemaBasics(operatingSchema, SchemaBuilder.struct());
            for (Field field : operatingSchema.fields()) {
                if (!other.containsKey(field.name())) {
                    builder.field(field.name(), field.schema());
                }
                if (other.containsKey(field.name())) {
                    Map<String, List<String>> maps = other.get(field.name());
                    if (!maps.isEmpty()) {
                        moveSchema(field.schema(), other.get(field.name()));
                    }
                }
            }
            moveSchema = builder.build();
            moveSchemaCache.put(operatingSchema, moveSchema);
        }
        return moveSchema;
    }

    private Map<String, List<String>> mapEntryHeader(List<String> fields, List<String> headers) {
        Map<String, List<String>> map = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            String header = headers.get(i);
            map.computeIfPresent(fields.get(i), (s, strings) -> {
                strings.add(header);
                return strings;
            });
            map.computeIfAbsent(fields.get(i), s -> {
                List<String> h = new ArrayList<>();
                h.add(header);
                return h;
            });
        }
        return map;
    }

    private static Map<String, Map<String, List<String>>> castsEntries(Map<String, List<String>> casts) {
        final Map<String, Map<String, List<String>>> entries = new HashMap<>();
        for (String path: casts.keySet()) {
            if (path.contains(".")) {
                final String fieldName = path.substring(0, path.indexOf("."));
                final String tail = path.substring(path.indexOf(".") + 1);
                entries.computeIfPresent(fieldName, (s, map) -> {
                    map.put(tail, casts.get(path));
                    return map;
                });
                entries.computeIfAbsent(fieldName, s -> {
                    Map<String, List<String>> map = new HashMap<>();
                    map.put(tail, casts.get(path));
                    return map;
                });
            } else {
                entries.put(path, Collections.emptyMap());
            }
        }
        return entries;
    }

    private R applySchemaless(R record, Object operatingValue) {
        Headers updatedHeaders = record.headers().duplicate();
        Map<String, Object> value = Requirements.requireMap(operatingValue, "header " + operation);
        Map<String, List<String>> join = mapEntryHeader(fields, headers);
        Map<String, Object> updatedValue = updateValue(join, updatedHeaders, value, new HashMap<>(value));
        return newRecord(record, null, updatedValue, updatedHeaders);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> updateValue(Map<String, List<String>> entries, Headers updatedHeaders, Map<String, Object> value, Map<String, Object> updatedValue) {
        Map<String, Map<String, List<String>>> join = castsEntries(entries);
        for (Map.Entry<String, Map<String, List<String>>> entry : join.entrySet()) {
            Map<String, List<String>> other = entry.getValue();
            if (other.isEmpty()) {
                Object fieldValue = value.get(entry.getKey());
                List<String> strings = entries.get(entry.getKey());
                for (String header : strings) {
                    updatedHeaders.add(header, fieldValue, null);
                }
                if (operation == Operation.MOVE) {
                    updatedValue.remove(entry.getKey());
                }
            } else {
                Map<String, Object> o = new HashMap<>((Map<String, Object>) value.get(entry.getKey()));
                Map<String, Object> o1 = new HashMap<>((Map<String, Object>) updatedValue.get(entry.getKey()));
                updatedValue.put(entry.getKey(), updateValue(other, updatedHeaders, o, o1));
            }
        }
        return updatedValue;
    }

    protected abstract Object operatingValue(R record);
    protected abstract Schema operatingSchema(R record);
    protected abstract R newRecord(R record, Schema updatedSchema, Object updatedValue, Iterable<Header> updatedHeaders);

    public static class Key<R extends ConnectRecord<R>> extends HeaderFrom<R> {

        @Override
        public Object operatingValue(R record) {
            return record.key();
        }

        @Override
        protected Schema operatingSchema(R record) {
            return record.keySchema();
        }

        @Override
        protected R newRecord(R record, Schema updatedSchema, Object updatedValue, Iterable<Header> updatedHeaders) {
            return record.newRecord(record.topic(), record.kafkaPartition(), updatedSchema, updatedValue,
                    record.valueSchema(), record.value(), record.timestamp(), updatedHeaders);
        }
    }

    public static class Value<R extends ConnectRecord<R>> extends HeaderFrom<R> {

        @Override
        public Object operatingValue(R record) {
            return record.value();
        }

        @Override
        protected Schema operatingSchema(R record) {
            return record.valueSchema();
        }

        @Override
        protected R newRecord(R record, Schema updatedSchema, Object updatedValue, Iterable<Header> updatedHeaders) {
            return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(), record.key(),
                    updatedSchema, updatedValue, record.timestamp(), updatedHeaders);
        }
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void close() {

    }

    @Override
    public void configure(Map<String, ?> props) {
        final SimpleConfig config = new SimpleConfig(CONFIG_DEF, props);
        fields = config.getList(FIELDS_FIELD);
        headers = config.getList(HEADERS_FIELD);
        if (headers.size() != fields.size()) {
            throw new ConfigException(format("'%s' config must have the same number of elements as '%s' config.",
                    FIELDS_FIELD, HEADERS_FIELD));
        }
        operation = Operation.fromName(config.getString(OPERATION_FIELD));
    }
}
