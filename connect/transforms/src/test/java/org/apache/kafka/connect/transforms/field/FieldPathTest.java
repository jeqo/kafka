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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.util.SchemaUtil;
import org.junit.jupiter.api.Test;

class FieldPathTest {
    final static String[] EMPTY_PATH = new String[]{};

    @Test void shouldBuildV1WithDotsAndBacktickPair() {
        assertArrayEquals(new String[] {"foo.bar.baz"}, FieldPath.ofV1("foo.bar.baz").path());
        assertArrayEquals(new String[] {"foo.`bar.baz`"}, FieldPath.ofV1("foo.`bar.baz`").path());
    }

    @Test void shouldBuildV2WithEmptyPath() {
        assertArrayEquals(EMPTY_PATH, FieldPath.of("", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildV2WithNullPath() {
        assertArrayEquals(EMPTY_PATH, FieldPath.of(null, FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildV2WithoutDots() {
        assertArrayEquals(new String[] {"foobarbaz"}, FieldPath.of("foobarbaz", FieldSyntaxVersion.V2).path());
    }
    @Test void shouldBuildV2WithoutWrappingBackticks() {
        assertArrayEquals(new String[] {"foo`bar`baz"}, FieldPath.of("foo`bar`baz", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildV2WhenIncludesDots() {
        assertArrayEquals(new String[] {"foo", "bar", "baz"}, FieldPath.of("foo.bar.baz", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildV2WhenIncludesDotsAndBacktickPair() {
        assertArrayEquals(new String[] {"foo", "bar.baz"}, FieldPath.of("foo.`bar.baz`", FieldSyntaxVersion.V2).path());
        assertArrayEquals(new String[] {"foo", "bar", "baz"}, FieldPath.of("foo.`bar`.baz", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildV2AndIgnoreBackticksThatAreNotWrapping() {
        assertArrayEquals(new String[] {"foo", "ba`r.baz"}, FieldPath.of("foo.`ba`r.baz`", FieldSyntaxVersion.V2).path());
        assertArrayEquals(new String[] {"foo", "ba`r", "baz"}, FieldPath.of("foo.ba`r.baz", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildV2AndEscapeBackticks() {
        assertArrayEquals(new String[] {"foo", "bar`.`baz"}, FieldPath.of("foo.`bar\\`.\\`baz`", FieldSyntaxVersion.V2).path());
        assertArrayEquals(new String[] {"foo", "bar\\`.`baz"}, FieldPath.of("foo.`bar\\\\`.\\`baz`", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildV2WithBackticksWrappingBackticks() {
        assertArrayEquals(new String[] {"foo", "`bar`", "baz"}, FieldPath.of("foo.``bar``.baz", FieldSyntaxVersion.V2).path());
        assertArrayEquals(new String[] {"`foo.bar.baz`"}, FieldPath.of("``foo.bar.baz``", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldFilterSchemaV1Fields() {
        Schema schema = SchemaBuilder.struct().field("foo", Schema.STRING_SCHEMA)
            .field("bar", Schema.STRING_SCHEMA)
            .field("baz", Schema.INT32_SCHEMA)
            .build();

        SchemaBuilder updated = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());
        Schema result = FieldPath.of("foo", FieldSyntaxVersion.V1)
            .updateSchemaAt(schema, updated, (builder, field) -> {
                // ignore field
            });

        assertEquals(2, result.fields().size());
        assertEquals("bar", result.fields().get(0).name());
        assertEquals("baz", result.fields().get(1).name());
    }
    @Test void shouldFilterSchemaV2Fields() {
        Schema schema = SchemaBuilder.struct().field("foo",
            SchemaBuilder.struct().field("bar", Schema.STRING_SCHEMA)
                .field("baz", Schema.INT32_SCHEMA))
            .build();

        SchemaBuilder updated = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());
        Schema result = FieldPath.of("foo.baz", FieldSyntaxVersion.V2)
            .updateSchemaAt(schema, updated, (builder, field) -> {
                // ignore field
            });

        assertEquals(1, result.fields().size());
        assertEquals(1, result.field("foo").schema().fields().size());
        assertEquals("bar", result.field("foo").schema().fields().get(0).name());
    }

    @Test void shouldRenameSchemaV1Fields() {
        Schema schema = SchemaBuilder.struct().field("foo", Schema.STRING_SCHEMA)
            .field("bar", Schema.STRING_SCHEMA)
            .field("baz", Schema.INT32_SCHEMA)
            .build();

        SchemaBuilder updated = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());
        Schema result = FieldPath.of("foo", FieldSyntaxVersion.V1)
            .updateSchemaAt(schema, updated, (builder, field) -> builder.field("other", field.schema()));

        assertEquals(3, result.fields().size());
        assertEquals("other", result.fields().get(0).name());
        assertEquals("bar", result.fields().get(1).name());
        assertEquals("baz", result.fields().get(2).name());
    }

    @Test void shouldRenameSchemaV2Fields() {
        Schema schema = SchemaBuilder.struct().field("foo",
                SchemaBuilder.struct().field("bar", Schema.STRING_SCHEMA)
                    .field("baz", Schema.INT32_SCHEMA))
            .build();

        SchemaBuilder updated = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());
        Schema result = FieldPath.of("foo.baz", FieldSyntaxVersion.V2)
            .updateSchemaAt(schema, updated, (builder, field) -> builder.field("other", field.schema()));

        assertEquals(1, result.fields().size());
        assertEquals(2, result.field("foo").schema().fields().size());
        assertEquals("bar", result.field("foo").schema().fields().get(0).name());
        assertEquals("other", result.field("foo").schema().fields().get(1).name());
    }

    @Test void shouldUpdateValueV1FromSchemaless() {
        final Map<String, Object> value = Collections.singletonMap("foo", 42);

        final Map<String, Object> updated = FieldPath.of("foo", FieldSyntaxVersion.V1)
            .updateValueAt(value, (map, f, v) -> map.put(f, ((Integer) v) * 2));

        assertEquals(84, updated.get("foo"));
    }

    @SuppressWarnings("unchecked")
    @Test void shouldUpdateNestedValueV2FromSchemaless() {
        final Map<String, Object> value = Collections.singletonMap("foo", Collections.singletonMap("bar", 42));

        final Map<String, Object> updated = FieldPath.of("foo.bar", FieldSyntaxVersion.V2)
            .updateValueAt(value, (map, f, v) -> map.put(f, ((Integer) v) * 2));

        assertEquals(84, ((Map<String, Object>) updated.get("foo")).get("bar"));
    }

    @Test void shouldUpdateValueV1WithSchema() {
        final Schema schema = SchemaBuilder.struct().field("foo", Schema.INT32_SCHEMA).build();
        final Struct value = new Struct(schema).put("foo", 42);

        final Struct updated = FieldPath.of("foo", FieldSyntaxVersion.V1)
            .updateValueAt(schema, value, schema,
                (oldField, updatedField, s, v) -> s.put(updatedField, ((Integer) v) * 2));

        assertEquals(84, updated.getInt32("foo"));
    }

    @Test void shouldUpdateNestedValueV2WithSchema() {
        final SchemaBuilder barSchema = SchemaBuilder.struct().field("bar", Schema.INT32_SCHEMA);
        final Schema schema = SchemaBuilder.struct().field("foo", barSchema).build();
        final Struct value = new Struct(schema).put("foo", new Struct(barSchema).put("bar", 42));

        final Struct updated = FieldPath.of("foo.bar", FieldSyntaxVersion.V2)
            .updateValueAt(schema, value, schema,
                (oldField, updatedField, s, v) -> s.put(updatedField, ((Integer) v) * 2));

        assertEquals(84, updated.getStruct("foo").getInt32("bar"));
    }
}