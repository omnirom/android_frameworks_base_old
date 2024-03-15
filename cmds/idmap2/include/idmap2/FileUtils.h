/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef IDMAP2_INCLUDE_IDMAP2_FILEUTILS_H_
#define IDMAP2_INCLUDE_IDMAP2_FILEUTILS_H_

#include <sys/types.h>

#include <string>
#include <string_view>

namespace android::idmap2::utils {

constexpr std::string_view kIdmapCacheDir = "/data/resource-cache";
constexpr const mode_t kIdmapFilePermissionMask = 0133;  // u=rw,g=r,o=r

bool UidHasWriteAccessToPath(uid_t uid, const std::string& path);

std::string RandomStringForPath(size_t length);

}  // namespace android::idmap2::utils

#endif  // IDMAP2_INCLUDE_IDMAP2_FILEUTILS_H_
