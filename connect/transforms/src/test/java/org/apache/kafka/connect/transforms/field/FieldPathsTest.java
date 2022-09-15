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

import org.junit.jupiter.api.Test;

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

    //TODO failing
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
}