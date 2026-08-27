//! Real `diplomat_core`-driven backend: walks the actual HIR (not a guess)
//! and emits .clj + a C shim, following the rules verified by hand against
//! Thingy in findings/milestone-1..4. Scope is intentionally limited to the
//! shapes the spike crate exercises — an unsupported shape panics loudly
//! rather than silently emitting something wrong.
//!
//! Critical rule (milestone-4/5): ANY struct-by-value crossing, param or
//! return, ALWAYS goes through the shim — never emit a direct defcfn
//! against a struct-by-value symbol, even where SysV ABI register-packing
//! would happen to make it work (verified this occurs for sum_with's
//! DiplomatU8View — 2 fields, <=16 bytes, all-integer-class, so it landed
//! in the same registers as 2 flattened scalar args — but that's a fragile
//! x86-64-specific coincidence, not something to generate code that
//! depends on).

use diplomat_core::hir::{self, ReturnType, SuccessType, Type, TypeDef, PrimitiveType, IntType, IntSizeType, StructPathLike};
use std::fmt::Write as _;
use std::path::Path;

// C reserved words that must be renamed when used as parameter names.
const C_KEYWORDS: &[&str] = &[
    "default", "register", "auto", "extern", "static", "void", "int", "char",
    "short", "long", "float", "double", "signed", "unsigned", "struct", "union",
    "enum", "typedef", "return", "if", "else", "for", "while", "do", "switch",
    "case", "break", "continue", "goto", "const", "volatile", "inline",
];

fn to_snake(s: &str) -> String {
    to_kebab(s).replace('-', "_")
}

fn safe_c_ident(s: &str) -> String {
    let snake = to_snake(s);
    if C_KEYWORDS.contains(&snake.as_str()) { format!("{snake}_") } else { snake }
}

fn to_kebab(s: &str) -> String {
    // Treat a run of uppercase letters (optionally followed by a digit) as one
    // token — e.g. "ICU4X" → "icu4x", not "i-c-u4-x".
    let chars: Vec<char> = s.chars().collect();
    let mut out = String::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c == '_' {
            out.push('-');
            i += 1;
        } else if c.is_uppercase() {
            let run_start = i;
            while i < chars.len() && chars[i].is_uppercase() { i += 1; }
            if i < chars.len() && chars[i].is_ascii_digit() { i += 1; }
            let run_len = i - run_start;
            let (acronym_end, next_start) = if run_len > 1 && i < chars.len() && chars[i].is_lowercase() {
                (i - 1, i - 1)
            } else {
                (i, i)
            };
            if run_start != 0 { out.push('-'); }
            for &ac in &chars[run_start..acronym_end] {
                out.extend(ac.to_lowercase());
            }
            i = next_start;
        } else {
            out.extend(c.to_lowercase());
            i += 1;
        }
    }
    out
}

fn prim_to_jolt_and_c(p: &PrimitiveType) -> (&'static str, &'static str) {
    match p {
        PrimitiveType::Bool => (":int", "bool"), // no :bool keyword — verified
        PrimitiveType::Int(IntType::U8) => (":uint8", "uint8_t"),
        PrimitiveType::Int(IntType::I8) => (":int", "int8_t"),
        PrimitiveType::Int(IntType::I32) => (":int", "int32_t"),
        PrimitiveType::Int(IntType::U32) => (":uint", "uint32_t"),
        // No 16-bit keyword in jolt.ffi — widen to 32-bit (ABI zero/sign-extends).
        PrimitiveType::Int(IntType::U16) => (":uint", "uint16_t"),
        PrimitiveType::Int(IntType::I16) => (":int", "int16_t"),
        PrimitiveType::Int(IntType::I64) => (":int64", "int64_t"),
        PrimitiveType::Int(IntType::U64) => (":uint64", "uint64_t"),
        PrimitiveType::IntSize(IntSizeType::Usize) => (":size_t", "size_t"),
        PrimitiveType::IntSize(IntSizeType::Isize) => (":ssize_t", "ssize_t"),
        PrimitiveType::Float(hir::FloatType::F32) => (":float", "float"),
        PrimitiveType::Float(hir::FloatType::F64) => (":double", "double"),
        PrimitiveType::Char => (":uint", "char32_t"),
        PrimitiveType::Byte => (":uint8", "uint8_t"),
        PrimitiveType::Ordering => (":int", "int8_t"),
        other => panic!("jolt-diplomat-backend: unsupported primitive {other:?}"),
    }
}

/// Map a C type name back to the Jolt ffi keyword for that type.
/// Used where we have a C type string (from shim generation) and need
/// the Jolt side — avoids a second hand-coded table that could drift from
/// prim_to_jolt_and_c.
fn c_ty_to_jolt_kw(c_ty: &str) -> &'static str {
    match c_ty {
        "uint8_t" => ":uint8",
        "uint16_t" | "uint32_t" | "char32_t" => ":uint",
        "int8_t" | "int16_t" | "int32_t" | "bool" => ":int",
        "int64_t" => ":int64",
        "uint64_t" => ":uint64",
        "size_t" => ":size_t",
        "ssize_t" => ":ssize_t",
        "float" => ":float",
        "double" => ":double",
        "void*" => ":pointer",
        _ => panic!("jolt-diplomat-backend: no jolt keyword for C type {c_ty}"),
    }
}

fn prim_to_diplomat_view_suffix(p: &PrimitiveType) -> &'static str {
    match p {
        PrimitiveType::Bool => "Bool",
        PrimitiveType::Int(IntType::U8) | PrimitiveType::Byte => "U8",
        PrimitiveType::Int(IntType::I8) => "I8",
        PrimitiveType::Int(IntType::I16) => "I16",
        PrimitiveType::Int(IntType::U16) => "U16",
        PrimitiveType::Int(IntType::I32) => "I32",
        PrimitiveType::Int(IntType::U32) => "U32",
        PrimitiveType::Int(IntType::I64) => "I64",
        PrimitiveType::Int(IntType::U64) => "U64",
        PrimitiveType::IntSize(IntSizeType::Usize) => "Usize",
        PrimitiveType::IntSize(IntSizeType::Isize) => "Isize",
        PrimitiveType::Float(hir::FloatType::F64) => "F64",
        PrimitiveType::Float(hir::FloatType::F32) => "F32",
        PrimitiveType::Char => "Char",
        other => panic!("jolt-diplomat-backend: no DiplomatXView mapping for {other:?}"),
    }
}

