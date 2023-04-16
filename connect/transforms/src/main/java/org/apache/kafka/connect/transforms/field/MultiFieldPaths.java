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
import java.util.HashMap;
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
public class MultiFieldPaths implements FieldPaths {

    final Map<String, Object> pathTree;

    MultiFieldPaths(Set<SingleFieldPath> paths) {
        pathTree = buildPathTree(paths, 0, new HashMap<>());
    }

    /**
     * Build a nested map of paths to field paths to be used when traversing data structures.
     * <p>
     * With the following paths:
     * <ul>
     *     <li>foo.bar</li>
     *     <li>foo.baz</li>
     *     <li>foo.baz.other</li>
     * </ul>
     * a tree with the following structure will be created:
     * <ul>
     *     <li>foo:
     *     <ul>
     *         <li>bar</li>
     *         <li>baz:
     *         <ul>
     *             <li>other</li>
     *         </ul>
     *         </li>
     *     </ul>
     *     </li>
     * </ul>
     *
     * @param paths input paths
     * @param stepIdx paths step index, starting at zero
     * @param pathTree building tree, starting empty
     */
    static Map<String, Object> buildPathTree(Set<SingleFieldPath> paths, int stepIdx, Map<String, Object> pathTree) {
        Objects.requireNonNull(pathTree, "Resulting path three may not be null");
        if (stepIdx < 0) throw new IllegalArgumentException("stepAt index may be higher or equal than zero");

        // group paths by prefix,
        // if paths overlap (e.g. `foo` and `foo.bar` are added)
        // only the children are kept (`foo.bar`)
        final Map<String, Set<SingleFieldPath>> groups = paths.stream()
            .filter(p -> p.stepAt(stepIdx) != null)
            .collect(Collectors.groupingBy(
                path -> path.stepAt(stepIdx),
                Collectors.toSet()
            ));

        // create tree from grouped paths
        for (Map.Entry<String, Set<SingleFieldPath>> entry : groups.entrySet()) {
            if (entry.getValue().size() == 1) {
                final SingleFieldPath path = entry.getValue().iterator().next();
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
        return pathTree;
    }

    /**
     * Find values at the field paths on the tree.
     * @param struct data value
     * @return map of field paths and field/values
     */
    @Override
    public Map<FieldPaths, Map.Entry<Field, Object>> fieldAndValuesFrom(Schema schema, Struct struct) {
        return findFieldAndValues(schema, struct, pathTree, new HashMap<>());
    }
    @Override
    public Map<FieldPaths, Map.Entry<Field, Object>> fieldAndValuesFrom(Struct struct) {
        return findFieldAndValues(struct.schema(), struct, pathTree, new HashMap<>());
    }

    @Override
    public Map.Entry<Field, Object> fieldAndValueFrom(Struct struct) {
        Map<FieldPaths, Map.Entry<Field, Object>> map = findFieldAndValues(struct.schema(), struct, pathTree, new HashMap<>());
        return map.get(map.keySet().iterator().next());
    }

    @Override
    public Map.Entry<Field, Object> fieldAndValueFrom(Schema schema, Struct struct) {
        Map<FieldPaths, Map.Entry<Field, Object>> map = fieldAndValuesFrom(schema, struct);
        return map.get(map.keySet().iterator().next());
    }

    @SuppressWarnings("unchecked")
    private Map<FieldPaths, Map.Entry<Field, Object>> findFieldAndValues(
        Schema originalSchema,
            Struct originalValue,
            Map<String, Object> treeAt,
            Map<FieldPaths, Map.Entry<Field, Object>> fieldAndValueMap
    ) {
        for (Map.Entry<String, Object> step : treeAt.entrySet()) {
            Field field = originalSchema.field(step.getKey());
            if (step.getValue() instanceof SingleFieldPath) {
                Map.Entry<Field, Object> fieldAndValue =
                        field != null
                                ? new AbstractMap.SimpleImmutableEntry<>(field, originalValue.get(field))
                                : null;
                fieldAndValueMap.put((SingleFieldPath) step.getValue(), fieldAndValue);
            } else {
                if (field.schema().type() == Type.STRUCT) {
                    findFieldAndValues(
                            originalSchema.field(field.name()).schema(),
                            originalValue.getStruct(field.name()),
                            (Map<String, Object>) step.getValue(),
                            fieldAndValueMap
                    );
                }
            }
        }
        return fieldAndValueMap;
    }

    @Override
    public Field fieldFrom(Schema schema) {
        Map<FieldPaths, Field> map = fieldsFrom(schema);
        return map.get(map.keySet().iterator().next());
    }


    @Override
    public Map<FieldPaths, Field> fieldsFrom(Schema schema) {
        return findFields(schema, pathTree, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private Map<FieldPaths, Field> findFields(
        Schema originalSchema,
        Map<String, Object> treeAt,
        Map<FieldPaths, Field> result
    ) {
        for (Map.Entry<String, Object> step : treeAt.entrySet()) {
            Field field = originalSchema.field(step.getKey());
            if (step.getValue() instanceof SingleFieldPath) {
                result.put((SingleFieldPath) step.getValue(), field);
            } else {
                if (field.schema().type() == Type.STRUCT) {
                    findFields(
                        field.schema(),
                        (Map<String, Object>) step.getValue(),
                        result
                    );
                }
            }
        }
        return result;


    }

    /**
     * Find values at the field paths on the tree.
     * @param value data value
     * @return map of field paths and field/values
     */
    @Override
    public Map<FieldPaths, Map.Entry<String, Object>> fieldAndValuesFrom(Map<String, Object> value) {
        return findFieldAndValues(value, pathTree, new HashMap<>());
    }

    @Override
    public Map.Entry<String, Object> fieldAndValueFrom(Map<String, Object> map) {
        Map<FieldPaths, Map.Entry<String, Object>> result = fieldAndValuesFrom(map);
        return result.get(result.keySet().iterator().next());
    }

    @SuppressWarnings("unchecked")
    private Map<FieldPaths, Map.Entry<String, Object>> findFieldAndValues(
            Map<String, Object> value,
            Map<String, Object> treeAt,
            Map<FieldPaths, Map.Entry<String, Object>> fieldAndValueMap
    ) {
        for (Map.Entry<String, Object> step : treeAt.entrySet()) {
            Object fieldValue = value.get(step.getKey());
            if (step.getValue() instanceof SingleFieldPath) {
                fieldAndValueMap.put(
                        (SingleFieldPath) step.getValue(),
                        new AbstractMap.SimpleImmutableEntry<>(step.getKey(), fieldValue)
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

}
