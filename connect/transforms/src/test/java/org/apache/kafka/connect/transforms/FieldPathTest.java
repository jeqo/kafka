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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Test;

class FieldPathTest {
    final static String[] EMPTY_PATH = new String[]{};

    @Test void shouldHandleV1WithDotsAndBacktickPair() {
        assertArrayEquals(new String[] {"foo.bar.baz"}, FieldPath.from("foo.bar.baz", FieldSyntaxVersion.V1).path());
        assertArrayEquals(new String[] {"foo.`bar.baz`"}, FieldPath.from("foo.`bar.baz`", FieldSyntaxVersion.V1).path());
    }

    @Test void testEmptyPath() {
        assertArrayEquals(EMPTY_PATH, FieldPath.from("", FieldSyntaxVersion.V2).path());
    }

    @Test void testNullPath() {
        assertArrayEquals(EMPTY_PATH, FieldPath.from(null, FieldSyntaxVersion.V2).path());
    }

    @Test void testWithoutDots() {
        assertArrayEquals(new String[] {"foobarbaz"}, FieldPath.from("foobarbaz", FieldSyntaxVersion.V2).path());
    }
    @Test void testWithoutWrappingBackticks() {
        assertArrayEquals(new String[] {"foo`bar`baz"}, FieldPath.from("foo`bar`baz", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildPathWhenIncludesDots() {
        assertArrayEquals(new String[] {"foo", "bar", "baz"}, FieldPath.from("foo.bar.baz", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildPathWhenIncludesDotsAndBacktickPair() {
        assertArrayEquals(new String[] {"foo", "bar.baz"}, FieldPath.from("foo.`bar.baz`", FieldSyntaxVersion.V2).path());
        assertArrayEquals(new String[] {"foo", "bar", "baz"}, FieldPath.from("foo.`bar`.baz", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildPathAndIgnoreBackticksThatAreNotWrapping() {
        assertArrayEquals(new String[] {"foo", "ba`r.baz"}, FieldPath.from("foo.`ba`r.baz`", FieldSyntaxVersion.V2).path());
        assertArrayEquals(new String[] {"foo", "ba`r", "baz"}, FieldPath.from("foo.ba`r.baz", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildPathAndEscapeBackticks() {
        assertArrayEquals(new String[] {"foo", "bar`.`baz"}, FieldPath.from("foo.`bar\\`.\\`baz`", FieldSyntaxVersion.V2).path());
        assertArrayEquals(new String[] {"foo", "bar\\`.`baz"}, FieldPath.from("foo.`bar\\\\`.\\`baz`", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldBuildPathWithoutWrappingBackticks() {
        assertArrayEquals(new String[] {"foo", "`bar`", "baz"}, FieldPath.from("foo.``bar``.baz", FieldSyntaxVersion.V2).path());
        assertArrayEquals(new String[] {"`foo.bar.baz`"}, FieldPath.from("``foo.bar.baz``", FieldSyntaxVersion.V2).path());
    }

    @Test void shouldFilterSchemaFields() {
        Schema schema = SchemaBuilder.struct().field("foo",
            SchemaBuilder.struct().field("bar", Schema.STRING_SCHEMA)
                .field("baz", Schema.INT32_SCHEMA))
            .build();
        Schema result = FieldPath.from("foo.baz", FieldSyntaxVersion.V2).updateSchemaAt(schema, (builder, field) -> {
            // ignore field
        });
        assertEquals(result.fields().size(), 1);
        assertEquals(result.field("foo").schema().fields().size(), 1);
        assertEquals(result.field("foo").schema().fields().get(0).name(), "bar");
    }

    @Test void shouldRenameSchemaFields() {
        Schema schema = SchemaBuilder.struct().field("foo",
                SchemaBuilder.struct().field("bar", Schema.STRING_SCHEMA)
                    .field("baz", Schema.INT32_SCHEMA))
            .build();
        Schema result = FieldPath.from("foo.baz", FieldSyntaxVersion.V2)
            .updateSchemaAt(schema, (builder, field) -> builder.field("other", field.schema()));
        assertEquals(result.fields().size(), 1);
        assertEquals(result.field("foo").schema().fields().size(), 2);
        assertEquals(result.field("foo").schema().fields().get(0).name(), "bar");
        assertEquals(result.field("foo").schema().fields().get(1).name(), "other");
    }

    @SuppressWarnings("unchecked")
    @Test void shouldUpdateNestedValueFromSchemaless() {
        final Map<String, Object> value = Collections.singletonMap("foo", Collections.singletonMap("bar", 42));

        final Map<String, Object> updated = FieldPath.from("foo.bar", FieldSyntaxVersion.V2)
            .updateValueAt(value, (map, f, v) -> map.put(f, ((Integer) v) * 2));
        assertEquals(84, ((Map<String, Object>) updated.get("foo")).get("bar"));
    }

    @Test void shouldUpdateNestedValueWithSchema() {
        final SchemaBuilder barSchema = SchemaBuilder.struct().field("bar", Schema.INT32_SCHEMA);
        final Schema schema = SchemaBuilder.struct().field("foo", barSchema).build();
        final Struct value = new Struct(schema).put("foo", new Struct(barSchema).put("bar", 42));

        final Struct updated = FieldPath.from("foo.bar", FieldSyntaxVersion.V2)
            .updateValueAt(value, schema, (s, f, v) -> s.put(f, ((Integer) v) * 2));
        assertEquals(84, updated.getStruct("foo").getInt32("bar"));
    }
}