/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "ResourceUtils.h"

#include <algorithm>
#include <sstream>

#include "android-base/stringprintf.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/ResourceUtils.h"

#include "NameMangler.h"
#include "SdkConstants.h"
#include "format/binary/ResourceTypeExtensions.h"
#include "text/Unicode.h"
#include "text/Utf8Iterator.h"
#include "util/Files.h"
#include "util/Util.h"

using ::aapt::text::Utf8Iterator;
using ::android::ConfigDescription;
using ::android::StringPiece;
using ::android::StringPiece16;
using ::android::base::StringPrintf;

namespace aapt {
namespace ResourceUtils {

static std::optional<ResourceNamedType> ToResourceNamedType(const char16_t* type16,
                                                            const char* type, size_t type_len) {
  std::optional<ResourceNamedTypeRef> parsed_type;
  std::string converted;
  if (type16) {
    converted = android::util::Utf16ToUtf8(StringPiece16(type16, type_len));
    parsed_type = ParseResourceNamedType(converted);
  } else if (type) {
    parsed_type = ParseResourceNamedType(StringPiece(type, type_len));
  } else {
    return {};
  }
  if (!parsed_type) {
    return {};
  }
  return parsed_type->ToResourceNamedType();
}

std::optional<ResourceName> ToResourceName(const android::ResTable::resource_name& name_in) {
  // TODO: Remove this when ResTable and AssetManager(1) are removed from AAPT2
  ResourceName name_out;
  if (!name_in.package) {
    return {};
  }

  name_out.package = android::util::Utf16ToUtf8(StringPiece16(name_in.package, name_in.packageLen));

  std::optional<ResourceNamedType> type =
      ToResourceNamedType(name_in.type, name_in.name8, name_in.typeLen);
  if (!type) {
    return {};
  }
  name_out.type = *type;

  if (name_in.name) {
    name_out.entry = android::util::Utf16ToUtf8(StringPiece16(name_in.name, name_in.nameLen));
  } else if (name_in.name8) {
    name_out.entry.assign(name_in.name8, name_in.nameLen);
  } else {
    return {};
  }
  return name_out;
}

std::optional<ResourceName> ToResourceName(const android::AssetManager2::ResourceName& name_in) {
  ResourceName name_out;
  if (!name_in.package) {
    return {};
  }

  name_out.package = std::string(name_in.package, name_in.package_len);

  std::optional<ResourceNamedType> type =
      ToResourceNamedType(name_in.type16, name_in.type, name_in.type_len);
  if (!type) {
    return {};
  }
  name_out.type = *type;

  if (name_in.entry16) {
    name_out.entry = android::util::Utf16ToUtf8(StringPiece16(name_in.entry16, name_in.entry_len));
  } else if (name_in.entry) {
    name_out.entry = std::string(name_in.entry, name_in.entry_len);
  } else {
    return {};
  }
  return name_out;
}

bool ParseResourceName(StringPiece str, ResourceNameRef* out_ref, bool* out_private) {
  if (str.empty()) {
    return false;
  }

  size_t offset = 0;
  bool priv = false;
  if (str.data()[0] == '*') {
    priv = true;
    offset = 1;
  }

  StringPiece package;
  StringPiece type;
  StringPiece entry;
  if (!android::ExtractResourceName(str.substr(offset, str.size() - offset), &package, &type,
                                    &entry)) {
    return false;
  }

  std::optional<ResourceNamedTypeRef> parsed_type = ParseResourceNamedType(type);
  if (!parsed_type) {
    return false;
  }

  if (entry.empty()) {
    return false;
  }

  if (out_ref) {
    out_ref->package = package;
    out_ref->type = *parsed_type;
    out_ref->entry = entry;
  }

  if (out_private) {
    *out_private = priv;
  }
  return true;
}

bool ParseReference(StringPiece str, ResourceNameRef* out_ref, bool* out_create,
                    bool* out_private) {
  StringPiece trimmed_str(util::TrimWhitespace(str));
  if (trimmed_str.empty()) {
    return false;
  }

  bool create = false;
  bool priv = false;
  if (trimmed_str.data()[0] == '@') {
    size_t offset = 1;
    if (trimmed_str.data()[1] == '+') {
      create = true;
      offset += 1;
    }

    ResourceNameRef name;
    if (!ParseResourceName(
            trimmed_str.substr(offset, trimmed_str.size() - offset), &name,
            &priv)) {
      return false;
    }

    if (create && priv) {
      return false;
    }

    if (create && name.type.type != ResourceType::kId) {
      return false;
    }

    if (out_ref) {
      *out_ref = name;
    }

    if (out_create) {
      *out_create = create;
    }

    if (out_private) {
      *out_private = priv;
    }
    return true;
  }
  return false;
}

bool IsReference(StringPiece str) {
  return ParseReference(str, nullptr, nullptr, nullptr);
}

bool ParseAttributeReference(StringPiece str, ResourceNameRef* out_ref) {
  StringPiece trimmed_str(util::TrimWhitespace(str));
  if (trimmed_str.empty()) {
    return false;
  }

  if (*trimmed_str.data() == '?') {
    StringPiece package;
    StringPiece type;
    StringPiece entry;
    if (!android::ExtractResourceName(trimmed_str.substr(1, trimmed_str.size() - 1), &package,
                                      &type, &entry)) {
      return false;
    }

    if (!type.empty() && type != "attr") {
      return false;
    }

    if (entry.empty()) {
      return false;
    }

    if (out_ref) {
      out_ref->package = package;
      out_ref->type = ResourceNamedTypeWithDefaultName(ResourceType::kAttr);
      out_ref->entry = entry;
    }
    return true;
  }
  return false;
}

bool IsAttributeReference(StringPiece str) {
  return ParseAttributeReference(str, nullptr);
}

/*
 * Style parent's are a bit different. We accept the following formats:
 *
 * @[[*]package:][style/]<entry>
 * ?[[*]package:]style/<entry>
 * <[*]package>:[style/]<entry>
 * [[*]package:style/]<entry>
 */
std::optional<Reference> ParseStyleParentReference(StringPiece str, std::string* out_error) {
  if (str.empty()) {
    return {};
  }

  StringPiece name = str;

  bool has_leading_identifiers = false;
  bool private_ref = false;

  // Skip over these identifiers. A style's parent is a normal reference.
  if (name.data()[0] == '@' || name.data()[0] == '?') {
    has_leading_identifiers = true;
    name = name.substr(1, name.size() - 1);
  }

  if (name.data()[0] == '*') {
    private_ref = true;
    name = name.substr(1, name.size() - 1);
  }

  ResourceNameRef ref;
  ref.type = ResourceNamedTypeWithDefaultName(ResourceType::kStyle);

  StringPiece type_str;
  android::ExtractResourceName(name, &ref.package, &type_str, &ref.entry);
  if (!type_str.empty()) {
    // If we have a type, make sure it is a Style.
    const ResourceType* parsed_type = ParseResourceType(type_str);
    if (!parsed_type || *parsed_type != ResourceType::kStyle) {
      std::stringstream err;
      err << "invalid resource type '" << type_str << "' for parent of style";
      *out_error = err.str();
      return {};
    }
  }

  if (!has_leading_identifiers && ref.package.empty() && !type_str.empty()) {
    std::stringstream err;
    err << "invalid parent reference '" << str << "'";
    *out_error = err.str();
    return {};
  }

  Reference result(ref);
  result.private_reference = private_ref;
  return result;
}

std::optional<Reference> ParseXmlAttributeName(StringPiece str) {
  StringPiece trimmed_str = util::TrimWhitespace(str);
  const char* start = trimmed_str.data();
  const char* const end = start + trimmed_str.size();
  const char* p = start;

  Reference ref;
  if (p != end && *p == '*') {
    ref.private_reference = true;
    start++;
    p++;
  }

  StringPiece package;
  StringPiece name;
  while (p != end) {
    if (*p == ':') {
      package = StringPiece(start, p - start);
      name = StringPiece(p + 1, end - (p + 1));
      break;
    }
    p++;
  }

  ref.name = ResourceName(package, ResourceNamedTypeWithDefaultName(ResourceType::kAttr),
                          name.empty() ? trimmed_str : name);
  return std::optional<Reference>(std::move(ref));
}

std::unique_ptr<Reference> TryParseReference(StringPiece str, bool* out_create) {
  ResourceNameRef ref;
  bool private_ref = false;
  if (ParseReference(str, &ref, out_create, &private_ref)) {
    std::unique_ptr<Reference> value = util::make_unique<Reference>(ref);
    value->private_reference = private_ref;
    return value;
  }

  if (ParseAttributeReference(str, &ref)) {
    if (out_create) {
      *out_create = false;
    }
    return util::make_unique<Reference>(ref, Reference::Type::kAttribute);
  }
  return {};
}

std::unique_ptr<Item> TryParseNullOrEmpty(StringPiece str) {
  const StringPiece trimmed_str(util::TrimWhitespace(str));
  if (trimmed_str == "@null") {
    return MakeNull();
  } else if (trimmed_str == "@empty") {
    return MakeEmpty();
  }
  return {};
}

std::unique_ptr<Reference> MakeNull() {
  // TYPE_NULL with data set to 0 is interpreted by the runtime as an error.
  // Instead we set the data type to TYPE_REFERENCE with a value of 0.
  return util::make_unique<Reference>();
}

std::unique_ptr<BinaryPrimitive> MakeEmpty() {
  return util::make_unique<BinaryPrimitive>(android::Res_value::TYPE_NULL,
                                            android::Res_value::DATA_NULL_EMPTY);
}

std::unique_ptr<BinaryPrimitive> TryParseEnumSymbol(const Attribute* enum_attr, StringPiece str) {
  StringPiece trimmed_str(util::TrimWhitespace(str));
  for (const Attribute::Symbol& symbol : enum_attr->symbols) {
    // Enum symbols are stored as @package:id/symbol resources,
    // so we need to match against the 'entry' part of the identifier.
    const ResourceName& enum_symbol_resource_name = symbol.symbol.name.value();
    if (trimmed_str == enum_symbol_resource_name.entry) {
      android::Res_value value = {};
      value.dataType = symbol.type;
      value.data = symbol.value;
      return util::make_unique<BinaryPrimitive>(value);
    }
  }
  return {};
}

std::unique_ptr<BinaryPrimitive> TryParseFlagSymbol(const Attribute* flag_attr, StringPiece str) {
  android::Res_value flags = {};
  flags.dataType = android::Res_value::TYPE_INT_HEX;
  flags.data = 0u;

  if (util::TrimWhitespace(str).empty()) {
    // Empty string is a valid flag (0).
    return util::make_unique<BinaryPrimitive>(flags);
  }

  for (StringPiece part : util::Tokenize(str, '|')) {
    StringPiece trimmed_part = util::TrimWhitespace(part);

    bool flag_set = false;
    for (const Attribute::Symbol& symbol : flag_attr->symbols) {
      // Flag symbols are stored as @package:id/symbol resources,
      // so we need to match against the 'entry' part of the identifier.
      const ResourceName& flag_symbol_resource_name =
          symbol.symbol.name.value();
      if (trimmed_part == flag_symbol_resource_name.entry) {
        flags.data |= symbol.value;
        flag_set = true;
        break;
      }
    }

    if (!flag_set) {
      return {};
    }
  }
  return util::make_unique<BinaryPrimitive>(flags);
}

static uint32_t ParseHex(char c, bool* out_error) {
  if (c >= '0' && c <= '9') {
    return c - '0';
  } else if (c >= 'a' && c <= 'f') {
    return c - 'a' + 0xa;
  } else if (c >= 'A' && c <= 'F') {
    return c - 'A' + 0xa;
  } else {
    *out_error = true;
    return 0xffffffffu;
  }
}

std::unique_ptr<BinaryPrimitive> TryParseColor(StringPiece str) {
  StringPiece color_str(util::TrimWhitespace(str));
  const char* start = color_str.data();
  const size_t len = color_str.size();
  if (len == 0 || start[0] != '#') {
    return {};
  }

  android::Res_value value = {};
  bool error = false;
  if (len == 4) {
    value.dataType = android::Res_value::TYPE_INT_COLOR_RGB4;
    value.data = 0xff000000u;
    value.data |= ParseHex(start[1], &error) << 20;
    value.data |= ParseHex(start[1], &error) << 16;
    value.data |= ParseHex(start[2], &error) << 12;
    value.data |= ParseHex(start[2], &error) << 8;
    value.data |= ParseHex(start[3], &error) << 4;
    value.data |= ParseHex(start[3], &error);
  } else if (len == 5) {
    value.dataType = android::Res_value::TYPE_INT_COLOR_ARGB4;
    value.data |= ParseHex(start[1], &error) << 28;
    value.data |= ParseHex(start[1], &error) << 24;
    value.data |= ParseHex(start[2], &error) << 20;
    value.data |= ParseHex(start[2], &error) << 16;
    value.data |= ParseHex(start[3], &error) << 12;
    value.data |= ParseHex(start[3], &error) << 8;
    value.data |= ParseHex(start[4], &error) << 4;
    value.data |= ParseHex(start[4], &error);
  } else if (len == 7) {
    value.dataType = android::Res_value::TYPE_INT_COLOR_RGB8;
    value.data = 0xff000000u;
    value.data |= ParseHex(start[1], &error) << 20;
    value.data |= ParseHex(start[2], &error) << 16;
    value.data |= ParseHex(start[3], &error) << 12;
    value.data |= ParseHex(start[4], &error) << 8;
    value.data |= ParseHex(start[5], &error) << 4;
    value.data |= ParseHex(start[6], &error);
  } else if (len == 9) {
    value.dataType = android::Res_value::TYPE_INT_COLOR_ARGB8;
    value.data |= ParseHex(start[1], &error) << 28;
    value.data |= ParseHex(start[2], &error) << 24;
    value.data |= ParseHex(start[3], &error) << 20;
    value.data |= ParseHex(start[4], &error) << 16;
    value.data |= ParseHex(start[5], &error) << 12;
    value.data |= ParseHex(start[6], &error) << 8;
    value.data |= ParseHex(start[7], &error) << 4;
    value.data |= ParseHex(start[8], &error);
  } else {
    return {};
  }
  return error ? std::unique_ptr<BinaryPrimitive>()
               : util::make_unique<BinaryPrimitive>(value);
}

std::optional<bool> ParseBool(StringPiece str) {
  StringPiece trimmed_str(util::TrimWhitespace(str));
  if (trimmed_str == "true" || trimmed_str == "TRUE" || trimmed_str == "True") {
    return std::optional<bool>(true);
  } else if (trimmed_str == "false" || trimmed_str == "FALSE" ||
             trimmed_str == "False") {
    return std::optional<bool>(false);
  }
  return {};
}

std::optional<uint32_t> ParseInt(StringPiece str) {
  std::u16string str16 = android::util::Utf8ToUtf16(str);
  android::Res_value value;
  if (android::ResTable::stringToInt(str16.data(), str16.size(), &value)) {
    return value.data;
  }
  return {};
}

std::optional<ResourceId> ParseResourceId(StringPiece str) {
  StringPiece trimmed_str(util::TrimWhitespace(str));

  std::u16string str16 = android::util::Utf8ToUtf16(trimmed_str);
  android::Res_value value;
  if (android::ResTable::stringToInt(str16.data(), str16.size(), &value)) {
    if (value.dataType == android::Res_value::TYPE_INT_HEX) {
      ResourceId id(value.data);
      if (id.is_valid()) {
        return id;
      }
    }
  }
  return {};
}

std::optional<int> ParseSdkVersion(StringPiece str) {
  StringPiece trimmed_str(util::TrimWhitespace(str));

  std::u16string str16 = android::util::Utf8ToUtf16(trimmed_str);
  android::Res_value value;
  if (android::ResTable::stringToInt(str16.data(), str16.size(), &value)) {
    return static_cast<int>(value.data);
  }

  // Try parsing the code name.
  std::optional<int> entry = GetDevelopmentSdkCodeNameVersion(trimmed_str);
  if (entry) {
    return entry.value();
  }

  // Try parsing codename from "[codename].[preview_sdk_fingerprint]" value.
  const StringPiece::const_iterator begin = std::begin(trimmed_str);
  const StringPiece::const_iterator end = std::end(trimmed_str);
  const StringPiece::const_iterator codename_end = std::find(begin, end, '.');
  entry = GetDevelopmentSdkCodeNameVersion(StringPiece(begin, codename_end - begin));
  if (entry) {
    return entry.value();
  }
  return {};
}

std::unique_ptr<BinaryPrimitive> TryParseBool(StringPiece str) {
  if (std::optional<bool> maybe_result = ParseBool(str)) {
    const uint32_t data = maybe_result.value() ? 0xffffffffu : 0u;
    return util::make_unique<BinaryPrimitive>(android::Res_value::TYPE_INT_BOOLEAN, data);
  }
  return {};
}

std::unique_ptr<BinaryPrimitive> MakeBool(bool val) {
  return util::make_unique<BinaryPrimitive>(android::Res_value::TYPE_INT_BOOLEAN,
                                            val ? 0xffffffffu : 0u);
}

std::unique_ptr<BinaryPrimitive> TryParseInt(StringPiece str) {
  std::u16string str16 = android::util::Utf8ToUtf16(util::TrimWhitespace(str));
  android::Res_value value;
  if (!android::ResTable::stringToInt(str16.data(), str16.size(), &value)) {
    return {};
  }
  return util::make_unique<BinaryPrimitive>(value);
}

std::unique_ptr<BinaryPrimitive> MakeInt(uint32_t val) {
  return util::make_unique<BinaryPrimitive>(android::Res_value::TYPE_INT_DEC, val);
}

std::unique_ptr<BinaryPrimitive> TryParseFloat(StringPiece str) {
  std::u16string str16 = android::util::Utf8ToUtf16(util::TrimWhitespace(str));
  android::Res_value value;
  if (!android::ResTable::stringToFloat(str16.data(), str16.size(), &value)) {
    return {};
  }
  return util::make_unique<BinaryPrimitive>(value);
}

uint32_t AndroidTypeToAttributeTypeMask(uint16_t type) {
  switch (type) {
    case android::Res_value::TYPE_NULL:
    case android::Res_value::TYPE_REFERENCE:
    case android::Res_value::TYPE_ATTRIBUTE:
    case android::Res_value::TYPE_DYNAMIC_REFERENCE:
    case android::Res_value::TYPE_DYNAMIC_ATTRIBUTE:
      return android::ResTable_map::TYPE_REFERENCE;

    case android::Res_value::TYPE_STRING:
      return android::ResTable_map::TYPE_STRING;

    case android::Res_value::TYPE_FLOAT:
      return android::ResTable_map::TYPE_FLOAT;

    case android::Res_value::TYPE_DIMENSION:
      return android::ResTable_map::TYPE_DIMENSION;

    case android::Res_value::TYPE_FRACTION:
      return android::ResTable_map::TYPE_FRACTION;

    case android::Res_value::TYPE_INT_DEC:
    case android::Res_value::TYPE_INT_HEX:
      return android::ResTable_map::TYPE_INTEGER |
             android::ResTable_map::TYPE_ENUM |
             android::ResTable_map::TYPE_FLAGS;

    case android::Res_value::TYPE_INT_BOOLEAN:
      return android::ResTable_map::TYPE_BOOLEAN;

    case android::Res_value::TYPE_INT_COLOR_ARGB8:
    case android::Res_value::TYPE_INT_COLOR_RGB8:
    case android::Res_value::TYPE_INT_COLOR_ARGB4:
    case android::Res_value::TYPE_INT_COLOR_RGB4:
      return android::ResTable_map::TYPE_COLOR;

    default:
      return 0;
  };
}

std::unique_ptr<Item> TryParseItemForAttribute(
    android::IDiagnostics* diag, StringPiece value, uint32_t type_mask,
    const std::function<bool(const ResourceName&)>& on_create_reference) {
  using android::ResTable_map;

  auto null_or_empty = TryParseNullOrEmpty(value);
  if (null_or_empty) {
    return null_or_empty;
  }

  bool create = false;
  auto reference = TryParseReference(value, &create);
  if (reference) {
    reference->type_flags = type_mask;
    if (create && on_create_reference) {
      if (!on_create_reference(reference->name.value())) {
        return {};
      }
    }
    return std::move(reference);
  }

  if (type_mask & ResTable_map::TYPE_COLOR) {
    // Try parsing this as a color.
    auto color = TryParseColor(value);
    if (color) {
      return std::move(color);
    }
  }

  if (type_mask & ResTable_map::TYPE_BOOLEAN) {
    // Try parsing this as a boolean.
    auto boolean = TryParseBool(value);
    if (boolean) {
      return std::move(boolean);
    }
  }

  if (type_mask & ResTable_map::TYPE_INTEGER) {
    // Try parsing this as an integer.
    auto integer = TryParseInt(value);
    if (integer) {
      return std::move(integer);
    }
  }

  const uint32_t float_mask =
      ResTable_map::TYPE_FLOAT | ResTable_map::TYPE_DIMENSION | ResTable_map::TYPE_FRACTION;
  if (type_mask & float_mask) {
    // Try parsing this as a float.
    auto floating_point = TryParseFloat(value);
    if (floating_point) {
      // Only check if the parsed result lost precision when the parsed item is
      // android::Res_value::TYPE_FLOAT and there is other possible types saved in type_mask, like
      // ResTable_map::TYPE_INTEGER.
      if (type_mask & AndroidTypeToAttributeTypeMask(floating_point->value.dataType)) {
        const bool mayOnlyBeFloat = (type_mask & ~float_mask) == 0;
        const bool parsedAsFloat = floating_point->value.dataType == android::Res_value::TYPE_FLOAT;
        if (!mayOnlyBeFloat && parsedAsFloat) {
          float f = reinterpret_cast<float&>(floating_point->value.data);
          std::u16string str16 = android::util::Utf8ToUtf16(util::TrimWhitespace(value));
          double d;
          if (android::ResTable::stringToDouble(str16.data(), str16.size(), d)) {
            // Parse as a float only if the difference between float and double parsed from the
            // same string is smaller than 1, otherwise return as raw string.
            if (fabs(f - d) < 1) {
              return std::move(floating_point);
            } else {
              if (diag->IsVerbose()) {
                diag->Note(android::DiagMessage()
                           << "precision lost greater than 1 while parsing float " << value
                           << ", return a raw string");
              }
            }
          }
        } else {
          return std::move(floating_point);
        }
      }
    }
  }
  return {};
}

/**
 * We successively try to parse the string as a resource type that the Attribute
 * allows.
 */
std::unique_ptr<Item> TryParseItemForAttribute(
    android::IDiagnostics* diag, StringPiece str, const Attribute* attr,
    const std::function<bool(const ResourceName&)>& on_create_reference) {
  using android::ResTable_map;

  const uint32_t type_mask = attr->type_mask;
  auto value = TryParseItemForAttribute(diag, str, type_mask, on_create_reference);
  if (value) {
    return value;
  }

  if (type_mask & ResTable_map::TYPE_ENUM) {
    // Try parsing this as an enum.
    auto enum_value = TryParseEnumSymbol(attr, str);
    if (enum_value) {
      return std::move(enum_value);
    }
  }

  if (type_mask & ResTable_map::TYPE_FLAGS) {
    // Try parsing this as a flag.
    auto flag_value = TryParseFlagSymbol(attr, str);
    if (flag_value) {
      return std::move(flag_value);
    }
  }
  return {};
}

std::string BuildResourceFileName(const ResourceFile& res_file, const NameMangler* mangler) {
  std::stringstream out;
  out << "res/" << res_file.name.type;
  if (res_file.config != ConfigDescription{}) {
    out << "-" << res_file.config;
  }
  out << "/";

  if (mangler && mangler->ShouldMangle(res_file.name.package)) {
    out << NameMangler::MangleEntry(res_file.name.package, res_file.name.entry);
  } else {
    out << res_file.name.entry;
  }
  out << file::GetExtension(res_file.source.path);
  return out.str();
}

std::unique_ptr<Item> ParseBinaryResValue(const ResourceType& type, const ConfigDescription& config,
                                          const android::ResStringPool& src_pool,
                                          const android::Res_value& res_value,
                                          android::StringPool* dst_pool) {
  if (type == ResourceType::kId) {
    if (res_value.dataType != android::Res_value::TYPE_REFERENCE &&
        res_value.dataType != android::Res_value::TYPE_DYNAMIC_REFERENCE) {
      // plain "id" resources are actually encoded as unused values (aapt1 uses an empty string,
      // while aapt2 uses a false boolean).
      return util::make_unique<Id>();
    }
    // fall through to regular reference deserialization logic
  }

  const uint32_t data = android::util::DeviceToHost32(res_value.data);
  switch (res_value.dataType) {
    case android::Res_value::TYPE_STRING: {
      const std::string str = android::util::GetString(src_pool, data);
      auto spans_result = src_pool.styleAt(data);

      // Check if the string has a valid style associated with it.
      if (spans_result.has_value() &&
          (*spans_result)->name.index != android::ResStringPool_span::END) {
        const android::ResStringPool_span* spans = spans_result->unsafe_ptr();
        android::StyleString style_str = {str};
        while (spans->name.index != android::ResStringPool_span::END) {
          style_str.spans.push_back(
              android::Span{android::util::GetString(src_pool, spans->name.index), spans->firstChar,
                            spans->lastChar});
          spans++;
        }
        return util::make_unique<StyledString>(dst_pool->MakeRef(
            style_str,
            android::StringPool::Context(android::StringPool::Context::kNormalPriority, config)));
      } else {
        if (type != ResourceType::kString && util::StartsWith(str, "res/")) {
          // This must be a FileReference.
          std::unique_ptr<FileReference> file_ref = util::make_unique<FileReference>(
              dst_pool->MakeRef(str, android::StringPool::Context(
                                         android::StringPool::Context::kHighPriority, config)));
          if (type == ResourceType::kRaw) {
            file_ref->type = ResourceFile::Type::kUnknown;
          } else if (util::EndsWith(*file_ref->path, ".xml")) {
            file_ref->type = ResourceFile::Type::kBinaryXml;
          } else if (util::EndsWith(*file_ref->path, ".png")) {
            file_ref->type = ResourceFile::Type::kPng;
          }
          return std::move(file_ref);
        }

        // There are no styles associated with this string, so treat it as a simple string.
        return util::make_unique<String>(
            dst_pool->MakeRef(str, android::StringPool::Context(config)));
      }
    } break;

    case android::Res_value::TYPE_REFERENCE:
    case android::Res_value::TYPE_ATTRIBUTE:
    case android::Res_value::TYPE_DYNAMIC_REFERENCE:
    case android::Res_value::TYPE_DYNAMIC_ATTRIBUTE: {
      Reference::Type ref_type = Reference::Type::kResource;
      if (res_value.dataType == android::Res_value::TYPE_ATTRIBUTE ||
          res_value.dataType == android::Res_value::TYPE_DYNAMIC_ATTRIBUTE) {
        ref_type = Reference::Type::kAttribute;
      }

      if (data == 0u) {
        // A reference of 0, must be the magic @null reference.
        return util::make_unique<Reference>();
      }

      // This is a normal reference.
      auto reference = util::make_unique<Reference>(data, ref_type);
      if (res_value.dataType == android::Res_value::TYPE_DYNAMIC_REFERENCE ||
          res_value.dataType == android::Res_value::TYPE_DYNAMIC_ATTRIBUTE) {
        reference->is_dynamic = true;
      }
      return reference;
    } break;
  }

  // Treat this as a raw binary primitive.
  return util::make_unique<BinaryPrimitive>(res_value);
}

// Converts the codepoint to UTF-8 and appends it to the string.
static bool AppendCodepointToUtf8String(char32_t codepoint, std::string* output) {
  ssize_t len = utf32_to_utf8_length(&codepoint, 1);
  if (len < 0) {
    return false;
  }

  const size_t start_append_pos = output->size();

  // Make room for the next character.
  output->resize(output->size() + len);

  char* dst = &*(output->begin() + start_append_pos);
  utf32_to_utf8(&codepoint, 1, dst, len + 1);
  return true;
}

// Reads up to 4 UTF-8 characters that represent a Unicode escape sequence, and appends the
// Unicode codepoint represented by the escape sequence to the string.
static bool AppendUnicodeEscapeSequence(Utf8Iterator* iter, std::string* output) {
  char32_t code = 0;
  for (size_t i = 0; i < 4 && iter->HasNext(); i++) {
    char32_t codepoint = iter->Next();
    char32_t a;
    if (codepoint >= U'0' && codepoint <= U'9') {
      a = codepoint - U'0';
    } else if (codepoint >= U'a' && codepoint <= U'f') {
      a = codepoint - U'a' + 10;
    } else if (codepoint >= U'A' && codepoint <= U'F') {
      a = codepoint - U'A' + 10;
    } else {
      return {};
    }
    code = (code << 4) | a;
  }
  return AppendCodepointToUtf8String(code, output);
}

StringBuilder::StringBuilder(bool preserve_spaces)
    : preserve_spaces_(preserve_spaces), quote_(preserve_spaces) {
}

StringBuilder& StringBuilder::AppendText(const std::string& text) {
  if (!error_.empty()) {
    return *this;
  }

  const size_t previous_len = xml_string_.text.size();
  Utf8Iterator iter(text);
  while (iter.HasNext()) {
    char32_t codepoint = iter.Next();
    if (!preserve_spaces_ && !quote_ && (codepoint <= std::numeric_limits<char>::max())
                                         && isspace(static_cast<char>(codepoint))) {
      if (!last_codepoint_was_space_) {
        // Emit a space if it's the first.
        xml_string_.text += ' ';
        last_codepoint_was_space_ = true;
      }

      // Keep eating spaces.
      continue;
    }

    // This is not a space.
    last_codepoint_was_space_ = false;

    if (codepoint == U'\\') {
      if (iter.HasNext()) {
        codepoint = iter.Next();
        switch (codepoint) {
          case U't':
            xml_string_.text += '\t';
            break;
          case U'n':
            xml_string_.text += '\n';
            break;

          case U'#':
          case U'@':
          case U'?':
          case U'"':
          case U'\'':
          case U'\\':
            xml_string_.text += static_cast<char>(codepoint);
            break;

          case U'u':
            if (!AppendUnicodeEscapeSequence(&iter, &xml_string_.text)) {
              error_ =
                  StringPrintf("invalid unicode escape sequence in string\n\"%s\"", text.c_str());
              return *this;
            }
            break;

          default:
            // Ignore the escape character and just include the codepoint.
            AppendCodepointToUtf8String(codepoint, &xml_string_.text);
            break;
        }
      }
    } else if (!preserve_spaces_ && codepoint == U'"') {
      // Only toggle the quote state when we are not preserving spaces.
      quote_ = !quote_;

    } else if (!preserve_spaces_ && !quote_ && codepoint == U'\'') {
      // This should be escaped when we are not preserving spaces
      error_ = StringPrintf("unescaped apostrophe in string\n\"%s\"", text.c_str());
      return *this;

    } else {
      AppendCodepointToUtf8String(codepoint, &xml_string_.text);
    }
  }

  // Accumulate the added string's UTF-16 length.
  const uint8_t* utf8_data = reinterpret_cast<const uint8_t*>(xml_string_.text.c_str());
  const size_t utf8_length = xml_string_.text.size();
  ssize_t len = utf8_to_utf16_length(utf8_data + previous_len, utf8_length - previous_len);
  if (len < 0) {
    error_ = StringPrintf("invalid unicode code point in string\n\"%s\"", utf8_data + previous_len);
    return *this;
  }

  utf16_len_ += static_cast<uint32_t>(len);
  return *this;
}

StringBuilder::SpanHandle StringBuilder::StartSpan(const std::string& name) {
  if (!error_.empty()) {
    return 0u;
  }

  // When we start a span, all state associated with whitespace truncation and quotation is ended.
  ResetTextState();
  android::Span span;
  span.name = name;
  span.first_char = span.last_char = utf16_len_;
  xml_string_.spans.push_back(std::move(span));
  return xml_string_.spans.size() - 1;
}

void StringBuilder::EndSpan(SpanHandle handle) {
  if (!error_.empty()) {
    return;
  }

  // When we end a span, all state associated with whitespace truncation and quotation is ended.
  ResetTextState();
  xml_string_.spans[handle].last_char = utf16_len_ - 1u;
}

StringBuilder::UntranslatableHandle StringBuilder::StartUntranslatable() {
  if (!error_.empty()) {
    return 0u;
  }

  UntranslatableSection section;
  section.start = section.end = xml_string_.text.size();
  xml_string_.untranslatable_sections.push_back(section);
  return xml_string_.untranslatable_sections.size() - 1;
}

void StringBuilder::EndUntranslatable(UntranslatableHandle handle) {
  if (!error_.empty()) {
    return;
  }
  xml_string_.untranslatable_sections[handle].end = xml_string_.text.size();
}

FlattenedXmlString StringBuilder::GetFlattenedString() const {
  return xml_string_;
}

std::string StringBuilder::to_string() const {
  return xml_string_.text;
}

StringBuilder::operator bool() const {
  return error_.empty();
}

std::string StringBuilder::GetError() const {
  return error_;
}

void StringBuilder::ResetTextState() {
  quote_ = preserve_spaces_;
  last_codepoint_was_space_ = false;
}

}  // namespace ResourceUtils
}  // namespace aapt
