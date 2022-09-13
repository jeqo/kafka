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
package org.apache.kafka.connect.transforms.util;

import static org.apache.kafka.connect.transforms.util.FieldSyntaxVersion.V1;
import static org.apache.kafka.connect.transforms.util.FieldSyntaxVersion.V2;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.Test;

class FieldPathTest {
    final static String[] EMPTY_PATH = new String[]{};

    @Test void shouldHandleV1WithDotsAndBacktickPair() {
        assertArrayEquals(new String[] {"foo.bar.baz"}, FieldPath.from("foo.bar.baz", V1).path());
        assertArrayEquals(new String[] {"foo.`bar.baz`"}, FieldPath.from("foo.`bar.baz`", V1).path());
    }

    @Test void testEmptyPath() {
        assertArrayEquals(EMPTY_PATH, FieldPath.from("", V2).path());
    }

    @Test void testNullPath() {
        assertArrayEquals(EMPTY_PATH, FieldPath.from(null, V2).path());
    }

    @Test void testWithoutDots() {
        assertArrayEquals(new String[] {"foobarbaz"}, FieldPath.from("foobarbaz", V2).path());
    }
    @Test void testWithoutWrappingBackticks() {
        assertArrayEquals(new String[] {"foo`bar`baz"}, FieldPath.from("foo`bar`baz", V2).path());
    }

    @Test void shouldBuildPathWhenIncludesDots() {
        assertArrayEquals(new String[] {"foo", "bar", "baz"}, FieldPath.from("foo.bar.baz", V2).path());
    }

    @Test void shouldBuildPathWhenIncludesDotsAndBacktickPair() {
        assertArrayEquals(new String[] {"foo", "bar.baz"}, FieldPath.from("foo.`bar.baz`", V2).path());
        assertArrayEquals(new String[] {"foo", "bar", "baz"}, FieldPath.from("foo.`bar`.baz", V2).path());
    }

    @Test void shouldBuildPathAndIgnoreBackticksThatAreNotWrapping() {
        assertArrayEquals(new String[] {"foo", "ba`r.baz"}, FieldPath.from("foo.`ba`r.baz`", V2).path());
        assertArrayEquals(new String[] {"foo", "ba`r", "baz"}, FieldPath.from("foo.ba`r.baz", V2).path());
    }

    @Test void shouldBuildPathAndEscapeBackticks() {
        assertArrayEquals(new String[] {"foo", "bar`.`baz"}, FieldPath.from("foo.`bar\\`.\\`baz`", V2).path());
        assertArrayEquals(new String[] {"foo", "bar\\`.`baz"}, FieldPath.from("foo.`bar\\\\`.\\`baz`", V2).path());
    }

    @Test void shouldBuildPathWithoutWrappingBackticks() {
        assertArrayEquals(new String[] {"foo", "`bar`", "baz"}, FieldPath.from("foo.``bar``.baz", V2).path());
        assertArrayEquals(new String[] {"`foo.bar.baz`"}, FieldPath.from("``foo.bar.baz``", V2).path());
    }

    @Test void shouldFilterSchemaFields() {
        Schema schema = SchemaBuilder.struct().field("foo",
            SchemaBuilder.struct().field("bar", Schema.STRING_SCHEMA)
                .field("baz", Schema.INT32_SCHEMA))
            .build();
        Schema result = FieldPath.from("foo.baz", V2).updateSchemaAt(schema, (builder, field) -> {
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
        Schema result = FieldPath.from("foo.baz", V2)
            .updateSchemaAt(schema, (builder, field) -> builder.field("other", field.schema()));
        assertEquals(result.fields().size(), 1);
        assertEquals(result.field("foo").schema().fields().size(), 2);
        assertEquals(result.field("foo").schema().fields().get(0).name(), "bar");
        assertEquals(result.field("foo").schema().fields().get(1).name(), "other");
    }
}