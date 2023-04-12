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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Multiple field paths to access data objects ({@code Struct} or {@code Map}) efficiently,
 * instead of using {@see SingleFieldPath} individually.
 * <p>
 * If the SMT requires accessing a single field on the same data object,
 * use {@code FieldPath} instead.
 * <p>
 * Invariants:
 * <li>
 *     <ul>Tree values contain either a nested tree or a field path</ul>
 *     <ul>A tree cannot contain paths that are a subset of other paths (e.g. foo and foo.bar in V2 should collide and fail)</ul>
 * </li>
 *
 * See KIP-821.
 *
 * @see SingleFieldPath
 * @see FieldSyntaxVersion
 */
public class MultiFieldPaths implements FieldPath {

    final Map<String, Object> pathTree;
    final List<SingleFieldPath> paths;

    MultiFieldPaths(List<SingleFieldPath> paths) {
        this.paths = paths.stream().filter(Objects::nonNull).collect(Collectors.toList());
        pathTree = buildPathTree(this.paths, 0, new HashMap<>());
    }

    public static MultiFieldPaths of(SingleFieldPath path) {
        return new MultiFieldPaths(Collections.singletonList(path));
    }

    public static MultiFieldPaths of(SingleFieldPath... paths) {
        return new MultiFieldPaths(Arrays.asList(paths));
    }

    public static MultiFieldPaths of(List<SingleFieldPath> paths) {
        return new MultiFieldPaths(paths);
    }

    public static MultiFieldPaths of(Set<String> fields, FieldSyntaxVersion syntaxVersion) {
        return new MultiFieldPaths(fields.stream()
                .map(f -> SingleFieldPath.of(f, syntaxVersion))
                .collect(Collectors.toList()));
    }

    public static MultiFieldPaths of(List<String> fields, FieldSyntaxVersion syntaxVersion) {
        return new MultiFieldPaths(fields.stream()
                .map(f -> SingleFieldPath.of(f, syntaxVersion))
                .collect(Collectors.toList()));
    }

    Map<String, Object> buildPathTree(List<SingleFieldPath> paths, int stepIdx, Map<String, Object> pathTree) {
        if (paths.size() == 1) { // optimize for paths with a single member
            SingleFieldPath path = paths.get(0);
            if (path != null) {
                if (path.stepAt(stepIdx + 1) == null) { // if last path step
                    pathTree.put(path.stepAt(stepIdx), path);
                } else {
                    pathTree.put(path.stepAt(stepIdx),
                            buildPathTree(paths, stepIdx + 1, new HashMap<>()));
                }
            }
        } else {
            // group paths by prefix
            final Map<String, List<SingleFieldPath>> groups = new HashMap<>();
            for (SingleFieldPath path : paths) {
                if (path != null) {
                    String step = path.stepAt(stepIdx);
                    if (step != null) {
                        groups.computeIfPresent(step, (s, fieldPaths) -> {
                            for (SingleFieldPath other : fieldPaths) {
                                // avoid overlapping paths
                                if (!path.equals(other)
                                        && (other.stepAt(stepIdx + 1) == null
                                        || path.stepAt(stepIdx + 1) == null)) {
                                    throw new IllegalArgumentException(
                                            "Path " + other + " and " + path + " are overlapping. "
                                                    + "Paths need to point to leaf values");
                                }
                            }
                            if (!fieldPaths.contains(path)) {
                                fieldPaths.add(path);
                            }
                            return fieldPaths;
                        });
                        groups.computeIfAbsent(step, s -> {
                            List<SingleFieldPath> fieldPaths = new ArrayList<>();
                            fieldPaths.add(path);
                            return fieldPaths;
                        });
                    }
                }
            }

            // create tree from grouped paths
            for (Map.Entry<String, List<SingleFieldPath>> entry : groups.entrySet()) {
                if (entry.getValue().size() == 1) {
                    final SingleFieldPath path = entry.getValue().get(0);
                    if (path.stepAt(stepIdx + 1) == null) { // if it is the last path step
                        pathTree.put(entry.getKey(), path);
                    } else {
                        pathTree.put(entry.getKey(),
                                buildPathTree(entry.getValue(), stepIdx + 1, new HashMap<>()));
                    }
                } else {
                    pathTree.put(entry.getKey(),
                            buildPathTree(entry.getValue(), stepIdx + 1, new HashMap<>()));
                }
            }
        }
        return pathTree;
    }

