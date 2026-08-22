#!/usr/bin/env python3
"""Static verification of every Mixin target in this project.

WHY THIS EXISTS
---------------
Minecraft 26.1+ ships unobfuscated, so this project uses the non-remapping Loom
plugin: there is no refmap and no Mixin annotation processor (see build.gradle,
`sourceSets.full`). Nothing therefore validates at build time that a mixin's
target actually exists — `./gradlew build` is green even when an @Inject names a
method that was renamed or never existed, because the target name is just a
string in an annotation.

That is not a soft failure at runtime. Both mixin configs declare
`"injectors": { "defaultRequire": 1 }`, so an injection point that resolves to
nothing aborts the game during class transformation. The first time you learn
about it is a crash on startup.

This script closes that gap. It reads the COMPILED mixin classes (so the
descriptors it checks are the ones javac actually emitted, not a regex guess at
the source) and resolves every target against the real Minecraft jar from the
Loom cache plus the project's compile classpath.

WHAT IT CHECKS
--------------
  * the @Mixin target class exists
  * every @Inject / @Redirect / @ModifyArg / @ModifyVariable / @ModifyConstant
    `method` selector resolves to at least one method on the target, with the
    exact descriptor when one is given
  * the handler's own signature agrees with the target (parameter prefix +
    CallbackInfo/CallbackInfoReturnable for @Inject, redirected-call shape for
    @Redirect, modified-argument type for @ModifyArg, argument type/index for
    @ModifyVariable) and its static-ness matches the target's
  * @At(value = "INVOKE"/"INVOKE_ASSIGN"/"FIELD"/"NEW") targets exist on the
    class they name
  * @Shadow fields and methods exist on the target (walking its superclasses)
    with a matching descriptor
  * @Accessor / @Invoker resolve to a real field / method of the right type
  * mixins listed in a config exist, and mixin classes on disk are listed

THIRD-PARTY TARGETS
-------------------
Mixins on optional foreign mods (Sodium, Xaero) can only be verified when those
jars are present. Drop them in run/mods/ (or pass --jar) and they are checked
like anything else; otherwise they are reported as unverified, and the script
checks instead that they are marked @Pseudo — verified against
sponge-mixin 0.17.3's MixinInfo.getTargetClass, which skips a missing target
with a debug log when the mixin is @Pseudo and only errors when it is not.

USAGE
-----
    ./gradlew classes          # the compiled mixins are the input
    python3 tools/verify_mixins.py

Exits non-zero when a target does not resolve. Deliberately NOT wired into
`./gradlew build` or `check` — run it after touching a mixin, and before
tagging a release. `./gradlew mixinVerifyClasspath` (optional task) refreshes
the library classpath this script reads; it is invoked automatically when the
cached file is missing.

WHAT IT DOES NOT COVER
----------------------
See the "Not covered" note printed at the end of a run: @At ordinals and shift,
slice expressions, lambda/synthetic targets, LOCAL capture, and anything that
depends on the *body* of a target method rather than its signature.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

# --------------------------------------------------------------------------
# class file parsing
# --------------------------------------------------------------------------

CONSTANT_SIZES = {
    3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
    15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2,
}

ACC_STATIC = 0x0008
ACC_INTERFACE = 0x0200
ACC_ABSTRACT = 0x0400


def u1(b, o):
    return b[o], o + 1


def u2(b, o):
    return int.from_bytes(b[o:o + 2], "big"), o + 2


def u4(b, o):
    return int.from_bytes(b[o:o + 4], "big"), o + 4


class ConstantPool:
    def __init__(self, entries):
        self.entries = entries

    def utf8(self, idx):
        tag, payload = self.entries[idx]
        assert tag == 1, f"cp[{idx}] is tag {tag}, not utf8"
        return payload

    def class_name(self, idx):
        if idx == 0:
            return None
        tag, payload = self.entries[idx]
        assert tag == 7
        return self.utf8(payload)

    def const(self, idx):
        tag, payload = self.entries[idx]
        if tag == 1:
            return payload
        if tag in (3, 4, 5, 6):
            return payload
        if tag == 8:
            return self.utf8(payload)
        return payload


def read_constant_pool(data, o):
    count, o = u2(data, o)
    entries = [None] * count
    i = 1
    while i < count:
        tag, o = u1(data, o)
        if tag == 1:
            length, o = u2(data, o)
            entries[i] = (1, data[o:o + length].decode("utf-8", "replace"))
            o += length
        elif tag == 3:
            v, o = u4(data, o)
            entries[i] = (3, v)
        elif tag == 4:
            v, o = u4(data, o)
            entries[i] = (4, v)
        elif tag == 5:
            v = int.from_bytes(data[o:o + 8], "big", signed=True)
            o += 8
            entries[i] = (5, v)
        elif tag == 6:
            o += 8
            entries[i] = (6, 0.0)
        else:
            size = CONSTANT_SIZES.get(tag)
            if size is None:
                raise ValueError(f"unknown constant pool tag {tag}")
            if size == 2:
                v, o = u2(data, o)
            else:
                v = data[o:o + size]
                o += size
            entries[i] = (tag, v)
        i += 2 if tag in (5, 6) else 1
    return ConstantPool(entries), o


def read_element_value(data, o, cp):
    tag, o = u1(data, o)
    t = chr(tag)
    if t in "BCDFIJSZs":
        idx, o = u2(data, o)
        return cp.const(idx), o
    if t == "e":
        type_idx, o = u2(data, o)
        name_idx, o = u2(data, o)
        return ("enum", cp.utf8(type_idx), cp.utf8(name_idx)), o
    if t == "c":
        idx, o = u2(data, o)
        return ("class", cp.utf8(idx)), o
    if t == "@":
        anno, o = read_annotation(data, o, cp)
        return ("anno", anno), o
    if t == "[":
        n, o = u2(data, o)
        values = []
        for _ in range(n):
            v, o = read_element_value(data, o, cp)
            values.append(v)
        return values, o
    raise ValueError(f"unknown element_value tag {t!r}")


def read_annotation(data, o, cp):
    type_idx, o = u2(data, o)
    n, o = u2(data, o)
    values = {}
    for _ in range(n):
        name_idx, o = u2(data, o)
        v, o = read_element_value(data, o, cp)
        values[cp.utf8(name_idx)] = v
    return {"desc": cp.utf8(type_idx), "values": values}, o


def read_annotations_attr(payload, cp):
    o = 0
    n, o = u2(payload, o)
    out = []
    for _ in range(n):
        anno, o = read_annotation(payload, o, cp)
        out.append(anno)
    return out


def read_attributes(data, o, cp):
    count, o = u2(data, o)
    attrs = {}
    for _ in range(count):
        name_idx, o = u2(data, o)
        length, o = u4(data, o)
        name = cp.utf8(name_idx)
        attrs.setdefault(name, []).append(data[o:o + length])
        o += length
    return attrs, o


def annotations_of(attrs, cp):
    out = []
    for key in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
        for payload in attrs.get(key, []):
            out.extend(read_annotations_attr(payload, cp))
    return out


class Member:
    __slots__ = ("name", "desc", "access", "annotations")

    def __init__(self, name, desc, access, annotations):
        self.name = name
        self.desc = desc
        self.access = access
        self.annotations = annotations

    @property
    def is_static(self):
        return bool(self.access & ACC_STATIC)

    def annotation(self, desc):
        for a in self.annotations:
            if a["desc"] == desc:
                return a
        return None

    def __repr__(self):
        return f"{self.name}{self.desc}"


class JavaClass:
    def __init__(self, name, super_name, interfaces, access, fields, methods, annotations):
        self.name = name
        self.super_name = super_name
        self.interfaces = interfaces
        self.access = access
        self.fields = fields
        self.methods = methods
        self.annotations = annotations

    @property
    def is_interface(self):
        return bool(self.access & ACC_INTERFACE)

    def annotation(self, desc):
        for a in self.annotations:
            if a["desc"] == desc:
                return a
        return None

    def methods_named(self, name):
        return [m for m in self.methods if m.name == name]

    def field_named(self, name):
        for f in self.fields:
            if f.name == name:
                return f
        return None


def parse_class(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    o = 8
    cp, o = read_constant_pool(data, o)
    access, o = u2(data, o)
    this_idx, o = u2(data, o)
    super_idx, o = u2(data, o)
    n_ifaces, o = u2(data, o)
    interfaces = []
    for _ in range(n_ifaces):
        idx, o = u2(data, o)
        interfaces.append(cp.class_name(idx))

    def read_members():
        nonlocal o
        count, o2 = u2(data, o)
        o = o2
        out = []
        for _ in range(count):
            m_access, o3 = u2(data, o)
            name_idx, o3 = u2(data, o3)
            desc_idx, o3 = u2(data, o3)
            attrs, o3 = read_attributes(data, o3, cp)
            o = o3
            out.append(Member(cp.utf8(name_idx), cp.utf8(desc_idx), m_access,
                              annotations_of(attrs, cp)))
        return out

    fields = read_members()
    methods = read_members()
    class_attrs, o = read_attributes(data, o, cp)
    return JavaClass(cp.class_name(this_idx), cp.class_name(super_idx), interfaces,
                     access, fields, methods, annotations_of(class_attrs, cp))


# --------------------------------------------------------------------------
# classpath index
# --------------------------------------------------------------------------

class ClassIndex:
    """Lazily decodes classes out of a list of jars and class directories."""

    def __init__(self):
        self._locations = {}     # internal name -> (kind, container, entry)
        self._cache = {}
        self._zips = {}
        self.sources = []

    def add_jar(self, path):
        path = str(path)
        try:
            zf = zipfile.ZipFile(path)
        except (OSError, zipfile.BadZipFile):
            return False
        self._zips[path] = zf
        added = 0
        for name in zf.namelist():
            if name.endswith(".class"):
                internal = name[:-6]
                if internal not in self._locations:
                    self._locations[internal] = ("jar", path, name)
                    added += 1
        self.sources.append((path, added))
        return True

    def add_dir(self, path):
        path = Path(path)
        if not path.is_dir():
            return False
        added = 0
        for f in path.rglob("*.class"):
            internal = str(f.relative_to(path))[:-6].replace(os.sep, "/")
            if internal not in self._locations:
                self._locations[internal] = ("dir", str(path), str(f))
                added += 1
        self.sources.append((str(path), added))
        return True

    def has(self, internal_name):
        return internal_name in self._locations

    def get(self, internal_name):
        if internal_name in self._cache:
            return self._cache[internal_name]
        loc = self._locations.get(internal_name)
        if loc is None:
            self._cache[internal_name] = None
            return None
        kind, container, entry = loc
        if kind == "jar":
            data = self._zips[container].read(entry)
        else:
            data = Path(entry).read_bytes()
        cls = parse_class(data)
        self._cache[internal_name] = cls
        return cls

    def hierarchy(self, internal_name):
        """Target class first, then superclasses, then interfaces (breadth-ish)."""
        seen = set()
        order = []
        queue = [internal_name]
        while queue:
            name = queue.pop(0)
            if not name or name in seen:
                continue
            seen.add(name)
            cls = self.get(name)
            if cls is None:
                continue
            order.append(cls)
            if cls.super_name:
                queue.append(cls.super_name)
            queue.extend(cls.interfaces)
        return order


# --------------------------------------------------------------------------
# descriptors
# --------------------------------------------------------------------------

def split_params(desc):
    """'(ILjava/lang/String;[I)V' -> (['I','Ljava/lang/String;','[I'], 'V')"""
    assert desc.startswith("("), desc
    end = desc.index(")")
    body = desc[1:end]
    ret = desc[end + 1:]
    params = []
    i = 0
    while i < len(body):
        start = i
        while body[i] == "[":
            i += 1
        if body[i] == "L":
            i = body.index(";", i) + 1
        else:
            i += 1
        params.append(body[start:i])
    return params, ret


def pretty_type(desc):
    arr = 0
    while desc.startswith("["):
        arr += 1
        desc = desc[1:]
    prims = {"V": "void", "Z": "boolean", "B": "byte", "C": "char", "S": "short",
             "I": "int", "J": "long", "F": "float", "D": "double"}
    if desc in prims:
        base = prims[desc]
    elif desc.startswith("L"):
        base = desc[1:-1].split("/")[-1].replace("$", ".")
    else:
        base = desc
    return base + "[]" * arr


def pretty_sig(name, desc):
    params, ret = split_params(desc)
    return f"{pretty_type(ret)} {name}({', '.join(pretty_type(p) for p in params)})"


def lvt_slots(params, is_static):
    """LVT index of each parameter, honouring the two-slot long/double rule."""
    idx = 0 if is_static else 1
    out = []
    for p in params:
        out.append(idx)
        idx += 2 if p in ("J", "D") else 1
    return out


CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"

A_MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;"
A_PSEUDO = "Lorg/spongepowered/asm/mixin/Pseudo;"
A_SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;"
A_UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;"
A_ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;"
A_INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;"

INJECTORS = {
    "Lorg/spongepowered/asm/mixin/injection/Inject;": "@Inject",
    "Lorg/spongepowered/asm/mixin/injection/Redirect;": "@Redirect",
    "Lorg/spongepowered/asm/mixin/injection/ModifyArg;": "@ModifyArg",
    "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;": "@ModifyArgs",
    "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;": "@ModifyVariable",
    "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;": "@ModifyConstant",
    "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;": "@ModifyExpressionValue",
    "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;": "@WrapOperation",
    "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;": "@WrapMethod",
}

# name(desc)ret, optionally prefixed with an owner and/or a mapping namespace
SELECTOR_RE = re.compile(
    r"^(?:(?P<owner>L[^;()]+;)\s*)?(?P<name>[^()]*?)\s*(?P<desc>\(.*\).*)?$")

MEMBER_RE = re.compile(
    r"^(?P<owner>L[^;()]+;)?(?P<name>[^;()]+)(?:(?P<desc>\(.*\).*)|:(?P<fdesc>.+))?$")


def as_list(v):
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


# --------------------------------------------------------------------------
# findings
# --------------------------------------------------------------------------

ERROR, WARN, INFO, OK = "ERROR", "WARN", "INFO", "OK"


class Report:
    def __init__(self):
        self.rows = []          # (level, mixin, target, detail)
        self.checked_injections = 0
        self.checked_shadows = 0
        self.checked_accessors = 0
        self.checked_at_targets = 0

    def add(self, level, mixin, target, detail):
        self.rows.append((level, mixin, target, detail))

    @property
    def errors(self):
        return [r for r in self.rows if r[0] == ERROR]

    @property
    def warnings(self):
        return [r for r in self.rows if r[0] == WARN]


# --------------------------------------------------------------------------
# verification
# --------------------------------------------------------------------------

def resolve_selector(index, target_cls, selector):
    """Return (matches, exact_desc_given, parse_ok)."""
    sel = selector.strip()
    if ":" in sel.split("(")[0] and not sel.startswith("L"):
        # strip a mapping namespace such as "named:"
        sel = sel.split(":", 1)[1]
    m = SELECTOR_RE.match(sel)
    if not m:
        return [], False, False
    name = m.group("name")
    desc = m.group("desc")
    owner = m.group("owner")
    search_cls = target_cls
    if owner:
        owned = index.get(owner[1:-1])
        if owned is not None:
            search_cls = owned
    if name in ("*", ""):
        return list(search_cls.methods), bool(desc), True
    candidates = [mm for mm in search_cls.methods if mm.name == name]
    if desc:
        candidates = [mm for mm in candidates if mm.desc == desc]
    return candidates, bool(desc), True


def check_at(index, report, mixin_name, at_anno, where):
    """Verify an @At member target exists. Returns the resolved Member or None."""
    values = at_anno["values"]
    kind = values.get("value")
    target = values.get("target")
    if not target:
        return None
    report.checked_at_targets += 1
    m = MEMBER_RE.match(target.strip())
    if not m or not m.group("owner"):
        report.add(WARN, mixin_name, where, f'@At target not parseable: "{target}"')
        return None
    owner = m.group("owner")[1:-1]
    name = m.group("name")
    desc = m.group("desc")
    fdesc = m.group("fdesc")
    owner_cls = index.get(owner)
    if owner_cls is None:
        level = ERROR if owner.startswith("net/minecraft/") else INFO
        report.add(level, mixin_name, where,
                   f'@At(value="{kind}") owner {owner} not on the verification classpath'
                   + (" — cannot verify" if level == INFO else ""))
        return None
    if kind == "NEW":
        return None
    if fdesc:  # field access
        for cls in index.hierarchy(owner):
            f = cls.field_named(name)
            if f is not None:
                if f.desc != fdesc:
                    report.add(ERROR, mixin_name, where,
                               f"@At FIELD {owner}.{name} has descriptor {f.desc}, "
                               f"selector says {fdesc}")
                return f
        report.add(ERROR, mixin_name, where, f"@At FIELD {owner}.{name} not found")
        return None
    hits = []
    for cls in index.hierarchy(owner):
        hits.extend([mm for mm in cls.methods
                     if mm.name == name and (desc is None or mm.desc == desc)])
    if not hits:
        report.add(ERROR, mixin_name, where,
                   f'@At(value="{kind}") target {owner}.{name}{desc or ""} not found')
        return None
    return hits[0]


def check_inject_signature(report, mixin_name, handler, target, where):
    hp, hret = split_params(handler.desc)
    tp, tret = split_params(target.desc)
    if hret != "V":
        report.add(ERROR, mixin_name, where,
                   f"@Inject handler must return void, returns {pretty_type(hret)}")
        return
    expected_ci = CI if tret == "V" else CIR
    if len(hp) < len(tp) + 1 or hp[:len(tp)] != tp:
        report.add(ERROR, mixin_name, where,
                   f"handler params ({', '.join(pretty_type(p) for p in hp)}) do not "
                   f"start with target params ({', '.join(pretty_type(p) for p in tp)})")
        return
    if hp[len(tp)] != expected_ci:
        report.add(ERROR, mixin_name, where,
                   f"handler param {len(tp)} is {pretty_type(hp[len(tp)])}, expected "
                   f"{pretty_type(expected_ci)} (target returns {pretty_type(tret)})")
        return
    extra = hp[len(tp) + 1:]
    if extra:
        report.add(INFO, mixin_name, where,
                   f"{len(extra)} trailing handler param(s) — LOCAL capture, not verified")


def check_modify_variable(report, mixin_name, handler, target, anno, where):
    hp, hret = split_params(handler.desc)
    tp, tret = split_params(target.desc)
    if len(hp) < 1 or hp[0] != hret:
        report.add(ERROR, mixin_name, where,
                   "@ModifyVariable handler must take and return the same type, "
                   f"got ({', '.join(pretty_type(p) for p in hp)}) -> {pretty_type(hret)}")
        return
    args_only = anno["values"].get("argsOnly", False)
    index_val = anno["values"].get("index")
    if not args_only:
        report.add(INFO, mixin_name, where,
                   "argsOnly not set — modifies a local, which is body-dependent and "
                   "not statically verifiable")
        return
    slots = lvt_slots(tp, target.is_static)
    if index_val is not None:
        if index_val not in slots:
            report.add(ERROR, mixin_name, where,
                       f"index = {index_val} is not an argument slot of "
                       f"{target.name}{target.desc} (arg slots: {slots})")
            return
        arg = tp[slots.index(index_val)]
        if arg != hret:
            report.add(ERROR, mixin_name, where,
                       f"index = {index_val} is {pretty_type(arg)}, handler modifies "
                       f"{pretty_type(hret)}")
        return
    matching = [i for i, p in enumerate(tp) if p == hret]
    if not matching:
        report.add(ERROR, mixin_name, where,
                   f"no {pretty_type(hret)} argument on {target.name}{target.desc}")
    elif len(matching) > 1:
        report.add(WARN, mixin_name, where,
                   f"{len(matching)} arguments of type {pretty_type(hret)} and no "
                   "explicit index — Mixin picks the first, which is fragile")


def check_redirect(report, mixin_name, handler, redirected, at_kind, where):
    if redirected is None:
        return
    hp, hret = split_params(handler.desc)
    if not redirected.desc.startswith("("):     # field redirect
        return
    tp, tret = split_params(redirected.desc)
    expected = list(tp)
    if not redirected.is_static and at_kind in ("INVOKE", "INVOKE_ASSIGN"):
        expected = ["<receiver>"] + expected
    got = list(hp)
    if len(got) < len(expected):
        report.add(ERROR, mixin_name, where,
                   f"@Redirect handler takes {len(got)} params, redirected call needs "
                   f"{len(expected)}")
        return
    offset = 0
    if expected and expected[0] == "<receiver>":
        offset = 1
        expected = expected[1:]
    if got[offset:offset + len(expected)] != expected:
        report.add(ERROR, mixin_name, where,
                   f"@Redirect handler params ({', '.join(pretty_type(p) for p in got)}) "
                   f"do not match redirected call ({', '.join(pretty_type(p) for p in expected)})")
        return
    if hret != tret:
        report.add(ERROR, mixin_name, where,
                   f"@Redirect handler returns {pretty_type(hret)}, redirected call "
                   f"returns {pretty_type(tret)}")


def check_modify_arg(report, mixin_name, handler, called, anno, where):
    if called is None or not called.desc.startswith("("):
        return
    hp, hret = split_params(handler.desc)
    tp, _ = split_params(called.desc)
    if len(hp) != 1 or hp[0] != hret:
        report.add(ERROR, mixin_name, where,
                   "@ModifyArg handler must be (T) -> T, got "
                   f"({', '.join(pretty_type(p) for p in hp)}) -> {pretty_type(hret)}")
        return
    index_val = anno["values"].get("index")
    if index_val is None:
        matching = [i for i, p in enumerate(tp) if p == hret]
        if not matching:
            report.add(ERROR, mixin_name, where,
                       f"called method has no {pretty_type(hret)} argument")
        elif len(matching) > 1:
            report.add(WARN, mixin_name, where,
                       f"{len(matching)} arguments of type {pretty_type(hret)} and no "
                       "explicit index")
        return
    if index_val >= len(tp):
        report.add(ERROR, mixin_name, where,
                   f"index = {index_val} but the called method has {len(tp)} arguments")
        return
    if tp[index_val] != hret:
        report.add(ERROR, mixin_name, where,
                   f"index = {index_val} is {pretty_type(tp[index_val])}, handler "
                   f"modifies {pretty_type(hret)}")


def accessor_field_name(anno, method_name):
    value = anno["values"].get("value")
    if value:
        return value
    bare = method_name.split("$")[-1]
    for prefix in ("get", "set", "is"):
        if bare.startswith(prefix) and len(bare) > len(prefix):
            rest = bare[len(prefix):]
            return rest[0].lower() + rest[1:]
    return bare


def verify_mixin(index, report, mixin_cls, default_require):
    simple = mixin_cls.name.split("/")[-1]
    mixin_anno = mixin_cls.annotation(A_MIXIN)
    if mixin_anno is None:
        report.add(ERROR, simple, "-", "class is listed in the mixin config but has no @Mixin annotation")
        return []
    pseudo = mixin_cls.annotation(A_PSEUDO) is not None
    targets = []
    for v in as_list(mixin_anno["values"].get("value")):
        if isinstance(v, tuple) and v[0] == "class":
            targets.append(v[1][1:-1])
    for v in as_list(mixin_anno["values"].get("targets")):
        if isinstance(v, str):
            targets.append(v.replace(".", "/"))

    if not targets:
        report.add(ERROR, simple, "-", "@Mixin declares no target")
        return []

    resolved = []
    summaries = []
    for t in targets:
        cls = index.get(t)
        if cls is None:
            if pseudo:
                report.add(INFO, simple, t,
                           "third-party target not on the classpath — @Pseudo, so Mixin "
                           "skips it silently at runtime; targets unverified")
            else:
                report.add(ERROR, simple, t,
                           "target class not found and the mixin is NOT @Pseudo — this is "
                           "a startup crash in a required config")
            summaries.append((t, None, pseudo))
            continue
        resolved.append(cls)
        summaries.append((t, cls, pseudo))

    if not resolved:
        return summaries

    for target_cls in resolved:
        verify_against_target(index, report, mixin_cls, target_cls, simple, default_require)
    return summaries


def verify_against_target(index, report, mixin_cls, target_cls, simple, default_require):
    tname = target_cls.name

    # ---- @Shadow fields
    for f in mixin_cls.fields:
        if f.annotation(A_SHADOW) is None:
            continue
        report.checked_shadows += 1
        where = f"@Shadow field {f.name}"
        found = None
        for cls in index.hierarchy(tname):
            found = cls.field_named(f.name)
            if found is not None:
                break
        if found is None:
            report.add(ERROR, simple, tname, f"{where}: no such field on {tname}")
        elif found.desc != f.desc:
            report.add(ERROR, simple, tname,
                       f"{where}: target field is {pretty_type(found.desc)}, mixin "
                       f"declares {pretty_type(f.desc)}")

    for m in mixin_cls.methods:
        # ---- @Shadow methods
        if m.annotation(A_SHADOW) is not None:
            report.checked_shadows += 1
            where = f"@Shadow method {m.name}{m.desc}"
            hits = [mm for cls in index.hierarchy(tname) for mm in cls.methods
                    if mm.name == m.name and mm.desc == m.desc]
            if not hits:
                loose = [mm for cls in index.hierarchy(tname) for mm in cls.methods
                         if mm.name == m.name]
                if loose:
                    report.add(ERROR, simple, tname,
                               f"{where}: {tname} has {m.name} but with descriptor(s) "
                               + ", ".join(sorted({x.desc for x in loose})))
                else:
                    report.add(ERROR, simple, tname, f"{where}: no such method on {tname}")
            continue

        # ---- @Accessor / @Invoker
        acc = m.annotation(A_ACCESSOR)
        if acc is not None:
            report.checked_accessors += 1
            fname = accessor_field_name(acc, m.name)
            where = f'@Accessor("{fname}") {m.name}'
            found = None
            for cls in index.hierarchy(tname):
                found = cls.field_named(fname)
                if found is not None:
                    break
            if found is None:
                report.add(ERROR, simple, tname, f"{where}: no field {fname} on {tname}")
            else:
                hp, hret = split_params(m.desc)
                if not hp and hret != "V":
                    if hret != found.desc:
                        report.add(ERROR, simple, tname,
                                   f"{where}: getter returns {pretty_type(hret)}, field is "
                                   f"{pretty_type(found.desc)}")
                elif len(hp) == 1 and hret == "V":
                    if hp[0] != found.desc:
                        report.add(ERROR, simple, tname,
                                   f"{where}: setter takes {pretty_type(hp[0])}, field is "
                                   f"{pretty_type(found.desc)}")
                else:
                    report.add(WARN, simple, tname, f"{where}: not a getter or setter shape")
            continue

        inv = m.annotation(A_INVOKER)
        if inv is not None:
            report.checked_accessors += 1
            iname = inv["values"].get("value") or m.name.split("$")[-1]
            where = f'@Invoker("{iname}") {m.name}'
            hits = [mm for cls in index.hierarchy(tname) for mm in cls.methods
                    if mm.name == iname]
            if not hits:
                report.add(ERROR, simple, tname, f"{where}: no method {iname} on {tname}")
            elif not any(mm.desc == m.desc for mm in hits):
                report.add(ERROR, simple, tname,
                           f"{where}: descriptors on target are "
                           + ", ".join(sorted({x.desc for x in hits}))
                           + f"; invoker declares {m.desc}")
            continue

        # ---- injectors
        for desc, label in INJECTORS.items():
            anno = m.annotation(desc)
            if anno is None:
                continue
            verify_injector(index, report, simple, tname, target_cls, m, anno, label,
                            default_require)


def verify_injector(index, report, simple, tname, target_cls, handler, anno, label,
                    default_require):
    values = anno["values"]
    selectors = [s for s in as_list(values.get("method")) if isinstance(s, str)]
    require = values.get("require", default_require)
    where_base = f"{label} {handler.name}"

    if not selectors:
        report.add(WARN, simple, tname, f"{where_base}: no method selector")
        return

    for sel in selectors:
        report.checked_injections += 1
        where = f'{where_base} -> "{sel}"'
        matches, exact, ok = resolve_selector(index, target_cls, sel)
        if not ok:
            report.add(WARN, simple, tname, f"{where}: selector not parseable")
            continue
        if not matches:
            same_name = [mm for mm in target_cls.methods if mm.name == sel.split("(")[0]]
            hint = ""
            if same_name:
                hint = ("; target has that name with descriptor(s) "
                        + ", ".join(sorted({x.desc for x in same_name})))
            level = ERROR if require and require > 0 else WARN
            report.add(level, simple, tname,
                       f"{where}: NOT FOUND on {tname}{hint} "
                       f"(require = {require})")
            continue
        if not exact and len(matches) > 1:
            report.add(WARN, simple, tname,
                       f"{where}: bare name matches {len(matches)} overloads ("
                       + ", ".join(sorted(x.desc for x in matches))
                       + ") — every one of them is injected into; pin the descriptor")

        for target in matches:
            if target.is_static != handler.is_static:
                report.add(ERROR, simple, tname,
                           f"{where}: target is "
                           f"{'static' if target.is_static else 'an instance method'} but "
                           f"the handler is "
                           f"{'static' if handler.is_static else 'an instance method'}")

        primary = matches[0]
        at_annos = [a[1] for a in as_list(values.get("at")) if isinstance(a, tuple) and a[0] == "anno"]
        at_annos += [a[1] for a in as_list(values.get("value")) if isinstance(a, tuple) and a[0] == "anno"]
        resolved_at = []
        for at in at_annos:
            resolved_at.append((at, check_at(index, report, simple, at, where)))

        if label == "@Inject":
            if len(matches) == 1 or exact:
                check_inject_signature(report, simple, handler, primary, where)
        elif label == "@ModifyVariable":
            if len(matches) == 1 or exact:
                check_modify_variable(report, simple, handler, primary, anno, where)
        elif label == "@Redirect":
            for at, member in resolved_at:
                check_redirect(report, simple, handler, member,
                               at["values"].get("value"), where)
        elif label == "@ModifyArg":
            for at, member in resolved_at:
                check_modify_arg(report, simple, handler, member, anno, where)

        for at, _member in resolved_at:
            v = at["values"]
            if "ordinal" in v:
                report.add(INFO, simple, tname,
                           f'{where}: @At ordinal = {v["ordinal"]} — the count of matching '
                           "instructions is body-dependent and NOT verified here")
            if "slice" in v or "args" in v:
                report.add(INFO, simple, tname,
                           f"{where}: @At has slice/args — not verified here")


# --------------------------------------------------------------------------
# discovery
# --------------------------------------------------------------------------

def strip_json_comments(text):
    return re.sub(r"^\s*//.*$", "", text, flags=re.MULTILINE)


def load_config(path):
    return json.loads(strip_json_comments(path.read_text()))


def find_minecraft_jar(root, explicit):
    if explicit:
        return Path(explicit)
    version = None
    props = root / "gradle.properties"
    if props.exists():
        m = re.search(r"^\s*minecraft_version\s*=\s*(\S+)", props.read_text(), re.MULTILINE)
        if m:
            version = m.group(1)
    cache = Path.home() / ".gradle" / "caches" / "fabric-loom"
    candidates = []
    if version:
        candidates.append(cache / version / "minecraft-merged.jar")
        candidates.append(cache / version / "minecraft-client.jar")
    candidates.extend(sorted(cache.glob("*/minecraft-merged.jar"), reverse=True))
    for c in candidates:
        if c.exists():
            return c
    return None


def gradle_classpath(root, refresh):
    """Read (and if needed regenerate) the library classpath dumped by the
    optional `mixinVerifyClasspath` Gradle task."""
    cp_file = root / "build" / "mixin-verify-classpath.txt"
    if refresh or not cp_file.exists():
        gradlew = root / "gradlew"
        if gradlew.exists():
            try:
                subprocess.run([str(gradlew), "-q", "mixinVerifyClasspath"],
                               cwd=str(root), check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               timeout=600)
            except (subprocess.SubprocessError, OSError):
                pass
    if not cp_file.exists():
        return []
    return [Path(line) for line in cp_file.read_text().split(os.pathsep)
            if line.strip() and Path(line.strip()).exists()]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--minecraft-jar", default=None)
    ap.add_argument("--jar", action="append", default=[],
                    help="extra jar to resolve targets against (e.g. Sodium, Xaero)")
    ap.add_argument("--refresh-classpath", action="store_true",
                    help="re-run the Gradle task that dumps the library classpath")
    ap.add_argument("--no-gradle", action="store_true",
                    help="never invoke Gradle; use only the Minecraft jar and --jar")
    ap.add_argument("--verbose", "-v", action="store_true",
                    help="also print INFO rows and per-mixin OK lines")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    report = Report()
    index = ClassIndex()

    mc_jar = find_minecraft_jar(root, args.minecraft_jar)
    if mc_jar is None or not mc_jar.exists():
        print("FATAL: could not find the Minecraft jar in the Loom cache "
              "(~/.gradle/caches/fabric-loom/<version>/minecraft-merged.jar). "
              "Run ./gradlew build once, or pass --minecraft-jar.", file=sys.stderr)
        return 2
    index.add_jar(mc_jar)

    for j in args.jar:
        if not index.add_jar(j):
            print(f"WARNING: could not read jar {j}", file=sys.stderr)

    mods_dir = root / "run" / "mods"
    if mods_dir.is_dir():
        for j in sorted(mods_dir.glob("*.jar")):
            index.add_jar(j)

    if not args.no_gradle:
        for j in gradle_classpath(root, args.refresh_classpath):
            index.add_jar(j)

    configs = [
        (root / "src/main/resources/nexomod.mixins.json",
         root / "build/classes/java/main"),
        (root / "src/tactical/resources/nexomod-tactical.mixins.json",
         root / "build/classes/java/tactical"),
    ]

    all_summaries = []
    any_config = False
    for cfg_path, classes_dir in configs:
        if not cfg_path.exists():
            continue
        any_config = True
        cfg = load_config(cfg_path)
        pkg = cfg["package"].replace(".", "/")
        default_require = cfg.get("injectors", {}).get("defaultRequire", 1)
        required = cfg.get("required", False)
        if not classes_dir.is_dir():
            print(f"FATAL: {classes_dir} does not exist — run ./gradlew classes first.",
                  file=sys.stderr)
            return 2
        index.add_dir(classes_dir)

        listed = []
        for key in ("mixins", "client", "server"):
            listed.extend(cfg.get(key, []))

        pkg_dir = classes_dir / pkg
        on_disk = {p.stem for p in pkg_dir.glob("*.class") if "$" not in p.stem}
        for name in sorted(on_disk - set(listed)):
            report.add(WARN, name, "-",
                       f"class exists in {pkg} but is not listed in {cfg_path.name} — "
                       "it will never be applied")

        # staleness guard: a .class older than its .java is a lie
        src_dir = (root / "src/main/java" if "tactical" not in str(classes_dir)
                   else root / "src/tactical/java") / pkg
        for java in sorted(src_dir.glob("*.java")):
            cls = pkg_dir / (java.stem + ".class")
            if cls.exists() and cls.stat().st_mtime < java.stat().st_mtime:
                report.add(WARN, java.stem, "-",
                           "compiled class is older than the source — run ./gradlew classes")

        print(f"\n=== {cfg_path.relative_to(root)}  "
              f"(required={required}, defaultRequire={default_require}, "
              f"{len(listed)} mixins) ===")

        for name in listed:
            internal = f"{pkg}/{name.replace('.', '/')}"
            cls = index.get(internal)
            if cls is None:
                report.add(ERROR, name, "-",
                           f"listed in {cfg_path.name} but {internal}.class was not found")
                continue
            summaries = verify_mixin(index, report, cls, default_require)
            for t, resolved, pseudo in summaries:
                all_summaries.append((name, t, resolved is not None, pseudo))

    if not any_config:
        print("FATAL: no mixin config found.", file=sys.stderr)
        return 2

    # ---------------- output ----------------
    by_mixin = {}
    for level, mixin, target, detail in report.rows:
        by_mixin.setdefault(mixin, []).append((level, target, detail))

    print("\n--- targets ---")
    width = max((len(n) for n, _, _, _ in all_summaries), default=10)
    for name, target, found, pseudo in all_summaries:
        mark = "ok " if found else ("skip" if pseudo else "MISS")
        print(f"  [{mark}] {name.ljust(width)}  {target}")

    print("\n--- findings ---")
    printed = 0
    for mixin in sorted(by_mixin):
        rows = [r for r in by_mixin[mixin]
                if r[0] != INFO or args.verbose]
        if not rows:
            continue
        print(f"  {mixin}")
        for level, target, detail in rows:
            printed += 1
            print(f"    {level:<5} {detail}")
    if printed == 0:
        print("  (none)")

    print("\n--- summary ---")
    print(f"  injection selectors checked : {report.checked_injections}")
    print(f"  @Shadow members checked     : {report.checked_shadows}")
    print(f"  @Accessor/@Invoker checked  : {report.checked_accessors}")
    print(f"  @At member targets checked  : {report.checked_at_targets}")
    print(f"  errors                      : {len(report.errors)}")
    print(f"  warnings                    : {len(report.warnings)}")
    print("\n  Not covered by this check: @At ordinals and shifts, slice expressions,")
    print("  constant matching, LOCAL capture, lambda/synthetic targets, and anything")
    print("  that depends on the body of a target method rather than its signature.")

    if report.errors:
        print(f"\nFAILED: {len(report.errors)} unresolved mixin target(s).")
        return 1
    print("\nOK: every mixin target resolved.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
