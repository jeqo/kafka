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
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.transforms.field.FieldPath;
import org.apache.kafka.connect.transforms.field.FieldPaths;
import org.apache.kafka.connect.transforms.field.FieldSyntaxVersion;
import org.apache.kafka.connect.transforms.field.MapValueUpdater;
import org.apache.kafka.connect.transforms.field.StructSchemaUpdater;
import org.apache.kafka.connect.transforms.field.StructValueUpdater;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import java.util.Date;
import java.util.Map;

import static org.apache.kafka.connect.transforms.util.Requirements.requireMap;
import static org.apache.kafka.connect.transforms.util.Requirements.requireSinkRecord;
import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

public abstract class InsertField<R extends ConnectRecord<R>> implements Transformation<R> {

    public static final String OVERVIEW_DOC =
            "Insert field(s) using attributes from the record metadata or a configured static value."
                    + "<p/>Use the concrete transformation type designed for the record key (<code>" + Key.class.getName() + "</code>) "
                    + "or value (<code>" + Value.class.getName() + "</code>).";

    private interface ConfigName {
        String TOPIC_FIELD = "topic.field";
        String PARTITION_FIELD = "partition.field";
        String OFFSET_FIELD = "offset.field";
        String TIMESTAMP_FIELD = "timestamp.field";
        String STATIC_FIELD = "static.field";
        String STATIC_VALUE = "static.value";
    }

    private static final String OPTIONALITY_DOC = "Suffix with <code>!</code> to make this a required field, or <code>?</code> to keep it optional (the default).";

    public static final ConfigDef CONFIG_DEF = FieldSyntaxVersion.baseConfigDef()
            .define(ConfigName.TOPIC_FIELD, ConfigDef.Type.STRING, null,
                    ConfigDef.Importance.MEDIUM,
                    "Field name for Kafka topic. " + OPTIONALITY_DOC)
            .define(ConfigName.PARTITION_FIELD, ConfigDef.Type.STRING, null,
                    ConfigDef.Importance.MEDIUM,
                    "Field name for Kafka partition. " + OPTIONALITY_DOC)
            .define(ConfigName.OFFSET_FIELD, ConfigDef.Type.STRING, null,
                    ConfigDef.Importance.MEDIUM,
                    "Field name for Kafka offset - only applicable to sink connectors.<br/>"
                            + OPTIONALITY_DOC)
            .define(ConfigName.TIMESTAMP_FIELD, ConfigDef.Type.STRING, null,
                    ConfigDef.Importance.MEDIUM,
                    "Field name for record timestamp. " + OPTIONALITY_DOC)
            .define(ConfigName.STATIC_FIELD, ConfigDef.Type.STRING, null,
                    ConfigDef.Importance.MEDIUM,
                    "Field name for static data field. " + OPTIONALITY_DOC)
            .define(ConfigName.STATIC_VALUE, ConfigDef.Type.STRING, null, ConfigDef.Importance.MEDIUM,
                    "Static field value, if field name configured.");

    private static final String PURPOSE = "field insertion";

    private static final Schema OPTIONAL_TIMESTAMP_SCHEMA = Timestamp.builder().optional().build();

    private static final class InsertionSpec {

        final FieldPath path;
        final boolean optional;

        private InsertionSpec(String name, boolean optional, FieldSyntaxVersion syntaxVersion) {
            this.path = FieldPath.of(name, syntaxVersion);
            this.optional = optional;
        }

        public static InsertionSpec parse(String spec, FieldSyntaxVersion syntaxVersion) {
            if (spec == null) {
                return null;
            }
            if (spec.endsWith("?")) {
                return new InsertionSpec(spec.substring(0, spec.length() - 1), true, syntaxVersion);
            }
            if (spec.endsWith("!")) {
                return new InsertionSpec(spec.substring(0, spec.length() - 1), false,
                        syntaxVersion);
            }
            return new InsertionSpec(spec, true, syntaxVersion);
        }
    }

    private InsertionSpec topicField;
    private InsertionSpec partitionField;
    private InsertionSpec offsetField;
    private InsertionSpec timestampField;
    private InsertionSpec staticField;
    private String staticValue;
    private FieldPaths fieldPaths;

    private Cache<Schema, Schema> schemaUpdateCache;

    @Override
    public void configure(Map<String, ?> props) {
        final SimpleConfig config = new SimpleConfig(CONFIG_DEF, props);
        FieldSyntaxVersion syntaxVersion = FieldSyntaxVersion.fromConfig(config);
        topicField = InsertionSpec.parse(config.getString(ConfigName.TOPIC_FIELD), syntaxVersion);
        partitionField = InsertionSpec.parse(config.getString(ConfigName.PARTITION_FIELD),
                syntaxVersion);
        offsetField = InsertionSpec.parse(config.getString(ConfigName.OFFSET_FIELD), syntaxVersion);
        timestampField = InsertionSpec.parse(config.getString(ConfigName.TIMESTAMP_FIELD),
                syntaxVersion);
        staticField = InsertionSpec.parse(config.getString(ConfigName.STATIC_FIELD), syntaxVersion);
        staticValue = config.getString(ConfigName.STATIC_VALUE);

        if (topicField == null && partitionField == null && offsetField == null
                && timestampField == null && staticField == null) {
            throw new ConfigException("No field insertion configured");
        }

        if (staticField != null && staticValue == null) {
            throw new ConfigException(ConfigName.STATIC_VALUE, null,
                    "No value specified for static field: " + staticField);
        }

        fieldPaths = prepareFieldPaths(config);

        schemaUpdateCache = new SynchronizedCache<>(new LRUCache<>(16));
    }