    /**
     * Find values at the field paths on the tree.
     * @param struct data value
     * @return map of field paths and field/values
     */
    public Map<SingleFieldPath, Map.Entry<Field, Object>> fieldAndValuesFrom(Struct struct) {
        return findFieldAndValues(struct, pathTree, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private Map<SingleFieldPath, Map.Entry<Field, Object>> findFieldAndValues(
            Struct originalValue,
            Map<String, Object> treeAt,
            Map<SingleFieldPath, Map.Entry<Field, Object>> fieldAndValueMap
    ) {
        for (Map.Entry<String, Object> step : treeAt.entrySet()) {
            Field field = originalValue.schema().field(step.getKey());
            if (step.getValue() instanceof SingleFieldPath) {
                Map.Entry<Field, Object> fieldAndValue =
                        field != null
                                ? new AbstractMap.SimpleImmutableEntry<>(field, originalValue.get(field))
                                : null;
                fieldAndValueMap.put((SingleFieldPath) step.getValue(), fieldAndValue);
            } else {
                if (field.schema().type() == Type.STRUCT) {
                    findFieldAndValues(
                            originalValue.getStruct(field.name()),
                            (Map<String, Object>) step.getValue(),
                            fieldAndValueMap
                    );
                }
            }
        }
        return fieldAndValueMap;
    }

    /**
     * Find values at the field paths on the tree.
     * @param value data value
     * @return map of field paths and field/values
     */
    public Map<SingleFieldPath, MapFieldAndValue> fieldAndValuesFrom(Map<String, Object> value) {
        return findFieldAndValues(value, pathTree, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private Map<SingleFieldPath, MapFieldAndValue> findFieldAndValues(
            Map<String, Object> value,
            Map<String, Object> treeAt,
            Map<SingleFieldPath, MapFieldAndValue> fieldAndValueMap
    ) {
        for (Map.Entry<String, Object> step : treeAt.entrySet()) {
            Object fieldValue = value.get(step.getKey());
            if (step.getValue() instanceof SingleFieldPath) {
                fieldAndValueMap.put((
                                SingleFieldPath) step.getValue(),
                        new MapFieldAndValue(step.getKey(), fieldValue)
                );
            } else {
                if (fieldValue instanceof Map) {
                    findFieldAndValues(
                            (Map<String, Object>) fieldValue,
                            (Map<String, Object>) step.getValue(),
                            fieldAndValueMap
                    );
                }
            }
        }
        return fieldAndValueMap;
    }

    @Override
    public Map<String, Object> updateValueFrom(
            Map<String, Object> originalValue,
            MapValueUpdater whenFound
    ) {
        return updateValues(originalValue, pathTree, whenFound,
                (originalParent, updatedParent, fieldPath, fieldName) -> {
                    // filter out
                },
                (originalParent, updatedParent, fieldPath, fieldName) ->
                    updatedParent.put(fieldName, originalParent.get(fieldName)));
    }

    @Override
    public Map<String, Object> updateValueFrom(
            Map<String, Object> originalValue,
            MapValueUpdater whenFound,
            MapValueUpdater whenNotFound
    ) {
        return updateValues(originalValue, pathTree, whenFound,
                (originalParent, updatedParent, fieldPath, fieldName) -> {
                    // filter out
                },
                whenNotFound);
    }

    @Override
    public Map<String, Object> updateValueFrom(
            Map<String, Object> originalValue,
            MapValueUpdater whenFound,
            MapValueUpdater whenNotFound,
            MapValueUpdater toOthers
    ) {
        return updateValues(originalValue, pathTree, whenFound, whenNotFound, toOthers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> updateValues(
            Map<String, Object> originalValue,
            Map<String, Object> treeAt,
            MapValueUpdater matching,
            MapValueUpdater notFound,
            MapValueUpdater others
    ) {
        if (originalValue == null) return null;
        Map<String, Object> updatedValue = new HashMap<>(originalValue.size());
        Map<String, Object> notFoundFields = new HashMap<>(treeAt);
        for (Map.Entry<String, Object> entry : originalValue.entrySet()) {
            String fieldName = entry.getKey();
            Object fieldValue = entry.getValue();
            if (!treeAt.isEmpty()) {
                if (treeAt.containsKey(fieldName)) {
                    notFoundFields.remove(fieldName);
                    Object treeValue = treeAt.get(fieldName);
                    if (treeValue instanceof SingleFieldPath) {
                        matching.apply(originalValue, updatedValue, (SingleFieldPath) treeValue, fieldName);
                    } else {
                        if (fieldValue instanceof Map) {
                            Map<String, Object> updatedField = updateValues(
                                    (Map<String, Object>) fieldValue,
                                    (Map<String, Object>) treeValue,
                                    matching, notFound, others);
                            updatedValue.put(fieldName, updatedField);
                        } else {
                            // add back to not found and apply others, as only leaf values are updated
                            notFoundFields.put(fieldName, treeValue);
                            others.apply(originalValue, updatedValue, null, fieldName);
                        }
                    }
                } else {
                    others.apply(originalValue, updatedValue, null, fieldName);
                }
            } else {
                others.apply(originalValue, updatedValue, null, fieldName);
            }
        }
        for (Map.Entry<String, Object> entry : notFoundFields.entrySet()) {
            String fieldName = entry.getKey();
            Object treeValue = entry.getValue();
            if (treeValue instanceof SingleFieldPath) {
                notFound.apply(originalValue, updatedValue, (SingleFieldPath) treeValue, fieldName);
            } else {
                Map<String, Object> updatedField = updateValues(
                        new HashMap<>(),
                        (Map<String, Object>) treeValue,
                        matching, notFound, others);
                updatedValue.put(fieldName, updatedField);
            }
        }

        return updatedValue;
    }

    @Override
    public Struct updateValueFrom(
            Schema originalSchema,
            Struct originalValue,
            Schema updatedSchema,
            StructValueUpdater whenFound
    ) {
        return updateValues(originalSchema, originalValue, updatedSchema, pathTree, whenFound,
                (originalParent, originalField, updatedParent, updatedField, fieldPath) -> {
                    // filter out
                },
                (originalParent, originalField, updatedParent, nullUpdatedField, nullFieldPath) ->
                        updatedParent.put(originalField.name(), originalParent.get(originalField)));
    }

    @Override
    public Struct updateValueFrom(
            Schema originalSchema,
            Struct originalValue,
            Schema updatedSchema,
            StructValueUpdater whenFound,
            StructValueUpdater whenNotFound,
            StructValueUpdater toOthers
    ) {
        return updateValues(originalSchema, originalValue, updatedSchema, pathTree,
                whenFound, whenNotFound, toOthers);
    }

    @Override
    public Struct updateValueFrom(
            Schema originalSchema,
            Struct originalValue,
            Schema updatedSchema,
            StructValueUpdater whenFound,
            StructValueUpdater whenNotFound
    ) {
        return updateValues(originalSchema, originalValue, updatedSchema, pathTree, whenFound,
                (originalParent, originalField, updatedParent, updatedField, fieldPath) -> {
                    // filter out
                },
                whenNotFound);
    }

    @SuppressWarnings("unchecked")
    private Struct updateValues(
            Schema originalSchema,
            Struct originalValue,
            Schema updateSchema,
            Map<String, Object> treeAt,
            StructValueUpdater matching,
            StructValueUpdater notFound,
            StructValueUpdater others
    ) {
        Struct updatedValue = new Struct(updateSchema);
        Map<String, Object> notFoundFields = new HashMap<>(treeAt);
        for (Field field : originalSchema.fields()) {
            if (!treeAt.isEmpty()) {
                if (treeAt.containsKey(field.name())) {
                    notFoundFields.remove(field.name());
                    final Object treeValue = treeAt.get(field.name());
                    if (treeValue instanceof SingleFieldPath) {
                        matching.apply(
                                originalValue,
                                originalSchema.field(field.name()),
                                updatedValue,
                                updateSchema.field(field.name()),
                                (SingleFieldPath) treeValue
                        );
                    } else {
                        if (field.schema().type() == Type.STRUCT) {
                            Struct fieldValue = updateValues(
                                    field.schema(),
                                    originalValue.getStruct(field.name()),
                                    updateSchema.field(field.name()).schema(),
                                    (Map<String, Object>) treeValue,
                                    matching, notFound, others
                            );
                            updatedValue.put(updateSchema.field(field.name()), fieldValue);
                        } else {
                            // add back to not found and apply others, as only leaf values are updated
                            notFoundFields.put(field.name(), treeValue);
                            others.apply(originalValue, field, updatedValue, null, null);
                        }
                    }
                } else {
                    others.apply(originalValue, field, updatedValue, null, null);
                }
            } else {
                others.apply(originalValue, field, updatedValue, null, null);
            }
        }
        for (Map.Entry<String, Object> entry : notFoundFields.entrySet()) {
            String fieldName = entry.getKey();
            Object treeValue = entry.getValue();
            if (treeValue instanceof SingleFieldPath) {
                notFound.apply(
                        originalValue,
                        null,
                        updatedValue,
                        updateSchema.field(fieldName),
                        (SingleFieldPath) treeValue
                );
            } else {
                Struct fieldValue = updateValues(
                        SchemaBuilder.struct().build(),
                        null,
                        updateSchema.field(fieldName).schema(),
                        (Map<String, Object>) treeValue,
                        matching, notFound, others
                );
                updatedValue.put(updateSchema.field(fieldName), fieldValue);
            }
        }
        return updatedValue;
    }

    @Override
    public Schema updateSchemaFrom(
            Schema originalSchema,
            StructSchemaUpdater whenFound
    ) {
        SchemaBuilder updated = SchemaUtil.copySchemaBasics(originalSchema, SchemaBuilder.struct());
        return updateSchema(originalSchema, updated, pathTree, whenFound,
                (schemaBuilder, field, fieldPath) -> { /* ignore */ },
                (schemaBuilder, field, fieldPath) -> schemaBuilder.field(field.name(), field.schema()));
    }

    @Override
    public Schema updateSchemaFrom(
            Schema originalSchema,
            StructSchemaUpdater whenFound,
            StructSchemaUpdater whenNotFound
    ) {
        SchemaBuilder updated = SchemaUtil.copySchemaBasics(originalSchema, SchemaBuilder.struct());
        return updateSchema(originalSchema, updated, pathTree, whenFound,
                (schemaBuilder, field, fieldPath) -> { /* ignore */ },
                whenNotFound);
    }

    @Override
    public Schema updateSchemaFrom(
            Schema originalSchema,
            StructSchemaUpdater whenFound,
            StructSchemaUpdater whenNotFound,
            StructSchemaUpdater toOthers
    ) {
        SchemaBuilder updated = SchemaUtil.copySchemaBasics(originalSchema, SchemaBuilder.struct());
        return updateSchema(originalSchema, updated, pathTree, whenFound, whenNotFound, toOthers);
    }

    @Override
    public Schema updateSchemaFrom(
            Schema originalSchema,
            SchemaBuilder baselineSchemaBuilder,
            StructSchemaUpdater whenFound
    ) {
        return updateSchema(originalSchema, baselineSchemaBuilder, pathTree, whenFound,
                (schemaBuilder, field, fieldPath) -> { /* ignore */ },
                (schemaBuilder, field, fieldPath) -> schemaBuilder.field(field.name(), field.schema()));
    }

    @SuppressWarnings("unchecked")
    private Schema updateSchema(
            Schema originalSchema,
            SchemaBuilder baseSchemaBuilder,
            Map<String, Object> treeAt,
            StructSchemaUpdater whenFound,
            StructSchemaUpdater whenNotFound,
            StructSchemaUpdater toOtherFields
    ) {
        if (originalSchema.isOptional()) {
            baseSchemaBuilder.optional();
        }
        Map<String, Object> notFoundFields = new HashMap<>(treeAt);
        for (Field field : originalSchema.fields()) {
            if (!treeAt.isEmpty()) {
                if (!treeAt.containsKey(field.name())) {
                    toOtherFields.apply(baseSchemaBuilder, field, null);
                } else {
                    notFoundFields.remove(field.name());
                    if (treeAt.get(field.name()) instanceof SingleFieldPath) {
                        whenFound.apply(baseSchemaBuilder, field, (SingleFieldPath) treeAt.get(field.name()));
                    } else {
                        if (field.schema().type() == Type.STRUCT) {
                            Schema fieldSchema = updateSchema(
                                    field.schema(),
                                    SchemaBuilder.struct(),
                                    (Map<String, Object>) treeAt.get(field.name()),
                                    whenFound, whenNotFound, toOtherFields);
                            baseSchemaBuilder.field(field.name(), fieldSchema);
                        } else {
                            toOtherFields.apply(baseSchemaBuilder, field, null);
                        }
                    }
                }
            } else {
                toOtherFields.apply(baseSchemaBuilder, field, null);
            }
        }
        for (Map.Entry<String, Object> entry : notFoundFields.entrySet()) {
            String fieldName = entry.getKey();
            Object treeValue = entry.getValue();
            if (treeValue instanceof SingleFieldPath) {
                whenNotFound.apply(baseSchemaBuilder, null, (SingleFieldPath) treeValue);
            } else {
                Schema fieldSchema = updateSchema(
                        SchemaBuilder.struct().build(),
                        SchemaBuilder.struct(),
                        (Map<String, Object>) treeValue,
                        whenFound, whenNotFound, toOtherFields);
                baseSchemaBuilder.field(fieldName, fieldSchema);
            }
        }
        return baseSchemaBuilder.build();
    }

    public int size() {
        return paths.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MultiFieldPaths that = (MultiFieldPaths) o;
        return Objects.equals(pathTree, that.pathTree);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pathTree);
    }

    @Override
    public String toString() {
        return "FieldPaths(pathTree = " + pathTree + ")";
    }

    public static class Builder {
        List<SingleFieldPath> paths = new ArrayList<>();

        final FieldSyntaxVersion syntaxVersion;

        public Builder(FieldSyntaxVersion syntaxVersion) {
            this.syntaxVersion = syntaxVersion;
        }

        public Builder add(SingleFieldPath fieldPath) {
            paths.add(fieldPath);
            return this;
        }

        public MultiFieldPaths build() {
            return new MultiFieldPaths(paths);
        }
    }
}
