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
package org.apache.kafka.connect.transforms.field;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Schema.Type;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.util.SchemaUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Multiple field paths to access record structures ({@code Struct} or {@code Map} efficiently,
 * instead of using single {@code FieldPath} individually.
 * <br/>
 * Invariants:
 * <li>
 *     <ul>Tree nodes contain either a nested tree or a field path</ul>
 *     <ul>A tree cannot contain paths that are a subset of other paths (e.g. foo and foo.bar in V2 should collide and fail)</ul>
 * </li>
 */
public class FieldPaths {

    final Map<String, Object> pathTree;
    final List<FieldPath> paths;

    FieldPaths(List<FieldPath> paths) {
        this.paths = paths;
        pathTree = buildTree(paths, 0, new HashMap<>());
    }

    public static FieldPaths of(FieldPath path) {
        return new FieldPaths(Collections.singletonList(path));
    }

    public static FieldPaths of(FieldPath... paths) {
        return new FieldPaths(Arrays.asList(paths));
    }

    public static FieldPaths of(Set<String> fields, FieldSyntaxVersion syntaxVersion) {
        return new FieldPaths(fields.stream()
            .map(f -> FieldPath.of(f, syntaxVersion))
            .collect(Collectors.toList()));
    }

    public static FieldPaths of(List<String> fields, FieldSyntaxVersion syntaxVersion) {
        return new FieldPaths(fields.stream()
            .map(f -> FieldPath.of(f, syntaxVersion))
            .collect(Collectors.toList()));
    }

    Map<String, Object> buildTree(List<FieldPath> paths, int step, Map<String, Object> tree) {
        if (paths.size() == 1) { // optimize for paths with a single member
            FieldPath path = paths.get(0);
            if (path.at(step + 1) == null) { // if last path step
                tree.put(path.at(step), path);
            } else {
                tree.put(path.at(step), buildTree(paths, step + 1, new HashMap<>()));
            }
        } else {
            // group paths by prefix
            final Map<String, List<FieldPath>> groups = new HashMap<>();
            for (FieldPath path : paths) {
                String pathStep = path.at(step);
                if (pathStep != null) {
                    groups.computeIfPresent(pathStep, (s, fieldPaths) -> {
                        for (FieldPath other : fieldPaths) {
                            if (!path.equals(other) && (other.at(step + 1) == null || path.at(step + 1) == null)) {
                                throw new IllegalArgumentException(
                                    "Path " + other + " and " + path + " are overlapping. "
                                        + "Paths need to point to leaf values");
                            }
                        }
                        if (!fieldPaths.contains(path)) fieldPaths.add(path);
                        return fieldPaths;
                    });
                    groups.computeIfAbsent(pathStep, s -> {
                        List<FieldPath> fieldPaths = new ArrayList<>();
                        fieldPaths.add(path);
                        return fieldPaths;
                    });
                }
            }

            // create tree from grouped paths
            for (Map.Entry<String, List<FieldPath>> entry : groups.entrySet()) {
                if (entry.getValue().size() == 1) {
                    final FieldPath path = entry.getValue().get(0);
                    if (path.at(step + 1) == null) { // if last path step
                        tree.put(entry.getKey(), path);
                    } else {
                        tree.put(entry.getKey(),
                            buildTree(entry.getValue(), step + 1, new HashMap<>()));
                    }
                } else {
                    tree.put(entry.getKey(), buildTree(entry.getValue(), step + 1, new HashMap<>()));
                }
            }
        }
        return tree;
    }

    public Map<FieldPath, StructFieldAndValue> fieldAndValuesFrom(Struct struct) {
        final Map<FieldPath, StructFieldAndValue> map = findFieldAndValues(struct, pathTree, new HashMap<>());
        for (FieldPath path : paths) {
            if (!map.containsKey(path)) {
                map.put(path, null);
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<FieldPath, StructFieldAndValue> findFieldAndValues(Struct struct, Map<String, Object> tree, Map<FieldPath, StructFieldAndValue> map) {
        for (Map.Entry<String, Object> step : tree.entrySet()) {
            Field field = struct.schema().field(step.getKey());
            if (step.getValue() instanceof FieldPath) {
                map.put((FieldPath) step.getValue(), field != null ? new StructFieldAndValue(field, struct.get(field)) : null);
            } else {
                if (field.schema().type() == Type.STRUCT) { // what if we don't get to the leaf? how to nullify a path not found
                    findFieldAndValues(struct.getStruct(field.name()), (Map<String, Object>) step.getValue(), map);
                }
            }
        }
        return map;
    }

    public Map<FieldPath, MapFieldAndValue> fieldAndValuesFrom(Map<String, Object> value) {
        final Map<FieldPath, MapFieldAndValue> map = findFieldAndValues(value, pathTree, new HashMap<>());
        for (FieldPath path : paths) {
            if (!map.containsKey(path)) {
                map.put(path, null);
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<FieldPath, MapFieldAndValue> findFieldAndValues(Map<String, Object> value, Map<String, Object> tree, Map<FieldPath, MapFieldAndValue> map) {
        for (Map.Entry<String, Object> step : tree.entrySet()) {
            Object fieldValue = value.get(step.getKey());
            if (step.getValue() instanceof FieldPath) {
                map.put((FieldPath) step.getValue(), new MapFieldAndValue(step.getKey(), fieldValue));
            } else {
                if (fieldValue instanceof Map) { // what if we don't get to the leaf? how to nullify a path not found
                    findFieldAndValues((Map<String, Object>) fieldValue, (Map<String, Object>) step.getValue(), map);
                }
            }
        }
        return map;
    }

    public Map<String, Object> updateValuesAt(Map<String, Object> value, MapValueUpdater updater) {
        return updateValues(value, pathTree, updater);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> updateValues(Map<String, Object> value, Map<String, Object> tree, MapValueUpdater updater) {
        Map<String, Object> updated = new HashMap<>(value);
        for (Map.Entry<String, Object> entry : tree.entrySet()) {
            final String fieldName = entry.getKey();
            if (value.containsKey(fieldName)) {
                if (entry.getValue() instanceof FieldPath) {
                    updater.apply(updated, fieldName, entry.getValue());
                } else {
                    if (value.get(fieldName) instanceof Map) {
                        updated.put(
                            fieldName,
                            updateValues(
                                (Map<String, Object>) updated.get(fieldName),
                                (Map<String, Object>) entry.getValue(),
                                updater));
                    }
                }
            }
        }
        return updated;
    }

    public Struct updateValuesAt(Schema schema, Struct value, Schema updatedSchema, StructValueUpdater change) {
        return updateValues(schema, value, updatedSchema,  pathTree, change);
    }

    @SuppressWarnings("unchecked")
    private Struct updateValues(Schema schema, Struct value, Schema updateSchema, Map<String, Object> tree, StructValueUpdater change) {
        Struct updated = new Struct(updateSchema);
        for (Field field : updateSchema.fields()) {
            if (!tree.isEmpty()) {
                if (tree.containsKey(field.name())) {
                    if (tree.get(field.name()) instanceof FieldPath) {
                        change.apply(schema.field(field.name()), updateSchema.field(field.name()), updated, value.get(field.name()));
                    } else {
                        if (field.schema().type() == Type.STRUCT) {
                            updated.put(
                                field,
                                updateValues(field.schema(), value.getStruct(field.name()),
                                    updateSchema.field(field.name()).schema(),
                                    (Map<String, Object>) tree.get(field.name()),
                                    change));
                        }
                    }
                } else {
                    updated.put(field, value.get(field.name()));
                }
            } else {
                updated.put(field, value.get(field.name()));
            }
        }
        return updated;
    }

    public Schema updateSchemaAt(Schema schema, BiConsumer<SchemaBuilder, Field> change) {
        SchemaBuilder updated = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());
        return updateSchema(schema, updated, pathTree, change);
    }

    @SuppressWarnings("unchecked")
    private Schema updateSchema(Schema operatingSchema, SchemaBuilder builder, Map<String, Object> tree, BiConsumer<SchemaBuilder, Field> change) {
        if (operatingSchema.isOptional()) {
            builder.optional();
        }
        for (Field field : operatingSchema.fields()) {
            if (!tree.isEmpty()) {
                if (!tree.containsKey(field.name())) {
                    builder.field(field.name(), field.schema());
                } else {
                    if (tree.get(field.name()) instanceof FieldPath) {
                        change.accept(builder, field);
                    } else {
                        if (field.schema().type() == Type.STRUCT) {
                            builder.field(
                                field.name(),
                                updateSchema(
                                    field.schema(),
                                    SchemaBuilder.struct(),
                                    (Map<String, Object>) tree.get(field.name()),
                                    change));
                        } else {
                            builder.field(field.name(), field.schema());
                        }
                    }
                }
            } else {
                builder.field(field.name(), field.schema());
            }
        }
        return builder.build();
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", FieldPaths.class.getSimpleName() + "[", "]")
            .add("pathTree=" + pathTree)
            .toString();
    }
}