    private FieldPaths prepareFieldPaths(SimpleConfig config) {
        FieldPaths.Builder builder = FieldPaths.newBuilder(FieldSyntaxVersion.fromConfig(config));
        if (topicField != null) {
            builder.add(topicField.path);
        }
        if (partitionField != null) {
            builder.add(partitionField.path);
        }
        if (offsetField != null) {
            builder.add(offsetField.path);
        }
        if (timestampField != null) {
            builder.add(timestampField.path);
        }
        if (staticField != null) {
            builder.add(staticField.path);
        }
        return builder.build();
    }

    @Override
    public R apply(R record) {
        if (operatingValue(record) == null) {
            return record;
        } else if (operatingSchema(record) == null) {
            return applySchemaless(record);
        } else {
            return applyWithSchema(record);
        }
    }

    private R applySchemaless(R record) {
        final Map<String, Object> value = requireMap(operatingValue(record), PURPOSE);

        final Map<String, Object> updatedValue = fieldPaths.updateValuesFrom(
                value,
                updateMapFields(record),
                insertMapFields(record),
                (originalParent, updatedParent, nullFieldPath, fieldName) ->
                        updatedParent.put(fieldName, originalParent.get(fieldName))
        );

        return newRecord(record, null, updatedValue);
    }

    private MapValueUpdater updateMapFields(R record) {
        return (originalParent, updatedParent, fieldPath, fieldName) -> {
            // TODO check new configs to avoid override
            if (topicField != null && topicField.path.equals(fieldPath)) {
                updatedParent.put(fieldName, record.topic());
            }
            if (partitionField != null && partitionField.path.equals(fieldPath)) {
                updatedParent.put(fieldName, record.kafkaPartition());
            }
            if (offsetField != null && offsetField.path.equals(fieldPath)) {
                updatedParent.put(fieldName, requireSinkRecord(record, PURPOSE).kafkaOffset());
            }
            if (timestampField != null && timestampField.path.equals(fieldPath)) {
                updatedParent.put(fieldName, record.timestamp());
            }
            if (staticField != null && staticValue != null && staticField.path.equals(fieldPath)) {
                updatedParent.put(fieldName, staticValue);
            }
        };
    }

    private MapValueUpdater insertMapFields(R record) {
        return (originalParent, updatedParent, fieldPath, fieldName) -> {
            if (topicField != null && topicField.path.equals(fieldPath)) {
                updatedParent.put(fieldName, record.topic());
            }
            if (partitionField != null && partitionField.path.equals(fieldPath)) {
                updatedParent.put(fieldName, record.kafkaPartition());
            }
            if (offsetField != null && offsetField.path.equals(fieldPath)) {
                updatedParent.put(fieldName, requireSinkRecord(record, PURPOSE).kafkaOffset());
            }
            if (timestampField != null && timestampField.path.equals(fieldPath)) {
                updatedParent.put(fieldName, record.timestamp());
            }
            if (staticField != null && staticValue != null && staticField.path.equals(fieldPath)) {
                updatedParent.put(fieldName, staticValue);
            }
        };
    }

    private R applyWithSchema(R record) {
        final Struct value = requireStruct(operatingValue(record), PURPOSE);

        Schema updatedSchema = schemaUpdateCache.get(value.schema());
        if (updatedSchema == null) {
            updatedSchema = makeUpdatedSchema(value.schema());
            schemaUpdateCache.put(value.schema(), updatedSchema);
        }

        final Struct updatedValue = fieldPaths.updateValuesFrom(value.schema(), value, updatedSchema,
                // matching, then update
                updateStructFields(record),
                // not found, then insert
                insertStructFields(record),
                // others, then keep
                (originalParent, originalField, updatedParent, nullUpdatedField, nullFieldPath) ->
                        updatedParent.put(originalField.name(), originalParent.get(originalField)));

        return newRecord(record, updatedSchema, updatedValue);
    }

    private StructValueUpdater updateStructFields(R record) {
        return (originalParent, nullOriginalField, updatedParent, updatedField, fieldPath) -> {
            updateStructMetaFields(record, updatedParent, updatedField, fieldPath); // divided to pass NPath check
            if (staticField != null && staticValue != null && staticField.path.equals(fieldPath)) {
                updatedParent.put(updatedField, staticValue);
            }
        };
    }

