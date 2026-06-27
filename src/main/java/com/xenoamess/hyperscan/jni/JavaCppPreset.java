/*
 * Copyright (C) 2020 Dmytro Maksutov
 *
 * Licensed either under the Apache License, Version 2.0, or (at your option)
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation (subject to the "Classpath" exception),
 * either version 2, or any later version (collectively, the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     http://www.gnu.org/licenses/
 *     http://www.gnu.org/software/classpath/license.html
 *
 * or as provided in the LICENSE.txt file that accompanied this code.
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xenoamess.hyperscan.jni;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.presets.javacpp;
import org.bytedeco.javacpp.tools.Info;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.InfoMapper;

@Properties(
        inherit = javacpp.class,
        value = {
                @Platform(
                        value = {
                                "linux-x86_64",
                                "linux-x86_64-avx2",
                                "linux-x86_64-baseline",
                                "linux-arm64",
                                "linux-arm64-baseline",
                                "windows-x86_64",
                                "windows-x86_64-baseline",
                                "macosx-x86_64",
                                "macosx-arm64"
                        },
                        compiler = "cpp11",
                        include = {"hs/hs_common.h", "hs/hs_compile.h", "hs/hs_runtime.h", "hs/hs.h"},
                        link = {"hs", "hs_runtime"}
                )
        },
        target = "com.xenoamess.hyperscan.jni",
        global = "com.xenoamess.hyperscan.jni.hyperscan"
)
public class JavaCppPreset implements InfoMapper {
    public void map(InfoMap infoMap) {
        infoMap.put(new Info("HS_CDECL").cppTypes().annotations());
        // The Intel Hyperscan Windows build does not export hs_compile_lit* in
        // the hs static library, causing link errors. They are not used by the
        // wrapper, so skip them for all platforms.
        infoMap.put(new Info("hs_compile_lit", "hs_compile_lit_multi").skip());
    }
}