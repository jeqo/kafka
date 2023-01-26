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
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.utils.ConfigUtils;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.field.FieldSyntaxVersion;
import org.apache.kafka.connect.transforms.field.MultiFieldPaths;
import org.apache.kafka.connect.transforms.field.SingleFieldPath;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.kafka.connect.transforms.util.Requirements.requireMap;
import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

public abstract class ReplaceField<R extends ConnectRecord<R>> implements Transformation<R> {

    public static final String OVERVIEW_DOC = "Filter or rename fields."
            + "<p/>Use the concrete transformation type designed for the record key (<code>" + Key.class.getName() + "</code>) "
            + "or value (<code>" + Value.class.getName() + "</code>).";

    interface ConfigName {
        String EXCLUDE = "exclude";
        String INCLUDE = "include";

        // for backwards compatibility
        String INCLUDE_ALIAS = "whitelist";
        String EXCLUDE_ALIAS = "blacklist";

        String RENAME = "renames";
    }

    public static final ConfigDef CONFIG_DEF = FieldSyntaxVersion.baseConfigDef()
            .define(
                    ConfigName.EXCLUDE,
                    ConfigDef.Type.LIST,
                    Collections.emptyList(),
                    ConfigDef.Importance.MEDIUM,
                    "Fields to exclude. This takes precedence over the fields to include.")
            .define("blacklist",
                    ConfigDef.Type.LIST,
                    null,
                    Importance.LOW,
                    "Deprecated. Use " + ConfigName.EXCLUDE + " instead.")
            .define(ConfigName.INCLUDE,
                    ConfigDef.Type.LIST,
                    Collections.emptyList(),
                    ConfigDef.Importance.MEDIUM,
                    "Fields to include. If specified, only these fields will be used.")
            .define("whitelist",
                    ConfigDef.Type.LIST,
                    null,
                    Importance.LOW,
                    "Deprecated. Use " + ConfigName.INCLUDE + " instead.")
            .define(ConfigName.RENAME, ConfigDef.Type.LIST, Collections.emptyList(), new ConfigDef.Validator() {
                @SuppressWarnings("unchecked")
                @Override
                public void ensureValid(String name, Object value) {
                    parseRenameMappings((List<String>) value, FieldSyntaxVersion.V1);
                }

                @Override
                public String toString() {
                    return "list of colon-delimited pairs, e.g. <code>foo:bar,abc:xyz</code>";
                }
            }, ConfigDef.Importance.MEDIUM, "Field rename mappings.");

    private static final String PURPOSE = "field replacement";

    private MultiFieldPaths fields;
    private List<SingleFieldPath> exclude;
    private List<SingleFieldPath> include;
    private Map<SingleFieldPath, String> renames;
    private Map<String, SingleFieldPath> reverseRenames;
    private Cache<Schema, Schema> schemaUpdateCache;

    @Override
    public void configure(Map<String, ?> configs) {
        final SimpleConfig config = new SimpleConfig(CONFIG_DEF,
                ConfigUtils.translateDeprecatedConfigs(configs, new String[][] {
                        {ConfigName.INCLUDE, "whitelist"},
                        {ConfigName.EXCLUDE, "blacklist"},
                }));

        FieldSyntaxVersion syntaxVersion = FieldSyntaxVersion.fromConfig(config);
        exclude = config.getList(ConfigName.EXCLUDE).stream()
                .map(f -> SingleFieldPath.of(f, syntaxVersion))
                .collect(Collectors.toList());
        List<SingleFieldPath> paths = new ArrayList<>(exclude);
        include = config.getList(ConfigName.INCLUDE).stream()
                .map(f -> SingleFieldPath.of(f, syntaxVersion))
                .collect(Collectors.toList());
        paths.addAll(include);
        renames = parseRenameMappings(config.getList(ConfigName.RENAME), syntaxVersion);
        paths.addAll(renames.keySet());
        reverseRenames = invert(renames);
//        renamed = new ArrayList<>(renames.size());
//        for (Map.Entry<FieldPath, String> r : renames.entrySet()) {
//            paths.add(r.getKey());
//            final FieldPath renamed = r.getKey().renameLast(r.getValue());
//            paths.add(renamed);
//            this.renamed.add(renamed);
//        }

        fields = MultiFieldPaths.of(paths);

        schemaUpdateCache = new SynchronizedCache<>(new LRUCache<>(16));
    }