/// Emit `(->{Target} call owns?)`, qualifying with the alias when Target != owner.
fn emit_opaque_wrap(target: &str, owner: &str, call: &str, owns: bool) -> String {
    let owns_str = if owns { "true" } else { "false" };
    if target != owner {
        let alias = to_kebab(target);
        format!("({alias}/->{target} {call} {owns_str})")
    } else {
        format!("(->{target} {call} {owns_str})")
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let entry = Path::new(&args[1]);
    let out_dir = Path::new(&args[2]);
    std::fs::create_dir_all(out_dir).unwrap();

    let module = syn_inline_mod::parse_and_inline_modules(entry);
    let mut attr_validator = hir::BasicAttributeValidator::new("c");
    attr_validator.support = diplomat_tool_c_attr_support();
    let tcx = hir::TypeContext::from_syn(&module, Default::default(), attr_validator).unwrap_or_else(|e| {
        for (ctx, err) in e {
            eprintln!("Lowering error in {ctx}: {err}");
        }
        std::process::exit(1);
    });

    // Optional third arg: path to C headers dir; used to guard #include lines.
    let headers_dir: Option<std::path::PathBuf> = args.get(3).map(std::path::PathBuf::from);

    let mut shim_c = String::new();
    writeln!(shim_c, "// GENERATED by jolt-diplomat-backend. Do not hand-edit.").unwrap();
    writeln!(shim_c, "#include <string.h>").unwrap();
    writeln!(shim_c, "#include \"diplomat_runtime.h\"").unwrap();
    for (_id, def) in tcx.all_types() {
        if let TypeDef::Opaque(op) = def {
            let h = format!("{}.h", op.name);
            let exists = headers_dir.as_ref()
                .map(|d| d.join(&h).exists())
                .unwrap_or(true); // if no dir given, include unconditionally
            if exists {
                writeln!(shim_c, "#include \"{h}\"").unwrap();
            }
        }
    }
    writeln!(shim_c).unwrap();
    writeln!(shim_c, "void jolt_diplomat_simple_write(char* buf, size_t buf_size, void* out) {{").unwrap();
    writeln!(shim_c, "    DiplomatWrite w = diplomat_simple_write(buf, buf_size);").unwrap();
    writeln!(shim_c, "    memcpy(out, &w, sizeof(w));").unwrap();
    writeln!(shim_c, "}}").unwrap();
    writeln!(shim_c).unwrap();

    // Write .clj files into out_dir/diplomat/ so the namespace diplomat.X
    // resolves when out_dir is a source root.
    let clj_dir = out_dir.join("diplomat");
    std::fs::create_dir_all(&clj_dir).unwrap();

    for (_id, def) in tcx.all_types() {
        match def {
            TypeDef::Opaque(op) => {
                let clj = gen_opaque_clj(&tcx, op, &mut shim_c);
                let path = clj_dir.join(format!("{}.clj", to_snake(op.name.as_ref())));
                std::fs::write(&path, clj).unwrap();
                println!("wrote {}", path.display());
            }
            TypeDef::Enum(en) => {
                let clj = gen_enum_clj(en);
                let path = clj_dir.join(format!("{}.clj", to_snake(en.name.as_ref())));
                std::fs::write(&path, clj).unwrap();
                println!("wrote {}", path.display());
            }
            _ => {}
        }
    }

    let shim_path = out_dir.join("generated_shim.c");
    std::fs::write(&shim_path, shim_c).unwrap();
    println!("wrote {}", shim_path.display());
}

fn diplomat_tool_c_attr_support() -> hir::BackendAttrSupport {
    let mut s = hir::BackendAttrSupport::default();
    s.namespacing = false;
    s.memory_sharing = true;
    s.non_exhaustive_structs = true;
    s.method_overloading = true;
    s.utf8_strings = true;
    s.utf16_strings = true;
    s.static_slices = false;
    s.callbacks = true;
    s.option = true;
    s.owned_slices = true;
    s
}

// A struct field's shape — either a primitive leaf, an enum leaf,
// an Option<primitive/enum> leaf, or a nested struct.
#[allow(dead_code)]
enum FieldShape {
    Prim { jolt_ty: String, c_ty: String, is_bool: bool },
    EnumField { c_ty: String, enum_alias: String },
    // DiplomatOption<T>: C ABI is { T value; bool is_some } by value.
    // We emit two shim params: the inner value and a bool sentinel.
    // Jolt caller passes nil (→ is_some=0) or the value (→ is_some=1).
    // Option{Suffix} macro types: { union { T ok; }; bool is_ok; }
    OptionPrim { jolt_ty: String, opt_ty: String }, // opt_ty = "OptionU8" etc.
    OptionEnum { enum_name: String, enum_alias: String }, // {EnumName}_option
    Nested { c_struct_name: String, fields: Vec<(String, FieldShape)> },
}

fn resolve_field_shape<P: hir::TyPosition>(
    tcx: &hir::TypeContext,
    ty: &Type<P>,
    extra_requires: &mut std::collections::BTreeSet<String>,
) -> Result<FieldShape, String> {
    match ty {
        Type::Primitive(p) => {
            let (jolt_ty, c_ty) = prim_to_jolt_and_c(p);
            Ok(FieldShape::Prim { jolt_ty: jolt_ty.to_string(), c_ty: c_ty.to_string(), is_bool: matches!(p, PrimitiveType::Bool) })
        }
        Type::Enum(ep) => {
            let ed = tcx.resolve_enum(ep.tcx_id);
            let enum_alias = to_kebab(ed.name.as_str());
            extra_requires.insert(ed.name.as_str().to_string());
            Ok(FieldShape::EnumField { c_ty: ed.name.as_str().to_string(), enum_alias })
        }
        Type::DiplomatOption(inner) => match inner.as_ref() {
            Type::Primitive(p) => {
                let (jolt_ty, _) = prim_to_jolt_and_c(p);
                let opt_ty = format!("Option{}", prim_to_diplomat_view_suffix(p));
                Ok(FieldShape::OptionPrim { jolt_ty: jolt_ty.to_string(), opt_ty })
            }
            Type::Enum(ep) => {
                let ed = tcx.resolve_enum(ep.tcx_id);
                let enum_name = ed.name.as_str().to_string();
                let enum_alias = to_kebab(&enum_name);
                extra_requires.insert(enum_name.clone());
                Ok(FieldShape::OptionEnum { enum_name, enum_alias })
            }
            other => Err(format!("Option<{other:?}> struct field — only Option<prim/enum> supported")),
        },
        Type::Struct(sp) => {
            let sd = match sp.id() {
                hir::TypeId::Struct(sid) => tcx.resolve_struct(sid),
                other => return Err(format!("expected Struct TypeId, got {other:?}")),
            };
            let mut fields = vec![];
            for f in &sd.fields {
                fields.push((f.name.as_str().to_string(), resolve_field_shape(tcx, &f.ty, extra_requires)?));
            }
            Ok(FieldShape::Nested { c_struct_name: sd.name.as_str().to_string(), fields })
        }
        other => Err(format!("unsupported field/param type {other:?}")),
    }
}

fn flatten_leaves(
    shape: &FieldShape,
    flat_prefix: &str,
    clj_access: &str,
    out: &mut Vec<(String, String, String, String)>, // (c_ty, flat_c_name, jolt_ty, call_expr)
) {
    match shape {
        FieldShape::Prim { jolt_ty, c_ty, is_bool } => {
            let call_expr = if *is_bool { format!("(if {clj_access} 1 0)") } else { clj_access.to_string() };
            out.push((c_ty.clone(), flat_prefix.to_string(), jolt_ty.clone(), call_expr));
        }
        FieldShape::EnumField { c_ty, enum_alias } => {
            out.push((c_ty.clone(), flat_prefix.to_string(), ":int".to_string(),
                format!("({enum_alias}/kw->int {clj_access})")));
        }
        FieldShape::OptionPrim { jolt_ty, .. } => {
            // Flatten to two scalar C params; build_c_literal reconstructs OptionX.
            // _ok holds the value (0 if nil), _is_ok is the boolean.
            out.push(("int".to_string(), format!("{flat_prefix}_ok"), jolt_ty.clone(),
                format!("(or {clj_access} 0)")));
            out.push(("bool".to_string(), format!("{flat_prefix}_is_ok"), ":int".to_string(),
                format!("(if (nil? {clj_access}) 0 1)")));
        }
        FieldShape::OptionEnum { enum_name, enum_alias } => {
            out.push((enum_name.clone(), format!("{flat_prefix}_ok"), ":int".to_string(),
                format!("(if {clj_access} ({enum_alias}/kw->int {clj_access}) 0)")));
            out.push(("bool".to_string(), format!("{flat_prefix}_is_ok"), ":int".to_string(),
                format!("(if (nil? {clj_access}) 0 1)")));
        }
        FieldShape::Nested { fields, .. } => {
            for (fname, sub) in fields {
                let sub_prefix = format!("{flat_prefix}_{fname}");
                let sub_access = format!("(:{} {clj_access})", to_kebab(fname));
                flatten_leaves(sub, &sub_prefix, &sub_access, out);
            }
        }
    }
}

fn build_c_literal(shape: &FieldShape, flat_prefix: &str) -> String {
    match shape {
        FieldShape::Prim { .. } | FieldShape::EnumField { .. } => flat_prefix.to_string(),
        FieldShape::OptionPrim { opt_ty, .. } => {
            // Option{Suffix} = { union { T ok; }; bool is_ok; }
            // C param is named {flat_prefix}_ok (from flatten_leaves), not {flat_prefix}.
            format!("({opt_ty}){{ .ok = {flat_prefix}_ok, .is_ok = {flat_prefix}_is_ok }}")
        }
        FieldShape::OptionEnum { enum_name, .. } => {
            // {EnumName}_option typedef in per-type .d.h headers.
            format!("({enum_name}_option){{ .ok = {flat_prefix}_ok, .is_ok = {flat_prefix}_is_ok }}")
        }
        FieldShape::Nested { c_struct_name, fields } => {
            let inits: Vec<String> = fields.iter()
                .map(|(fname, sub)| format!(".{fname} = {}", build_c_literal(sub, &format!("{flat_prefix}_{fname}"))))
                .collect();
            format!("({c_struct_name}){{ {} }}", inits.join(", "))
        }
    }
}

/// Collect leaf fields for a struct return, returning
/// (kebab_name, c_field_path, jolt_read_ty) per leaf.
/// c_field_path is usable in offsetof(StructName, c_field_path).
/// jolt_read_ty is the keyword for ffi/read (or ":u16" for dr/read-u16).
fn collect_struct_field_leaves(
    fields: &[(String, FieldShape)],
    prefix_kebab: &str,
    prefix_c: &str,
    out: &mut Vec<(String, String, String)>,
) {
    for (fname, shape) in fields {
        let kebab = if prefix_kebab.is_empty() {
            to_kebab(fname)
        } else {
            format!("{prefix_kebab}-{}", to_kebab(fname))
        };
        let c_path = if prefix_c.is_empty() {
            fname.clone()
        } else {
            format!("{prefix_c}.{fname}")
        };
        match shape {
            FieldShape::Prim { jolt_ty, c_ty, .. } => {
                let read_ty = match c_ty.as_str() {
                    "bool" => ":uint8".to_string(),
                    "uint16_t" | "int16_t" => ":u16".to_string(),
                    _ => jolt_ty.clone(),
                };
                out.push((kebab, c_path, read_ty));
            }
            FieldShape::EnumField { .. } => {
                out.push((kebab, c_path, ":int".to_string()));
            }
            FieldShape::Nested { fields: sub_fields, .. } => {
                collect_struct_field_leaves(sub_fields, &kebab, &c_path, out);
            }
            _ => {} // OptionPrim/OptionEnum in struct fields — not yet supported
        }
    }
}

fn gen_opaque_clj(tcx: &hir::TypeContext, op: &hir::OpaqueDef, shim_c: &mut String) -> String {
    let name = op.name.as_str();
    let mut body = String::new();
    let _ = writeln!(body, "(dr/defopaque {} \"{}\")", name, op.dtor_abi_name);
    let _ = writeln!(body);

    let mut extra_requires: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();
    for m in &op.methods {
        let _ = gen_method(tcx, name, m, &mut body, shim_c, &mut extra_requires);
    }

    let mut out = String::new();
    let _ = writeln!(out, "(ns diplomat.{}", to_kebab(name));
    let _ = writeln!(out, "  \"GENERATED by jolt-diplomat-backend from the real Diplomat HIR.");
    let _ = writeln!(out, "  Do not hand-edit — see findings/milestone-5-findings.md.\"");
    let _ = writeln!(out, "  (:require [jolt.ffi :as ffi]");
    let _ = writeln!(out, "            [diplomat.runtime :as dr]");
    for enum_name in &extra_requires {
        let _ = writeln!(out, "            [diplomat.{} :as {}]", to_kebab(enum_name), to_kebab(enum_name));
    }
    let _ = writeln!(out, "  ))");
    let _ = writeln!(out);
    out.push_str(&body);
    out
}

/// Classify the return type of a method into one unambiguous variant.
/// Computed once, replaces the chain of successive Option guards.
enum ReturnKind {
    Unit,
    Write,
    NullableWrite,
    Fallible { is_write: bool, is_unit: bool },
    BorrowedSlice { jolt_ty: &'static str, c_ty: &'static str, view_suffix: &'static str },
    StructReturn { name: String, fields: Vec<(String, FieldShape)> },
    NullablePrim { jolt_ty: &'static str, c_ty: &'static str },
    Enum { name: String, alias: String },
    NullableOpaque { target: String },
    Opaque { target: String },
    PlainPrim { c_ty: &'static str },
}

fn classify_return(
    tcx: &hir::TypeContext,
    owner: &str,
    m: &hir::Method,
    extra_requires: &mut std::collections::BTreeSet<String>,
) -> Option<ReturnKind> {
    match &m.output {
        ReturnType::Infallible(SuccessType::Unit) => Some(ReturnKind::Unit),

        ReturnType::Infallible(SuccessType::Write) => Some(ReturnKind::Write),

        ReturnType::Nullable(SuccessType::Write) => Some(ReturnKind::NullableWrite),

        ReturnType::Fallible(st, _) => Some(ReturnKind::Fallible {
            is_write: matches!(st, SuccessType::Write),
            is_unit: matches!(st, SuccessType::Unit),
        }),

        ReturnType::Infallible(SuccessType::OutType(hir::OutType::Slice(
            hir::Slice::Primitive(_, prim)
        ))) => {
            let (jolt_ty, c_ty) = prim_to_jolt_and_c(prim);
            let view_suffix = prim_to_diplomat_view_suffix(prim);
            Some(ReturnKind::BorrowedSlice { jolt_ty, c_ty, view_suffix })
        }

        ReturnType::Infallible(SuccessType::OutType(hir::OutType::Struct(sp))) => {
            let sd = match sp.id() {
                hir::TypeId::Struct(sid) => tcx.resolve_struct(sid),
                other => {
                    eprintln!("skipped {owner}::{} (struct return TypeId {other:?})", m.name);
                    return None;
                }
            };
            let mut fields = vec![];
            for f in &sd.fields {
                match resolve_field_shape(tcx, &f.ty, extra_requires) {
                    Ok(shape) => fields.push((f.name.as_str().to_string(), shape)),
                    Err(e) => {
                        eprintln!("skipped {owner}::{} (struct return field {}: {e})", m.name, f.name);
                        return None;
                    }
                }
            }
            Some(ReturnKind::StructReturn { name: sd.name.as_str().to_string(), fields })
        }

        ReturnType::Nullable(SuccessType::OutType(hir::OutType::Primitive(p))) => {
            let (jolt_ty, c_ty) = prim_to_jolt_and_c(p);
            Some(ReturnKind::NullablePrim { jolt_ty, c_ty })
        }

        ReturnType::Infallible(SuccessType::OutType(hir::OutType::Enum(ep))) => {
            let ed = tcx.resolve_enum(ep.tcx_id);
            let name = ed.name.as_str().to_string();
            let alias = to_kebab(&name);
            extra_requires.insert(name.clone());
            Some(ReturnKind::Enum { name, alias })
        }

        ReturnType::Infallible(SuccessType::OutType(hir::OutType::Opaque(op))) => {
            let target = tcx.resolve_opaque(op.tcx_id).name.as_str().to_string();
            if target != owner { extra_requires.insert(target.clone()); }
            if op.is_optional() {
                Some(ReturnKind::NullableOpaque { target })
            } else {
                Some(ReturnKind::Opaque { target })
            }
        }

        ReturnType::Infallible(SuccessType::OutType(hir::OutType::Primitive(p))) => {
            Some(ReturnKind::PlainPrim { c_ty: prim_to_jolt_and_c(p).1 })
        }

        other => {
            eprintln!("skipped {owner}::{} (unsupported return {other:?})", m.name);
            None
        }
    }
}

// Returns false if the method was skipped (unsupported shape).
fn gen_method(
    tcx: &hir::TypeContext,
    owner: &str,
    m: &hir::Method,
    out: &mut String,
    shim_c: &mut String,
    extra_requires: &mut std::collections::BTreeSet<String>,
) -> bool {
    let fn_name = to_kebab(m.name.as_str());
    let has_self = m.param_self.is_some();

    // Only skip owned-slice params (Strs) — Option<T> is now handled below.
    let has_owned_slice = m.params.iter().any(|p| matches!(p.ty, Type::Slice(hir::Slice::Strs(_))));
    if has_owned_slice {
        eprintln!("skipped {owner}::{} (owned-slice param — not supported)", m.name);
        return false;
    }

    let rk = match classify_return(tcx, owner, m, extra_requires) {
        Some(r) => r,
        None => return false,
    };

    let needs_shim = m.params.iter().any(|p| matches!(
        p.ty,
        Type::Struct(_) | Type::Slice(_) | Type::Callback(_) | Type::Enum(_) |
        Type::Opaque(_) | Type::DiplomatOption(_)
    )) || !matches!(rk, ReturnKind::Unit | ReturnKind::PlainPrim { .. }
                          | ReturnKind::Opaque { .. } | ReturnKind::NullableOpaque { .. });

    if !needs_shim {
        // Direct defcfn — only primitives in params and a simple return.
        let mut arg_types = vec![];
        if has_self { arg_types.push(":pointer".to_string()); }
        let mut public_names = vec![];
        let mut call_exprs = vec![];
        if has_self {
            public_names.push("self".to_string());
            call_exprs.push("(:ptr self)".to_string());
        }
        for p in &m.params {
            let Type::Primitive(prim) = &p.ty else {
                eprintln!("skipped {owner}::{} (direct-call: non-primitive param {})", m.name, p.name);
                return false;
            };
            let (jolt_ty, _c_ty) = prim_to_jolt_and_c(prim);
            arg_types.push(jolt_ty.to_string());
            let n = to_kebab(p.name.as_str());
            public_names.push(n.clone());
            call_exprs.push(n);
        }
        let ret_ty = match &rk {
            ReturnKind::Unit => ":void",
            ReturnKind::PlainPrim { c_ty } => c_ty_to_jolt_kw(c_ty),
            ReturnKind::Opaque { .. } | ReturnKind::NullableOpaque { .. } => ":pointer",
            _ => unreachable!(),
        };
        let c_sym = m.abi_name.to_string();
        let _ = writeln!(out, "(ffi/defcfn ^:private c-{fn_name} \"{c_sym}\" [{}] {ret_ty})",
            arg_types.join(" "));
        let call = format!("(c-{fn_name} {})", call_exprs.join(" "));
        let body = match &rk {
            ReturnKind::NullableOpaque { target } => {
                let wrap = emit_opaque_wrap(target, owner, "p", true);
                format!("(let [p {call}] (when (not= 0 p) {wrap}))")
            }
            ReturnKind::Opaque { target } => emit_opaque_wrap(target, owner, &call, false),
            _ => call,
        };
        let _ = writeln!(out, "(defn {fn_name} [{}] {body})", public_names.join(" "));
        let _ = writeln!(out);
        return true;
    }

    // Shimmed path.
    struct ArgSpec {
        clj_type: String,
        call_expr: String,
    }
    let shim_sym = format!("jolt_{}", m.abi_name);
    let mut c_params = vec![];
    let mut arg_specs: Vec<ArgSpec> = vec![];
    let mut buffer_wraps: Vec<(String, String, String)> = vec![];
    let mut callback_wraps: Vec<String> = vec![];

    if has_self {
        let self_const = match &m.param_self {
            Some(ps) if matches!(ps.ty, hir::SelfType::Opaque(ref o) if o.owner.mutability == hir::Mutability::Mutable) => "",
            _ => "const ",
        };
        c_params.push(format!("{self_const}{owner}* self"));
        arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: "(:ptr self)".into() });
    }

    let mut call_args = vec![];
    if has_self { call_args.push("self".to_string()); }

    for p in &m.params {
        let pname = to_kebab(p.name.as_str()); // Jolt name (kebab)
        let cname = safe_c_ident(p.name.as_str()); // C name (snake, keyword-safe)
        match &p.ty {
            Type::Primitive(prim) => {
                let (jolt_ty, c_ty) = prim_to_jolt_and_c(prim);
                c_params.push(format!("{c_ty} {cname}"));
                arg_specs.push(ArgSpec { clj_type: jolt_ty.into(), call_expr: pname.clone() });
                call_args.push(cname);
            }
            Type::Enum(ep) => {
                let ed = tcx.resolve_enum(ep.tcx_id);
                let enum_name = ed.name.as_str().to_string();
                let enum_alias = to_kebab(&enum_name);
                extra_requires.insert(enum_name.clone());
                c_params.push(format!("{enum_name} {cname}"));
                arg_specs.push(ArgSpec {
                    clj_type: ":int".into(),
                    call_expr: format!("({enum_alias}/kw->int {pname})"),
                });
                call_args.push(cname);
            }
            Type::Slice(hir::Slice::Str(_, hir::StringEncoding::Utf8 | hir::StringEncoding::UnvalidatedUtf8)) => {
                c_params.push(format!("const char* {cname}_data"));
                c_params.push(format!("size_t {cname}_len"));
                arg_specs.push(ArgSpec { clj_type: ":string".into(), call_expr: pname.clone() });
                arg_specs.push(ArgSpec { clj_type: ":size_t".into(), call_expr: format!("(count {pname})") });
                call_args.push(format!("(DiplomatStringView){{ .data = {cname}_data, .len = {cname}_len }}"));
            }
            Type::Slice(hir::Slice::Str(_, hir::StringEncoding::UnvalidatedUtf16)) => {
                // DiplomatString16View — {char16_t* data, size_t len}.
                c_params.push(format!("const char16_t* {cname}_data"));
                c_params.push(format!("size_t {cname}_len"));
                arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: pname.clone() });
                arg_specs.push(ArgSpec { clj_type: ":size_t".into(), call_expr: format!("(count {pname})") });
                call_args.push(format!("(DiplomatString16View){{ .data = {cname}_data, .len = {cname}_len }}"));
            }
            Type::Slice(hir::Slice::Primitive(borrow, prim)) => {
                let (jolt_ty, c_ty) = prim_to_jolt_and_c(prim);
                let view_suffix = prim_to_diplomat_view_suffix(prim);
                let is_mut = borrow.as_borrowed().map(|b| b.mutability == hir::Mutability::Mutable).unwrap_or(false);
                let const_kw = if is_mut { "" } else { "const " };
                let view_mut = if is_mut { "Mut" } else { "" };
                c_params.push(format!("{const_kw}{c_ty}* {cname}_data"));
                c_params.push(format!("size_t {cname}_len"));
                arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: format!("{pname}-buf") });
                arg_specs.push(ArgSpec { clj_type: ":size_t".into(), call_expr: format!("(count {pname})") });
                buffer_wraps.push((format!("{pname}-buf"), jolt_ty.to_string(), pname.clone()));
                call_args.push(format!("(Diplomat{view_suffix}View{view_mut}){{ .data = {cname}_data, .len = {cname}_len }}"));
            }
            Type::Struct(_) => {
                let shape = match resolve_field_shape(tcx, &p.ty, extra_requires) {
                    Ok(s) => s,
                    Err(e) => {
                        eprintln!("skipped {owner}::{} (struct param {pname}: {e})", m.name);
                        return false;
                    }
                };
                let mut leaves = vec![];
                // Use cname as C prefix, pname as Jolt accessor prefix.
                flatten_leaves(&shape, &cname, &pname, &mut leaves);
                for (c_ty, flat_c_name, jolt_ty, call_expr) in &leaves {
                    c_params.push(format!("{c_ty} {flat_c_name}"));
                    arg_specs.push(ArgSpec { clj_type: jolt_ty.clone(), call_expr: call_expr.clone() });
                }
                call_args.push(build_c_literal(&shape, &cname));
            }
            Type::DiplomatOption(inner) => {
                // Option<T>: shim receives (T value, bool is_ok).
                // Jolt caller passes nil → is_ok=0, or the value → is_ok=1.
                match inner.as_ref() {
                    Type::Primitive(prim) => {
                        let (jolt_ty, c_ty) = prim_to_jolt_and_c(prim);
                        let view_suffix = prim_to_diplomat_view_suffix(prim);
                        let opt_ty = format!("Option{view_suffix}");
                        c_params.push(format!("{c_ty} {cname}_value"));
                        c_params.push(format!("bool {cname}_is_ok"));
                        arg_specs.push(ArgSpec { clj_type: jolt_ty.into(), call_expr: format!("(or {pname} 0)") });
                        arg_specs.push(ArgSpec { clj_type: ":int".into(), call_expr: format!("(if (nil? {pname}) 0 1)") });
                        call_args.push(format!("({opt_ty}){{ .ok = {cname}_value, .is_ok = {cname}_is_ok }}"));
                    }
                    Type::Enum(ep) => {
                        let ed = tcx.resolve_enum(ep.tcx_id);
                        let enum_name = ed.name.as_str().to_string();
                        let enum_alias = to_kebab(&enum_name);
                        extra_requires.insert(enum_name.clone());
                        let opt_ty = format!("{enum_name}_option");
                        c_params.push(format!("{enum_name} {cname}_value"));
                        c_params.push(format!("bool {cname}_is_ok"));
                        arg_specs.push(ArgSpec {
                            clj_type: ":int".into(),
                            call_expr: format!("(if {pname} ({enum_alias}/kw->int {pname}) 0)"),
                        });
                        arg_specs.push(ArgSpec { clj_type: ":int".into(), call_expr: format!("(if (nil? {pname}) 0 1)") });
                        call_args.push(format!("({opt_ty}){{ .ok = {cname}_value, .is_ok = {cname}_is_ok }}"));
                    }
                    other => {
                        eprintln!("skipped {owner}::{} (Option<{other:?}> param — only prim/enum supported)", m.name);
                        return false;
                    }
                }
            }
            Type::Opaque(op) => {
                let op_name = tcx.resolve_opaque(op.tcx_id).name.as_str().to_string();
                c_params.push(format!("const {op_name}* {cname}"));
                arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: format!("(:ptr {pname})") });
                call_args.push(cname);
            }
            Type::Callback(cb) => {
                let mut cb_c_params = vec!["const void*".to_string()];
                let mut cb_jolt_param_types = vec![];
                let mut cb_param_names = vec![];
                for (i, cp) in cb.params.iter().enumerate() {
                    let Type::Primitive(prim) = &cp.ty else {
                        eprintln!("skipped {owner}::{} (callback param type {:?} unsupported)", m.name, cp.ty);
                        return false;
                    };
                    let (jolt_ty, c_ty) = prim_to_jolt_and_c(prim);
                    cb_c_params.push(c_ty.to_string());
                    cb_jolt_param_types.push(jolt_ty.to_string());
                    cb_param_names.push(format!("a{i}"));
                }
                let (cb_ret_c, cb_ret_jolt) = match cb.output.as_ref() {
                    ReturnType::Infallible(SuccessType::Unit) => ("void".to_string(), ":void".to_string()),
                    ReturnType::Infallible(SuccessType::OutType(Type::Primitive(p))) => {
                        let (jolt_ty, c_ty) = prim_to_jolt_and_c(p);
                        (c_ty.to_string(), jolt_ty.to_string())
                    }
                    other => {
                        eprintln!("skipped {owner}::{} (callback return type {other:?} unsupported)", m.name);
                        return false;
                    }
                };

                let struct_name = format!("DiplomatCallback_{owner}_{}_f", m.name);
                c_params.push(format!("const void* {cname}_data"));
                c_params.push(format!("{cb_ret_c} (*{cname}_run_callback)({})", cb_c_params.join(", ")));
                c_params.push(format!("void (*{cname}_destructor)(const void*)"));

                arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: "ffi/null".into() });
                arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: format!("{pname}-run-cb") });
                arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: format!("{pname}-destructor") });

                let cb_fn_params = format!("_data {}", cb_param_names.join(" "));
                let cb_fn_call = format!("({pname} {})", cb_param_names.join(" "));
                let cb_ffi_types = std::iter::once(":pointer".to_string())
                    .chain(cb_jolt_param_types.iter().cloned())
                    .collect::<Vec<_>>()
                    .join(" ");

                callback_wraps.push(format!(
                    "(let [{pname}-state (atom nil)\n\
                     {indent}      {pname}-run-cb (ffi/foreign-callable\n\
                     {indent}                    (fn [{cb_fn_params}] {cb_fn_call})\n\
                     {indent}                    [{cb_ffi_types}] {cb_ret_jolt} :collect-safe)\n\
                     {indent}      {pname}-destructor (ffi/foreign-callable\n\
                     {indent}                        (fn [_data]\n\
                     {indent}                          (let [{{:keys [run-cb destructor]}} @{pname}-state]\n\
                     {indent}                            (ffi/free-callable run-cb)\n\
                     {indent}                            (ffi/free-callable destructor)))\n\
                     {indent}                        [:pointer] :void :collect-safe)]\n\
                     {indent}  (reset! {pname}-state {{:run-cb {pname}-run-cb :destructor {pname}-destructor}})",
                    pname = pname, indent = "", cb_fn_params = cb_fn_params, cb_fn_call = cb_fn_call,
                    cb_ffi_types = cb_ffi_types, cb_ret_jolt = cb_ret_jolt
                ));

                call_args.push(format!(
                    "({struct_name}){{ .data = {cname}_data, .run_callback = {cname}_run_callback, .destructor = {cname}_destructor }}"
                ));
            }
            other => {
                eprintln!("skipped {owner}::{} (unsupported param type {other:?})", m.name);
                return false;
            }
        }
    }

    let mut public_params = vec![];
    if has_self { public_params.push("self".to_string()); }
    for p in &m.params {
        public_params.push(to_kebab(p.name.as_str()));
    }

    let is_write = matches!(rk, ReturnKind::Write | ReturnKind::Fallible { is_write: true, .. });
    let is_nullable_write = matches!(rk, ReturnKind::NullableWrite);
    let out_ptr_needed = matches!(rk, ReturnKind::Fallible { .. });

    if is_write || is_nullable_write {
        c_params.push("DiplomatWrite* write".to_string());
        arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: "w".into() });
        call_args.push("write".to_string());
    }

    // If the error type is an opaque, capture its name for message extraction.
    let opaque_error: Option<(String, String)> = if let ReturnType::Fallible(_, Some(Type::Opaque(ep))) = &m.output {
        let err_name = tcx.resolve_opaque(ep.tcx_id).name.as_str().to_string();
        let err_alias = to_kebab(&err_name);
        extra_requires.insert(err_name.clone());
        Some((err_name, err_alias))
    } else { None };

    if out_ptr_needed {
        c_params.push("void* out".to_string());
        arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: "out".into() });
    }

    if let ReturnKind::BorrowedSlice { c_ty, .. } = &rk {
        c_params.push(format!("const {c_ty}** data_out"));
        c_params.push("size_t* len_out".to_string());
        arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: "data-out".into() });
        arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: "len-out".into() });
    }

    if let ReturnKind::NullablePrim { c_ty, .. } = &rk {
        c_params.push(format!("{c_ty}* out_val"));
        c_params.push("bool* out_is_ok".to_string());
        arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: "out-val".into() });
        arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: "out-is-ok".into() });
    }

    if let ReturnKind::StructReturn { name: struct_name, .. } = &rk {
        c_params.push(format!("{struct_name}* out"));
        arg_specs.push(ArgSpec { clj_type: ":pointer".into(), call_expr: "out".into() });
    }

    let shim_c_ret = match &rk {
        ReturnKind::Enum { name, .. } => name.as_str(),
        ReturnKind::NullableWrite => "int",
        ReturnKind::BorrowedSlice { .. } | ReturnKind::NullablePrim { .. }
        | ReturnKind::StructReturn { .. } | ReturnKind::Fallible { .. } => "void",
        ReturnKind::Opaque { .. } => "void*",
        ReturnKind::Unit | ReturnKind::Write | ReturnKind::NullableOpaque { .. } => "void",
        ReturnKind::PlainPrim { c_ty } => c_ty,
    };

    let real_call = format!("{}({})", m.abi_name, call_args.join(", "));
    let _ = writeln!(shim_c, "{shim_c_ret} {shim_sym}({}) {{", c_params.join(", "));

    match &rk {
        ReturnKind::NullablePrim { c_ty, .. } => {
            let result_ty = format!("{}_result", m.abi_name);
            let _ = writeln!(shim_c, "    {result_ty} r = {real_call};");
            let _ = writeln!(shim_c, "    *out_val = ({c_ty})r.ok;");
            let _ = writeln!(shim_c, "    *out_is_ok = r.is_ok;");
        }
        ReturnKind::StructReturn { name: struct_name, .. } => {
            let _ = writeln!(shim_c, "    {struct_name} r = {real_call};");
            let _ = writeln!(shim_c, "    memcpy(out, &r, sizeof(r));");
        }
        ReturnKind::BorrowedSlice { view_suffix, .. } => {
            let _ = writeln!(shim_c, "    Diplomat{view_suffix}View sv = {real_call};");
            let _ = writeln!(shim_c, "    *data_out = sv.data;");
            let _ = writeln!(shim_c, "    *len_out = sv.len;");
        }
        ReturnKind::Fallible { .. } => {
            let result_ty = format!("{}_result", m.abi_name);
            let _ = writeln!(shim_c, "    {result_ty} r = {real_call};");
            let _ = writeln!(shim_c, "    memcpy(out, &r, sizeof(r));");
        }
        ReturnKind::NullableWrite => {
            let result_ty = format!("{}_result", m.abi_name);
            let _ = writeln!(shim_c, "    {result_ty} r = {real_call};");
            let _ = writeln!(shim_c, "    return (int)r.is_ok;");
        }
        ReturnKind::Unit | ReturnKind::Write | ReturnKind::NullableOpaque { .. } => {
            let _ = writeln!(shim_c, "    {real_call};");
        }
        _ => {
            let _ = writeln!(shim_c, "    return {real_call};");
        }
    }
    let _ = writeln!(shim_c, "}}");
    let _ = writeln!(shim_c);

    let clj_ret_ty = match &rk {
        ReturnKind::BorrowedSlice { .. } | ReturnKind::StructReturn { .. }
        | ReturnKind::NullablePrim { .. } | ReturnKind::Fallible { .. }
        | ReturnKind::Unit | ReturnKind::Write => ":void",
        ReturnKind::Enum { .. } | ReturnKind::NullableWrite => ":int",
        ReturnKind::Opaque { .. } | ReturnKind::NullableOpaque { .. } => ":pointer",
        ReturnKind::PlainPrim { c_ty } => c_ty_to_jolt_kw(c_ty),
    };

    let shim_arg_types: Vec<String> = arg_specs.iter().map(|a| a.clj_type.clone()).collect();
    let shim_call_exprs: Vec<String> = arg_specs.iter().map(|a| a.call_expr.clone()).collect();
    // For write paths: replace the "w" sentinel with the lambda arg name "w__".
    let exprs_w_as_arg: Vec<String> = shim_call_exprs.iter()
        .map(|s| if s == "w" { "w__".to_string() } else { s.clone() })
        .collect();

    // Struct return: emit sizeof + per-field offsetof shims, then defcfn + body.
    if let ReturnKind::StructReturn { name: struct_name, fields } = &rk {
        let struct_snake = to_snake(struct_name);
        let struct_kebab = to_kebab(struct_name);
        let sizeof_sym = format!("jolt_sizeof_{struct_snake}_mv1");
        let _ = writeln!(out, "(ffi/defcfn ^:private c-sizeof-{struct_kebab}-struct \"{sizeof_sym}\" [] :int)");
        let _ = writeln!(shim_c, "size_t {sizeof_sym}(void) {{ return sizeof({struct_name}); }}");

        let mut leaf_fields: Vec<(String, String, String)> = vec![];
        collect_struct_field_leaves(fields, "", "", &mut leaf_fields);

        // Emit one offsetof shim + defcfn per leaf field.
        for (fname_kebab, c_field_path, _) in &leaf_fields {
            let field_snake = fname_kebab.replace('-', "_");
            let offsetof_sym = format!("jolt_offsetof_{struct_snake}_{field_snake}_mv1");
            let _ = writeln!(shim_c, "size_t {offsetof_sym}(void) {{ return offsetof({struct_name}, {c_field_path}); }}");
            let _ = writeln!(out, "(ffi/defcfn ^:private c-offsetof-{struct_kebab}-{fname_kebab} \"{offsetof_sym}\" [] :int)");
        }

        let reads: Vec<String> = leaf_fields.iter()
            .map(|(fname_kebab, _, jt)| {
                let off_call = format!("(c-offsetof-{struct_kebab}-{fname_kebab})");
                let read_expr = if jt == ":u16" {
                    format!("(dr/read-u16 out {off_call})")
                } else {
                    format!("(ffi/read out {jt} {off_call})")
                };
                format!(":{fname_kebab} {read_expr}")
            })
            .collect();

        let _ = writeln!(out, "(ffi/defcfn ^:private c-{fn_name} \"{shim_sym}\" [{}] {clj_ret_ty})",
            shim_arg_types.join(" "));
        let _ = writeln!(out, "(defn {fn_name} [{}]", public_params.join(" "));
        let _ = writeln!(out, "  (let [sz (c-sizeof-{struct_kebab}-struct) out (ffi/alloc sz)]");
        let _ = writeln!(out, "    (try");
        let _ = writeln!(out, "      (c-{fn_name} {})", shim_call_exprs.join(" "));
        let _ = writeln!(out, "      {{{}}}", reads.join(" "));
        let _ = writeln!(out, "      (finally (ffi/free out)))))");
        let _ = writeln!(out);
        return true;
    }

    let _ = writeln!(out, "(ffi/defcfn ^:private c-{fn_name} \"{shim_sym}\" [{}] {clj_ret_ty})",
        shim_arg_types.join(" "));

    let mut body_lines: Vec<String> = vec![];

    // Helper: build the msg_fn suffix for unwrap-result!
    let msg_fn_suffix = |opaque_error: &Option<(String, String)>, fn_name: &str| -> String {
        match opaque_error.as_ref().map(|(_, a)| format!("{a}/message")) {
            Some(mf) => format!("     \"{owner}/{fn_name}\" {mf})"),
            None      => format!("     \"{owner}/{fn_name}\")"),
        }
    };

    if out_ptr_needed {
        let result_sizeof_sym = format!("jolt_sizeof_{}_result", m.abi_name);
        let result_is_ok_offset_sym = format!("jolt_offsetof_{}_result_is_ok", m.abi_name);
        let result_c_ty = format!("{}_result", m.abi_name);
        let _ = writeln!(out, "(ffi/defcfn ^:private c-sizeof-{fn_name}-result \"{result_sizeof_sym}\" [] :int)");
        let _ = writeln!(out, "(ffi/defcfn ^:private c-is-ok-offset-{fn_name} \"{result_is_ok_offset_sym}\" [] :int)");
        let _ = writeln!(shim_c, "size_t {result_sizeof_sym}(void) {{ return sizeof({result_c_ty}); }}");
        let _ = writeln!(shim_c, "size_t {result_is_ok_offset_sym}(void) {{ return offsetof({result_c_ty}, is_ok); }}");
        // Fix 1: error opaques are owned (closed?=false), not already-freed (closed?=true).
        let err_read = match &opaque_error {
            Some((err_name, err_alias)) =>
                format!("({err_alias}/->{err_name} (ffi/read out :pointer 0) false)"),
            None => "(ffi/read out :int 0)".to_string(),
        };
        if is_write {
            let inner_call = format!("(c-{fn_name} {})", exprs_w_as_arg.join(" "));
            let msg_suffix = msg_fn_suffix(&opaque_error, &fn_name);
            body_lines.push(format!("(let [sz (c-sizeof-{fn_name}-result) out (ffi/alloc sz) is-ok-off (c-is-ok-offset-{fn_name})]"));
            body_lines.push("  (try".to_string());
            body_lines.push(format!("    (let [s (dr/writeable-capture (fn [w__] {inner_call}))]"));
            body_lines.push("      (dr/unwrap-result!".to_string());
            body_lines.push("       (if (= 1 (ffi/read out :uint8 is-ok-off))".to_string());
            body_lines.push("         {:ok? true :value s}".to_string());
            body_lines.push(format!("         {{:ok? false :error {err_read}}})"));
            body_lines.push(format!("      {msg_suffix}")); // closes unwrap-result!
            body_lines.push("    )".to_string());           // closes let [s ...]
            body_lines.push("    (finally (ffi/free out))))".to_string());
        } else {
            let ok_val = if matches!(rk, ReturnKind::Fallible { is_unit: true, .. }) {
                "nil".to_string()
            } else {
                format!("(->{owner} (ffi/read out :pointer 0) false)")
            };
            body_lines.push(format!("(let [sz (c-sizeof-{fn_name}-result) out (ffi/alloc sz) is-ok-off (c-is-ok-offset-{fn_name})]"));
            body_lines.push("  (try".to_string());
            body_lines.push(format!("    (c-{fn_name} {})", shim_call_exprs.join(" ")));
            body_lines.push("    (dr/unwrap-result!".to_string());
            body_lines.push("     (if (= 1 (ffi/read out :uint8 is-ok-off))".to_string());
            body_lines.push(format!("       {{:ok? true :value {ok_val}}}"));
            body_lines.push(format!("       {{:ok? false :error {err_read}}})"));
            body_lines.push(msg_fn_suffix(&opaque_error, &fn_name));
            body_lines.push("    (finally (ffi/free out))))".to_string());
        }
    } else if is_nullable_write {
        let inner_call = format!("(c-{fn_name} {})", exprs_w_as_arg.join(" "));
        body_lines.push(format!("(dr/writeable-capture-when (fn [w__] {inner_call}))"));
    } else if is_write {
        let inner_call = format!("(c-{fn_name} {})", exprs_w_as_arg.join(" "));
        body_lines.push(format!("(dr/writeable-capture (fn [w__] {inner_call}))"))
    } else if let ReturnKind::BorrowedSlice { jolt_ty, .. } = &rk {
        body_lines.push(format!("(let [data-out (ffi/alloc 8) len-out (ffi/alloc 8)]"));
        body_lines.push("  (try".to_string());
        body_lines.push(format!("    (c-{fn_name} {})", shim_call_exprs.join(" ")));
        body_lines.push(format!("    (let [ptr (ffi/read data-out :pointer 0) n (ffi/read len-out :size_t 0)]"));
        body_lines.push(format!("      (ffi/read-array ptr {jolt_ty} n))"));
        body_lines.push("    (finally (ffi/free data-out) (ffi/free len-out))))".to_string());
    } else if let ReturnKind::NullablePrim { jolt_ty, .. } = &rk {
        body_lines.push(format!("(let [out-val (ffi/alloc 8) out-is-ok (ffi/alloc 1)]"));
        body_lines.push("  (try".to_string());
        body_lines.push(format!("    (c-{fn_name} {})", shim_call_exprs.join(" ")));
        body_lines.push(format!("    (when (not= 0 (ffi/read out-is-ok :uint8 0)) (ffi/read out-val {jolt_ty} 0))"));
        body_lines.push("    (finally (ffi/free out-val) (ffi/free out-is-ok))))".to_string());
    } else if let ReturnKind::Enum { alias, .. } = &rk {
        body_lines.push(format!("({alias}/int->kw (c-{fn_name} {}))", shim_call_exprs.join(" ")));
    } else if let ReturnKind::Opaque { target } = &rk {
        let call_expr = format!("(c-{fn_name} {})", shim_call_exprs.join(" "));
        body_lines.push(emit_opaque_wrap(target, owner, &call_expr, false));
    } else {
        body_lines.push(format!("(c-{fn_name} {})", shim_call_exprs.join(" ")));
    }

    let mut indent = String::new();
    let mut prefix_lines: Vec<String> = vec![];
    let mut suffix_close = String::new();
    for cb_wrap in &callback_wraps {
        prefix_lines.push(cb_wrap.clone());
        indent.push_str("  ");
        suffix_close.push(')');
    }
    for (buf_var, elem_type, seq_expr) in &buffer_wraps {
        prefix_lines.push(format!("{indent}(dr/with-primitive-buffer [{buf_var} {elem_type} {seq_expr}]"));
        indent.push_str("  ");
        suffix_close.push(')');
    }

    let _ = writeln!(out, "(defn {fn_name} [{}]", public_params.join(" "));
    for line in &prefix_lines {
        let _ = writeln!(out, "  {line}");
    }
    for line in &body_lines {
        let _ = writeln!(out, "  {indent}{line}");
    }
    if !suffix_close.is_empty() {
        let _ = writeln!(out, "  {suffix_close}");
    }
    let _ = writeln!(out, ")");
    let _ = writeln!(out);
    true
}

fn gen_enum_clj(en: &hir::EnumDef) -> String {
    let mut out = String::new();
    let _ = writeln!(out, "(ns diplomat.{}", to_kebab(en.name.as_ref()));
    let _ = writeln!(out, "  \"GENERATED by jolt-diplomat-backend.\")");
    let _ = writeln!(out);
    let _ = writeln!(out, "(def kw->int {{");
    for v in &en.variants {
        let _ = writeln!(out, "  :{} {}", to_kebab(v.name.as_ref()), v.discriminant);
    }
    let _ = writeln!(out, "  }})");
    let _ = writeln!(out);
    let _ = writeln!(out, "(def int->kw (clojure.set/map-invert kw->int))");
    out
}
