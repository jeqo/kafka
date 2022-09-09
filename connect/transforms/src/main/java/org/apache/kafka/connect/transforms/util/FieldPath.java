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
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;

public class FieldPath {

    private static final Map<String, FieldPath> PATHS_CACHE = new ConcurrentHashMap<>();
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

    public FieldPath(String path) {
        if (path == null || path.isEmpty()) {
            this.path = new String[] {};
        } else {
            if (!path.contains(".")) {
                this.path = new String[] {path};
            } else {
                List<String> fields = new ArrayList<>();
                StringBuilder s = new StringBuilder(path);
                while (s.length() > 0) {
                    if (s.charAt(0) == '`') { // opening backtick pair
                        s.deleteCharAt(0);
                        int idx = 0;
                        int lastIdx = s.length() - 1;
                        while (idx <= lastIdx) {
                            final int atBacktick = s.indexOf("`", idx);
                            if (atBacktick == -1) {
                                throw new IllegalArgumentException(
                                    "incomplete backtick pair at " + s);
                            }
                            if (atBacktick != lastIdx) { // non-global backtick
                                if (s.charAt(atBacktick + 1) != '.') { // not wrapping
                                    idx = atBacktick + 1; // move index forward and keep searching
                                } else if (s.charAt(atBacktick - 1) == '\\') { // escaped
                                    idx = atBacktick + 1; // move index forward and keep searching
                                } else {
                                    fields.add(checkIncompleteBackticksPair(s.substring(0, atBacktick))); // get field
                                    idx = lastIdx + 1; // go out
                                    s.delete(0, atBacktick + 2); // rm backtick and dot
                                }
                            } else { // global backtick
                                fields.add(checkIncompleteBackticksPair(s.substring(0, atBacktick))); // get within backticks
                                idx = lastIdx + 1; // go out
                                s.delete(0, atBacktick + 1);
                            }
                        }
                    } else {
                        // by dots
                        final int atDot = s.indexOf(".");
                        if (atDot > 0) { // get and move forward
                            fields.add(checkIncompleteBackticksPair(s.substring(0, atDot)));
                            s.delete(0, atDot + 1);
                        } else { // add all
                            fields.add(checkIncompleteBackticksPair(s.toString()));
                            s.delete(0, s.length());
                        }
                    }
                }
                this.path = fields.toArray(new String[0]);
            }
        }
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

    private String checkIncompleteBackticksPair(String field) {
        StringBuilder s = new StringBuilder(field);
        int idx = 0;
        while (idx >= 0) {
            idx = s.indexOf("`", idx + 1);
            if (idx >= 1 && s.length() > 2) {
                if (s.charAt(idx - 1) == '.' || (idx < s.length() - 1 && s.charAt(idx + 1) == '.' && s.charAt(idx - 1) != '\\')) {
                    throw new IllegalArgumentException("incomplete backtick pair at " + field);
                }
                if (s.charAt(idx - 1) == '\\') {
                    if (s.charAt(idx + 1) == '.' || // before a dot
                        s.charAt(idx - 2) == '.' || // after a dot
                        (idx == 1 && s.charAt(0) == '\\') // at the beginning
                        || idx == s.length() - 1) { // at the end
                        s.deleteCharAt(idx - 1);
                    }
                }
            }
        }
        return s.toString();
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
