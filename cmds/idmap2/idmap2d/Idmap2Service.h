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

#ifndef IDMAP2_IDMAP2D_IDMAP2SERVICE_H_
#define IDMAP2_IDMAP2D_IDMAP2SERVICE_H_

#include <android-base/unique_fd.h>
#include <android/os/BnIdmap2.h>
#include <android/os/FabricatedOverlayInfo.h>
#include <binder/BinderService.h>
#include <idmap2/ResourceContainer.h>
#include <idmap2/Result.h>

#include <filesystem>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <variant>
#include <vector>

namespace android::os {

class Idmap2Service : public BinderService<Idmap2Service>, public BnIdmap2 {
 public:
  static char const* getServiceName() {
    return "idmap";
  }

  binder::Status getIdmapPath(const std::string& overlay_path, int32_t user_id,
                              std::string* _aidl_return) override;

  binder::Status removeIdmap(const std::string& overlay_path, int32_t user_id,
                             bool* _aidl_return) override;

  binder::Status verifyIdmap(const std::string& target_path, const std::string& overlay_path,
                             const std::string& overlay_name, int32_t fulfilled_policies,
                             bool enforce_overlayable, int32_t user_id,
                             bool* _aidl_return) override;

  binder::Status createIdmap(const std::string& target_path, const std::string& overlay_path,
                             const std::string& overlay_name, int32_t fulfilled_policies,
                             bool enforce_overlayable, int32_t user_id,
                             std::optional<std::string>* _aidl_return) override;

  binder::Status createFabricatedOverlay(
      const os::FabricatedOverlayInternal& overlay,
      std::optional<os::FabricatedOverlayInfo>* _aidl_return) override;

  binder::Status deleteFabricatedOverlay(const std::string& overlay_path,
                                         bool* _aidl_return) override;

  binder::Status acquireFabricatedOverlayIterator(int32_t* _aidl_return) override;

  binder::Status releaseFabricatedOverlayIterator(int32_t iteratorId) override;

  binder::Status nextFabricatedOverlayInfos(int32_t iteratorId,
      std::vector<os::FabricatedOverlayInfo>* _aidl_return) override;

  binder::Status dumpIdmap(const std::string& overlay_path, std::string* _aidl_return) override;

 private:
  // idmap2d is killed after a period of inactivity, so any information stored on this class should
  // be able to be recalculated if idmap2 dies and restarts.
  std::unique_ptr<idmap2::TargetResourceContainer> framework_apk_cache_;

  int32_t frro_iter_id_ = 0;
  std::optional<std::filesystem::directory_iterator> frro_iter_;
  std::mutex frro_iter_mutex_;

  template <typename T>
  using MaybeUniquePtr = std::variant<std::unique_ptr<T>, T*>;

  using TargetResourceContainerPtr = MaybeUniquePtr<idmap2::TargetResourceContainer>;
  idmap2::Result<TargetResourceContainerPtr> GetTargetContainer(const std::string& target_path);

  template <typename T>
  WARN_UNUSED static const T* GetPointer(const MaybeUniquePtr<T>& ptr);
};

template <typename T>
const T* Idmap2Service::GetPointer(const MaybeUniquePtr<T>& ptr) {
  auto u = std::get_if<T*>(&ptr);
  if (u != nullptr) {
    return *u;
  }
  return std::get<std::unique_ptr<T>>(ptr).get();
}

}  // namespace android::os

#endif  // IDMAP2_IDMAP2D_IDMAP2SERVICE_H_
