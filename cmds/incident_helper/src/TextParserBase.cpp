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

#define LOG_TAG "incident_helper"

#include "TextParserBase.h"

#include <android-base/file.h>

using namespace android::base;

// ================================================================================
status_t NoopParser::Parse(const int in, const int out) const
{
    string content;
    if (!ReadFdToString(in, &content)) {
        fprintf(stderr, "[%s]Failed to read data from incidentd\n", this->name.c_str());
        return -1;
    }
    if (!WriteStringToFd(content, out)) {
        fprintf(stderr, "[%s]Failed to write data to incidentd\n", this->name.c_str());
        return -1;
    }
    return NO_ERROR;
}

// ================================================================================
status_t ReverseParser::Parse(const int in, const int out) const
{
    string content;
    if (!ReadFdToString(in, &content)) {
        fprintf(stderr, "[%s]Failed to read data from incidentd\n", this->name.c_str());
        return -1;
    }
    // reverse the content
    reverse(content.begin(), content.end());
    if (!WriteStringToFd(content, out)) {
        fprintf(stderr, "[%s]Failed to write data to incidentd\n", this->name.c_str());
        return -1;
    }
    return NO_ERROR;
}