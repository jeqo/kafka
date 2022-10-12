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

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.util.SchemaUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FieldPathsTest {
    @Test void shouldBuildPathWithSinglePathV1() {
        FieldPath path = FieldPath.of("foo.bar.baz", FieldSyntaxVersion.V1);
        FieldPaths paths = FieldPaths.of(path);
        assertEquals(1, paths.pathTree.size());
        assertEquals(path, paths.pathTree.get("foo.bar.baz"));
    }

    @Test void shouldBuildPathWithSamePathV1() {
        FieldPath path = FieldPath.of("foo.bar.baz", FieldSyntaxVersion.V1);
        FieldPaths paths = FieldPaths.of(path, path);
        assertEquals(1, paths.pathTree.size());
        assertEquals(path, paths.pathTree.get("foo.bar.baz"));
    }

    @Test void shouldBuildPathWithSinglePathV2() {
        FieldPath path = FieldPath.of("foo.bar.baz", FieldSyntaxVersion.V2);
        FieldPaths paths = FieldPaths.of(path);
        assertEquals(1, paths.pathTree.size());
        assertEquals(path, ((Map<?, ?>) ((Map<?, ?>) paths.pathTree.get("foo")).get("bar")).get("baz"));
    }

    @Test void shouldFailWhenPathsCollide() {
        assertThrows(IllegalArgumentException.class,
            () -> FieldPaths.of(FieldPath.ofV2("foo"), FieldPath.ofV2("foo.bar")));
    }

    @Test void shouldRenameSchemaV1Fields() {
        Schema schema = SchemaBuilder.struct()
                .field("foo", Schema.STRING_SCHEMA)
                .field("bar", Schema.STRING_SCHEMA)
                .field("baz", Schema.INT32_SCHEMA)
                .build();

        FieldPaths fieldPath = FieldPaths.of(Arrays.asList("foo", "bar"), FieldSyntaxVersion.V1);
        SchemaBuilder updated = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());
        Schema result = fieldPath.updateSchemaFrom(
                schema,
                updated,
                (builder, field, path) -> builder.field(field.name() + "_other", field.schema())
        );

        assertEquals(3, result.fields().size());
        assertEquals("foo_other", result.fields().get(0).name());
        assertEquals("bar_other", result.fields().get(1).name());
        assertEquals("baz", result.fields().get(2).name());
    }

    @Test void shouldRenameSchemaV2Fields() {
        SchemaBuilder nested = SchemaBuilder.struct()
                .field("bar", Schema.STRING_SCHEMA)
                .field("baz", Schema.INT32_SCHEMA);
        Schema schema = SchemaBuilder.struct()
                .field("foo", nested)
                .build();

        FieldPaths fieldPath = FieldPaths.of(Arrays.asList("foo.baz", "foo.bar"), FieldSyntaxVersion.V2);
        Schema result = fieldPath.updateSchemaFrom(
                schema,
                (builder, field, path) -> builder.field(field.name() + "_other", field.schema())
        );

        assertEquals(1, result.fields().size());
        assertEquals(2, result.field("foo").schema().fields().size());
        assertEquals("bar_other", result.field("foo").schema().fields().get(0).name());
        assertEquals("baz_other", result.field("foo").schema().fields().get(1).name());
    }

    @Test void shouldUpdateValuesV1FromSchemaless() {
        Map<String, Object> value = new HashMap<>();
        value.put("foo", 42);
        value.put("bar", 21);

        FieldPath fooPath = FieldPath.of("foo", FieldSyntaxVersion.V1);
        FieldPath barPath = FieldPath.of("bar", FieldSyntaxVersion.V1);
        FieldPaths fieldPaths = FieldPaths.of(fooPath, barPath);
        Map<String, Object> updated = fieldPaths.updateValuesFrom(
                value,
                (orig, map, f, k) -> map.put(k, ((Integer) orig.get(k)) * 2)
        );

        Map<FieldPath, MapFieldAndValue> actual = fieldPaths.fieldAndValuesFrom(updated);
        assertEquals(84, actual.get(fooPath).value());
        assertEquals(42, actual.get(barPath).value());
    }

    @Test void shouldUpdateNestedValuesV2FromSchemaless() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("bar", 21);
        nested.put("baz", 42);
        Map<String, Object> value = Collections.singletonMap("foo", nested);

        FieldPath barPath = FieldPath.of("foo.bar", FieldSyntaxVersion.V2);
        FieldPath bazPath = FieldPath.of("foo.baz", FieldSyntaxVersion.V2);
        FieldPaths fieldPaths = FieldPaths.of(bazPath, barPath);
        Map<String, Object> updated = fieldPaths.updateValuesFrom(
                value,
                (orig, map, f, k) -> map.put(k, ((Integer) orig.get(k)) * 2)
        );

        Map<FieldPath, MapFieldAndValue> actual = fieldPaths.fieldAndValuesFrom(updated);
        assertEquals(84, actual.get(bazPath).value());
        assertEquals(42, actual.get(barPath).value());
    }

    @Test void shouldUpdateValueV1WithSchema() {
        Schema schema = SchemaBuilder.struct()
                .field("foo.bar", Schema.INT32_SCHEMA)
                .field("foo.baz", Schema.INT32_SCHEMA)
                .build();
        Struct value = new Struct(schema)
                .put("foo.bar", 21)
                .put("foo.baz", 42);

        FieldPath bazPath = FieldPath.of("foo.baz", FieldSyntaxVersion.V1);
        FieldPath barPath = FieldPath.of("foo.bar", FieldSyntaxVersion.V1);
        FieldPaths fieldPaths = FieldPaths.of(bazPath, barPath);
        Struct updated = fieldPaths.updateValuesFrom(schema, value, schema,
                (orig, oldField, s, updatedField, f) -> s.put(updatedField, ((Integer) orig.get(oldField)) * 2));

        Map<FieldPath, StructFieldAndValue> actual = fieldPaths.fieldAndValuesFrom(updated);
        assertEquals(84, actual.get(bazPath).value());
        assertEquals(42, actual.get(barPath).value());
    }

    @Test void shouldUpdateNestedValueV2WithSchema() {
        SchemaBuilder nestedSchema = SchemaBuilder.struct()
                .field("bar", Schema.INT32_SCHEMA)
                .field("baz", Schema.INT32_SCHEMA);
        Schema schema = SchemaBuilder.struct()
                .field("foo", nestedSchema)
                .build();
        Struct nested = new Struct(nestedSchema)
                .put("bar", 21)
                .put("baz", 42);
        Struct value = new Struct(schema).put("foo", nested);

        FieldPath bazPath = FieldPath.of("foo.baz", FieldSyntaxVersion.V2);
        FieldPath barPath = FieldPath.of("foo.bar", FieldSyntaxVersion.V2);
        FieldPaths fieldPaths = FieldPaths.of(bazPath, barPath);
        Struct updated = fieldPaths.updateValuesFrom(schema, value, schema,
                (orig, oldField, s, updatedField, f) -> s.put(updatedField, ((Integer) orig.get(oldField)) * 2));

        Map<FieldPath, StructFieldAndValue> actual = fieldPaths.fieldAndValuesFrom(updated);
        assertEquals(84, actual.get(bazPath).value());
        assertEquals(42, actual.get(barPath).value());
    }
}