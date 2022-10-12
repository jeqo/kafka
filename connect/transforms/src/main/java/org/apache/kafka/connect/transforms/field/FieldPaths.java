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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Multiple field paths to access data objects ({@code Struct} or {@code Map}) efficiently,
 * instead of using single {@see FieldPath} individually.
 * <p>
 * Invariants:
 * <li>
 *     <ul>Tree values contain either a nested tree or a field path</ul>
 *     <ul>A tree cannot contain paths that are a subset of other paths (e.g. foo and foo.bar in V2 should collide and fail)</ul>
 * </li>
 */
public class FieldPaths {

    final Map<String, Object> pathTree;
    final List<FieldPath> paths;

    FieldPaths(List<FieldPath> paths) {
        this.paths = paths;
        pathTree = buildPathTree(paths, 0, new HashMap<>());
    }

    public static FieldPaths of(FieldPath path) {
        return new FieldPaths(Collections.singletonList(path));
    }

    public static FieldPaths of(FieldPath... paths) {
        return new FieldPaths(Arrays.asList(paths));
    }

    public static FieldPaths of(List<FieldPath> paths) {
        return new FieldPaths(paths);
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

    Map<String, Object> buildPathTree(List<FieldPath> paths, int stepIdx, Map<String, Object> pathTree) {
        if (paths.size() == 1) { // optimize for paths with a single member
            FieldPath path = paths.get(0);
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
            final Map<String, List<FieldPath>> groups = new HashMap<>();
            for (FieldPath path : paths) {
                if (path != null) {
                    String step = path.stepAt(stepIdx);
                    if (step != null) {
                        groups.computeIfPresent(step, (s, fieldPaths) -> {
                            for (FieldPath other : fieldPaths) {
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
                            List<FieldPath> fieldPaths = new ArrayList<>();
                            fieldPaths.add(path);
                            return fieldPaths;
                        });
                    }
                }
            }

            // create tree from grouped paths
            for (Map.Entry<String, List<FieldPath>> entry : groups.entrySet()) {
                if (entry.getValue().size() == 1) {
                    final FieldPath path = entry.getValue().get(0);
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
    public Map<FieldPath, StructFieldAndValue> fieldAndValuesFrom(Struct struct) {
        return findFieldAndValues(struct, pathTree, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private Map<FieldPath, StructFieldAndValue> findFieldAndValues(
            Struct originalValue,
            Map<String, Object> treeAt,
            Map<FieldPath, StructFieldAndValue> fieldAndValueMap
    ) {
        for (Map.Entry<String, Object> step : treeAt.entrySet()) {
            Field field = originalValue.schema().field(step.getKey());
            if (step.getValue() instanceof FieldPath) {
                StructFieldAndValue fieldAndValue =
                        field != null
                                ? new StructFieldAndValue(field, originalValue.get(field))
                                : null;
                fieldAndValueMap.put((FieldPath) step.getValue(), fieldAndValue);
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
    public Map<FieldPath, MapFieldAndValue> fieldAndValuesFrom(Map<String, Object> value) {
        return findFieldAndValues(value, pathTree, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private Map<FieldPath, MapFieldAndValue> findFieldAndValues(
            Map<String, Object> value,
            Map<String, Object> treeAt,
            Map<FieldPath, MapFieldAndValue> fieldAndValueMap
    ) {
        for (Map.Entry<String, Object> step : treeAt.entrySet()) {
            Object fieldValue = value.get(step.getKey());
            if (step.getValue() instanceof FieldPath) {
                fieldAndValueMap.put((
                        FieldPath) step.getValue(),
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

    /**
     * Find values at the path tree leafs within the {@code Map} and apply update function when found.
     *
     * @param originalValue  schemaless data value
     * @param update function to apply when found
     * @return updated data value
     */
    public Map<String, Object> updateValuesFrom(
            Map<String, Object> originalValue,
            MapValueUpdater update
    ) {
        return updateValues(originalValue, pathTree, update,
                (originalParent, updatedParent, fieldPath, fieldName) ->
                    updatedParent.put(fieldName, originalParent.get(fieldName)));
    }

    public Map<String, Object> updateValuesFrom(
            Map<String, Object> originalValue,
            MapValueUpdater update,
            MapValueUpdater others
    ) {
        return updateValues(originalValue, pathTree, update, others);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> updateValues(
            Map<String, Object> originalValue,
            Map<String, Object> treeAt,
            MapValueUpdater update,
            MapValueUpdater others
    ) {
        if (originalValue == null) return null;
        Map<String, Object> updatedValue = new HashMap<>(originalValue.size());
        for (Map.Entry<String, Object> entry : originalValue.entrySet()) {
            String fieldName = entry.getKey();
            Object fieldValue = entry.getValue();
            if (!treeAt.isEmpty()) {
                if (treeAt.containsKey(fieldName)) {
                    Object treeValue = treeAt.get(fieldName);
                    if (treeValue instanceof FieldPath) {
                        update.apply(originalValue, updatedValue, (FieldPath) treeValue, fieldName);
                    } else {
                        if (fieldValue instanceof Map) {
                            Map<String, Object> updatedField = updateValues(
                                    (Map<String, Object>) fieldValue,
                                    (Map<String, Object>) treeValue,
                                    update,
                                    others);
                            updatedValue.put(fieldName, updatedField);
                        } else {
                            updatedValue.put(fieldName, fieldValue);
                        }
                    }
                } else {
                    others.apply(originalValue, updatedValue, null, fieldName);
                }
            } else {
                others.apply(originalValue, updatedValue, null, fieldName);
            }
        }

        return updatedValue;
    }

    /**
     * Find values at the path tree leafs within the {@code Struct} and apply update function when found.
     *
     * @param originalSchema original struct schema
     * @param originalValue  schema-based data value
     * @param updatedSchema updated struct schema
     * @param update function to apply when found
     * @return updated data value
     */
    public Struct updateValuesFrom(
            Schema originalSchema,
            Struct originalValue,
            Schema updatedSchema,
            StructValueUpdater update
    ) {
        return updateValues(originalSchema, originalValue, updatedSchema, pathTree, update,
                (originalParent, originalField, updatedParent, nullUpdatedField, nullFieldPath) ->
                        updatedParent.put(originalField.name(), originalParent.get(originalField)));
    }

    public Struct updateValuesFrom(
            Schema originalSchema,
            Struct originalValue,
            Schema updatedSchema,
            StructValueUpdater update,
            StructValueUpdater others
    ) {
        return updateValues(originalSchema, originalValue, updatedSchema, pathTree, update, others);
    }

    @SuppressWarnings("unchecked")
    private Struct updateValues(
            Schema originalSchema,
            Struct originalValue,
            Schema updateSchema,
            Map<String, Object> treeAt,
            StructValueUpdater update,
            StructValueUpdater others
    ) {
        Struct updatedValue = new Struct(updateSchema);
        for (Field field : originalSchema.fields()) {
            if (!treeAt.isEmpty()) {
                if (treeAt.containsKey(field.name())) {
                    if (treeAt.get(field.name()) instanceof FieldPath) {
                        update.apply(
                                originalValue,
                                originalSchema.field(field.name()),
                                updatedValue,
                                updateSchema.field(field.name()),
                                (FieldPath) treeAt.get(field.name())
                        );
                    } else {
                        if (field.schema().type() == Type.STRUCT) {
                            Struct fieldValue = updateValues(
                                    field.schema(),
                                    originalValue.getStruct(field.name()),
                                    updateSchema.field(field.name()).schema(),
                                    (Map<String, Object>) treeAt.get(field.name()),
                                    update,
                                    others
                            );
                            updatedValue.put(updateSchema.field(field.name()), fieldValue);
                        }
                    }
                } else {
                    others.apply(originalValue, field, updatedValue, null, null);
                }
            } else {
                others.apply(originalValue, field, updatedValue, null, null);
            }
        }
        return updatedValue;
    }

    /**
     * Find the {@code Field}s at the path tree leafs, and apply an update function. If fields are not
     * found, then no update function is applied.
     * <p>
     * A copy of the {@code Schema} will be used as a base for the updated schema.
     *
     * @return the updated schema
     */
    public Schema updateSchemaFrom(
            Schema originalSchema,
            StructSchemaUpdater update
    ) {
        SchemaBuilder updated = SchemaUtil.copySchemaBasics(originalSchema, SchemaBuilder.struct());
        return updateSchema(originalSchema, updated, pathTree, update,
                (schemaBuilder, field, fieldPath) -> schemaBuilder.field(field.name(), field.schema()));
    }

    public Schema updateSchemaFrom(
            Schema originalSchema,
            StructSchemaUpdater update,
            StructSchemaUpdater others
    ) {
        SchemaBuilder updated = SchemaUtil.copySchemaBasics(originalSchema, SchemaBuilder.struct());
        return updateSchema(originalSchema, updated, pathTree, update, others);
    }

    /**
     * Find the {@code Field}s at the path tree leafs, and apply an update function. If fields are not
     * found, then no update function is applied.
     * <p>
     *
     * @return the updated schema
     */
    public Schema updateSchemaFrom(
            Schema originalSchema,
            SchemaBuilder baseline,
            StructSchemaUpdater update
    ) {
        return updateSchema(originalSchema, baseline, pathTree, update,
                (schemaBuilder, field, fieldPath) -> schemaBuilder.field(field.name(), field.schema()));
    }

    @SuppressWarnings("unchecked")
    private Schema updateSchema(
            Schema originalSchema,
            SchemaBuilder baseSchemaBuilder,
            Map<String, Object> treeAt,
            StructSchemaUpdater update,
            StructSchemaUpdater others
    ) {
        if (originalSchema.isOptional()) {
            baseSchemaBuilder.optional();
        }
        for (Field field : originalSchema.fields()) {
            if (!treeAt.isEmpty()) {
                if (!treeAt.containsKey(field.name())) {
                    others.apply(baseSchemaBuilder, field, null);
                } else {
                    if (treeAt.get(field.name()) instanceof FieldPath) {
                        update.apply(baseSchemaBuilder, field, (FieldPath) treeAt.get(field.name()));
                    } else {
                        if (field.schema().type() == Type.STRUCT) {
                            Schema fieldSchema = updateSchema(
                                    field.schema(),
                                    SchemaBuilder.struct(),
                                    (Map<String, Object>) treeAt.get(field.name()),
                                    update, others);
                            baseSchemaBuilder.field(field.name(), fieldSchema);
                        } else {
                            others.apply(baseSchemaBuilder, field, null);
                        }
                    }
                }
            } else {
                others.apply(baseSchemaBuilder, field, null);
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
        FieldPaths that = (FieldPaths) o;
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
}
