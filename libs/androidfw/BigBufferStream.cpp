/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "androidfw/BigBufferStream.h"

#include <algorithm>

namespace android {

//
// BigBufferInputStream
//

bool BigBufferInputStream::Next(const void** data, size_t* size) {
  if (iter_ == buffer_->end()) {
    return false;
  }

  if (offset_ == iter_->size) {
    ++iter_;
    if (iter_ == buffer_->end()) {
      return false;
    }
    offset_ = 0;
  }

  *data = iter_->buffer.get() + offset_;
  *size = iter_->size - offset_;
  bytes_read_ += iter_->size - offset_;
  offset_ = iter_->size;
  return true;
}

void BigBufferInputStream::BackUp(size_t count) {
  if (count > offset_) {
    bytes_read_ -= offset_;
    offset_ = 0;
  } else {
    offset_ -= count;
    bytes_read_ -= count;
  }
}

bool BigBufferInputStream::CanRewind() const {
  return true;
}

bool BigBufferInputStream::Rewind() {
  iter_ = buffer_->begin();
  offset_ = 0;
  bytes_read_ = 0;
  return true;
}

size_t BigBufferInputStream::ByteCount() const {
  return bytes_read_;
}

bool BigBufferInputStream::HadError() const {
  return false;
}

size_t BigBufferInputStream::TotalSize() const {
  return buffer_->size();
}

bool BigBufferInputStream::ReadFullyAtOffset(void* data, size_t byte_count, off64_t offset) {
  if (byte_count == 0) {
    return true;
  }
  if (offset < 0) {
    return false;
  }
  if (offset > std::numeric_limits<off64_t>::max() - byte_count) {
    return false;
  }
  if (offset + byte_count > buffer_->size()) {
    return false;
  }
  auto p = reinterpret_cast<uint8_t*>(data);
  for (auto iter = buffer_->begin(); iter != buffer_->end() && byte_count > 0; ++iter) {
    if (offset < iter->size) {
      size_t to_read = std::min(byte_count, (size_t)(iter->size - offset));
      memcpy(p, iter->buffer.get() + offset, to_read);
      byte_count -= to_read;
      p += to_read;
      offset = 0;
    } else {
      offset -= iter->size;
    }
  }
  return byte_count == 0;
}

//
// BigBufferOutputStream
//

bool BigBufferOutputStream::Next(void** data, size_t* size) {
  *data = buffer_->NextBlock(size);
  return true;
}

void BigBufferOutputStream::BackUp(size_t count) {
  buffer_->BackUp(count);
}

size_t BigBufferOutputStream::ByteCount() const {
  return buffer_->size();
}

bool BigBufferOutputStream::HadError() const {
  return false;
}

}  // namespace android