    private void updateStructMetaFields(R record, Struct updatedParent, Field updatedField,
            FieldPath fieldPath) {
        if (topicField != null && topicField.path.equals(fieldPath)) {
            updatedParent.put(updatedField, record.topic());
        }
        if (partitionField != null && partitionField.path.equals(fieldPath)) {
            if (record.kafkaPartition() != null) {
                updatedParent.put(updatedField, record.kafkaPartition());
            }
        }
        if (offsetField != null && offsetField.path.equals(fieldPath)) {
            updatedParent.put(updatedField, requireSinkRecord(record, PURPOSE).kafkaOffset());
        }
        if (timestampField != null && timestampField.path.equals(fieldPath)) {
            if (record.timestamp() != null) {
                updatedParent.put(updatedField, new Date(record.timestamp()));
            }
        }
    }

    private StructValueUpdater insertStructFields(R record) {
        return (originalParent, nullOriginalField, updatedParent, updatedField, fieldPath) -> {
            insertStructMetaFields(record, updatedParent, updatedField, fieldPath); // divided to pass NPath check
            if (staticField != null && staticValue != null && staticField.path.equals(fieldPath)) {
                updatedParent.put(updatedField, staticValue);
            }
        };
    }

    private void insertStructMetaFields(R record, Struct updatedParent, Field updatedField,
            FieldPath fieldPath) {
        if (topicField != null && topicField.path.equals(fieldPath)) {
            updatedParent.put(updatedField, record.topic());
        }
        if (partitionField != null && partitionField.path.equals(fieldPath) && record.kafkaPartition() != null) {
            updatedParent.put(updatedField, record.kafkaPartition());
        }
        if (offsetField != null && offsetField.path.equals(fieldPath)) {
            updatedParent.put(updatedField, requireSinkRecord(record, PURPOSE).kafkaOffset());
        }
        if (timestampField != null && timestampField.path.equals(fieldPath) && record.timestamp() != null) {
            updatedParent.put(updatedField, new Date(record.timestamp()));
        }
    }

    private Schema makeUpdatedSchema(Schema schema) {
        return fieldPaths.updateSchemaFrom(
                schema,
                // if matching
                updateSchemaFields(),
                // if match not found
                updateSchemaFields(),
                // other fields
                (schemaBuilder, field, nullFieldPath) -> schemaBuilder.field(field.name(), field.schema()));
    }

    private StructSchemaUpdater updateSchemaFields() {
        return (builder, nullField, fieldPath) -> {
            if (topicField != null && topicField.path.equals(fieldPath)) {
                builder.field(
                        fieldPath.last(),
                        topicField.optional ? Schema.OPTIONAL_STRING_SCHEMA : Schema.STRING_SCHEMA);
            }
            if (partitionField != null && partitionField.path.equals(fieldPath)) {
                builder.field(
                        fieldPath.last(),
                        partitionField.optional ? Schema.OPTIONAL_INT32_SCHEMA : Schema.INT32_SCHEMA);
            }
            if (offsetField != null && offsetField.path.equals(fieldPath)) {
                builder.field(
                        fieldPath.last(),
                        offsetField.optional ? Schema.OPTIONAL_INT64_SCHEMA : Schema.INT64_SCHEMA);
            }
            if (timestampField != null && timestampField.path.equals(fieldPath)) {
                builder.field(
                        fieldPath.last(),
                        timestampField.optional ? OPTIONAL_TIMESTAMP_SCHEMA : Timestamp.SCHEMA);
            }
            if (staticField != null && staticField.path.equals(fieldPath)) {
                builder.field(
                        fieldPath.last(),
                        staticField.optional ? Schema.OPTIONAL_STRING_SCHEMA : Schema.STRING_SCHEMA);
            }
        };
    }

    @Override
    public void close() {
        schemaUpdateCache = null;
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    protected abstract Schema operatingSchema(R record);

    protected abstract Object operatingValue(R record);

    protected abstract R newRecord(R record, Schema updatedSchema, Object updatedValue);

    public static class Key<R extends ConnectRecord<R>> extends InsertField<R> {

        @Override
        protected Schema operatingSchema(R record) {
            return record.keySchema();
        }

        @Override
        protected Object operatingValue(R record) {
            return record.key();
        }

        @Override
        protected R newRecord(R record, Schema updatedSchema, Object updatedValue) {
            return record.newRecord(record.topic(), record.kafkaPartition(), updatedSchema, updatedValue, record.valueSchema(), record.value(), record.timestamp());
        }

    }

    public static class Value<R extends ConnectRecord<R>> extends InsertField<R> {

        @Override
        protected Schema operatingSchema(R record) {
            return record.valueSchema();
        }

        @Override
        protected Object operatingValue(R record) {
            return record.value();
        }

        @Override
        protected R newRecord(R record, Schema updatedSchema, Object updatedValue) {
            return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(), record.key(), updatedSchema, updatedValue, record.timestamp());
        }
    }
}
