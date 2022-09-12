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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;

public class FieldPath {

    private static final String BACKTICK = "`";
    private static final String DOT = ".";
    public static final char BACKTICK_CHAR = '`';
    public static final char DOT_CHAR = '.';
    public static final char BACKSLASH_CHAR = '\\';

    private static final Map<String, FieldPath> PATHS_CACHE = new HashMap<>();

    private final String[] path;

    public static FieldPath from(String pathText) {
        if (PATHS_CACHE.containsKey(pathText)) {
            return PATHS_CACHE.get(pathText);
        } else {
            final FieldPath fieldPath = new FieldPath(pathText);
            PATHS_CACHE.put(pathText, fieldPath);
            return fieldPath;
        }
    }

    FieldPath(String path) {
        if (path == null || path.isEmpty()) { // empty path
            this.path = new String[] {};
        } else {
            if (!path.contains(DOT) &&
                !(path.startsWith(BACKTICK) && path.endsWith(BACKTICK))) { // does not need path steps
                this.path = new String[] {path};
            } else {
                // track fields in path steps
                List<String> steps = new ArrayList<>();
                // reuse string bits, will shrink as path is built
                StringBuilder s = new StringBuilder(path);

                while (s.length() > 0) {
                    if (s.charAt(0) == BACKTICK_CHAR) { // has opening backtick pair
                        s.deleteCharAt(0);

                        // find backtick pair
                        int idx = 0;
                        while (idx >= 0) {
                            idx = s.indexOf(BACKTICK, idx);
                            if (idx == -1) {
                                throw new IllegalArgumentException(
                                    "Incomplete backtick pair at [...]`" + s);
                            }
                            if (idx != s.length() - 1) { // non-global backtick
                                if (s.charAt(idx + 1) != DOT_CHAR ||
                                    s.charAt(idx - 1) == BACKSLASH_CHAR) { // not wrapped or escaped
                                    idx++; // move index forward and keep searching
                                } else { // it's end pair
                                    steps.add(checkIncompleteBacktickPair(s.substring(0, idx)));
                                    s.delete(0, idx + 2); // rm backtick and dot
                                    break;
                                }
                            } else { // global backtick
                                steps.add(checkIncompleteBacktickPair(s.substring(0, idx)));
                                s.delete(0, s.length());
                                break;
                            }
                        }
                    } else { // by dots
                        final int atDot = s.indexOf(DOT);
                        if (atDot > 0) { // get step and move forward
                            steps.add(checkIncompleteBacktickPair(s.substring(0, atDot)));
                            s.delete(0, atDot + 1);
                        } else { // add all
                            steps.add(checkIncompleteBacktickPair(s.toString()));
                            s.delete(0, s.length());
                        }
                    }
                }

                this.path = steps.toArray(new String[0]);
            }
        }
    }

    private String checkIncompleteBacktickPair(String field) {
        StringBuilder s = new StringBuilder(field);
        int idx = 0;
        while (idx >= 0) {
            idx = s.indexOf(BACKTICK, idx + 1);
            if (idx >= 1 && s.length() > 2) {
                if (s.charAt(idx - 1) == DOT_CHAR
                    || (idx < s.length() - 1 && s.charAt(idx + 1) == DOT_CHAR
                    && s.charAt(idx - 1) != BACKSLASH_CHAR)) {
                    throw new IllegalArgumentException("Incomplete backtick pair at [...]" + field);
                }
                if (s.charAt(idx - 1) == BACKSLASH_CHAR) { // escape backtick
                    if (s.charAt(idx + 1) == DOT_CHAR || // before a dot
                        s.charAt(idx - 2) == DOT_CHAR || // after a dot
                        (idx == 1 && s.charAt(0) == BACKSLASH_CHAR) // at the beginning
                        || idx == s.length() - 1) { // at the end
                        s.deleteCharAt(idx - 1);
                    }
                }
            }
        }
        return s.toString();
    }

    public Field fieldAt(Schema schema) {
        Schema current = schema;
        for (int i = 0; i < path.length; i++) {
            if (current == null) return null;
            if (i == path.length - 1) { // get value
                return current.field(path[i]);
            } else { // iterate
                current = current.field(path[i]).schema();
            }
        }
        return null;
    }

    public Object valueAt(Struct struct) {
        Struct current = struct;
        for (int i = 0; i < path.length; i++) {
            if (current == null) return null;
            if (i == path.length - 1) { // get value
                return current.get(path[i]);
            } else { // iterate
                current = current.getStruct(path[i]);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Object valueAt(Map<String, Object> map) {
        Map<String, Object> current = new HashMap<>(map);
        for (int i = 0; i < path.length; i++) {
            if (current == null) return null;
            if (i == path.length - 1) {
                return current.get(path[i]);
            } else {
                current = (Map<String, Object>) current.get(path[i]);
            }
        }
        return null;
    }

    public String[] path() {
        return Arrays.copyOf(path, path.length);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", FieldPath.class.getSimpleName() + "[", "]")
            .add("path=" + Arrays.toString(path))
            .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FieldPath fieldPath = (FieldPath) o;
        return Arrays.equals(path, fieldPath.path);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(path);
    }

    public static void main(String[] args) {
        FieldPath p1 = new FieldPath("foo.bar.baz");
        System.out.println(Arrays.toString(p1.path));
        FieldPath p2 = new FieldPath("foo.`bar.baz`");
        System.out.println(Arrays.toString(p2.path));
        FieldPath p3 = new FieldPath("foo.`bar`.baz");
        System.out.println(Arrays.toString(p3.path));
        FieldPath p4 = new FieldPath("foo.ba`r.baz");
        System.out.println(Arrays.toString(p4.path));
        FieldPath p5 = new FieldPath("foo.`bar\\`.\\`baz`");
        System.out.println(Arrays.toString(p5.path));
        FieldPath p6 = new FieldPath("foo.`b`ar.baz`");
        System.out.println(Arrays.toString(p6.path));
        FieldPath p7 = new FieldPath("foo.`bar\\\\`.\\`baz`");
        System.out.println(Arrays.toString(p7.path));
        FieldPath p8 = new FieldPath("foo.``bar``.baz");
        System.out.println(Arrays.toString(p8.path));
    }
}
