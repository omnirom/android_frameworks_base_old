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

#include "java/JavaClassGenerator.h"

#include <algorithm>
#include <ostream>
#include <set>
#include <sstream>
#include <tuple>

#include "android-base/errors.h"
#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "androidfw/StringPiece.h"

#include "NameMangler.h"
#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "SdkConstants.h"
#include "ValueVisitor.h"
#include "java/AnnotationProcessor.h"
#include "java/ClassDefinition.h"
#include "process/SymbolTable.h"

using ::aapt::text::Printer;
using ::android::OutputStream;
using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {

static const std::set<StringPiece> sJavaIdentifiers = {
    "abstract",   "assert",       "boolean",   "break",      "byte",
    "case",       "catch",        "char",      "class",      "const",
    "continue",   "default",      "do",        "double",     "else",
    "enum",       "extends",      "final",     "finally",    "float",
    "for",        "goto",         "if",        "implements", "import",
    "instanceof", "int",          "interface", "long",       "native",
    "new",        "package",      "private",   "protected",  "public",
    "return",     "short",        "static",    "strictfp",   "super",
    "switch",     "synchronized", "this",      "throw",      "throws",
    "transient",  "try",          "void",      "volatile",   "while",
    "true",       "false",        "null"};

static bool IsValidSymbol(StringPiece symbol) {
  return sJavaIdentifiers.find(symbol) == sJavaIdentifiers.end();
}

// Java symbols can not contain . or -, but those are valid in a resource name.
// Replace those with '_'.
std::string JavaClassGenerator::TransformToFieldName(StringPiece symbol) {
  std::string output(symbol);
  for (char& c : output) {
    if (c == '.' || c == '-') {
      c = '_';
    }
  }
  return output;
}

// Transforms an attribute in a styleable to the Java field name:
//
// <declare-styleable name="Foo">
//   <attr name="android:bar" />
//   <attr name="bar" />
// </declare-styleable>
//
// Foo_android_bar
// Foo_bar
static std::string TransformNestedAttr(const ResourceNameRef& attr_name,
                                       const std::string& styleable_class_name,
                                       StringPiece package_name_to_generate) {
  std::string output = styleable_class_name;

  // We may reference IDs from other packages, so prefix the entry name with
  // the package.
  if (!attr_name.package.empty() &&
      package_name_to_generate != attr_name.package) {
    output += "_" + JavaClassGenerator::TransformToFieldName(attr_name.package);
  }
  output += "_" + JavaClassGenerator::TransformToFieldName(attr_name.entry);
  return output;
}

static void AddAttributeFormatDoc(AnnotationProcessor* processor, Attribute* attr) {
  const uint32_t type_mask = attr->type_mask;
  if (type_mask & android::ResTable_map::TYPE_REFERENCE) {
    processor->AppendComment(
        "<p>May be a reference to another resource, in the form\n"
        "\"<code>@[+][<i>package</i>:]<i>type</i>/<i>name</i></code>\" or a "
        "theme\n"
        "attribute in the form\n"
        "\"<code>?[<i>package</i>:]<i>type</i>/<i>name</i></code>\".");
  }

  if (type_mask & android::ResTable_map::TYPE_STRING) {
    processor->AppendComment(
        "<p>May be a string value, using '\\\\;' to escape characters such as\n"
        "'\\\\n' or '\\\\uxxxx' for a unicode character;");
  }

  if (type_mask & android::ResTable_map::TYPE_INTEGER) {
    processor->AppendComment(
        "<p>May be an integer value, such as \"<code>100</code>\".");
  }

  if (type_mask & android::ResTable_map::TYPE_BOOLEAN) {
    processor->AppendComment(
        "<p>May be a boolean value, such as \"<code>true</code>\" or\n"
        "\"<code>false</code>\".");
  }

  if (type_mask & android::ResTable_map::TYPE_COLOR) {
    processor->AppendComment(
        "<p>May be a color value, in the form of "
        "\"<code>#<i>rgb</i></code>\",\n"
        "\"<code>#<i>argb</i></code>\", \"<code>#<i>rrggbb</i></code>\", or \n"
        "\"<code>#<i>aarrggbb</i></code>\".");
  }

  if (type_mask & android::ResTable_map::TYPE_FLOAT) {
    processor->AppendComment(
        "<p>May be a floating point value, such as \"<code>1.2</code>\".");
  }

  if (type_mask & android::ResTable_map::TYPE_DIMENSION) {
    processor->AppendComment(
        "<p>May be a dimension value, which is a floating point number "
        "appended with a\n"
        "unit such as \"<code>14.5sp</code>\".\n"
        "Available units are: px (pixels), dp (density-independent pixels),\n"
        "sp (scaled pixels based on preferred font size), in (inches), and\n"
        "mm (millimeters).");
  }

  if (type_mask & android::ResTable_map::TYPE_FRACTION) {
    processor->AppendComment(
        "<p>May be a fractional value, which is a floating point number "
        "appended with\n"
        "either % or %p, such as \"<code>14.5%</code>\".\n"
        "The % suffix always means a percentage of the base size;\n"
        "the optional %p suffix provides a size relative to some parent "
        "container.");
  }

  if (type_mask &
      (android::ResTable_map::TYPE_FLAGS | android::ResTable_map::TYPE_ENUM)) {
    if (type_mask & android::ResTable_map::TYPE_FLAGS) {
      processor->AppendComment(
          "<p>Must be one or more (separated by '|') of the following "
          "constant values.</p>");
    } else {
      processor->AppendComment(
          "<p>Must be one of the following constant values.</p>");
    }

    processor->AppendComment(
        "<table>\n<colgroup align=\"left\" />\n"
        "<colgroup align=\"left\" />\n"
        "<colgroup align=\"left\" />\n"
        "<tr><th>Constant</th><th>Value</th><th>Description</th></tr>\n");
    for (const Attribute::Symbol& symbol : attr->symbols) {
      std::stringstream line;
      line << "<tr><td>" << symbol.symbol.name.value().entry << "</td>"
           << "<td>" << std::hex << symbol.value << std::dec << "</td>"
           << "<td>" << util::TrimWhitespace(symbol.symbol.GetComment())
           << "</td></tr>";
      processor->AppendComment(line.str());
    }
    processor->AppendComment("</table>");
  }
}

JavaClassGenerator::JavaClassGenerator(IAaptContext* context,
                                       ResourceTable* table,
                                       const JavaClassGeneratorOptions& options)
    : context_(context), table_(table), options_(options) {}

bool JavaClassGenerator::SkipSymbol(Visibility::Level level) {
  switch (options_.types) {
    case JavaClassGeneratorOptions::SymbolTypes::kAll:
      return false;
    case JavaClassGeneratorOptions::SymbolTypes::kPublicPrivate:
      return level == Visibility::Level::kUndefined;
    case JavaClassGeneratorOptions::SymbolTypes::kPublic:
      return level != Visibility::Level::kPublic;
  }
  return true;
}

// Whether or not to skip writing this symbol.
bool JavaClassGenerator::SkipSymbol(const std::optional<SymbolTable::Symbol>& symbol) {
  return !symbol || (options_.types == JavaClassGeneratorOptions::SymbolTypes::kPublic &&
                     !symbol.value().is_public);
}

struct StyleableAttr {
  const Reference* attr_ref = nullptr;
  std::string field_name;
  std::optional<SymbolTable::Symbol> symbol;
};

static bool operator<(const StyleableAttr& lhs, const StyleableAttr& rhs) {
  const ResourceId lhs_id = lhs.attr_ref->id.value_or(ResourceId(0));
  const ResourceId rhs_id = rhs.attr_ref->id.value_or(ResourceId(0));
  if (lhs_id == rhs_id) {
    return lhs.attr_ref->name.value() < rhs.attr_ref->name.value();
  }
  return cmp_ids_dynamic_after_framework(lhs_id, rhs_id);
}

static FieldReference GetRFieldReference(const ResourceName& name,
                                         StringPiece fallback_package_name) {
  const std::string_view package_name = name.package.empty() ? fallback_package_name : name.package;
  const std::string entry = JavaClassGenerator::TransformToFieldName(name.entry);
  return FieldReference(
      StringPrintf("%s.R.%s.%s", package_name.data(), name.type.to_string().data(), entry.c_str()));
}

bool JavaClassGenerator::ProcessStyleable(const ResourceNameRef& name, const ResourceId& id,
                                          const Styleable& styleable,
                                          StringPiece package_name_to_generate,
                                          ClassDefinition* out_class_def,
                                          MethodDefinition* out_rewrite_method,
                                          Printer* r_txt_printer) {
  const std::string array_field_name = TransformToFieldName(name.entry);
  std::unique_ptr<ResourceArrayMember> array_def =
      util::make_unique<ResourceArrayMember>(array_field_name);

  // The array must be sorted by resource ID.
  std::vector<StyleableAttr> sorted_attributes;
  sorted_attributes.reserve(styleable.entries.size());
  for (const auto& attr : styleable.entries) {
    // If we are not encoding final attributes, the styleable entry may have no
    // ID if we are building a static library.
    CHECK(!options_.use_final || attr.id) << "no ID set for Styleable entry";
    CHECK(bool(attr.name)) << "no name set for Styleable entry";

    // We will need the unmangled, transformed name in the comments and the field,
    // so create it once and cache it in this StyleableAttr data structure.
    StyleableAttr styleable_attr;
    styleable_attr.attr_ref = &attr;

    // The field name for this attribute is prefixed by the name of this styleable and
    // the package it comes from.
    styleable_attr.field_name =
        TransformNestedAttr(attr.name.value(), array_field_name, package_name_to_generate);

    Reference ref = attr;
    if (attr.name.value().package.empty()) {

      // If the resource does not have a package name, set the package to the unmangled package name
      // of the styleable declaration because attributes without package names would have been
      // declared in the same package as the styleable.
      ref.name = ResourceName(package_name_to_generate, ref.name.value().type,
                              ref.name.value().entry);
    }

    // Look up the symbol so that we can write out in the comments what are possible legal values
    // for this attribute.
    const SymbolTable::Symbol* symbol = context_->GetExternalSymbols()->FindByReference(ref);

    if (symbol && symbol->attribute) {
      // Copy the symbol data structure because the returned instance can be destroyed.
      styleable_attr.symbol = *symbol;
    }
    sorted_attributes.push_back(std::move(styleable_attr));
  }

  // Sort the attributes by ID.
  std::sort(sorted_attributes.begin(), sorted_attributes.end());

  // Build the JavaDoc comment for the Styleable array. This has references to child attributes
  // and what possible values can be used for them.
  const size_t attr_count = sorted_attributes.size();
  if (out_class_def != nullptr && attr_count > 0) {
    std::stringstream styleable_comment;
    if (!styleable.GetComment().empty()) {
      styleable_comment << styleable.GetComment() << "\n";
    } else {
      // Apply a default intro comment if the styleable has no comments of its own.
      styleable_comment << "Attributes that can be used with a " << array_field_name << ".\n";
    }

    styleable_comment << "<p>Includes the following attributes:</p>\n"
                         "<table>\n"
                         "<colgroup align=\"left\" />\n"
                         "<colgroup align=\"left\" />\n"
                         "<tr><th>Attribute</th><th>Description</th></tr>\n";

    // Removed and hidden attributes are public but hidden from the documentation, so don't emit
    // them as part of the class documentation.
    std::vector<StyleableAttr> documentation_attrs = sorted_attributes;
    auto documentation_remove_iter = std::remove_if(documentation_attrs.begin(),
                                                    documentation_attrs.end(),
                                                    [&](StyleableAttr entry) -> bool {
      if (SkipSymbol(entry.symbol)) {
        return true;
      }
      const StringPiece attr_comment_line = entry.symbol.value().attribute->GetComment();
      return attr_comment_line.find("@removed") != std::string::npos ||
             attr_comment_line.find("@hide") != std::string::npos;
    });
    documentation_attrs.erase(documentation_remove_iter, documentation_attrs.end());

    // Build the table of attributes with their links and names.
    for (const StyleableAttr& entry : documentation_attrs) {
      const ResourceName& attr_name = entry.attr_ref->name.value();
      styleable_comment << "<tr><td><code>{@link #" << entry.field_name << " "
                        << (!attr_name.package.empty() ? attr_name.package
                                                       : package_name_to_generate)
                        << ":" << attr_name.entry << "}</code></td>";

      // Only use the comment up until the first '.'. This is to stay compatible with
      // the way old AAPT did it (presumably to keep it short and to avoid including
      // annotations like @hide which would affect this Styleable).
      StringPiece attr_comment_line = entry.symbol.value().attribute->GetComment();
      styleable_comment << "<td>" << AnnotationProcessor::ExtractFirstSentence(attr_comment_line)
                        << "</td></tr>\n";
    }
    styleable_comment << "</table>\n";

    // Generate the @see lines for each attribute.
    for (const StyleableAttr& entry : documentation_attrs) {
      styleable_comment << "@see #" << entry.field_name << "\n";
    }

    array_def->GetCommentBuilder()->AppendComment(styleable_comment.str());
  }

  if (r_txt_printer != nullptr) {
    r_txt_printer->Print("int[] styleable ").Print(array_field_name).Print(" {");
  }

  // Add the ResourceIds to the array member.
  for (size_t i = 0; i < attr_count; i++) {
    const StyleableAttr& attr = sorted_attributes[i];
    std::string r_txt_contents;
    if (attr.symbol && attr.symbol.value().is_dynamic) {
      if (!attr.attr_ref->name) {
        error_ = "unable to determine R.java field name of dynamic resource";
        return false;
      }

      const FieldReference field_name =
          GetRFieldReference(attr.attr_ref->name.value(), package_name_to_generate);
      array_def->AddElement(field_name);
      r_txt_contents = field_name.ref;
    } else {
      const ResourceId attr_id = attr.attr_ref->id.value_or(ResourceId(0));
      array_def->AddElement(attr_id);
      r_txt_contents = to_string(attr_id);
    }

    if (r_txt_printer != nullptr) {
      if (i != 0) {
        r_txt_printer->Print(",");
      }
      r_txt_printer->Print(" ").Print(r_txt_contents);
    }
  }

  if (r_txt_printer != nullptr) {
    r_txt_printer->Println(" }");
  }

  // Add the Styleable array to the Styleable class.
  if (out_class_def != nullptr) {
    out_class_def->AddMember(std::move(array_def));
  }

  // Now we emit the indices into the array.
  for (size_t i = 0; i < attr_count; i++) {
    const StyleableAttr& styleable_attr = sorted_attributes[i];
    if (SkipSymbol(styleable_attr.symbol)) {
      continue;
    }

    if (out_class_def != nullptr) {
      StringPiece comment = styleable_attr.attr_ref->GetComment();
      if (styleable_attr.symbol.value().attribute && comment.empty()) {
        comment = styleable_attr.symbol.value().attribute->GetComment();
      }

      if (comment.find("@removed") != std::string::npos) {
        // Removed attributes are public but hidden from the documentation, so
        // don't emit them as part of the class documentation.
        continue;
      }

      const ResourceName& attr_name = styleable_attr.attr_ref->name.value();

      StringPiece package_name = attr_name.package;
      if (package_name.empty()) {
        package_name = package_name_to_generate;
      }

      std::unique_ptr<IntMember> index_member =
          util::make_unique<IntMember>(sorted_attributes[i].field_name, static_cast<uint32_t>(i));

      AnnotationProcessor* attr_processor = index_member->GetCommentBuilder();

      if (!comment.empty()) {
        attr_processor->AppendComment("<p>\n@attr description");
        attr_processor->AppendComment(comment);
      } else {
        std::stringstream default_comment;
        default_comment << "<p>This symbol is the offset where the "
                        << "{@link " << package_name << ".R.attr#"
                        << TransformToFieldName(attr_name.entry) << "}\n"
                        << "attribute's value can be found in the "
                        << "{@link #" << array_field_name << "} array.";
        attr_processor->AppendComment(default_comment.str());
      }

      attr_processor->AppendNewLine();
      AddAttributeFormatDoc(attr_processor, styleable_attr.symbol.value().attribute.get());
      attr_processor->AppendNewLine();
      attr_processor->AppendComment(
          StringPrintf("@attr name %s:%s", package_name.data(), attr_name.entry.data()));

      out_class_def->AddMember(std::move(index_member));
    }

    if (r_txt_printer != nullptr) {
      r_txt_printer->Println(
          StringPrintf("int styleable %s %zd", sorted_attributes[i].field_name.c_str(), i));
    }
  }

  return true;
}

void JavaClassGenerator::ProcessResource(const ResourceNameRef& name, const ResourceId& id,
                                         const ResourceEntry& entry, ClassDefinition* out_class_def,
                                         MethodDefinition* out_rewrite_method,
                                         text::Printer* r_txt_printer) {
  ResourceId real_id = id;
  if (context_->GetMinSdkVersion() < SDK_O && name.type.type == ResourceType::kId &&
      id.package_id() > kAppPackageId) {
    // Workaround for feature splits using package IDs > 0x7F.
    // See b/37498913.
    real_id = ResourceId(kAppPackageId, id.package_id(), id.entry_id());
  }

  const std::string field_name = TransformToFieldName(name.entry);
  if (out_class_def != nullptr) {
    auto resource_member =
        util::make_unique<ResourceMember>(field_name, real_id, entry.visibility.staged_api);

    // Build the comments and annotations for this entry.
    AnnotationProcessor* processor = resource_member->GetCommentBuilder();

    // Add the comments from any <public> tags.
    if (entry.visibility.level != Visibility::Level::kUndefined) {
      processor->AppendComment(entry.visibility.comment);
    }

    // Add the comments from all configurations of this entry.
    for (const auto& config_value : entry.values) {
      processor->AppendComment(config_value->value->GetComment());
    }

    // If this is an Attribute, append the format Javadoc.
    if (!entry.values.empty()) {
      if (Attribute* attr = ValueCast<Attribute>(entry.values.front()->value.get())) {
        // We list out the available values for the given attribute.
        AddAttributeFormatDoc(processor, attr);
      }
    }

    out_class_def->AddMember(std::move(resource_member));
  }

  if (r_txt_printer != nullptr) {
    r_txt_printer->Print("int ")
        .Print(name.type.to_string())
        .Print(" ")
        .Print(field_name)
        .Print(" ")
        .Println(real_id.to_string());
  }

  if (out_rewrite_method != nullptr) {
    const auto type_str = name.type.to_string();
    out_rewrite_method->AppendStatement(
        StringPrintf("%s.%s = (%s.%s & 0x00ffffff) | packageIdBits;", type_str.data(),
                     field_name.data(), type_str.data(), field_name.data()));
  }
}

std::optional<std::string> JavaClassGenerator::UnmangleResource(
    StringPiece package_name, StringPiece package_name_to_generate, const ResourceEntry& entry) {
  if (SkipSymbol(entry.visibility.level)) {
    return {};
  }

  std::string unmangled_package;
  std::string unmangled_name = entry.name;
  if (NameMangler::Unmangle(&unmangled_name, &unmangled_package)) {
    // The entry name was mangled, and we successfully unmangled it.
    // Check that we want to emit this symbol.
    if (package_name_to_generate != unmangled_package) {
      // Skip the entry if it doesn't belong to the package we're writing.
      return {};
    }
  } else if (package_name_to_generate != package_name) {
    // We are processing a mangled package name,
    // but this is a non-mangled resource.
    return {};
  }
  return {std::move(unmangled_name)};
}

bool JavaClassGenerator::ProcessType(StringPiece package_name_to_generate,
                                     const ResourceTablePackage& package,
                                     const ResourceTableType& type,
                                     ClassDefinition* out_type_class_def,
                                     MethodDefinition* out_rewrite_method_def,
                                     Printer* r_txt_printer) {
  for (const auto& entry : type.entries) {
    const std::optional<std::string> unmangled_name =
        UnmangleResource(package.name, package_name_to_generate, *entry);
    if (!unmangled_name) {
      continue;
    }

    // Create an ID if there is one (static libraries don't need one).
    ResourceId id;
    if (entry->id) {
      id = entry->id.value();
    }

    // We need to make sure we hide the fact that we are generating kAttrPrivate attributes.
    const auto target_type = type.named_type.type == ResourceType::kAttrPrivate
                                 ? ResourceNamedTypeWithDefaultName(ResourceType::kAttr)
                                 : type.named_type;
    const ResourceNameRef resource_name(package_name_to_generate, target_type,
                                        unmangled_name.value());

    // Check to see if the unmangled name is a valid Java name (not a keyword).
    if (!IsValidSymbol(unmangled_name.value())) {
      std::stringstream err;
      err << "invalid symbol name '" << resource_name << "'";
      error_ = err.str();
      return false;
    }

    if (resource_name.type.type == ResourceType::kStyleable) {
      CHECK(!entry->values.empty());
      const auto styleable = reinterpret_cast<const Styleable*>(entry->values.front()->value.get());
      if (!ProcessStyleable(resource_name, id, *styleable, package_name_to_generate,
                            out_type_class_def, out_rewrite_method_def, r_txt_printer)) {
        return false;
      }
    } else {
      ProcessResource(resource_name, id, *entry, out_type_class_def, out_rewrite_method_def,
                      r_txt_printer);
    }
  }
  return true;
}

bool JavaClassGenerator::Generate(StringPiece package_name_to_generate, OutputStream* out,
                                  OutputStream* out_r_txt) {
  return Generate(package_name_to_generate, package_name_to_generate, out, out_r_txt);
}

static void AppendJavaDocAnnotations(const std::vector<std::string>& annotations,
                                     AnnotationProcessor* processor) {
  for (const std::string& annotation : annotations) {
    std::string proper_annotation = "@";
    proper_annotation += annotation;
    processor->AppendComment(proper_annotation);
  }
}

bool JavaClassGenerator::Generate(StringPiece package_name_to_generate,
                                  StringPiece out_package_name, OutputStream* out,
                                  OutputStream* out_r_txt) {
  ClassDefinition r_class("R", ClassQualifier::kNone, true);
  std::unique_ptr<MethodDefinition> rewrite_method;

  std::unique_ptr<Printer> r_txt_printer;
  if (out_r_txt != nullptr) {
    r_txt_printer = util::make_unique<Printer>(out_r_txt);
  }
  // Generate an onResourcesLoaded() callback if requested.
  if (out != nullptr && options_.rewrite_callback_options) {
    rewrite_method =
        util::make_unique<MethodDefinition>("public static void onResourcesLoaded(int p)");
    for (const std::string& package_to_callback :
         options_.rewrite_callback_options.value().packages_to_callback) {
      rewrite_method->AppendStatement(
          StringPrintf("%s.R.onResourcesLoaded(p);", package_to_callback.data()));
    }
    rewrite_method->AppendStatement("final int packageIdBits = p << 24;");
  }

  const bool is_public = (options_.types == JavaClassGeneratorOptions::SymbolTypes::kPublic);

  for (const auto& package : table_->packages) {
    for (const auto& type : package->types) {
      if (type->named_type.type == ResourceType::kAttrPrivate ||
          type->named_type.type == ResourceType::kMacro) {
        // We generate kAttrPrivate as part of the kAttr type, so skip them here.
        // Macros are not actual resources, so skip them as well.
        continue;
      }

      // Stay consistent with AAPT and generate an empty type class if the R class is public.
      const bool force_creation_if_empty = is_public;

      std::unique_ptr<ClassDefinition> class_def;
      if (out != nullptr) {
        class_def = util::make_unique<ClassDefinition>(
            to_string(type->named_type.type), ClassQualifier::kStatic, force_creation_if_empty);
      }

      if (!ProcessType(package_name_to_generate, *package, *type, class_def.get(),
                       rewrite_method.get(), r_txt_printer.get())) {
        return false;
      }

      if (type->named_type.type == ResourceType::kAttr) {
        // Also include private attributes in this same class.
        if (const ResourceTableType* priv_type =
                package->FindTypeWithDefaultName(ResourceType::kAttrPrivate)) {
          if (!ProcessType(package_name_to_generate, *package, *priv_type, class_def.get(),
                           rewrite_method.get(), r_txt_printer.get())) {
            return false;
          }
        }
      }

      if (out != nullptr && type->named_type.type == ResourceType::kStyleable && is_public) {
        // When generating a public R class, we don't want Styleable to be part
        // of the API. It is only emitted for documentation purposes.
        class_def->GetCommentBuilder()->AppendComment("@doconly");
      }

      if (out != nullptr) {
        AppendJavaDocAnnotations(options_.javadoc_annotations, class_def->GetCommentBuilder());
        r_class.AddMember(std::move(class_def));
      }
    }
  }

  if (rewrite_method != nullptr) {
    r_class.AddMember(std::move(rewrite_method));
  }

  if (out != nullptr) {
    AppendJavaDocAnnotations(options_.javadoc_annotations, r_class.GetCommentBuilder());
    ClassDefinition::WriteJavaFile(&r_class, out_package_name, options_.use_final, !is_public, out);
  }
  return true;
}

}  // namespace aapt