    static Map<SingleFieldPath, String> parseRenameMappings(
            List<String> mappings,
            FieldSyntaxVersion syntaxVersion
    ) {
        final Map<SingleFieldPath, String> m = new HashMap<>();
        for (String mapping : mappings) {
            final String[] parts = mapping.split(":");
            if (parts.length != 2) {
                throw new ConfigException(ConfigName.RENAME, mappings,
                        "Invalid rename mapping: " + mapping);
            }
            m.put(SingleFieldPath.of(parts[0], syntaxVersion), parts[1]);
        }
        return m;
    }

    static Map<String, SingleFieldPath> invert(Map<SingleFieldPath, String> source) {
        final Map<String, SingleFieldPath> m = new HashMap<>();
        for (Map.Entry<SingleFieldPath, String> e : source.entrySet()) {
            m.put(e.getValue(), e.getKey());
        }
        return m;
    }

    boolean filter(SingleFieldPath fieldName) {
        return !exclude.contains(fieldName) && (include.isEmpty() || include.contains(fieldName));
    }

    String renamed(SingleFieldPath fieldPath, String defaultName) {
        final String mapping = renames.get(fieldPath);
        return mapping == null ? defaultName : mapping;
    }

    SingleFieldPath reverseRenamed(String fieldName, SingleFieldPath defaultPath) {
        final SingleFieldPath mapping = reverseRenames.get(fieldName);
        return mapping == null ? defaultPath : mapping;
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

        final Map<String, Object> updated = fields.updateValueFrom(
                value,
                (originalParent, updatedValue, fieldPath, fieldName) -> {
                    if (filter(fieldPath)) {
                        updatedValue.put(renamed(fieldPath, fieldName), originalParent.get(fieldName));
                    }
                },
                (originalParent, updatedValue, nullFieldPath, fieldName) -> {
                    if (include.isEmpty()) {
                        updatedValue.put(fieldName, originalParent.get(fieldName));
                    }
                });
        return newRecord(record, null, updated);
    }

    private R applyWithSchema(R record) {
        final Struct value = requireStruct(operatingValue(record), PURPOSE);

        Schema updatedSchema = schemaUpdateCache.get(value.schema());
        if (updatedSchema == null) {
            updatedSchema = makeUpdatedSchema(value.schema());
            schemaUpdateCache.put(value.schema(), updatedSchema);
        }

        final Struct updatedValue = fields.updateValueFrom(value.schema(), value, updatedSchema,
                (originalParent, originalField, updatedParent, updatedField, fieldPath) -> {
                    if (filter(fieldPath)) {
                        updatedParent.put(renamed(fieldPath, originalField.name()), originalParent.get(originalField));
                    }
                },
                (originalParent, originalField, updatedParent, nullUpdatedField, nullFieldPath) -> {
                    if (include.isEmpty()) {
                        updatedParent.put(originalField, originalParent.get(originalField));
                    }
                });

        return newRecord(record, updatedSchema, updatedValue);
    }

    private Schema makeUpdatedSchema(Schema schema) {
        return fields.updateSchemaFrom(
                schema,
                (schemaBuilder, field, fieldPath) -> {
                    if (filter(fieldPath)) {
                        schemaBuilder.field(renamed(fieldPath, field.name()), field.schema());
                    }
                },
                (schemaBuilder, field, nullFieldPath) -> {
                    if (include.isEmpty()) {
                        schemaBuilder.field(field.name(), field.schema());
                    }
                });
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void close() {
        schemaUpdateCache = null;
    }

    protected abstract Schema operatingSchema(R record);

    protected abstract Object operatingValue(R record);

    protected abstract R newRecord(R record, Schema updatedSchema, Object updatedValue);

    public static class Key<R extends ConnectRecord<R>> extends ReplaceField<R> {

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

    public static class Value<R extends ConnectRecord<R>> extends ReplaceField<R> {

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
