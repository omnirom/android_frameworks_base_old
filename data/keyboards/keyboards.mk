# Copyright (C) 2010 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Warning: this is actually a product definition, to be inherited from

include $(LOCAL_PATH)/common.mk

PRODUCT_COPY_FILES := $(foreach file,$(framework_keylayouts),\
    $(file):system/usr/keylayout/$(notdir $(file)))

PRODUCT_COPY_FILES += $(foreach file,$(framework_keycharmaps),\
    $(file):system/usr/keychars/$(notdir $(file)))

PRODUCT_COPY_FILES += $(foreach file,$(framework_keyconfigs),\
    $(file):system/usr/idc/$(notdir $(file)))
